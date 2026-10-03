#!/bin/bash
# Plugin library steps for entrypoint.sh, run as the server user before Java (docs/PRD-v0.3.md C1, C2). Defines functions only.

library_name_ok() { [[ $1 =~ ^Claude[A-Za-z0-9_]{0,26}$ && $1 != ClaudeControl* ]]; }

# Regular jars of BASE in PLUGINS, one "<revision> <path>" per line (revision 1 is <Base>.jar).
library_revisions() {
    local plugins=$1 base=$2 f stem
    for f in "$plugins/$base.jar" "$plugins/${base}"_r*.jar; do
        [[ -f $f && ! -L $f ]] || continue
        stem=$(basename -- "$f" .jar)
        if [[ $stem == "$base" ]]; then echo "1 $f"
        elif [[ $stem =~ ^${base}_r([0-9]+)$ ]]; then echo "${BASH_REMATCH[1]} $f"; fi
    done
}

# 0 when some jar listed in REVS ("<rev> <path>" lines) has sha SHA.
library_revision_has_sha() {
    local r p
    while read -r r p; do
        [[ $(sha256sum -- "$p" | cut -d' ' -f1) == "$2" ]] && return 0
    done <<<"$1"
    return 1
}

# Moves FILE into PLUGINS/claude-quarantine/superseded/, prefixing the epoch when the name is taken.
library_supersede() {
    local plugins=$1 f=$2 dir=$1/claude-quarantine/superseded dest
    [[ ! -L $dir ]] && mkdir -p "$dir" || return 1
    dest=$dir/$(basename -- "$f")
    [[ -e $dest ]] && dest=$dir/$(date +%s)-$(basename -- "$f")
    mv -- "$f" "$dest" || return 1
    echo "$dest"
}

# Writes stdin to FILE through a temp file in the same directory and a rename.
library_write() {
    local tmp
    tmp=$(mktemp -- "$1.XXXXXX.tmp")
    cat > "$tmp"
    mv -f -- "$tmp" "$1"
}

# C1: shelve plugins whose lifetime ran out while the server was down. $1 = plugins dir, $2 = now (epoch).
# Newest revision to claude-library/<Base>.jar (the plugin rewrites its name at start), older ones to quarantine/superseded.
library_shelve_expired() {
    local plugins=$1 now=$2 lib=$1/claude-library epoch base f rev best line keep=() shelved=() older
    for f in "$lib" "$lib/lifetimes.txt" "$lib/shelved-at-boot.txt"; do
        if [[ -L $f ]]; then echo "library: refusing to shelve at boot: $f is a symlink"; return 0; fi
    done
    [[ -f $lib/lifetimes.txt ]] || return 0
    while read -r epoch base _; do
        [[ -n $epoch ]] || continue
        if ! [[ $epoch =~ ^[0-9]+$ ]] || ! library_name_ok "$base"; then echo "library: ignoring lifetimes line naming '${base}'"; continue; fi
        if (( epoch > now )); then keep+=("$epoch $base"); continue; fi
        for f in "$plugins/$base.jar" "$plugins/${base}"_r*.jar; do
            [[ -L $f ]] && echo "library: refusing to move $f: it is a symlink"
        done
        best=$(library_revisions "$plugins" "$base" | sort -rn | head -n 1 | cut -d' ' -f2-)
        [[ -n $best ]] || continue
        while read -r rev f; do
            [[ $f == "$best" ]] && continue
            if older=$(library_supersede "$plugins" "$f"); then echo "library: older revision $f of $base moved to $older"
            else echo "library: cannot move older revision $f of $base"; fi
        done < <(library_revisions "$plugins" "$base")
        mv -f -- "$best" "$lib/$base.jar" || { echo "library: cannot shelve $best"; continue; }
        shelved+=("$base")
        echo "library: lifetime of $base ended while the server was down; shelved $best"
    done < "$lib/lifetimes.txt"
    if (( ${#shelved[@]} )); then
        { [[ -f $lib/shelved-at-boot.txt ]] && cat -- "$lib/shelved-at-boot.txt"; printf '%s\n' "${shelved[@]}"; } | library_write "$lib/shelved-at-boot.txt"
    fi
    printf '%s\n' "${keep[@]}" | sed '/^$/d' | library_write "$lib/lifetimes.txt"
    return 0
}

# C2: resolve an interrupted shelve or activate before Java loads anything: the library copy wins over a copy in plugins/.
library_reconcile() {
    local plugins=$1 lib=$1/claude-library f base rev dest marker revs
    [[ -d $lib && ! -L $lib ]] || return 0
    for f in "$lib"/*.tmp; do
        [[ -f $f && ! -L $f ]] || continue
        if rm -f -- "$f"; then echo "library reconcile: removed leftover $(basename -- "$f")"; else echo "library reconcile: cannot remove $f"; fi
    done
    for f in "$lib"/Claude*.jar; do
        [[ -f $f && ! -L $f ]] || continue
        base=$(basename -- "$f" .jar)
        library_name_ok "$base" || continue
        revs=$(library_revisions "$plugins" "$base")
        [[ -n $revs ]] || continue
        marker=$lib/$base.activating
        libsha=$(sha256sum -- "$f" | cut -d' ' -f1)
        if [[ -f $marker && ! -L $marker && $(head -n 1 -- "$marker") == "$libsha" ]] && library_revision_has_sha "$revs" "$libsha"; then
            if rm -f -- "$f" "$marker"; then echo "library reconcile: completed the activation of $base"
            else echo "library reconcile: cannot remove $f or its marker"; fi
            while read -r rev f; do
                [[ $(sha256sum -- "$f" | cut -d' ' -f1) == "$libsha" ]] && continue
                if dest=$(library_supersede "$plugins" "$f"); then echo "library reconcile: other jar $f of $base moved to $dest"
                else echo "library reconcile: cannot move $f"; fi
            done <<<"$revs"
            continue
        fi
        while read -r rev f; do
            if dest=$(library_supersede "$plugins" "$f"); then
                echo "library reconcile: $base is in claude-library/ and plugins/; kept the library copy, moved $f to $dest"
            else echo "library reconcile: cannot move $f"; fi
        done <<<"$revs"
    done
    return 0
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    set -euo pipefail
    case ${1:-} in
        shelve) library_shelve_expired "$2" "$3" ;;
        reconcile) library_reconcile "$2" ;;
        *) echo "usage: library.sh shelve PLUGINS NOW | reconcile PLUGINS" >&2; exit 2 ;;
    esac
fi
