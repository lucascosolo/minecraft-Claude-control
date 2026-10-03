#!/bin/bash
# Boot guard logic, sourced by entrypoint.sh and test-entrypoint.sh. Defines functions only.

# Claude-written jars in a plugins dir: Claude*.jar except ClaudeControl*.jar.
boot_guard_jars() {
    local f
    for f in "$1"/Claude*.jar; do
        [[ -f $f && $(basename -- "$f") != ClaudeControl*.jar ]] && echo "$f"
    done
    return 0
}

# Prints exactly one line: quarantine <jar> | hold | halt | none.
boot_guard_decide() {
    local failures=$1 logfile=$2 plugins=$3 last_ok=${4:-0}
    local jar name main pkg m
    if (( failures <= 0 )); then echo none; return 0; fi
    if (( failures >= 3 )); then echo halt; return 0; fi
    if (( failures == 2 )); then
        [[ -n $(boot_guard_jars "$plugins") ]] && echo hold || echo halt
        return 0
    fi
    if [[ -f $logfile ]]; then
        local errs
        errs=$(grep -E 'ERROR|Exception|Error:' -- "$logfile" || true)
        if [[ -n $errs ]]; then
            while IFS= read -r jar; do
                name=$(basename -- "$jar" .jar)
                [[ $name =~ ^(.*)_r[0-9]+$ ]] && name=${BASH_REMATCH[1]}
                if grep -qF -- "[$name]" <<<"$errs"; then echo "quarantine $jar"; return 0; fi
                main=$(unzip -p "$jar" plugin.yml 2>/dev/null | sed -n 's/^main:[[:space:]]*//p' | head -n 1 | tr -d '\r"'"'"' ')
                pkg=${main%.*}
                if [[ -n $main && $pkg != "$main" ]] && grep -qF -- "at $pkg." "$logfile"; then
                    echo "quarantine $jar"; return 0
                fi
            done < <(boot_guard_jars "$plugins")
        fi
    fi
    local newest=
    newest=$(while IFS= read -r jar; do
        m=$(stat -c %Y -- "$jar")
        (( m > last_ok )) && printf '%s %s\n' "$m" "$jar"
    done < <(boot_guard_jars "$plugins") | sort -rn | head -n 1 | cut -d' ' -f2-) || true
    if [[ -n $newest ]]; then echo "quarantine $newest"; else echo none; fi
}

# Writes stdin to FILE as the server user; refuses FILE or its directory being a symlink (needs as_server from launch.sh; a non-root caller already is the server user).
server_write() {
    local f=$1
    path_safe "$(dirname -- "$(dirname -- "$f")")" "$f" || return 1
    if (( EUID == 0 )); then as_server tee -- "$f" >/dev/null; else tee -- "$f" >/dev/null; fi
}

boot_guard_count() {
    local n
    [[ -f $1 && ! -L $1 ]] && n=$(cat -- "$1" 2>/dev/null) || n=0
    [[ $n =~ ^[0-9]+$ ]] && echo "$n" || echo 0
}

# boot_guard_mv FILE DIR: mv -T to an unused name in DIR (epoch-prefixed if taken); never overwrites or follows a link. Echoes the name used.
boot_guard_mv() {
    local dest=$2/$(basename -- "$1")
    if [[ -e $dest || -L $dest ]]; then dest=$2/$(date +%s)-$(basename -- "$1"); fi
    if [[ -e $dest || -L $dest ]]; then return 1; fi
    mv -T -- "$1" "$dest" && basename -- "$dest"
}

# Applies a decision inside SERVER_DIR (plugins/, logs/latest.log, counter file). Echoes one 'boot guard:' line per stage.
boot_guard_apply() {
    local decision=$1 dir=$2
    local plugins=$dir/plugins log=$dir/logs/latest.log jar f n
    n=$(boot_guard_count "$dir/claude-boot-failures")
    case $decision in
        quarantine\ *)
            jar=${decision#quarantine }
            path_safe "$dir" "$plugins/claude-quarantine" || return 0
            mkdir -p "$plugins/claude-quarantine"
            if boot_guard_mv "$jar" "$plugins/claude-quarantine" >/dev/null; then
                echo "boot guard: the previous boot did not finish; moved $(basename -- "$jar") to plugins/claude-quarantine/"
            else
                echo "boot guard: cannot move $jar to plugins/claude-quarantine/"
            fi
            { basename -- "$jar"; tail -n 60 "$log" 2>/dev/null || true; } | server_write "$dir/claude-quarantined-on-boot" || true
            safe_chown "$dir" mc:mc "$dir/claude-quarantined-on-boot" "$plugins/claude-quarantine"
            ;;
        hold)
            path_safe "$dir" "$plugins/claude-held" || return 0
            mkdir -p "$plugins/claude-held"
            { echo "held after $n failed boots"
              while IFS= read -r f; do
                  if boot_guard_mv "$f" "$plugins/claude-held"; then :; else echo "cannot move $f"; fi
              done < <(boot_guard_jars "$plugins")
            } | server_write "$dir/claude-held-on-boot" || true
            echo "boot guard: $n boots failed in a row; moved every Claude-written jar to plugins/claude-held/ and retrying"
            safe_chown "$dir" mc:mc "$dir/claude-held-on-boot" "$plugins/claude-held"
            ;;
        halt)
            if [[ -d $plugins/claude-held ]] && path_safe "$dir" "$plugins/claude-held"; then
                for f in "$plugins"/claude-held/*.jar; do
                    [[ -f $f && ! -L $f ]] && { boot_guard_mv "$f" "$plugins" >/dev/null || echo "boot guard: cannot move $f back to plugins/"; }
                done
            fi
            rm -f -- "$dir/claude-held-on-boot"
            { date; tail -n 60 "$log" 2>/dev/null || true; } | server_write "$dir/claude-boot-halted" || true
            safe_chown "$dir" mc:mc "$dir/claude-boot-halted"
            echo "boot guard: HALTED after $n failed boots in a row; the server fails without any Claude-written plugin, so the cause is elsewhere."
            echo "boot guard: held jars were moved back to plugins/. Not starting Java; the container stays up idle."
            echo "boot guard: see /server/claude-boot-halted for the last log lines. Fix the cause, then delete that file (or restart the container) to try again."
            ;;
    esac
    return 0
}

# A15 step for entrypoint.sh inside SERVER: the marker survives only a boot that never finished loading; escalate by consecutive failures.
# Returns 2 when the guard halted (the caller waits for the halt file to go), 0 otherwise. Always leaves the marker present.
boot_guard_step() {
    local dir=$1 marker=$1/claude-boot-pending counter=$1/claude-boot-failures halted=$1/claude-boot-halted failures last_ok decision rc=0
    if [[ -e $halted ]]; then
        mv -f -T -- "$halted" "$halted.prev" || echo "boot guard: cannot move $halted aside"
        echo 0 | server_write "$counter" || true
        [[ -L $marker ]] || rm -f -- "$marker"
        echo "boot guard: found $halted from a previous halt; treating this start as a manual retry"
    elif [[ -e $marker || -L $marker ]]; then
        failures=$(( $(boot_guard_count "$counter") + 1 ))
        echo "$failures" | server_write "$counter" || true
        last_ok=$(stat -c %Y "$dir/claude-boot-ok" 2>/dev/null || echo 0)
        decision=$(boot_guard_decide "$failures" "$dir/logs/latest.log" "$dir/plugins" "$last_ok")
        boot_guard_apply "$decision" "$dir"
        [[ $decision == halt ]] && rc=2
    else
        echo 0 | server_write "$counter" || true
    fi
    safe_chown "$dir" mc:mc "$counter"
    : | server_write "$marker" || true
    safe_chown "$dir" mc:mc "$marker"
    return $rc
}
