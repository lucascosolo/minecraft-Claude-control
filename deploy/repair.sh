#!/bin/bash
# Repair mode (docs/PRD-v0.3.md R1-R9, round N), sourced by entrypoint.sh. Defines functions only.
# Needs path_safe (permissions.sh), server_write/boot_guard_mv (boot-guard.sh), as_server/launch_command (launch.sh).

: "${REPAIR_ATTEMPTS:=3}" "${REPAIR_STAYUP:=300}" "${REPAIR_TIMEOUT:=600}" "${REPAIR_CRASHES:=3}" "${REPAIR_CRASH_WINDOW:=1800}"
: "${REPAIR_INCIDENTS:=2}" "${REPAIR_POLL:=5}" "${REPAIR_STOP_GRACE:=45}" "${REPAIR_FILE_CAP:=1048576}"
: "${REPAIR_PROMPT:=/usr/local/share/claude-repair/prompt.md}" "${REPAIR_HOME:=/home/repair}"
: "${REPAIR_WORK:=$REPAIR_HOME/work}" "${REPAIR_HELPER:=/usr/local/bin/claude-repair}"

# Numeric lines of FILE newer than SINCE.
repair_recent() {
    local file=$1 since=$2 t
    [[ -f $file && ! -L $file ]] || return 0
    while IFS= read -r t || [[ -n $t ]]; do
        [[ $t =~ ^[0-9]+$ ]] && (( t > since )) && echo "$t"
    done < "$file"
    return 0
}

# R1/O6: runs before boot_guard_step. A run that loaded (no boot marker left) died when it left no clean-stop marker
# or a crash report newer than its load.
repair_crash_step() {
    local dir=$1 now=$2 hist=$1/claude-crash-history n clean=0
    [[ -e $dir/claude-clean-stop || -L $dir/claude-clean-stop ]] && clean=1
    repair_clear_clean_stop "$dir"
    [[ ! -e $dir/claude-boot-pending && ! -L $dir/claude-boot-pending && -e $dir/claude-boot-ok ]] || return 0
    if (( clean )); then repair_crash_report_newer "$dir" || return 0; fi
    { repair_recent "$hist" $(( now - REPAIR_CRASH_WINDOW )); echo "$now"; } | server_write "$hist" >/dev/null || true
    n=$(repair_crash_count "$dir" "$now")
    echo "repair: the last run stopped without a clean shutdown ($n in the last $(( REPAIR_CRASH_WINDOW / 60 )) minutes)"
    return 0
}

repair_crash_report_newer() {
    [[ -d $1/crash-reports && ! -L $1/crash-reports && -f $1/claude-boot-ok && ! -L $1/claude-boot-ok ]] &&
        [[ -n $(find -P "$1/crash-reports" -maxdepth 1 -type f -newer "$1/claude-boot-ok" -print -quit 2>/dev/null) ]]
}

# O6: before every launch, so a marker can only come from the run that follows.
repair_clear_clean_stop() { rm -f -- "$1/claude-clean-stop" 2>/dev/null || true; }

repair_enabled() { [[ ${CLAUDE_REPAIR:-} == on ]]; }

repair_crash_count() {
    repair_recent "$1/claude-crash-history" $(( $2 - REPAIR_CRASH_WINDOW )) | wc -l
}

repair_incident_allowed() {
    (( $(repair_recent "$1/claude-repair/incidents" $(( $2 - 86400 )) | wc -l) < REPAIR_INCIDENTS ))
}

repair_incident_record() {
    local dir=$1/claude-repair
    path_safe "$1" "$dir" >/dev/null || return 0
    [[ -d $dir ]] || as_server mkdir -p -- "$dir"
    { repair_recent "$dir/incidents" $(( $2 - 86400 )); echo "$2"; } | server_write "$dir/incidents" >/dev/null || true
}

# N2: configuration files a repair may change, relative to DIR, one per line (an allowlist).
repair_config_files() {
    local dir=${1%/} f name
    {
        find -P "$dir" -maxdepth 1 -type f -links 1 \( -name '*.yml' -o -name '*.properties' \) ! -name permissions.yml ! -name commands.yml ! -name help.yml ! -name 'claude-*' -print0
        [[ -d $dir/config && ! -L $dir/config ]] && find -P "$dir/config" -xdev -type f -links 1 \
            \( -name '*.yml' -o -name '*.yaml' -o -name '*.toml' -o -name '*.properties' \) -print0
        [[ -d $dir/plugins && ! -L $dir/plugins ]] && find -P "$dir/plugins" -mindepth 2 -maxdepth 2 -type f -links 1 -name config.yml -print0
    } 2>/dev/null | while IFS= read -r -d '' f; do
        f=${f#"$dir"/}
        [[ $f == *$'\n'* ]] && continue
        path_safe "$dir" "$dir/$f" >/dev/null || continue
        if [[ $f == plugins/* ]]; then
            name=${f#plugins/}; name=${name%%/*}
            shopt -s nocasematch
            case $name in NomadLabs|NomadCairn|ClaudeControl|LuckPerms|claude-*|*perm*) shopt -u nocasematch; continue ;; esac
            shopt -u nocasematch
        fi
        printf '%s\n' "$f"
    done | LC_ALL=C sort
}

# O2-O4: one awk program for masking (op=mask), checking a change (op=check, the diff on stdin) and merging it (op=merge).
# Files: fo = live snapshot, fm = its masked copy (what the session saw), fn = the session's copy. POSIX awk (mawk).
REPAIR_AWK=$(cat <<'AWK'
function sens(c) { return tolower(c) ~ /pass|pw|secret|token|key|auth|credential|webhook|dsn|jdbc/ }
# P3: userinfo anywhere after ://, or a query string; flow() a flow mapping or sequence. Either masks the whole line.
function urlui(s) { return s ~ /:\/\/.*@/ || s ~ /\?.*=/ }
function flow(s) { return s ~ /[{[]/ }
function nocr(s) { sub(/\r$/, "", s); return s }
function crof(s) { return (s ~ /\r$/) ? "\r" : "" }
function oddbs(s) { return match(s, /\\+$/) && RLENGTH % 2 == 1 }
function readf(f, w,   line, n) { n = 0; if (f != "") { while ((getline line < f) > 0) L[w, ++n] = line; close(f) }; N[w] = n }
function anysens(p,   n, a, i) { n = split(p, a, "."); for (i = 1; i <= n; i++) if (sens(a[i])) return 1; return 0 }
function ykey(b,   q, p) {
    q = substr(b, 1, 1)
    if (q == "\"" || q == "'") {
        p = index(substr(b, 2), q)
        if (p == 0 || substr(b, p + 2) !~ /^:( |$)/) return 0
        KEY = substr(b, 2, p - 1); KTEXT = substr(b, 1, p + 1); VAL = substr(b, p + 3); return 1
    }
    if (!match(b, /:( |$)/)) return 0
    KEY = substr(b, 1, RSTART - 1); KTEXT = KEY; VAL = substr(b, RSTART + 1); return 1
}
function parse(w,   i, s, t, ind, body, d, rest, rind, j, p, sec) {
    d = 0; sec = ""
    for (i = 1; i <= N[w]; i++) {
        s = nocr(L[w, i]); T[w, i] = "other"; P[w, i] = ""
        if (mode == "yaml") {
            match(s, /^ */); ind = RLENGTH; body = substr(s, ind + 1)
            if (body ~ /^[ \t]*$/) T[w, i] = "blank"
            else if (body ~ /^#/) T[w, i] = "comment"
            else if (body ~ /^-( |$)/) {
                while (d > 0 && (SI[d] > ind || (SI[d] == ind && SL[d]))) d--
                d++; SI[d] = ind; SK[d] = "-"; SL[d] = 1
                rest = substr(body, 2); match(rest, /^ */); rind = ind + 1 + RLENGTH; rest = substr(rest, RLENGTH + 1)
                if (rest !~ /^-( |$)/ && ykey(rest)) { d++; SI[d] = rind; SK[d] = KEY; SL[d] = 0 }
                T[w, i] = "item"
            } else if (ykey(body)) {
                while (d > 0 && SI[d] >= ind) d--
                d++; SI[d] = ind; SK[d] = KEY; SL[d] = 0
                T[w, i] = "key"; KT[w, i] = KTEXT; VE[w, i] = (VAL ~ /^ *$/); V[w, i] = VAL
            }
            p = ""; for (j = 1; j <= d; j++) p = p (j > 1 ? "." : "") SK[j]
            P[w, i] = p
        } else if (mode == "props") {
            t = s; sub(/^[ \t\f]+/, "", t)
            if (i > 1 && T[w, i - 1] != "comment" && oddbs(nocr(L[w, i - 1]))) { T[w, i] = "cont"; P[w, i] = P[w, i - 1] }
            else if (t == "") T[w, i] = "blank"
            else if (t ~ /^[#!]/) T[w, i] = "comment"
            else { T[w, i] = "key"; P[w, i] = match(t, /[=: \t\f]/) ? substr(t, 1, RSTART - 1) : t }
        } else if (mode == "toml") {
            t = s; sub(/^[ \t]+/, "", t)
            if (t ~ /^\[/) { sec = t; gsub(/^\[+[ \t]*|[ \t]*\].*$/, "", sec); T[w, i] = "header"; P[w, i] = sec }
            else if (t ~ /^#/) T[w, i] = "comment"
            else if (match(t, /^("[^"]*"|[A-Za-z0-9_.-]+)[ \t]*=/)) {
                KT[w, i] = t; sub(/[ \t]*=.*$/, "", KT[w, i]); KEY = KT[w, i]; gsub(/"/, "", KEY)
                T[w, i] = "key"; P[w, i] = (sec == "" ? KEY : sec "." KEY)
            } else P[w, i] = sec
        }
    }
}
function maskline(w, i,   s, cr, ind, t) {
    s = nocr(L[w, i]); cr = crof(L[w, i]); t = T[w, i]
    match(s, /^[ \t]*/); ind = substr(s, 1, RLENGTH)
    if (t == "blank") return L[w, i]
    if (t == "comment" || t == "header") return urlui(s) ? ind "# <hidden>" cr : L[w, i]
    if (!urlui(s) && !flow(s) && !anysens(P[w, i])) return L[w, i]
    if (mode == "yaml" && t == "key" && KT[w, i] ~ /[{[]/) return ind "<hidden>" cr
    if (mode == "yaml" && t == "key") return (VE[w, i] && !urlui(s)) ? L[w, i] : ind KT[w, i] ": <hidden>" cr
    if (mode == "yaml" && t == "item") return ind "- <hidden>" cr
    if (mode == "props" && t == "key") return ind P[w, i] "=<hidden>" cr
    if (mode == "toml" && t == "key") return ind KT[w, i] " = <hidden>" cr
    return (mode == "props" && t == "cont" ? "" : ind) "<hidden>" cr
}
function shapeok(s,   v) {
    if (mode == "yaml") {
        if (s !~ /^ *[A-Za-z0-9_.-]+:( +[A-Za-z0-9_.:\/-]*)? *$/) return 0
        v = s; sub(/^ *[A-Za-z0-9_.-]+: */, "", v)
    } else if (mode == "props") {
        if (s !~ /^ *[A-Za-z0-9_.-]+ *= *[A-Za-z0-9_.:\/-]* *$/) return 0
        v = s; sub(/^[^=]*= */, "", v)
    } else return 0
    sub(/ *$/, "", v); SV = v
    return length(v) <= 64
}
function prot(p,   lp, n, a, i) {
    lp = tolower(p); n = split(lp, a, ".")
    for (i = 1; i <= n; i++) if (a[i] ~ /command|script/) return 1
    for (i = 1; i <= NRULES; i++) if (lp == R[i] || index(lp, R[i] ".") == 1 || index(R[i], lp ".") == 1) return 1
    return 0
}
function numbool(v) { return v ~ /^(-?[0-9]+(\.[0-9]+)?|[Tt][Rr][Uu][Ee]|[Ff][Aa][Ll][Ss][Ee])$/ }
# P1: a removed line that opens a block scalar or a map is never replaced in place.
function opener(k) { return VE[2, k] || V[2, k] ~ /^ *[|>]/ }
# The reason a changed line is refused, or "". RELAX (removed line replaced in place): 1 = shape not required,
# 2 = also masked only for its flow syntax. BARE: an added line may carry a bare word (P1); otherwise a number or boolean.
function judge(w, k, sg, relax, bare,   s, p) {
    s = nocr(L[w, k]); p = P[w, k]
    if (anysens(p) || urlui(s) || (w == 2 && L[2, k] != L[1, k] && relax != 2)) return "masked:" p
    if (!relax && !shapeok(s)) return "shape:" sg k
    if (w == 3 && rel != "server.properties" && !bare && !numbool(SV)) return "value:" sg k
    if (mode == "props" && k > 1 && oddbs(nocr(L[w, k - 1]))) return "continuation:" sg k
    if (rel == "server.properties") return (p in ALLOW) ? "" : "not-allowed:" p
    return prot(p) ? "protected:" p : ""
}
function out(r) { if (r != "") print r }
# One hunk: nr removed lines (RB) then na added (AB). k-for-k hunks pair lines, so a malformed line can be replaced in place;
# also one masked only for its flow syntax; a bare word only outside the DENY key paths (P1).
function flush(   m, r, pair, fm) {
    for (m = 1; m <= na; m++) {
        r = RB[m]
        pair = nr == na && T[2, r] == "key" && P[2, r] == P[3, AB[m]] && !opener(r) && !shapeok(nocr(L[1, r]))
        fm = pair && L[2, r] != L[1, r]
        AR[m] = judge(3, AB[m], "+", 0, pair && tolower(P[3, AB[m]]) !~ DENY); out(AR[m])
        RX[m] = pair && AR[m] !~ /^(shape|masked|value):/ ? (fm ? 2 : 1) : 0
    }
    for (m = 1; m <= nr; m++) out(judge(2, RB[m], "-", nr == na ? RX[m] : 0))
    nr = 0; na = 0
}
function aliases(w,   i, s) {
    for (i = 1; i <= N[w]; i++) {
        if (T[w, i] == "comment" || T[w, i] == "blank") continue
        s = nocr(L[w, i])
        if (s ~ /(^|[ ,:\[{-])[&*][A-Za-z0-9_-]/ || s ~ /<<[ ]*:/) return 1
    }
    return 0
}
BEGIN {
    readf(fo, 1); readf(fm, 2); readf(fn, 3)
    if (op == "mask") { parse(1); for (i = 1; i <= N[1]; i++) print maskline(1, i); exit }
    parse(2); parse(3)
    NRULES = split(rules, R, " ")
    DENY = "url|uri|host|address|endpoint|server|path|folder|dir|file|exec|cmd|run|shell|console|hook|command|script|world|container|update"
    split("view-distance simulation-distance max-players max-tick-time network-compression-threshold sync-chunk-writes use-native-transport entity-broadcast-range-percentage spawn-protection max-world-size rate-limit player-idle-timeout allow-flight", A, " ")
    for (k in A) ALLOW[A[k]] = 1
    if (op == "check") {
        for (w = 1; w <= 3; w += 2) for (k = 1; k <= N[w]; k++) if (index(nocr(L[w, k]), "\r")) { print "lone-cr"; w = 4; break }
        if (mode == "yaml" && (aliases(2) || aliases(3))) print "alias"
    }
    i = 0; j = 0; nr = 0; na = 0
}
$0 == "=" {
    if (op == "check") flush()
    i++; j++
    if (op == "merge") print L[1, i]
    else if (mode == "yaml" && T[2, i] != "blank" && T[2, i] != "comment" && P[2, i] != P[3, j]) print "moved:" P[3, j]
    next
}
$0 == "<" { i++; if (op == "check") { if (na) flush(); RB[++nr] = i }; next }
$0 == ">" { j++; if (op == "merge") print L[3, j]; else AB[++na] = j; next }
END { if (op == "check") flush() }
AWK
)

repair_mode_of() { case $1 in *.yml|*.yaml) echo yaml ;; *.properties) echo props ;; *.toml) echo toml ;; *.json) echo json ;; *) echo other ;; esac; }
repair_has_nul() { ! tr -d '\000' < "$1" | cmp -s - "$1"; }
repair_last_byte() { tail -c1 -- "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n'; }
repair_trim_nl() { if [[ ! -s $1 || $(repair_last_byte "$1") == 0a ]]; then cat; else head -c -1; fi; }

# O4: REL FILE -> the work copy's text: values of secret-looking keys and URLs with userinfo replaced by <hidden>.
repair_mask() {
    if repair_has_nul "$2"; then echo "<hidden binary file>"; return 0; fi
    if [[ $(repair_mode_of "$1") == json ]]; then echo "<hidden json file>"; return 0; fi
    LC_ALL=C awk -v op=mask -v mode="$(repair_mode_of "$1")" -v fo="$2" "$REPAIR_AWK" </dev/null | repair_trim_nl "$2"
    return 0
}

repair_rules() {
    case $1 in spigot.yml) echo "settings.restart-script settings.bungeecord" ;; config/*) echo proxies ;; esac
}

# O2-O4/N3: why the session's copy NEW of REL may not replace the live snapshot OLD, one reason per line; nothing = it may.
repair_check() { repair_check_raw "$@" | LC_ALL=C sort -u; return 0; }
repair_check_raw() {
    local rel=$1 old=$2 new=$3 t rc=0 f
    if repair_has_nul "$old" || repair_has_nul "$new"; then echo binary; return 0; fi
    t=$(mktemp -d) || { echo internal; return 0; }
    repair_mask "$rel" "$old" > "$t/m"
    if cmp -s -- "$t/m" "$new"; then rm -rf -- "$t"; return 0; fi
    case $(repair_mode_of "$rel") in yaml|props) ;; *) echo read-only; rm -rf -- "$t"; return 0 ;; esac
    for f in "$old" "$new"; do [[ $(repair_last_byte "$f") == 0d ]] && echo lone-cr; done
    diff -a --old-line-format=$'<\n' --new-line-format=$'>\n' --unchanged-line-format=$'=\n' -- "$t/m" "$new" > "$t/d" || rc=$?
    if (( rc > 1 )); then echo internal
    else LC_ALL=C awk -v op=check -v mode="$(repair_mode_of "$rel")" -v rel="$rel" -v rules="$(repair_rules "$rel")" \
        -v fo="$old" -v fm="$t/m" -v fn="$new" "$REPAIR_AWK" < "$t/d" || echo internal
    fi
    rm -rf -- "$t"
    return 0
}

# O4: NEW with every line the session left unchanged taken from OLD, so masked values come back. Use only after repair_check passed.
repair_merge() {
    local rel=$1 old=$2 new=$3 t rc=0
    t=$(mktemp -d) || return 1
    repair_mask "$rel" "$old" > "$t/m"
    diff -a --old-line-format=$'<\n' --new-line-format=$'>\n' --unchanged-line-format=$'=\n' -- "$t/m" "$new" > "$t/d" || rc=$?
    if (( rc > 1 )); then rm -rf -- "$t"; return 1; fi
    LC_ALL=C awk -v op=merge -v mode="$(repair_mode_of "$rel")" -v fo="$old" -v fm="$t/m" -v fn="$new" "$REPAIR_AWK" < "$t/d" | repair_trim_nl "$new"
    rm -rf -- "$t"
}

# N5: the token, every 12-character window of it, and anything shaped like an Anthropic key. The token is read from the environment.
repair_redact() {
    REPAIR_TOK=${CLAUDE_CODE_OAUTH_TOKEN:-} awk '
        BEGIN { t = ENVIRON["REPAIR_TOK"]; n = length(t) }
        {
            line = $0
            if (n > 0) {
                while ((p = index(line, t)) > 0) line = substr(line, 1, p - 1) "[redacted]" substr(line, p + n)
                for (i = 1; i + 11 <= n; i++) {
                    w = substr(t, i, 12)
                    while ((p = index(line, w)) > 0) line = substr(line, 1, p - 1) "[redacted]" substr(line, p + 12)
                }
            }
            gsub(/sk-ant-[A-Za-z0-9_-]+/, "[redacted]", line)
            print line
        }'
}

# N1: removes the u:repair entries an older version set on the live tree.
repair_clear_acls() {
    command -v getfacl >/dev/null && command -v setfacl >/dev/null || return 0
    local kind f
    getfacl -R -P -s -p -- "$1" 2>/dev/null | awk '
        /^# file: / { f = substr($0, 9); next }
        /^user:repair:/ { print "a\t" f } /^default:user:repair:/ { print "d\t" f }' | sort -u |
    while IFS=$'\t' read -r kind f; do
        [[ $f == *\\* || -L $f ]] && continue
        if [[ $kind == d ]]; then setfacl -d -x u:repair -- "$f"; else setfacl -x u:repair -- "$f"; fi || echo "repair: cannot remove the repair ACL on $f"
    done
    return 0
}

repair_append() { # FILE: stdin appended as the server user, never through a link
    [[ -L $1 ]] && return 1
    path_safe "$(dirname -- "$(dirname -- "$1")")" "$1" >/dev/null || return 1
    if (( EUID == 0 )); then as_server tee -a -- "$1" >/dev/null; else tee -a -- "$1" >/dev/null; fi
}

repair_plain() { # FILE ROOT: a regular single-link file, no link from ROOT down, within the size cap
    [[ -f $1 && ! -L $1 ]] && [[ $(stat -c %h -- "$1") == 1 ]] && path_safe "$2" "$1" >/dev/null && (( $(stat -c %s -- "$1") <= REPAIR_FILE_CAP ))
}

# N1/N9/O4: the session's private copy (REPAIR_WORK, secrets masked, logs redacted) and the snapshot RD/session-N taken right before it.
repair_work_build() {
    local dir=${1%/} rd=$2 n=$3 w=$REPAIR_WORK s=$2/session-$3 rel f
    rm -rf -- "$w"
    mkdir -p -m 0700 -- "$w/config" "$w/logs"
    as_server mkdir -p -- "$s"
    repair_config_files "$dir" | server_write "$s/files.txt" >/dev/null
    while IFS= read -r rel; do
        f=$dir/$rel
        repair_plain "$f" "$dir" || { echo "repair: not copying $rel (too large or not a plain file)"; continue; }
        mkdir -p -- "$w/config/$(dirname -- "$rel")" && repair_mask "$rel" "$f" > "$w/config/$rel"
        as_server mkdir -p -- "$s/$(dirname -- "$rel")" && as_server cp -P -- "$f" "$s/$rel"
    done < "$s/files.txt"
    repair_copy_log "$dir" "$dir/logs/latest.log" "$w/logs/latest.log"
    if [[ -d $dir/crash-reports && ! -L $dir/crash-reports ]]; then
        mkdir -p -- "$w/logs/crash-reports"
        find -P "$dir/crash-reports" -maxdepth 1 -type f -links 1 -printf '%T@ %f\n' 2>/dev/null | sort -rn | head -n 3 | cut -d' ' -f2- |
            while IFS= read -r f; do repair_copy_log "$dir" "$dir/crash-reports/$f" "$w/logs/crash-reports/$f"; done
    fi
    # O5: the logs as copied, out of the session's reach; holds are checked against these, not the work copy it can edit.
    as_server mkdir -p -- "$s/logs/crash-reports"
    for f in "$w"/logs/latest.log "$w"/logs/crash-reports/*; do
        [[ -f $f && ! -L $f ]] && server_write "$s/logs/${f#"$w"/logs/}" < "$f" >/dev/null
    done
    {
        find -P "$dir/plugins" -maxdepth 1 -type f -name '*.jar' -printf '%f\n' 2>/dev/null
        [[ -L $dir/plugins/claude-held ]] || find -P "$dir/plugins/claude-held" -maxdepth 1 -type f -name '*.jar' -printf 'claude-held/%f\n' 2>/dev/null
    } | LC_ALL=C sort > "$w/plugins.txt"
    chown -R -h repair:repair -- "$w" 2>/dev/null || echo "repair: cannot hand $w to the repair user"
    return 0
}

repair_copy_log() { # DIR SRC DST: the last REPAIR_FILE_CAP bytes of a plain file
    [[ -f $2 && ! -L $2 ]] && [[ $(stat -c %h -- "$2") == 1 ]] && path_safe "$1" "$2" >/dev/null || return 0
    local t
    t=$(mktemp) || return 0
    tail -c "$REPAIR_FILE_CAP" -- "$2" > "$t"
    repair_redact < "$t" | repair_trim_nl "$t" > "$3"
    rm -f -- "$t"
}

# N1/O2-O4: each changed work/config file goes back to the live tree as mc, masked values restored, unless repair_check refuses it.
repair_apply() {
    local dir=${1%/} rd=$2 n=$3 w=$REPAIR_WORK s=$2/session-$3 rel wf keys out
    local -A ok=()
    [[ -f $s/files.txt && ! -L $s/files.txt ]] || return 0
    while IFS= read -r rel; do ok[$rel]=1; done < <(repair_config_files "$dir")
    while IFS= read -r rel; do
        [[ -n $rel && -n ${ok[$rel]:-} && -f $s/$rel && ! -L $s/$rel ]] || continue
        wf=$w/config/$rel
        [[ -e $wf || -L $wf ]] || continue
        repair_plain "$wf" "$w" || { echo "repair: not applying $rel (not a plain file in the work copy)"; continue; }
        repair_mask "$rel" "$s/$rel" | cmp -s -- - "$wf" && continue
        keys=$(repair_check "$rel" "$s/$rel" "$wf" | paste -sd, -)
        if [[ -n $keys ]]; then
            printf '%s\t%s\t%s\n' "$n" "$rel" "$keys" | repair_append "$rd/refused.txt"
            echo "repair: refused $rel: $keys"
            continue
        fi
        as_server mkdir -p -- "$s/new/$(dirname -- "$rel")"
        out=$(mktemp) || continue
        if ! repair_merge "$rel" "$s/$rel" "$wf" > "$out" || ! server_write "$s/new/$rel" < "$out" >/dev/null; then
            rm -f -- "$out"; echo "repair: cannot stage $rel"; continue
        fi
        rm -f -- "$out"
        if [[ ! -e $rd/before/$rel && ! -L $rd/before/$rel ]]; then
            as_server mkdir -p -- "$rd/before/$(dirname -- "$rel")" && as_server cp -P -- "$s/$rel" "$rd/before/$rel" || { echo "repair: cannot save $rel"; continue; }
        fi
        if [[ -L $dir/$rel ]] || ! path_safe "$dir" "$dir/$rel" >/dev/null; then echo "repair: cannot apply $rel"; continue; fi
        printf '%s\t%s\n' "$n" "$rel" | repair_append "$rd/applied.txt"
        if repair_put "$s/new/$rel" "$dir/$rel"; then
            echo "repair: applied $rel"
        else
            echo "repair: cannot apply $rel"
        fi
    done < "$s/files.txt"
    return 0
}

# N4: holds, unholds and notes the session asked for in work/requests.json, run through claude-repair as mc.
repair_requests() {
    local dir=${1%/} rd=$2 n=$3 f=$REPAIR_WORK/requests.json entries action arg why
    [[ -e $f || -L $f ]] || return 0
    {
        if ! { [[ -f $f && ! -L $f ]] && [[ $(stat -c %h -- "$f") == 1 ]] && path_safe "$REPAIR_WORK" "$f" >/dev/null && (( $(stat -c %s -- "$f") <= 65536 )); }; then
            echo "refused: requests.json is not a plain file of at most 64 KiB"
        elif ! entries=$(jq -c 'if type == "array" then .[:20][] else error("not an array") end' < "$f" 2>/dev/null); then
            echo "refused: requests.json is not a JSON array"
        else
            (( $(jq 'length' < "$f") <= 20 )) || echo "refused: only the first 20 requests are run"
            while IFS= read -r entry; do
                [[ -n $entry ]] || continue
                if ! IFS=$'\t' read -r action arg < <(jq -r 'if type == "object" and (.action == "hold-plugin" or .action == "unhold-plugin") and (.jar | type) == "string" then [.action, .jar]
                        elif type == "object" and .action == "note" and (.text | type) == "string" then ["note", .text]
                        else empty end | @tsv' <<<"$entry" 2>/dev/null) || [[ -z $action ]]; then
                    echo "refused: ${entry:0:200}"
                    continue
                fi
                [[ $action == note ]] && arg=$(printf '%s\n' "$arg" | tr '[:cntrl:]' ' ' | repair_redact | head -c 500)
                if [[ $action == hold-plugin ]]; then
                    why=$(REPAIR_LOGS_COPY=$rd/session-$n/logs repair_hold_check "$dir" "$rd" "$arg")
                    if [[ -n $why ]]; then echo "refused: hold-plugin ${arg:0:200}: $why"; continue; fi
                fi
                echo "$action: ${arg:0:200}"
                as_server env CLAUDE_REPAIR_SERVER="$dir" "$REPAIR_HELPER" "$action" "$arg" 2>&1
            done <<<"$entries"
        fi
    } | repair_redact | repair_append "$rd/requests-$n.log"
    rm -f -- "$f"
    return 0
}

# P2: the ERROR/SEVERE and stack-trace lines of the log snapshot LOGS, chat lines excluded.
repair_log_errors() {
    find -P "$1" -type f -exec cat -- {} + 2>/dev/null | LC_ALL=C awk '
        /Chat Thread|\[Not Secure\]|issued server command|<[^ <>]+> / { next }
        /^\[[0-9:]+ (ERROR|SEVERE)\]:/ || /^\[[0-9:]+\] \[[^\/]*\/(ERROR|SEVERE)\]:/ || /^[ \t]+at / || /^Caused by: / ||
            /^[A-Za-z_$][A-Za-z0-9_$.]*(Exception|Error)(:|$)/'
    return 0
}

# O5/P2: why the session may not hold JAR, or nothing: its plugin name or jar file name must appear in an error line of the
# log snapshot, and it must not guard access, claims, the economy or the like.
repair_hold_check() {
    local dir=${1%/} rd=$2 jar=$3 base pname n logs=${REPAIR_LOGS_COPY:-$REPAIR_WORK/logs}
    [[ $jar =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$ ]] || { echo not-a-jar; return 0; }
    [[ -f $dir/plugins/$jar && ! -L $dir/plugins/$jar ]] || { echo missing; return 0; }
    base=${jar%.jar}
    pname=$(unzip -p "$dir/plugins/$jar" plugin.yml paper-plugin.yml 2>/dev/null | sed -n 's/^name:[[:space:]]*//p' | head -n 1 | tr -d '\r"'"'"' ' || true)
    local -a names=("$base" "$(sed -E 's/_r[0-9]+$//; s/[-_. ]v?[0-9].*$//' <<<"$base")" "$pname")
    for n in "${names[@]}"; do
        [[ ${n,,} =~ claudecontrol|perm|auth|login|guard|protect|ban|whitelist|anticheat|grief|cheat|grim|towny|lands|residence|claim|core|vault|essentials|luck ]] && { echo denied; return 0; }
    done
    if [[ -f $rd/helper.log ]] && (( $(awk -F'\t' '$2 == "hold"' "$rd/helper.log" 2>/dev/null | wc -l) >= 3 )); then echo limit; return 0; fi
    local -a pat=(-e "$jar")
    (( ${#pname} >= 3 )) && pat+=(-e "$pname")
    [[ -d $logs && ! -L $logs ]] && grep -qiF "${pat[@]}" <<<"$(repair_log_errors "$logs")" && return 0
    echo not-in-logs
}

repair_json() { # JSON string literal of $1
    local s=$1
    s=${s//\\/\\\\}; s=${s//\"/\\\"}; s=${s//$'\t'/ }; s=${s//$'\r'/ }; s=${s//$'\n'/ }
    printf '"%s"' "$(printf '%s' "$s" | tr -d '\000-\037')"
}

repair_rel_ok() { [[ -n $1 && $1 != /* && /$1/ != */../* && /$1/ != */./* ]]; }

# R4/N3: RD/changes.json from applied.txt, refused.txt and the helper's log.
repair_record_changes() {
    local dir=$1 rd=$2 a rel keys k sep= ks when action arg
    local -A seen=()
    {
        echo "["
        if [[ -f $rd/applied.txt && ! -L $rd/applied.txt ]]; then
            while IFS=$'\t' read -r a rel; do
                [[ $a =~ ^[0-9]+$ && -z ${seen[$rel]:-} ]] && repair_rel_ok "$rel" || continue
                seen[$rel]=1
                printf '%s{"attempt":%d,"action":"edit","path":%s}\n' "$sep" "$a" "$(repair_json "$rel")"; sep=,
            done < "$rd/applied.txt"
        fi
        if [[ -f $rd/refused.txt && ! -L $rd/refused.txt ]]; then
            while IFS=$'\t' read -r a rel keys; do
                [[ $a =~ ^[0-9]+$ ]] && repair_rel_ok "$rel" || continue
                ks=; IFS=, read -ra k <<<"$keys"
                for keys in "${k[@]}"; do ks+=${ks:+,}$(repair_json "$keys"); done
                printf '%s{"attempt":%d,"action":"refused","path":%s,"keys":[%s]}\n' "$sep" "$a" "$(repair_json "$rel")" "$ks"; sep=,
            done < "$rd/refused.txt"
        fi
        if [[ -f $rd/helper.log && ! -L $rd/helper.log ]]; then
            while IFS=$'\t' read -r when action arg; do
                case $action in
                    note) printf '%s{"action":"note","text":%s}\n' "$sep" "$(repair_json "$arg")" ;;
                    hold|unhold|restore) printf '%s{"action":"%s","path":%s}\n' "$sep" "$action" "$(repair_json "$arg")" ;;
                    *) continue ;;
                esac
                sep=,
            done < "$rd/helper.log"
        fi
        echo "]"
    } | repair_redact | server_write "$rd/changes.json" >/dev/null
    return 0
}

# Runs CMD as the repair user with a minimal environment, replacing the calling subshell; the token travels in the environment only.
# O1: the subprocess scrub is CLAUDE_REPAIR_ENV_SCRUB (0 or 1, anything else is 1).
as_repair() {
    local setpriv=${REPAIR_SETPRIV:-setpriv} scrub=${CLAUDE_REPAIR_ENV_SCRUB:-1}
    [[ $scrub == 0 || $scrub == 1 ]] || scrub=1
    if [[ ${AS_REPAIR_DRYRUN:-} == 1 ]]; then echo "$setpriv --reuid=repair --regid=repair --init-groups $*"; return 0; fi
    local v tok=${CLAUDE_CODE_OAUTH_TOKEN:-} home=$REPAIR_HOME
    for v in $(compgen -e); do unset "$v" 2>/dev/null || true; done
    export HOME=$home PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 CLAUDE_CODE_OAUTH_TOKEN=$tok DISABLE_AUTOUPDATER=1 CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=$scrub
    exec "$setpriv" --reuid=repair --regid=repair --init-groups "$@"
}

# O7: the pids under PROCROOT whose real uid is UID.
repair_uid_pids() {
    local uid=$1 root=${2:-/proc} d u
    for d in "$root"/[0-9]*; do
        [[ -f $d/status ]] || continue
        u=$(awk '/^Uid:/ { print $2; exit }' "$d/status" 2>/dev/null) || continue
        [[ $u == "$uid" ]] && echo "${d##*/}"
    done | sort -n
    return 0
}

# O7: every process of the repair user, before its files are read. Only as root, only that uid, by exact pid.
repair_kill_all() {
    (( EUID == 0 )) || return 0
    local uid pass pids
    uid=$(id -u repair 2>/dev/null) || return 0
    [[ $uid =~ ^[0-9]+$ ]] && (( uid != 0 )) || return 0
    for pass in 1 2; do
        pids=$(repair_uid_pids "$uid" | paste -sd' ' -)
        [[ -n $pids ]] || break
        # shellcheck disable=SC2086
        "${REPAIR_SETPRIV:-setpriv}" --reuid=repair --regid=repair --clear-groups kill -KILL $pids 2>/dev/null || true
        sleep 0.2
    done
    return 0
}

repair_kill_session() {
    setpriv --reuid=repair --regid=repair --clear-groups kill -TERM "$1" 2>/dev/null || kill -TERM "$1" 2>/dev/null || true
}

# N7: TERM/INT while a repair runs: stop the session now; the incident loop stops the trial server and rolls back.
repair_interrupt() {
    REPAIR_STOP=1
    [[ -n ${REPAIR_SESSION_PID:-} ]] && repair_kill_session "$REPAIR_SESSION_PID"
    return 0
}

# The CLI lives under the agent's home (0750): repair may traverse each directory on the way to it for one incident;
# -x removes the same entries (O7: never left in place).
repair_cli_access() {
    local op=m p d n=0 root=${REPAIR_CLI_ROOT:-/home}
    [[ ${1:-} == -x ]] && { op=x; shift; }
    p=${1:-$(command -v claude)} || return 0
    [[ -n $p ]] || return 0
    while (( n++ < 8 )); do
        d=$(dirname -- "$p")
        while [[ $d == "$root"/?* ]]; do
            if [[ $op == m ]]; then setfacl -m u:repair:x -- "$d" || echo "repair: cannot open $d to repair"
            else setfacl -x u:repair -- "$d" 2>/dev/null || true; fi
            d=$(dirname -- "$d")
        done
        [[ -L $p ]] || break
        case $(readlink -- "$p") in /*) p=$(readlink -- "$p") ;; *) p=$(dirname -- "$p")/$(readlink -- "$p") ;; esac
        p=$(realpath -s -m -- "$p")
    done
    return 0
}

repair_prompt() { # RD ATTEMPT REASON
    cat -- "$REPAIR_PROMPT" 2>/dev/null || echo "(repair prompt $REPAIR_PROMPT missing)"
    printf '\n## This attempt\n\nThis is attempt %d of %d.\nWhy the repair started: %s\nYour work folder: %s\n' "$2" "$REPAIR_ATTEMPTS" "$3" "$REPAIR_WORK"
    if [[ -f $1/helper.log && ! -L $1/helper.log ]]; then
        echo; echo "Your notes and actions from earlier attempts (each later attempt failed to stay up):"
        cut -f2- "$1/helper.log" | tr '\t' ' '
    fi
    for f in "$1"/refused.txt; do
        [[ -f $f && ! -L $f ]] || continue
        echo; echo "Changes that were refused because they touch protected settings (do not retry them):"
        cut -f2- "$f" | tr '\t' ' '
    done
}

# R3/N4/N5: one headless attempt inside REPAIR_WORK; its redacted output goes to RD/attempt-N.log.
repair_run_claude() {
    local dir=${1%/} rd=$2 attempt=$3 reason=$4 w=$REPAIR_WORK fd redactor rc=0
    local -a fmt=(--output-format text)
    [[ ${REPAIR_OUTPUT_FORMAT:-} == stream-json ]] && fmt=(--output-format stream-json --verbose)
    exec {fd}> >(repair_redact | server_write "$rd/attempt-$attempt.log" >/dev/null)
    redactor=$!
    ( cd -- "$w" && as_repair timeout -k 10 "$REPAIR_TIMEOUT" claude -p --model opus --effort medium "${fmt[@]}" \
            --permission-mode dontAsk --setting-sources user --strict-mcp-config --tools Read,Grep,Glob,Edit,Write \
            --allowedTools "Read(/$w/**)" "Grep(/$w/**)" "Glob(/$w/**)" "Edit(/$w/**)" "Write(/$w/**)" \
            --disallowedTools Bash WebFetch WebSearch NotebookEdit
    ) < <(repair_prompt "$rd" "$attempt" "$reason" | repair_redact) >&"$fd" 2>&1 &
    REPAIR_SESSION_PID=$!
    exec {fd}>&-
    wait "$REPAIR_SESSION_PID" || rc=$?
    while (( rc > 128 )) && [[ -e /proc/$REPAIR_SESSION_PID ]]; do rc=0; wait "$REPAIR_SESSION_PID" || rc=$?; done
    unset REPAIR_SESSION_PID
    (( ${REPAIR_STOP:-0} )) || wait "$redactor" 2>/dev/null || true
    echo "repair: attempt $attempt finished"
    return 0
}

# R5: starts the server in the background; rc 0 when it loaded and is still up after REPAIR_STAYUP seconds.
repair_server_trial() {
    local dir=$1 waited=0 cmd
    mapfile -t cmd < <(launch_command "$dir")
    (( ${#cmd[@]} )) || return 1
    : | server_write "$dir/claude-boot-pending" >/dev/null || true
    repair_clear_clean_stop "$dir"
    ( umask 0007; exec "${cmd[@]}" ) <&0 &
    REPAIR_SERVER_PID=$!
    while (( waited < REPAIR_STAYUP )); do
        sleep "$REPAIR_POLL"; waited=$(( waited + REPAIR_POLL ))
        (( ${REPAIR_STOP:-0} )) && return 1
        if ! repair_alive; then
            wait "$REPAIR_SERVER_PID" 2>/dev/null || true
            echo "repair: the server stopped after about ${waited}s"
            return 1
        fi
    done
    if [[ ! -e $dir/claude-boot-pending ]] || grep -q 'Done (' -- "$dir/logs/latest.log" 2>/dev/null; then
        echo "repair: the server loaded and stayed up for ${REPAIR_STAYUP}s"
        return 0
    fi
    echo "repair: the server did not finish loading in ${REPAIR_STAYUP}s; stopping it"
    repair_stop_server
    return 1
}

# The container has no CAP_KILL: root cannot signal mc's server, so liveness is read from /proc and signals are sent as mc.
repair_alive() {
    local st
    [[ -n ${REPAIR_SERVER_PID:-} ]] && st=$(awk '{print $3}' "/proc/$REPAIR_SERVER_PID/stat" 2>/dev/null) && [[ -n $st && $st != Z ]]
}

repair_stop_server() {
    local i=0
    repair_alive || { [[ -z ${REPAIR_SERVER_PID:-} ]] || wait "$REPAIR_SERVER_PID" 2>/dev/null || true; return 0; }
    as_server kill -TERM "$REPAIR_SERVER_PID" 2>/dev/null || true
    while repair_alive && (( i < REPAIR_STOP_GRACE )); do sleep 1; i=$(( i + 1 )); done
    repair_alive && { as_server kill -KILL "$REPAIR_SERVER_PID" 2>/dev/null || true; }
    wait "$REPAIR_SERVER_PID" 2>/dev/null || true
}

# Copies SRC over DST as the server user through a temp file in DST's directory, keeping DST's mode and owner (O7).
repair_put() {
    local src=$1 dst=$2 tmp
    tmp=$(as_server mktemp -p "$(dirname -- "$dst")" .claude-repair.XXXXXX) || return 1
    if as_server cp -- "$src" "$tmp" && { [[ ! -e $dst ]] || as_server chmod --reference="$dst" -- "$tmp"; } \
        && { [[ ! -e $dst || $(stat -c %u:%g -- "$dst") == "$(stat -c %u:%g -- "$tmp")" ]] || chown -h --reference="$dst" -- "$tmp"; } \
        && as_server mv -T -- "$tmp" "$dst"; then
        return 0
    fi
    as_server rm -f -- "$tmp"; return 1
}

# R5/N1: every applied file back to before/, every hold/unhold reversed. Files the repair never applied are not touched.
repair_rollback() {
    local dir=$1 rd=$2 a rel when action arg name i
    local -a moves=()
    local -A seen=()
    if [[ -f $rd/applied.txt && ! -L $rd/applied.txt ]]; then
        while IFS=$'\t' read -r a rel; do
            repair_rel_ok "$rel" && [[ -z ${seen[$rel]:-} ]] || continue
            seen[$rel]=1
            [[ -f $rd/before/$rel && ! -L $rd/before/$rel ]] || { echo "repair: no saved copy of $rel"; continue; }
            cmp -s -- "$dir/$rel" "$rd/before/$rel" && continue
            if [[ -L $dir/$rel ]] || ! path_safe "$dir" "$dir/$rel" >/dev/null; then echo "repair: cannot roll back $rel (link)"; continue; fi
            if repair_put "$rd/before/$rel" "$dir/$rel"; then echo "repair: rolled back $rel"; else echo "repair: cannot roll back $rel"; fi
        done < "$rd/applied.txt"
    fi
    if [[ -f $rd/helper.log && ! -L $rd/helper.log ]]; then
        while IFS=$'\t' read -r when action arg; do [[ $action == hold || $action == unhold ]] && moves+=("$action $arg"); done < "$rd/helper.log"
    fi
    for (( i = ${#moves[@]} - 1; i >= 0; i-- )); do
        action=${moves[i]%% *}; name=$(basename -- "${moves[i]#* }")
        if [[ $action == hold ]]; then repair_move_back "$dir/plugins/claude-held/$name" "$dir/plugins" "$dir"
        else repair_move_back "$dir/plugins/$name" "$dir/plugins/claude-held" "$dir"; fi
    done
    return 0
}

repair_move_back() {
    local src=$1 to=$2 dir=$3
    if [[ -f $src && ! -L $src && ! -e $to/$(basename -- "$src") && ! -L $to/$(basename -- "$src") ]] && path_safe "$dir" "$to" >/dev/null && path_safe "$dir" "$src" >/dev/null \
        && as_server mv -T -- "$src" "$to/$(basename -- "$src")"; then
        echo "repair: rolled back the move of $(basename -- "$src")"
    else
        echo "repair: cannot move $(basename -- "$src") back to $to"
    fi
}

# Jars the repair session holds at the end: held and not unheld since.
repair_held_now() {
    local rd=$1 when action arg
    local -A held=()
    [[ -f $rd/helper.log && ! -L $rd/helper.log ]] || return 0
    while IFS=$'\t' read -r when action arg; do
        case $action in hold) held[$(basename -- "$arg")]=1 ;; unhold) unset "held[$(basename -- "$arg")]" ;; esac
    done < "$rd/helper.log"
    (( ${#held[@]} )) && printf '%s\n' "${!held[@]}" | sort
    return 0
}

# R7/R8: RD/report.md.
repair_report() {
    local dir=$1 rd=$2 outcome=$3 reason=$4 attempts=$5 jar held
    held=$(repair_held_now "$rd")
    [[ $outcome == repaired ]] || held=
    {
        echo "# Repair $(basename -- "$rd")"
        echo
        echo "Outcome: $outcome"
        echo "Reason: $reason"
        echo "Attempts: $attempts"
        if [[ -n $held ]]; then
            echo; echo "## Held plugins"; echo
            while IFS= read -r jar; do
                echo "- $jar was moved to plugins/claude-held/. Players lose what this plugin provides until it is returned (for example Bedrock players cannot join without Geyser and Floodgate, graves stop without NomadCairn; data is untouched)."
            done <<<"$held"
        fi
        echo; echo "## Changes"; echo
        repair_read "$rd/changes.json" || echo "[]"
        echo; echo "## Diagnosis"; echo
        repair_read "$rd/helper.log" | awk -F'\t' '$2 == "note" { print "- " $3 }' || true
        if [[ -f $rd/attempt-$attempts.log && ! -L $rd/attempt-$attempts.log ]]; then echo; echo "Last lines of attempt $attempts:"; echo; repair_read "$rd/attempt-$attempts.log" | tail -n 60; fi
    } | repair_redact | server_write "$rd/report.md" >/dev/null
    return 0
}

# Prints FILE for the report: never a link, and read as the server user so a planted link shows nothing that user could not read anyway.
repair_read() {
    [[ -f $1 && ! -L $1 ]] || return 1
    if (( EUID == 0 )); then as_server cat -- "$1" 2>/dev/null; else cat -- "$1"; fi
}

repair_halt() { # DIR TEXT-FILE-or-message
    { date; echo "repair: $2"; [[ -n ${3:-} ]] && repair_read "$3" || true; } | repair_redact | server_write "$1/claude-boot-halted" >/dev/null || true
    safe_chown "$1" mc:mc "$1/claude-boot-halted"
}

# N9/P4: the stamps whose reports the start script wrote, in claude-repair/.root/index: a directory only root can write, so the
# server user can neither replace the index nor plant its temp file. The plugin trusts no other report.
repair_index_add() {
    local base=${1%/}/claude-repair idx tmp d
    d=$base/.root; idx=$d/index
    [[ -d $base ]] && path_safe "$1" "$base" >/dev/null || return 0
    [[ -e $d || -L $d ]] || mkdir -m 0755 -- "$d" 2>/dev/null || return 0
    if [[ ! -d $d || -L $d || ! -O $d ]]; then echo "repair: $d is not the start script's; index not updated"; return 0; fi
    tmp=$(mktemp -p "$d" index.XXXXXX) || return 0
    {
        if [[ -f $idx && ! -L $idx && $(stat -c %u -- "$idx") == "$(id -u)" ]]; then grep -E '^[0-9]{8}T[0-9]{6}Z$' -- "$idx" || true; fi
        echo "$2"
    } > "$tmp"
    chmod 0644 -- "$tmp" && mv -f -T -- "$tmp" "$idx" || rm -f -- "$tmp"
    return 0
}

# O7: stamp folders older than 30 days go, the newest 10 and the current one always stay.
repair_prune() {
    local base=${1%/}/claude-repair cutoff=$(( $2 - 30 * 86400 )) cur= s t i=0
    [[ -d $base && ! -L $base ]] || return 0
    [[ -f $base/current && ! -L $base/current ]] && { IFS= read -r cur < "$base/current" || true; }
    find -P "$base" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E '^[0-9]{8}T[0-9]{6}Z$' | LC_ALL=C sort -r |
    while IFS= read -r s; do
        i=$(( i + 1 ))
        (( i > 10 )) && [[ $s != "$cur" ]] || continue
        t=$(date -u -d "${s:0:4}-${s:4:2}-${s:6:2} ${s:9:2}:${s:11:2}:${s:13:2}" +%s 2>/dev/null) || continue
        if (( t < cutoff )); then as_server rm -rf -- "${base:?}/$s" || echo "repair: cannot prune $s"; fi
    done || true
    return 0
}

# N6: the reason for a repair, or nothing. Deciding on the crash history clears it.
repair_reason() {
    local dir=$1 rc=$2 now=$3
    if (( rc == 2 )); then
        echo "the server failed to start three times in a row (the boot guard had already tried quarantine and safe mode)"
    elif (( $(repair_crash_count "$dir" "$now") >= REPAIR_CRASHES )); then
        : | server_write "$dir/claude-crash-history" >/dev/null || true
        echo "the server crashed $REPAIR_CRASHES times within $(( REPAIR_CRASH_WINDOW / 60 )) minutes after loading"
    fi
    return 0
}

# N7: a claude-repair/current left by an interrupted start is rolled back before anything else.
repair_recover() {
    local dir=${1%/} cur=${1%/}/claude-repair/current stamp= rd
    [[ -e $cur || -L $cur ]] || return 0
    [[ -f $cur && ! -L $cur ]] && IFS= read -r stamp < "$cur"
    rd=$dir/claude-repair/$stamp
    if [[ $stamp =~ ^[0-9]{8}T[0-9]{6}Z$ && -d $rd && ! -L $rd ]]; then
        echo "repair: repair $stamp was interrupted; rolling back its changes"
        repair_rollback "$dir" "$rd"
        repair_record_changes "$dir" "$rd"
        repair_report "$dir" "$rd" "interrupted; rolled back" "the container stopped during the repair" 0
        repair_index_add "$dir" "$stamp"
        repair_incident_record "$dir" "$(date +%s)"
    fi
    rm -f -- "$cur"
    return 0
}

repair_changed_anything() { # RD: something was applied or a plugin is held
    [[ -s $1/applied.txt ]] || [[ -n $(repair_held_now "$1") ]]
}

# R1-R7/N1-N7: one incident. rc 0 = repaired, the server runs as REPAIR_SERVER_PID; rc 1 = rolled back (halted only with MAYHALT=1);
# rc 3 = interrupted by TERM/INT: trial server stopped, rolled back.
repair_incident() {
    local dir=${1%/} reason=$2 mayhalt=${3:-0} stamp rd attempt=0 outcome=
    if [[ -z ${CLAUDE_CODE_OAUTH_TOKEN:-} ]] || ! command -v claude >/dev/null; then
        echo "repair: cannot run (no CLAUDE_CODE_OAUTH_TOKEN or no claude CLI)"
        (( mayhalt )) && repair_halt "$dir" "repair could not run (no token or no claude CLI): $reason"
        return 1
    fi
    if ! path_safe "$dir" "$dir/claude-repair" >/dev/null; then
        (( mayhalt )) && repair_halt "$dir" "claude-repair is a link; not repairing"
        return 1
    fi
    repair_prune "$dir" "$(date +%s)"
    stamp=$(date -u +%Y%m%dT%H%M%SZ); rd=$dir/claude-repair/$stamp
    if ! as_server mkdir -p -- "$rd"; then
        (( mayhalt )) && repair_halt "$dir" "cannot create $rd"
        return 1
    fi
    REPAIR_STOP=0
    trap repair_interrupt TERM INT
    echo "$stamp" | server_write "$dir/claude-repair/current" >/dev/null
    repair_cli_access
    echo "repair: $reason; starting repair $stamp (up to $REPAIR_ATTEMPTS attempts)"
    while (( attempt < REPAIR_ATTEMPTS && ! REPAIR_STOP )); do
        attempt=$(( attempt + 1 ))
        repair_work_build "$dir" "$rd" "$attempt"
        repair_run_claude "$dir" "$rd" "$attempt" "$reason"
        repair_kill_all
        (( REPAIR_STOP )) && break
        repair_apply "$dir" "$rd" "$attempt"
        repair_requests "$dir" "$rd" "$attempt"
        repair_record_changes "$dir" "$rd"
        if repair_server_trial "$dir" && (( ! REPAIR_STOP )); then outcome=repaired; break; fi
    done
    repair_cli_access -x
    # A stop after the trial succeeded leaves the repair standing; repair_wait_server forwards it.
    if (( REPAIR_STOP )) && [[ $outcome != repaired ]]; then
        outcome="interrupted; rolled back"
        repair_stop_server
    fi
    if [[ $outcome != repaired ]]; then
        [[ -n $outcome ]] || outcome="rolled back"
        repair_rollback "$dir" "$rd"
        repair_record_changes "$dir" "$rd"
    fi
    # N6/O7: a repair that stood without changing anything is not an incident and has nothing to report.
    if [[ $outcome != repaired ]] || repair_changed_anything "$rd"; then
        repair_report "$dir" "$rd" "$outcome" "$reason" "$attempt"
        repair_index_add "$dir" "$stamp"
        repair_incident_record "$dir" "$(date +%s)"
    fi
    as_server rm -f -- "$dir/claude-repair/current"
    [[ $outcome == repaired ]] || trap - TERM INT
    case $outcome in
        repaired) echo "repair: repaired after $attempt attempt(s); see $rd/report.md"; return 0 ;;
        interrupted*) echo "repair: interrupted; every change was rolled back"; return 3 ;;
    esac
    if (( mayhalt )); then
        repair_halt "$dir" "all $attempt repair attempts failed; every change was rolled back. Report:" "$rd/report.md"
        echo "repair: all $attempt attempts failed; rolled back and halted. See $rd/report.md"
    else
        echo "repair: all $attempt attempts failed; rolled back. Starting the server normally. See $rd/report.md"
    fi
    return 1
}

# After a repair stands: the entrypoint waits on the server it started, forwarding a stop.
repair_wait_server() {
    trap 'as_server kill -TERM "$REPAIR_SERVER_PID" 2>/dev/null' TERM INT
    (( ${REPAIR_STOP:-0} )) && { as_server kill -TERM "$REPAIR_SERVER_PID" 2>/dev/null || true; }
    local rc=0
    while repair_alive; do wait "$REPAIR_SERVER_PID" || rc=$?; done
    return "$rc"
}
