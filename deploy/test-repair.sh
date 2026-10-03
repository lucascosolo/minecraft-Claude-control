# Repair mode tests (docs/PRD-v0.3.md R1-R9). SOURCED by test-entrypoint.sh: shares T, here, fresh, check, yn, mkjar, rec/unrec.
# Every call is guarded so a missing function fails its case instead of aborting the run.
# shellcheck shell=bash disable=SC2154,SC2034

as_server() { "$@"; }
real_as_repair=$(declare -f as_repair)   # captured BEFORE the override below
as_repair() { "$@"; }
saved_launch=$(declare -f launch_command)
saved_path=$PATH
unset REPAIR_SERVER_PID
mkdir -p "$T/bin" "$T/rhome"
printf 'PROMPT-MARKER-LINE\nfix the server\n' > "$T/prompt.md"
RTOKEN=sk-ant-test-XYZ
# helper wrapper (claude-repair in the tree is not executable); REPAIR_WORK is the session's private copy
printf '#!/bin/bash\nexec bash "%s/claude-repair" "$@"\n' "$here" > "$T/bin/claude-repair"; chmod +x "$T/bin/claude-repair"
export REPAIR_WORK=$T/rwork REPAIR_HELPER=$T/bin/claude-repair
export REPAIR_HOME=$T/rhome SRV_PID=$T/srv.pid STUB_PID=$T/claude.pid   # every fake server / stub session writes its own pid here, killed by exact pid only

rcof() { local rc=0; "$@" >/dev/null 2>&1 || rc=$?; echo "$rc"; }
killpidfile() { local p; [[ -s $1 ]] && p=$(cat "$1") && [[ $p =~ ^[0-9]+$ ]] && kill "$p" 2>/dev/null; rm -f "$1"; return 0; }
pidalive() { local p; [[ -s $1 ]] && p=$(cat "$1") && [[ $p =~ ^[0-9]+$ ]] && kill -0 "$p" 2>/dev/null && echo yes || echo no; }
killserver() { if [[ -n ${REPAIR_SERVER_PID:-} ]]; then kill "$REPAIR_SERVER_PID" 2>/dev/null || true; fi; unset REPAIR_SERVER_PID; killpidfile "$SRV_PID"; killpidfile "$STUB_PID"; }

# ---------------- R1: crash step / count ----------------
NOW=1000000
fresh; touch "$S/claude-clean-stop" "$S/claude-boot-ok"; echo 999900 > "$S/claude-crash-history"
out=$(REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW 2>&1); rc=$?
check "R1 clean-stop: removed, not counted, history untouched" "0 yes 999900" "$rc $(yn test ! -e "$S/claude-clean-stop") $(cat "$S/claude-crash-history" 2>/dev/null)"

fresh; echo x > "$T/elsewhere-cs"; ln -s "$T/elsewhere-cs" "$S/claude-clean-stop"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "R1 clean-stop link: name removed, target kept" "yes yes" "$(yn test ! -e "$S/claude-clean-stop" -a ! -L "$S/claude-clean-stop") $(yn test -f "$T/elsewhere-cs")"

fresh; touch "$S/claude-boot-ok"; printf '999000\n999900\n' > "$S/claude-crash-history"
out=$(REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW 2>&1); rc=$?
check "R1 crash: counted (history gains NOW)" "0 999000,999900,1000000" "$rc $(sort -n "$S/claude-crash-history" 2>/dev/null | paste -sd,)"
check "R1 crash: prints a repair: line" "yes" "$(grep -q '^repair:' <<<"$out" && echo yes || echo no)"

fresh; touch "$S/claude-boot-ok"; printf '100\n999900\n' > "$S/claude-crash-history"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "R1 crash: entries older than window pruned" "999900,1000000" "$(sort -n "$S/claude-crash-history" 2>/dev/null | paste -sd,)"

fresh; touch "$S/claude-boot-ok" "$S/claude-boot-pending"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1; rc=$?
check "R1 pending present: not counted" "0 no" "$rc $(yn test -s "$S/claude-crash-history")"

fresh
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1; rc=$?
check "R1 never loaded (no boot-ok): not counted" "0 no" "$rc $(yn test -s "$S/claude-crash-history")"

fresh; touch "$S/claude-boot-ok"; echo keep > "$T/elsewhere-hist"; ln -s "$T/elsewhere-hist" "$S/claude-crash-history"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "R1 symlinked history is not written through" "keep yes" "$(cat "$T/elsewhere-hist") $(yn test -L "$S/claude-crash-history")"

fresh; printf '100\n999900\njunk\n\n999950\n' > "$S/claude-crash-history"
check "R1 count: numeric lines inside the window" "2" "$(REPAIR_CRASH_WINDOW=1800 repair_crash_count "$S" $NOW 2>/dev/null)"
fresh
check "R1 count: missing history is 0" "0" "$(REPAIR_CRASH_WINDOW=1800 repair_crash_count "$S" $NOW 2>/dev/null)"

# ---------------- O6: crash reports newer than boot-ok count even after a clean stop ----------------
fresh; touch -d '2 hours ago' "$S/claude-boot-ok"; touch "$S/claude-clean-stop"; mkdir "$S/crash-reports"; touch "$S/crash-reports/crash-1.txt"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "O6 clean-stop + newer crash report: counted, marker removed" "1000000 yes" "$(cat "$S/claude-crash-history" 2>/dev/null) $(yn test ! -e "$S/claude-clean-stop")"
fresh; touch "$S/claude-boot-ok"; touch "$S/claude-clean-stop"; mkdir "$S/crash-reports"; touch -d '2 hours ago' "$S/crash-reports/crash-1.txt"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "O6 clean-stop + older crash report: not counted, marker removed" "no yes" "$(yn test -s "$S/claude-crash-history") $(yn test ! -e "$S/claude-clean-stop")"
fresh; touch -d '2 hours ago' "$S/claude-boot-ok"; touch "$S/claude-clean-stop"; mkdir "$S/crash-reports"; echo x > "$T/o6-target"; ln -s "$T/o6-target" "$S/crash-reports/crash-1.txt"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "O6 clean-stop + newer crash-report SYMLINK: not counted" "no" "$(yn test -s "$S/claude-crash-history")"
fresh; touch -d '2 hours ago' "$S/claude-boot-ok"; touch "$S/claude-boot-pending" "$S/claude-clean-stop"; mkdir "$S/crash-reports"; touch "$S/crash-reports/crash-1.txt"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "O6 boot-pending + newer crash report: still not counted (never loaded)" "no yes" "$(yn test -s "$S/claude-crash-history") $(yn test ! -e "$S/claude-clean-stop")"
fresh; touch "$S/claude-clean-stop"; mkdir "$S/crash-reports"; touch "$S/crash-reports/crash-1.txt"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "O6 no boot-ok: not counted even with a crash report" "no" "$(yn test -s "$S/claude-crash-history")"
fresh; touch -d '2 hours ago' "$S/claude-boot-ok"; mkdir "$S/crash-reports"; touch "$S/crash-reports/crash-1.txt"
REPAIR_CRASH_WINDOW=1800 repair_crash_step "$S" $NOW >/dev/null 2>&1
check "O6 no clean-stop + crash report newer: counted once" "1000000" "$(cat "$S/claude-crash-history" 2>/dev/null)"
fresh; touch "$S/claude-clean-stop"
rc=0; repair_clear_clean_stop "$S" >/dev/null 2>&1 || rc=$?
check "O6 clear_clean_stop: regular marker removed, rc 0" "0 yes" "$rc $(yn test ! -e "$S/claude-clean-stop")"
fresh; echo keep > "$T/o6-keep"; ln -s "$T/o6-keep" "$S/claude-clean-stop"
repair_clear_clean_stop "$S" >/dev/null 2>&1
check "O6 clear_clean_stop: link removed, target kept" "yes yes" "$(yn test ! -e "$S/claude-clean-stop" -a ! -L "$S/claude-clean-stop") $(yn test -f "$T/o6-keep")"
fresh
check "O6 clear_clean_stop: absent marker, rc 0" "0" "$(rcof repair_clear_clean_stop "$S")"

# ---------------- R1: incident allowance ----------------
fresh; export REPAIR_INCIDENTS=2
check "R1 incident_allowed with none recorded" "0" "$(rcof repair_incident_allowed "$S" 200000)"
repair_incident_record "$S" 199990 >/dev/null 2>&1
check "R1 incident_record creates dir and file" "199990" "$(cat "$S/claude-repair/incidents" 2>/dev/null)"
check "R1 incident_allowed with one of two" "0" "$(rcof repair_incident_allowed "$S" 200000)"
repair_incident_record "$S" 199995 >/dev/null 2>&1
check "R1 incident_allowed at the limit -> 1" "1" "$(rcof repair_incident_allowed "$S" 200000)"
fresh; mkdir -p "$S/claude-repair"; printf '1\n2\n' > "$S/claude-repair/incidents"
check "R1 incident_allowed ignores entries older than 86400 s" "0" "$(rcof repair_incident_allowed "$S" 200000)"
unset REPAIR_INCIDENTS

# ---------------- N2: config file set (allowlist) ----------------
mkserver() { # fresh server with a realistic mix of files
    fresh; mkdir -p "$P/Geyser-Spigot/sub" "$P/NomadLabs" "$P/NomadCairn" "$P/ClaudeControl" "$P/claude-held" "$P/LuckPerms" "$P/SomePermsX" "$P/X" "$S/config/sub" "$S/world/data" "$S/world_nether" "$S/myworld" "$S/crash-reports" "$S/cache" "$S/libraries" "$S/versions" "$S/claude-repair"
    echo max-players=abc > "$S/server.properties"; echo "a: 1" > "$S/bukkit.yml"
    echo "g: 1" > "$P/Geyser-Spigot/config.yml"; echo "g: 2" > "$P/Geyser-Spigot/sub/config.yml"; echo "p: 1" > "$S/config/paper-global.yml"
    echo "w: 1" > "$S/config/paper-world-defaults.yml"; echo "t = 1" > "$S/config/sub/a.toml"; echo x > "$S/config/sub/readme.txt"
    echo "cm: 1" > "$S/commands.yml"; echo "hp: 1" > "$S/help.yml"; echo "pm: 1" > "$S/permissions.yml"; echo "l: 1" > "$P/LuckPerms/config.yml"; echo "s: 1" > "$P/SomePermsX/config.yml"
    echo "c: 1" > "$P/NomadCairn/config.yml"; echo "d: 1" > "$P/X/data.yml"; echo "cx: 1" > "$S/claude-x.yml"
    echo x > "$S/world/level.dat"; echo "w: 1" > "$S/world/w.yml"; echo "{}" > "$S/world/data/d.json"
    echo "n: 1" > "$S/world_nether/b.yml"; echo x > "$S/myworld/level.dat"; echo "m: 1" > "$S/myworld/m.yml"
    echo "n: 1" > "$P/NomadLabs/n.yml"; echo "n: 1" > "$P/NomadLabs/config.yml"; echo "c: 1" > "$P/ClaudeControl/config.yml"; echo "h: 1" > "$P/claude-held/x.yml"
    echo "[]" > "$S/ops.json"; echo "[]" > "$S/whitelist.json"; echo "[]" > "$S/banned-players.json"; echo "[]" > "$S/usercache.json"
    echo "{}" > "$S/logs/l.json"; echo "{}" > "$S/crash-reports/c.json"; echo "{}" > "$S/cache/c.json"
    echo "{}" > "$S/libraries/l.json"; echo "{}" > "$S/versions/v.json"; echo "{}" > "$S/claude-repair/r.json"
    ln -s "$S/bukkit.yml" "$S/sym.yml"; echo "h: 1" > "$S/hl-a.yml"; ln "$S/hl-a.yml" "$S/hl-b.yml"
    mkjar "$P/ThirdParty.jar" ThirdParty x.T; mkjar "$P/claude-held/Held.jar" Held x.H
}
mkserver
got=$(repair_config_files "$S" 2>/dev/null | LC_ALL=C sort | paste -sd,)
check "N2 config_files: exactly the allowlist" "bukkit.yml,config/paper-global.yml,config/paper-world-defaults.yml,config/sub/a.toml,plugins/Geyser-Spigot/config.yml,server.properties" "$got"
for ex in commands.yml help.yml permissions.yml plugins/LuckPerms/config.yml plugins/SomePermsX/config.yml plugins/NomadCairn/config.yml plugins/NomadLabs/config.yml plugins/ClaudeControl/config.yml plugins/X/data.yml plugins/Geyser-Spigot/sub/config.yml claude-x.yml config/sub/readme.txt ops.json sym.yml hl-a.yml hl-b.yml world/w.yml; do
    check "N2 config_files: excludes $ex" "no" "$(repair_config_files "$S" 2>/dev/null | grep -qxF "$ex" && echo yes || echo no)"
done
check "N2 config_files: output is sorted" "yes" "$(repair_config_files "$S" 2>/dev/null | LC_ALL=C sort -c 2>/dev/null && echo yes || echo no)"
check "N2 config_files: rc 0" "0" "$(rcof repair_config_files "$S")"
mkserver; printf 'x' > "$S/nl"$'\n'"name.yml"
check "N2 config_files: newline in a name is skipped" "no" "$(repair_config_files "$S" 2>/dev/null | grep -q 'name.yml' && echo yes || echo no)"
mkserver; rm -rf "$S/config"; mkdir "$T/outcfg2"; echo "z: 1" > "$T/outcfg2/z.yml"; ln -s "$T/outcfg2" "$S/config"
check "N2 config_files: symlinked config/ dir is not followed" "no" "$(repair_config_files "$S" 2>/dev/null | grep -q 'z.yml' && echo yes || echo no)"
rm -rf "$T/outcfg2"

# ---------------- O: repair_enabled (round O1) ----------------
en() { ( if [[ $1 == UNSET ]]; then unset CLAUDE_REPAIR; else export CLAUDE_REPAIR=$1; fi; repair_enabled >/dev/null 2>&1; echo $? ); }
check "O1 repair_enabled: exactly 'on' -> rc 0" "0" "$(en on)"
for v in UNSET off ON On 1 yes true "" "on "; do check "O1 repair_enabled: '$v' -> rc 1" "1" "$(en "$v")"; done

# ---------------- O4: repair_mask ----------------
meq() { # NAME REL INPUT EXPECTED : byte-exact comparison
    printf '%s' "$3" > "$T/m-in"; printf '%s' "$4" > "$T/m-exp"
    if ! declare -F repair_mask >/dev/null; then check "$1" yes NOFUNC; return; fi
    repair_mask "$2" "$T/m-in" > "$T/m-out" 2>/dev/null
    check "$1" yes "$(yn cmp -s "$T/m-out" "$T/m-exp")"
}
meq "O4 mask yaml: api-key value hidden" x.yml $'api-key: abc\n' $'api-key: <hidden>\n'
meq "O4 mask yaml: url userinfo hides only that line, children by path" x.yml $'database:\n  url: mysql://u:p@h/db\n  user: bob\n' $'database:\n  url: <hidden>\n  user: bob\n'
meq "O4 mask yaml: sensitive parent masks children" x.yaml $'credentials:\n  user: bob\n' $'credentials:\n  user: <hidden>\n'
meq "O4 mask yaml: list item under sensitive key" x.yml $'secrets:\n  - abc\n' $'secrets:\n  - <hidden>\n'
meq "O4 mask yaml: list at parent's indent is under that key" x.yml $'tokens:\n- a\n- b\nplain:\n- c\n' $'tokens:\n- <hidden>\n- <hidden>\nplain:\n- c\n'
meq "O4 mask yaml: block scalar text masked" x.yml $'token: |\n  line1\n  line2\nother: 1\n' $'token: <hidden>\n  <hidden>\n  <hidden>\nother: 1\n'
meq "O4 mask yaml: keep-alive is not a key word, monkey is" x.yml $'keep-alive: 5\nmonkey: 1\n' $'keep-alive: 5\nmonkey: <hidden>\n'
meq "O4 mask yaml: case-insensitive, quoted key kept as written" x.yml $'"My-Token": abc\nPassWord: z\n' $'"My-Token": <hidden>\nPassWord: <hidden>\n'
meq "O4 mask yaml: children of a list-item mapping are under the item path" x.yml $'users:\n  - name: a\n    password: p\n    note: n\n' $'users:\n  - name: a\n    password: <hidden>\n    note: n\n'
meq "O4 mask yaml: comment masked only for url userinfo" x.yml $'# password: x\n  # see http://u:p@h/x\n\nz: 1\n' $'# password: x\n  # <hidden>\n\nz: 1\n'
meq "O4 mask yaml: CR kept after <hidden>" x.yml $'password: x\r\nlang: en\r\n' $'password: <hidden>\r\nlang: en\r\n'
meq "O4 mask yaml: nothing masked -> byte-identical (no final newline)" x.yml $'a: 1\nb:\n  c: 2' $'a: 1\nb:\n  c: 2'
meq "O4 mask yaml: nothing masked -> byte-identical (CRLF)" x.yml $'a: 1\r\nb: 2\r\n' $'a: 1\r\nb: 2\r\n'
meq "O4 mask props: password key" server.properties $'rcon.password=hunter2\nmotd=hi\n' $'rcon.password=<hidden>\nmotd=hi\n'
meq "O4 mask props: continuation lines of a masked line" x.properties $'pw-token=abc\\\ndef\nx=1\n' $'pw-token=<hidden>\n<hidden>\nx=1\n'
meq "O4 mask props: colon/space separators and leading space" x.properties $'secret-key: abc\n  token = z\n' $'secret-key=<hidden>\n  token=<hidden>\n'
meq "O4 mask props: url userinfo in a plain key and in a comment" x.properties $'link=http://u:p@h/x\n# http://u:p@h/y\nok=1\n' $'link=<hidden>\n# <hidden>\nok=1\n'
meq "O4 mask props: untouched file is byte-identical (CRLF, no final newline)" x.properties $'a=1\r\nb=2' $'a=1\r\nb=2'
meq "O4 mask toml: masked key keeps spacing" x.toml $'[db]\npassword = "x"\nport = 1\n' $'[db]\npassword = <hidden>\nport = 1\n'
meq "O4 mask toml: sensitive section name masks its keys" x.toml $'[auth]\nuser = "a"\n[other]\nn = 1\n' $'[auth]\nuser = <hidden>\n[other]\nn = 1\n'
meq "O4 mask json: sensitive key and url line" x.json $'{\n  "apiKey": "abc",\n  "n": 1,\n  "u": "http://a:b@h/x"\n}\n' $'{\n  "apiKey": <hidden>\n  "n": 1,\n  <hidden>\n}\n'
printf 'a: 1\0\nb: 2\n' > "$T/m-bin"
check "O4 mask: NUL byte -> single <hidden binary file> line" "<hidden binary file>" "$(repair_mask x.yml "$T/m-bin" 2>/dev/null || echo NOFUNC)"
printf 'a: 1\npassword: x\n\n# c\n  - z\nb: 2\n' > "$T/m-in"
check "O4 mask: same number of lines" "6 6" "$(wc -l < "$T/m-in") $(repair_mask x.yml "$T/m-in" 2>/dev/null | wc -l)"
check "O4 mask: rc 0" "0" "$(rcof repair_mask x.yml "$T/m-in")"

# ---------------- O2/O3: repair_check ----------------
ckfile() { # REL (uses $T/ck-old, $T/ck-new) -> comma-joined reasons
    declare -F repair_check >/dev/null || { echo NOFUNC; return; }
    local out rc=0; out=$(repair_check "$1" "$T/ck-old" "$T/ck-new" 2>/dev/null) || rc=$?
    ((rc == 0)) || { echo "RC$rc"; return; }
    paste -sd, <<<"$out"
}
ck() { printf '%s' "$2" > "$T/ck-old"; printf '%s' "$3" > "$T/ck-new"; ckfile "$1"; }
ckhas() { local o; o=$(ck "$1" "$2" "$3"); [[ ",$o," == *",$4"* ]] && echo yes || echo no; }
ckne() { local o; o=$(ck "$1" "$2" "$3"); [[ -n $o && $o != NOFUNC && $o != RC* ]] && echo yes || echo no; }
SP=server.properties; PX=plugins/X/config.yml
# allowed
check "O2 check: identical -> empty" "" "$(ck $SP $'max-players=20\n' $'max-players=20\n')"
check "O2 check: unchanged file with a secret -> empty (NEW = mask(OLD))" "" "$(ck $PX $'api-key: s3cret\nlang: en\n' $'api-key: <hidden>\nlang: en\n')"
check "O3 check: max-players=abc -> 20 is allowed" "" "$(ck $SP $'max-players=abc\n' $'max-players=20\n')"
check "O2 check: spigot debug false->true allowed" "" "$(ck spigot.yml $'settings:\n  debug: false\n  bungeecord: false\n' $'settings:\n  debug: true\n  bungeecord: false\n')"
check "O2 check: plugin g: 1 -> g: edited allowed" "" "$(ck $PX $'g: 1\n' $'g: edited\n')"
check "O2 check: adding a plain new key allowed" "" "$(ck $PX $'g: 1\n' $'g: 1\na: 1\n')"
check "O2 check: CRLF file with a valid change allowed" "" "$(ck $SP $'max-players=abc\r\n' $'max-players=20\r\n')"
check "O2 check: 64-char value allowed" "" "$(ck $PX $'g: 1\n' "g: $(head -c 64 /dev/zero | tr '\0' a)"$'\n')"
check "O2 check: bukkit.yml bungeecord is not a spigot-only rule" "" "$(ck bukkit.yml $'settings:\n  bungeecord: false\n' $'settings:\n  bungeecord: true\n')"
check "O2 check: plugin proxies key is not protected (rule is config/ only)" "" "$(ck $PX $'proxies:\n  foo: 1\n' $'proxies:\n  foo: 2\n')"
check "O2 check: unrelated config change next to unchanged masked line" "" "$(ck $PX $'api-key: s3cret\nlang: en\n' $'api-key: <hidden>\nlang: de\n')"
check "O2 check: untouched url-userinfo value is fine" "" "$(ck $PX $'db: mysql://u:p@h/x\nlang: en\n' $'db: <hidden>\nlang: de\n')"
check "O2 check: rc 0 even when refusing" "0" "$(rcof repair_check $SP "$T/ck-old" "$T/ck-new")"
# server.properties default-deny
check "O3 check: online-mode true->false" "not-allowed:online-mode" "$(ck $SP $'online-mode=true\n' $'online-mode=false\n')"
check "O3 check: motd change" "not-allowed:motd" "$(ck $SP $'motd=a\n' $'motd=b\n')"
check "O3 check: enable-command-block" "not-allowed:enable-command-block" "$(ck $SP $'enable-command-block=false\n' $'enable-command-block=true\n')"
check "O3 check: appended duplicate online-mode" "not-allowed:online-mode" "$(ck $SP $'max-players=20\n' $'max-players=20\nonline-mode=false\n')"
check "O3 check: deleted white-list line" "not-allowed:white-list" "$(ck $SP $'white-list=true\nmotd=x\n' $'motd=x\n')"
check "O3 check: CRLF online-mode change" "not-allowed:online-mode" "$(ck $SP $'online-mode=true\r\n' $'online-mode=false\r\n')"
check "O3 check: sorted and unique across lines" "not-allowed:motd,not-allowed:online-mode" "$(ck $SP $'online-mode=true\nmotd=a\nmax-players=1\n' $'online-mode=false\nmotd=b\nmax-players=2\n')"
# masked
check "O4 check: masked key edited" "yes" "$(ckhas $PX $'api-key: abc\nlang: en\n' $'api-key: abc\nlang: en\n' masked:api-key)"
check "O4 check: masked key edited (value changed)" "yes" "$(ckhas $PX $'api-key: abc\n' $'api-key: xyz\n' masked:api-key)"
check "O4 check: masked line deleted" "yes" "$(ckhas $PX $'api-key: abc\nlang: en\n' $'lang: en\n' masked)"
check "O4 check: adding webhook-url" "masked:webhook-url" "$(ck $PX $'g: 1\n' $'g: 1\nwebhook-url: x\n')"
check "O4 check: rcon.password edited" "yes" "$(ckhas $SP $'rcon.password=a\n' $'rcon.password=b\n' masked:rcon.password)"
check "O4 check: plugin api-token edited" "yes" "$(ckhas $PX $'api-token: a\nlang: en\n' $'api-token: b\nlang: en\n' masked:api-token)"
check "O4 check: paper proxies.velocity.secret edited" "yes" "$(ckhas config/paper-global.yml $'proxies:\n  velocity:\n    secret: a\n' $'proxies:\n  velocity:\n    secret: b\n' masked:proxies.velocity.secret)"
check "O4 check: url-userinfo value edited" "yes" "$(ckhas $PX $'db: mysql://u:p@h/x\n' $'db: mysql://u:p@h/y\n' masked:db)"
# protected paths
check "O2 check: spigot settings.bungeecord nested" "protected:settings.bungeecord" "$(ck spigot.yml $'settings:\n  bungeecord: false\n  debug: false\n' $'settings:\n  bungeecord: true\n  debug: false\n')"
check "O2 check: spigot settings.restart-script" "protected:settings.restart-script" "$(ck spigot.yml $'settings:\n  restart-script: ./a.sh\n' $'settings:\n  restart-script: ./b.sh\n')"
check "O2 check: deleted settings.bungeecord line" "protected:settings.bungeecord" "$(ck spigot.yml $'settings:\n  bungeecord: false\n  debug: false\n' $'settings:\n  debug: false\n')"
check "O2 check: flow map under settings (ancestor)" "yes" "$(ckhas spigot.yml $'settings:\n  bungeecord: false\n' $'settings: {bungeecord: true}\n' protected:settings)"
check "O2 check: duplicate settings block appended" "yes" "$(ckhas spigot.yml $'settings:\n  debug: false\n' $'settings:\n  debug: false\nsettings:\n  bungeecord: true\n' protected:)"
check "O2 check: key moved between sections is moved:" "yes" "$(ckhas spigot.yml $'other:\n  bungeecord: true\nsettings:\n  debug: false\n' $'settings:\n  bungeecord: true\nsettings:\n  debug: false\n' moved:settings.bungeecord)"
check "O2 check: paper proxies.velocity.enabled" "protected:proxies.velocity.enabled" "$(ck config/paper-global.yml $'proxies:\n  velocity:\n    enabled: false\n' $'proxies:\n  velocity:\n    enabled: true\n')"
check "O2 check: command-ish parent protects a child edit" "yes" "$(ckhas $PX $'commands:\n  - name: a\n    run: x\n' $'commands:\n  - name: a\n    run: y\n' protected:)"
check "O2 check: command-ish key" "yes" "$(ckhas $PX $'on-command: x\n' $'on-command: y\n' protected:on-command)"
check "O2 check: block scalar text under restart-script changed is refused" "yes" "$(ckne spigot.yml $'settings:\n  restart-script: |\n    ./a.sh\n' $'settings:\n  restart-script: |\n    ./b.sh\n')"
# shape
check "O2 check: list item '- command: x' under commands changed" "shape:+2,shape:-2" "$(ck $PX $'commands:\n  - command: x\n' $'commands:\n  - command: y\n')"
check "O2 check: list item change is a shape refusal" "shape:+4" "$(ck $PX $'lang: en\nlist:\n  - a\n' $'lang: de\nlist:\n  - a\n  - b\n')"
check "O2 check: comment-only change" "shape:+1,shape:-1" "$(ck $PX $'# a\ng: 1\n' $'# b\ng: 1\n')"
check "O2 check: blank line added" "yes" "$(ckhas $PX $'g: 1\n' $'g: 1\n\n' shape:+2)"
check "O2 check: 'online-mode false' (space separator)" "yes" "$(ckhas $SP $'online-mode=true\n' $'online-mode false\n' shape:+1)"
check "O2 check: 'online\\-mode=false' (backslash)" "yes" "$(ckhas $SP $'online-mode=true\n' $'online\\-mode=false\n' shape:+1)"
check "O2 check: 'online-mode:false' (colon in props)" "yes" "$(ckhas $SP $'online-mode=true\n' $'online-mode:false\n' shape:+1)"
check "O2 check: tab in value" "yes" "$(ckhas $PX $'g: 1\n' $'g: a\tb\n' shape:+1)"
check "O2 check: quoted value" "yes" "$(ckhas $PX $'g: 1\n' $'g: "x"\n' shape:+1)"
check "O2 check: non-ASCII value" "yes" "$(ckhas $PX $'g: 1\n' $'g: caf\xc3\xa9\n' shape:+1)"
check "O2 check: 65-char value" "shape:+1" "$(ck $PX $'g: 1\n' "g: $(head -c 65 /dev/zero | tr '\0' a)"$'\n')"
check "O2 check: flow mapping value" "yes" "$(ckhas $PX $'g: 1\n' $'g: {a: 1}\n' shape:+1)"
check "O2 check: yaml merge key" "yes" "$(ckhas $PX $'g: 1\n' $'g: 1\n<<: z\n' alias)"
check "O2 check: props value over 64 chars" "shape:+1" "$(ck $SP $'max-players=1\n' "max-players=$(head -c 65 /dev/zero | tr '\0' 1)"$'\n')"
# round O: in-place value repair (k-for-k replace hunk, same key path, new line clean)
PG=config/paper-global.yml
check "RO check: paper bad flow value repaired in place" "" "$(ck $PG $'misc:\n  max-joins-per-tick: [unclosed\n' $'misc:\n  max-joins-per-tick: 5\n')"
check "RO check: quoted bad value repaired in place" "" "$(ck $PX $'  x: "a b"\n' $'  x: 5\n')"
check "RO check: props bad value repaired in place" "" "$(ck $SP $'max-players=abc def\n' $'max-players=20\n')"
check "RO check: key renamed in place -> shape:-2" "shape:-2" "$(ck $PG $'misc:\n  max-joins-per-tick: [x\n' $'misc:\n  other: 5\n')"
check "RO check: bad line deleted without replacement -> shape:-2" "shape:-2" "$(ck $PX $'a: 1\n  a: [x\n' $'a: 1\n')"
o=$(ck $PX $'k:\n  a: [x\n' $'k:\n  a: {y}\n')
check "RO check: replacement that is itself bad keeps both shapes" "yes yes" "$([[ ",$o," == *,shape:+2,* ]] && echo yes || echo no) $([[ ",$o," == *,shape:-2,* ]] && echo yes || echo no)"
o=$(ck $PX $'  a: [x\n  b: [y\n' $'  a: 1\n')
check "RO check: 2-for-1 hunk is not a replace, both removed lines refused" "yes yes" "$([[ $o == *shape:-1* ]] && echo yes || echo no) $([[ $o == *shape:-2* ]] && echo yes || echo no)"
check "RO check: protected still wins over in-place repair" "protected:settings.bungeecord" "$(ck spigot.yml $'settings:\n  bungeecord: [x\n' $'settings:\n  bungeecord: true\n')"
check "RO check: props not-allowed still wins over in-place repair" "not-allowed:online-mode" "$(ck $SP $'online-mode=tr ue\n' $'online-mode=true\n')"
check "RO check: list item never qualifies" "yes" "$(ckhas $PX $'cmds:\n  - [x\n' $'cmds:\n  - y\n' shape:)"

# continuation
check "O2 check: props continuation smuggle" "continuation:+2,continuation:-2" "$(ck $SP $'motd=hi\\\nmax-players=1\n' $'motd=hi\\\nmax-players=2\n')"
# read-only / binary / lone-cr / alias
check "O2 check: json change is read-only" "yes" "$(ckhas config/x.json $'{"a": 1}\n' $'{"a": 2}\n' read-only)"
check "O2 check: toml change is read-only" "yes" "$(ckhas config/x.toml $'a = 1\n' $'a = 2\n' read-only)"
check "O2 check: json unchanged (NEW = mask(OLD)) is empty" "" "$(ck config/x.json $'{"a": 1}\n' $'{"a": 1}\n')"
printf 'a=1\0\n' > "$T/ck-old"; printf 'a=2\0\n' > "$T/ck-new"
o=$(ckfile $SP); check "O2 check: NUL byte -> binary" "yes" "$([[ ",$o," == *,binary,* ]] && echo yes || echo no)"
check "O2 check: lone CR smuggle in props" "yes" "$(ckhas $SP $'max-players=20\n' $'max-players=20\ronline-mode=false\n' lone-cr)"
check "O2 check: CR at end of file with no LF" "yes" "$(ckhas $SP $'max-players=20\n' $'max-players=20\r' lone-cr)"
check "O2 check: lone CR already in OLD" "yes" "$(ckhas $SP $'a=1\rb=2\n' $'a=1\rb=3\n' lone-cr)"
check "O2 check: yaml anchor/alias" "alias" "$(ck $PX $'a: &x 1\nb: *x\n' $'a: &x 1\nb: *x\nc: 2\n')"

# ---------------- O4: repair_merge ----------------
mgeq() { # NAME REL OLD NEW EXPECTED
    printf '%s' "$3" > "$T/mg-old"; printf '%s' "$4" > "$T/mg-new"; printf '%s' "$5" > "$T/mg-exp"
    if ! declare -F repair_merge >/dev/null; then check "$1" yes NOFUNC; return; fi
    repair_merge "$2" "$T/mg-old" "$T/mg-new" > "$T/mg-out" 2>/dev/null
    check "$1" yes "$(yn cmp -s "$T/mg-out" "$T/mg-exp")"
}
mgeq "O4 merge: masked line restored, other edit kept" x.yml $'api-key: s3cret\nlang: en\n' $'api-key: <hidden>\nlang: de\n' $'api-key: s3cret\nlang: de\n'
mgeq "O4 merge: CRLF kept on restored lines" x.yml $'password: pw\r\nlang: en\r\n' $'password: <hidden>\r\nlang: de\r\n' $'password: pw\r\nlang: de\r\n'
mgeq "O4 merge: no final newline in NEW -> none in output" x.properties $'rcon.password=p\nmax-players=1\n' $'rcon.password=<hidden>\nmax-players=2' $'rcon.password=p\nmax-players=2'
mgeq "O4 merge: added line kept, block-scalar text restored" x.yml $'token: |\n  abc\n' $'token: <hidden>\n  <hidden>\nnew: 1\n' $'token: |\n  abc\nnew: 1\n'

# ---------------- N5: repair_redact ----------------
FAKETOK=tok-QWERTYUIOPASDFG-1234567890xyz
export CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
nowin() { # path...: prints clean when no 12-character window of FAKETOK occurs under the paths
    local i; for ((i = 0; i <= ${#FAKETOK} - 12; i++)); do
        if grep -rqF -e "${FAKETOK:i:12}" "$@" 2>/dev/null; then echo "leak@$i"; return; fi
    done; echo clean
}
check "N5 redact: whole token and sk-ant pattern" "a [redacted] b [redacted] c" "$(echo "a $FAKETOK b sk-ant-api03_XYZ-9 c" | repair_redact 2>/dev/null)"
check "N5 redact: a 12-char window of the token" "x [redacted] y" "$(echo "x ${FAKETOK:5:12} y" | repair_redact 2>/dev/null)"
check "N5 redact: window at the very end of the token" "[redacted]" "$(echo "${FAKETOK: -12}" | repair_redact 2>/dev/null)"
export CLAUDE_CODE_OAUTH_TOKEN=
check "N5 redact: empty token leaves text, pattern still redacted" "keep  and [redacted]" "$(echo "keep  and sk-ant-zzz" | repair_redact 2>/dev/null)"
unset CLAUDE_CODE_OAUTH_TOKEN
check "N5 redact: unset token leaves text, pattern still redacted" "keep [redacted]" "$(echo "keep sk-ant-zzz" | repair_redact 2>/dev/null)"

# ---------------- O1: as_repair (setpriv path and scrub switch are read before the env is cleared) ----------------
printf '#!/bin/bash\nprintf "ARGS %%s\\n" "$*"\nenv\n' > "$T/bin/fake-setpriv"; chmod +x "$T/bin/fake-setpriv"
asr() { # extra env assignments... ; prints fake-setpriv output
    ( eval "$real_as_repair"; export UNRELATED_VAR=zzz REPAIR_SETPRIV=$T/bin/fake-setpriv CLAUDE_CODE_OAUTH_TOKEN=tok-1; unset CLAUDE_REPAIR_ENV_SCRUB
      local kv; for kv in "$@"; do export "$kv"; done
      as_repair mycmd a b ) 2>/dev/null
}
asr_names() { asr "$@" | grep -v '^ARGS' | cut -d= -f1 | grep -vxE 'PWD|SHLVL|_|OLDPWD' | LC_ALL=C sort | paste -sd,; }
check "O1 as_repair: execs REPAIR_SETPRIV with reuid/regid/init-groups and the command" "ARGS --reuid=repair --regid=repair --init-groups mycmd a b" "$(asr | grep '^ARGS')"
check "O1 as_repair: environment is exactly the six names" "CLAUDE_CODE_OAUTH_TOKEN,CLAUDE_CODE_SUBPROCESS_ENV_SCRUB,DISABLE_AUTOUPDATER,HOME,LANG,PATH" "$(asr_names)"
check "O1 as_repair: scrub defaults to 1" "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1" "$(asr | grep '^CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=')"
check "O1 as_repair: CLAUDE_REPAIR_ENV_SCRUB=0 -> 0" "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=0" "$(asr CLAUDE_REPAIR_ENV_SCRUB=0 | grep '^CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=')"
check "O1 as_repair: CLAUDE_REPAIR_ENV_SCRUB=1 -> 1" "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1" "$(asr CLAUDE_REPAIR_ENV_SCRUB=1 | grep '^CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=')"
check "O1 as_repair: CLAUDE_REPAIR_ENV_SCRUB=yes -> 1" "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1" "$(asr CLAUDE_REPAIR_ENV_SCRUB=yes | grep '^CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=')"
check "O1 as_repair: scrub=0 still passes only the six names" "CLAUDE_CODE_OAUTH_TOKEN,CLAUDE_CODE_SUBPROCESS_ENV_SCRUB,DISABLE_AUTOUPDATER,HOME,LANG,PATH" "$(asr_names CLAUDE_REPAIR_ENV_SCRUB=0)"

# ---------------- N1: repair_work_build ----------------
wb() { repair_work_build "$S" "$RD" "${1:-1}" >/dev/null 2>&1; }
mkserver; RD=$S/claude-repair/20260101T000000Z; mkdir -p "$RD"
mkdir -p "$REPAIR_WORK/stale"; echo old > "$REPAIR_WORK/requests.json"; echo old > "$REPAIR_WORK/stale/f"
rm -rf "$S/crash-reports"; mkdir "$S/crash-reports"; for i in 1 2 3 4; do echo "crash $i" > "$S/crash-reports/crash-$i.txt"; touch -d "$((10 - i)) hours ago" "$S/crash-reports/crash-$i.txt"; done
echo "latest line" > "$L"; ln -s "$L" "$S/logs/linked.log"
rc=0; repair_work_build "$S" "$RD" 2 >/dev/null 2>&1 || rc=$?
check "N1 work_build: rc 0, stale work removed, no requests.json" "0 yes yes" "$rc $(yn test ! -e "$REPAIR_WORK/stale") $(yn test ! -e "$REPAIR_WORK/requests.json")"
check "N1 work_build: config copies are byte copies" "yes yes yes" "$(yn cmp -s "$S/server.properties" "$REPAIR_WORK/config/server.properties") $(yn cmp -s "$S/config/paper-global.yml" "$REPAIR_WORK/config/config/paper-global.yml") $(yn cmp -s "$P/Geyser-Spigot/config.yml" "$REPAIR_WORK/config/plugins/Geyser-Spigot/config.yml")"
check "N1 work_build: no copy of non-allowlisted files" "0" "$(find "$REPAIR_WORK/config" -type f 2>/dev/null | grep -cE '/(world[^/]*)/|ops\.json|NomadLabs|LuckPerms')"
check "N1 work_build: logs/latest.log copied, other logs not" "yes no" "$(yn cmp -s "$L" "$REPAIR_WORK/logs/latest.log") $(yn test -e "$REPAIR_WORK/logs/linked.log")"
check "N1 work_build: newest 3 crash reports only" "crash-2.txt,crash-3.txt,crash-4.txt" "$(ls "$REPAIR_WORK/logs/crash-reports" 2>/dev/null | paste -sd,)"
check "N1 work_build: plugins.txt lists jars and claude-held/<jar>" "ThirdParty.jar,claude-held/Held.jar" "$(LC_ALL=C sort "$REPAIR_WORK/plugins.txt" 2>/dev/null | paste -sd,)"
check "N1 work_build: session-N snapshot (files.txt and copies)" "yes yes yes" "$(yn test -s "$RD/session-2/files.txt") $(yn cmp -s "$S/server.properties" "$RD/session-2/server.properties") $(yn cmp -s "$P/Geyser-Spigot/config.yml" "$RD/session-2/plugins/Geyser-Spigot/config.yml")"
check "N1 work_build: files.txt equals repair_config_files" "yes" "$(diff <(LC_ALL=C sort "$RD/session-2/files.txt" 2>/dev/null) <(repair_config_files "$S" 2>/dev/null | LC_ALL=C sort) >/dev/null && echo yes || echo no)"

mkserver; RD=$S/claude-repair/20260101T000000Z; mkdir -p "$RD"
rm -f "$L"; echo outside > "$T/outside.log"; ln -s "$T/outside.log" "$L"
rm -rf "$S/crash-reports"; mkdir "$T/outcrash"; echo c > "$T/outcrash/crash-9.txt"; ln -s "$T/outcrash" "$S/crash-reports"
wb
check "N1 work_build: symlinked latest.log and crash-reports dir skipped" "no no" "$(yn test -e "$REPAIR_WORK/logs/latest.log") $(yn test -e "$REPAIR_WORK/logs/crash-reports/crash-9.txt")"
rm -rf "$T/outcrash"

mkserver; RD=$S/claude-repair/20260101T000000Z; mkdir -p "$RD"
head -c 300 /dev/urandom | base64 -w0 | head -c 300 > "$L"; head -c 200 /dev/zero | tr '\0' 'y' > "$P/Geyser-Spigot/config.yml"
REPAIR_FILE_CAP=100 wb
check "N1 work_build: oversize log keeps its last REPAIR_FILE_CAP bytes" "100 yes" "$(stat -c %s "$REPAIR_WORK/logs/latest.log" 2>/dev/null) $(cmp -s <(tail -c 100 "$L") "$REPAIR_WORK/logs/latest.log" && echo yes || echo no)"
check "N1 work_build: oversize config skipped" "no yes" "$(yn test -e "$REPAIR_WORK/config/plugins/Geyser-Spigot/config.yml") $(yn test -e "$REPAIR_WORK/config/server.properties")"

# O4: the work copy is masked, session-N stays a byte copy, log copies are redacted
mkserver; RD=$S/claude-repair/20260101T000000Z; mkdir -p "$RD"
printf 'api-key: s3cret\nlang: en\n' > "$P/X/config.yml"; printf 'rcon.password=hunter2\nmax-players=abc\n' > "$S/server.properties"
wb
check "O4 work_build: secrets are <hidden> in work/config, other lines intact" "api-key: <hidden>|lang: en|rcon.password=<hidden>|max-players=abc" "$(paste -sd'|' "$REPAIR_WORK/config/plugins/X/config.yml" 2>/dev/null)|$(paste -sd'|' "$REPAIR_WORK/config/server.properties" 2>/dev/null)"
check "O4 work_build: session-N snapshot keeps the real values (byte copy)" "yes yes" "$(yn cmp -s "$P/X/config.yml" "$RD/session-1/plugins/X/config.yml") $(yn cmp -s "$S/server.properties" "$RD/session-1/server.properties")"
check "O4 work_build: no real secret anywhere in work/" "0" "$(grep -rlE 's3cret|hunter2' "$REPAIR_WORK" 2>/dev/null | wc -l | tr -d ' ')"
mkserver; RD=$S/claude-repair/20260101T000000Z; mkdir -p "$RD"; rm -rf "$S/crash-reports"; mkdir "$S/crash-reports"
export CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
printf 'boot %s end\n' "$FAKETOK" > "$L"; printf 'crash %s and sk-ant-zzz-9\n' "${FAKETOK:4:14}" > "$S/crash-reports/crash-1.txt"
wb
check "O4 work_build: token windows in latest.log and crash reports are [redacted]" "clean yes yes" "$(nowin "$REPAIR_WORK/logs") $(grep -qF '[redacted]' "$REPAIR_WORK/logs/latest.log" 2>/dev/null && echo yes || echo no) $(grep -qF '[redacted]' "$REPAIR_WORK/logs/crash-reports/crash-1.txt" 2>/dev/null && echo yes || echo no)"
unset CLAUDE_CODE_OAUTH_TOKEN

# ---------------- N1/N3/N9: repair_apply ----------------
mkapply() { mkserver; RD=$S/claude-repair/20260101T000000Z; mkdir -p "$RD"; repair_work_build "$S" "$RD" 1 >/dev/null 2>&1; W=$REPAIR_WORK/config; }
ap() { repair_apply "$S" "$RD" "${1:-1}" >/dev/null 2>&1; }
mkapply; echo max-players=20 > "$W/server.properties"; echo "g: edited" > "$W/plugins/Geyser-Spigot/config.yml"
rc=0; repair_apply "$S" "$RD" 1 >/dev/null 2>&1 || rc=$?
check "N1 apply: edit goes live, rc 0" "0 max-players=20 g: edited" "$rc $(cat "$S/server.properties") $(cat "$P/Geyser-Spigot/config.yml")"
check "N1 apply: applied.txt lists N<TAB>rel" "1 plugins/Geyser-Spigot/config.yml,1 server.properties" "$(awk -F'\t' '{print $1 " " $2}' "$RD/applied.txt" 2>/dev/null | LC_ALL=C sort | paste -sd,)"
check "N1 apply: before/ holds the session-start copy" "max-players=abc g: 1" "$(cat "$RD/before/server.properties" 2>/dev/null) $(cat "$RD/before/plugins/Geyser-Spigot/config.yml" 2>/dev/null)"
check "N1 apply: untouched file is not applied" "no" "$(grep -q bukkit.yml "$RD/applied.txt" 2>/dev/null && echo yes || echo no)"
check "N1 apply: no stray temp files in live tree" "yes" "$(yn test -z "$(find "$S" -mindepth 1 -maxdepth 1 \( -name '*tmp*' -o -name '.server.properties*' -o -name '.claude-repair.*' \))")"

mkapply; echo $'max-players=20\nonline-mode=false' > "$W/server.properties"; cp "$S/server.properties" "$T/live.before"
rc=0; out=$(repair_apply "$S" "$RD" 1 2>&1) || rc=$?
check "O2 apply: server.properties not-allowed key refused, live untouched, rc 0" "0 yes" "$rc $(yn cmp -s "$S/server.properties" "$T/live.before")"
check "O2 apply: refused.txt is N<TAB>rel<TAB>reasons" "1 server.properties not-allowed:online-mode" "$(awk -F'\t' '{print $1 " " $2 " " $3}' "$RD/refused.txt" 2>/dev/null)"
check "O2 apply: not in applied.txt, 'repair: refused' printed" "no yes" "$(grep -q server.properties "$RD/applied.txt" 2>/dev/null && echo yes || echo no) $(grep -q '^repair: refused server.properties: ' <<<"$out" && echo yes || echo no)"
mkapply; echo $'max-players=20\nmotd=x\nonline-mode=false' > "$W/server.properties"; ap
check "O2 apply: several reasons are comma-joined on one refused.txt line" "1 server.properties not-allowed:motd,not-allowed:online-mode" "$(awk -F'\t' '{print $1 " " $2 " " $3}' "$RD/refused.txt" 2>/dev/null)"

# O4: masked values survive an apply; edits to a masked line, lone CR and list items are refused
mkapply; printf 'api-key: s3cret\nlang: en\n' > "$P/X/config.yml"; repair_work_build "$S" "$RD" 1 >/dev/null 2>&1
sed -i 's/lang: en/lang: de/' "$W/plugins/X/config.yml"; ap
check "O4 apply: masked line untouched + valid edit -> live has real secret and the edit" "api-key: s3cret|lang: de" "$(paste -sd'|' "$P/X/config.yml")"
mkapply; printf 'api-key: s3cret\nlang: en\n' > "$P/X/config.yml"; repair_work_build "$S" "$RD" 1 >/dev/null 2>&1; cp "$P/X/config.yml" "$T/x.before"
sed -i 's/<hidden>/abc/' "$W/plugins/X/config.yml"; ap
check "O4 apply: masked line edited -> refused, live byte-identical" "yes plugins/X/config.yml masked:api-key" "$(yn cmp -s "$P/X/config.yml" "$T/x.before") $(awk -F'\t' '{print $2 " " $3}' "$RD/refused.txt" 2>/dev/null)"
mkapply; cp "$S/server.properties" "$T/live.before"; printf 'max-players=20\ronline-mode=false\n' > "$W/server.properties"; ap
check "O2 apply: lone-CR smuggle -> refused, live byte-identical" "yes yes" "$(yn cmp -s "$S/server.properties" "$T/live.before") $(grep -q 'lone-cr' "$RD/refused.txt" 2>/dev/null && echo yes || echo no)"
mkapply; printf 'commands:\n  - command: x\n' > "$P/X/config.yml"; repair_work_build "$S" "$RD" 1 >/dev/null 2>&1; cp "$P/X/config.yml" "$T/x.before"
sed -i 's/command: x/command: y/' "$W/plugins/X/config.yml"; ap
check "O2 apply: list-item command change -> refused (shape), live byte-identical" "yes plugins/X/config.yml shape:+2,shape:-2" "$(yn cmp -s "$P/X/config.yml" "$T/x.before") $(awk -F'\t' '{print $2 " " $3}' "$RD/refused.txt" 2>/dev/null)"
mkapply; mkdir -p "$S/config"; printf 'misc:\n  max-joins-per-tick: [unclosed\n' > "$S/config/paper-global.yml"; repair_work_build "$S" "$RD" 1 >/dev/null 2>&1
printf 'misc:\n  max-joins-per-tick: 5\n' > "$W/config/paper-global.yml"; ap
check "RO apply: in-place repair of a malformed value goes live" "misc:|  max-joins-per-tick: 5 yes" "$(paste -sd'|' "$S/config/paper-global.yml") $(grep -q paper-global.yml "$RD/applied.txt" 2>/dev/null && echo yes || echo no)"
mkapply; chmod 0640 "$S/server.properties"; echo max-players=20 > "$W/server.properties"; ap
check "O7 apply: applied file keeps the live file's mode" "max-players=20 640" "$(cat "$S/server.properties") $(stat -c %a "$S/server.properties")"
mkapply; echo max-players=20 > "$W/server.properties"
repair_put_orig=$(declare -f repair_put 2>/dev/null)
if [[ -n $repair_put_orig ]]; then
    eval "orig_$repair_put_orig"
    repair_put() { grep -q 'server.properties' "$RD/applied.txt" 2>/dev/null && echo yes > "$T/put-order" || echo no > "$T/put-order"; orig_repair_put "$@"; }
fi
rm -f "$T/put-order"; ap
[[ -n $repair_put_orig ]] && eval "$repair_put_orig"
check "O7 apply: applied.txt entry is written BEFORE the file is put" "yes max-players=20" "$(cat "$T/put-order" 2>/dev/null) $(cat "$S/server.properties")"

mkapply; rm -f "$W/bukkit.yml"; echo "a: stolen" > "$T/linktarget.yml"; ln -s "$T/linktarget.yml" "$W/bukkit.yml"; ap
check "N1 apply: symlinked work file skipped" "a: 1 no" "$(cat "$S/bukkit.yml") $(grep -q bukkit.yml "$RD/applied.txt" 2>/dev/null && echo yes || echo no)"
mkapply; ln "$W/bukkit.yml" "$T/hardlink-of-work"; echo "a: hard" >> "$W/bukkit.yml"; ap
check "N1 apply: hardlinked work file skipped" "a: 1 no" "$(cat "$S/bukkit.yml") $(grep -q bukkit.yml "$RD/applied.txt" 2>/dev/null && echo yes || echo no)"
mkapply; mv "$W/config" "$T/wconfig-moved"; mkdir "$T/wlinkdir"; echo "p: evil" > "$T/wlinkdir/paper-global.yml"; ln -s "$T/wlinkdir" "$W/config"; ap
check "N1 apply: symlinked component in work path skipped" "p: 1" "$(cat "$S/config/paper-global.yml")"
mkapply; head -c 300 /dev/zero | tr '\0' 'a' > "$W/bukkit.yml"; REPAIR_FILE_CAP=100 ap
check "N1 apply: oversize work file skipped" "a: 1" "$(cat "$S/bukkit.yml")"

mkapply; echo max-players=20 > "$W/server.properties"; ap 1
repair_work_build "$S" "$RD" 2 >/dev/null 2>&1; echo max-players=30 > "$W/server.properties"; ap 2
check "N9 apply: before/ never overwritten by a later session" "max-players=abc max-players=30" "$(cat "$RD/before/server.properties" 2>/dev/null) $(cat "$S/server.properties")"
check "N9 apply: applied.txt has both sessions" "1,2" "$(awk -F'\t' '$2=="server.properties"{print $1}' "$RD/applied.txt" 2>/dev/null | paste -sd,)"

mkapply; echo "a: server-rewrote" > "$S/bukkit.yml"; echo max-players=20 > "$W/server.properties"; ap
check "N9 apply: file rewritten by the server but unchanged in work is left alone" "a: server-rewrote no" "$(cat "$S/bukkit.yml") $(grep -q bukkit.yml "$RD/applied.txt" 2>/dev/null && echo yes || echo no)"
repair_rollback "$S" "$RD" >/dev/null 2>&1
check "N9 rollback: server-rewritten file is not rolled back, applied one is" "a: server-rewrote max-players=abc" "$(cat "$S/bukkit.yml") $(cat "$S/server.properties")"
mkapply; echo "new: 1" > "$W/brand-new.yml"; rm -f "$W/bukkit.yml"; ap
check "N1 apply: work-only file ignored, work-deleted file not deleted live" "no yes" "$(yn test -e "$S/brand-new.yml") $(yn test -f "$S/bukkit.yml")"

# ---------------- R3: claude-repair helper ----------------
hp() { CLAUDE_REPAIR_SERVER=$S bash "$here/claude-repair" "$@"; }
treehash() { (cd "$S" && find . -printf '%p|%y|%l|%s|%T@\n' | LC_ALL=C sort | sha256sum); }
mkrd() {
    mkserver; STAMP=20260101T000000Z; RD=$S/claude-repair/$STAMP; mkdir -p "$RD/before"
    echo "$STAMP" > "$S/claude-repair/current"
    printf 'server.properties\nbukkit.yml\nconfig/paper-global.yml\n' > "$RD/files.txt"
    echo max-players=20 > "$RD/before/server.properties"; echo "a: 0" > "$RD/before/bukkit.yml"
    mkdir -p "$RD/before/config"; echo "p: 0" > "$RD/before/config/paper-global.yml"
    mkjar "$P/BadPlugin.jar" BadPlugin x.B
}
refuse() { # name expected_rc helper-args...
    local name=$1 want=$2 h1 h2 rc=0 err; shift 2
    h1=$(treehash); err=$(hp "$@" 2>&1 >/dev/null) || rc=$?; h2=$(treehash)
    check "R3 refuses $name" "$want unchanged msg" "$rc $([[ $h1 == "$h2" ]] && echo unchanged || echo CHANGED) $([[ -n $err ]] && echo msg || echo silent)"
}
logf() { awk -F'\t' '{print $2 "|" $3}' "$RD/helper.log" 2>/dev/null | paste -sd,; }

mkrd; out=$(hp hold-plugin BadPlugin.jar 2>&1); rc=$?
check "R3 hold-plugin: rc 0, 'held' message, jar moved" "0 held BadPlugin.jar yes" "$rc $out $(yn test -f "$P/claude-held/BadPlugin.jar" -a ! -e "$P/BadPlugin.jar")"
check "R3 hold-plugin: logs epoch<TAB>hold<TAB>plugins/jar" "yes hold|plugins/BadPlugin.jar" "$(grep -qP '^[0-9]+\thold\t' "$RD/helper.log" 2>/dev/null && echo yes || echo no) $(logf)"
mkrd; hp hold-plugin plugins/BadPlugin.jar >/dev/null 2>&1; check "R3 hold-plugin: plugins/ prefix accepted" "yes" "$(yn test -f "$P/claude-held/BadPlugin.jar")"
mkrd; hp hold-plugin "$S/plugins/BadPlugin.jar" >/dev/null 2>&1; check "R3 hold-plugin: SERVER/plugins/ prefix accepted" "yes" "$(yn test -f "$P/claude-held/BadPlugin.jar")"

# N4: unhold only for a jar this incident held
mkrd; mv "$P/BadPlugin.jar" "$P/claude-held/"; printf '1\thold\tplugins/BadPlugin.jar\n' > "$RD/helper.log"; out=$(hp unhold-plugin BadPlugin.jar 2>&1); rc=$?
check "N4 unhold-plugin: jar held this incident -> rc 0, jar back" "0 yes" "$rc $(yn test -f "$P/BadPlugin.jar" -a ! -e "$P/claude-held/BadPlugin.jar")"
check "N4 unhold-plugin: log gains unhold line" "hold|plugins/BadPlugin.jar,unhold|plugins/BadPlugin.jar" "$(logf)"
mkrd; mv "$P/BadPlugin.jar" "$P/claude-held/"; refuse "unhold of a jar held before this incident (no log)" 1 unhold-plugin BadPlugin.jar
mkrd; mv "$P/BadPlugin.jar" "$P/claude-held/"; printf '1\thold\tplugins/BadPlugin.jar\n2\tunhold\tplugins/BadPlugin.jar\n' > "$RD/helper.log"; refuse "unhold of a jar already unheld in the log" 1 unhold-plugin BadPlugin.jar
mkrd; mv "$P/BadPlugin.jar" "$P/claude-held/"; printf '1\thold\tplugins/Other.jar\n' > "$RD/helper.log"; refuse "unhold of a jar the log did not hold" 1 unhold-plugin BadPlugin.jar

mkrd; echo max-players=abc > "$S/server.properties"; out=$(hp restore-file server.properties 2>&1); rc=$?
check "R3 restore-file relative: restored and logged" "0 max-players=20 restore|server.properties" "$rc $(cat "$S/server.properties") $(logf)"
mkrd; echo "a: 9" > "$S/bukkit.yml"; hp restore-file "$S/bukkit.yml" >/dev/null 2>&1; rc=$?
check "R3 restore-file absolute: restored, no stray temp files" "0 a: 0 yes" "$rc $(cat "$S/bukkit.yml") $(yn test -z "$(find "$S" -maxdepth 1 -mindepth 1 \( -name '*tmp*' -o -name '.*bukkit*' \))")"

mkrd; out=$(hp note one two 2>&1); rc=$?
check "R3 note: joins args, logs note" "0 note|one two" "$rc $(logf)"
mkrd; hp note $'a\tb\nc' d >/dev/null 2>&1
check "R3 note: tab and newline become spaces, one log line, 3 fields" "note|a b c d 1 3" "$(logf | tr ',' ' ') $(wc -l < "$RD/helper.log" 2>/dev/null | tr -d ' ') $(awk -F'\t' '{print NF}' "$RD/helper.log" 2>/dev/null | head -n1)"
mkrd; long=$(head -c 2500 /dev/zero | tr '\0' 'a'); hp note "$long" >/dev/null 2>&1
check "N4 note: capped at 500 chars" "500" "$(awk -F'\t' '{print length($3)}' "$RD/helper.log" 2>/dev/null)"

mkrd; refuse "hold ClaudeControl.jar" 1 hold-plugin ClaudeControl.jar
mkrd; mkjar "$P/claudecontrol-2.jar" X x.X; refuse "hold claudecontrol-2.jar (any case)" 1 hold-plugin claudecontrol-2.jar
mkrd; refuse "hold ../ traversal" 1 hold-plugin ../evil.jar
mkrd; refuse "hold plugins/../ traversal" 1 hold-plugin plugins/../BadPlugin.jar
mkrd; refuse "hold name with slash" 1 hold-plugin sub/BadPlugin.jar
mkrd; refuse "hold non-jar name" 1 hold-plugin server.properties
mkrd; rm -f "$P/BadPlugin.jar"; echo x > "$T/outjar.jar"; ln -s "$T/outjar.jar" "$P/Lnk.jar"; refuse "hold symlinked jar" 1 hold-plugin Lnk.jar
mkrd; rm -rf "$T/outheld" "$P/claude-held"; mkdir "$T/outheld"; ln -s "$T/outheld" "$P/claude-held"; refuse "hold with symlinked claude-held" 1 hold-plugin BadPlugin.jar
check "R3 symlinked claude-held target stays empty" "" "$(ls "$T/outheld")"
mkrd; mkjar "$P/claude-held/BadPlugin.jar" Old x.O; refuse "hold onto existing destination" 1 hold-plugin BadPlugin.jar
mkrd; mkjar "$P/claude-held/Gone.jar" G x.G; mkjar "$P/Gone.jar" G x.G; printf '1\thold\tplugins/Gone.jar\n' > "$RD/helper.log"; refuse "unhold onto existing destination" 1 unhold-plugin Gone.jar
mkrd; refuse "unhold of a jar not held" 1 unhold-plugin Nope.jar
mkrd; echo "[]" > "$RD/before/ops.json"; echo "[1]" > "$S/ops.json"; refuse "restore of a path not in files.txt" 1 restore-file ops.json
mkrd; refuse "restore with .. component" 1 restore-file plugins/../server.properties
mkrd; refuse "restore with control char" 1 restore-file $'server.properties\x01'
mkrd; rm -f "$RD/before/bukkit.yml"; refuse "restore with no before copy" 1 restore-file bukkit.yml
mkrd; rm -f "$S/server.properties"; echo secret > "$T/outprops"; ln -s "$T/outprops" "$S/server.properties"; refuse "restore onto a symlinked target" 1 restore-file server.properties
check "R3 restore through symlink leaves outside file" "secret" "$(cat "$T/outprops")"
mkrd; rm -rf "$S/config"; rm -rf "$T/outcfg"; mkdir "$T/outcfg"; echo orig > "$T/outcfg/paper-global.yml"; ln -s "$T/outcfg" "$S/config"
refuse "restore through a symlinked parent" 1 restore-file config/paper-global.yml
check "R3 restore through symlinked parent leaves outside file" "orig" "$(cat "$T/outcfg/paper-global.yml")"

mkrd; rm -f "$S/claude-repair/current"; err=$(hp note hi 2>&1 >/dev/null); rc=$?
check "R3 no current file: exit 2, 'no repair in progress'" "2 yes" "$rc $(grep -q 'no repair in progress' <<<"$err" && echo yes || echo no)"
mkrd; echo '../../etc' > "$S/claude-repair/current"; check "R3 invalid current: exit 2" "2" "$(rcof hp note hi)"
mkrd; check "R3 unknown subcommand: exit 2" "2" "$(rcof hp frobnicate x)"
mkrd; check "R3 hold-plugin without argument: exit 2" "2" "$(rcof hp hold-plugin)"
mkrd; check "R3 restore-file without argument: exit 2" "2" "$(rcof hp restore-file)"
mkrd; check "R3 no subcommand: exit 2" "2" "$(rcof hp)"

# ---------------- R4/N5: record_changes ----------------
mkrd; export CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
printf '1\tserver.properties\n2\tbukkit.yml\n3\tserver.properties\n' > "$RD/applied.txt"
printf '2\tspigot.yml\tsettings.bungeecord,rcon.password\n' > "$RD/refused.txt"
printf '1\thold\tplugins/A.jar\n2\tnote\tsaw sk-ant-abc_DEF-9 and %s\n3\trestore\tbukkit.yml\n4\tunhold\tplugins/B.jar\n' "$FAKETOK" > "$RD/helper.log"
echo "live edits must not matter: $(date)" >> "$S/bukkit.yml"
( repair_record_changes "$S" "$RD" ) >/dev/null 2>&1; rc=$?
cj=$RD/changes.json
check "N4 record_changes: rc 0, valid JSON array" "0 array" "$rc $(jq -r 'type' "$cj" 2>/dev/null)"
check "N4 record_changes: edits from applied.txt, first attempt wins" '[[1,"server.properties"],[2,"bukkit.yml"]]' "$(jq -c '[.[]|select(.action=="edit")|[.attempt,.path]]|sort_by(.[0])' "$cj" 2>/dev/null)"
check "N4 record_changes: refused entry with keys array" '[[2,"spigot.yml",["settings.bungeecord","rcon.password"]]]' "$(jq -c '[.[]|select(.action=="refused")|[.attempt,.path,.keys]]' "$cj" 2>/dev/null)"
check "N4 record_changes: helper entries in log order" "hold,note,restore,unhold" "$(jq -r '[.[]|select(.action!="edit" and .action!="refused")|.action]|join(",")' "$cj" 2>/dev/null)"
check "N4 record_changes: hold, restore, unhold paths" "plugins/A.jar bukkit.yml plugins/B.jar" "$(jq -r '[(.[]|select(.action=="hold")|.path),(.[]|select(.action=="restore")|.path),(.[]|select(.action=="unhold")|.path)]|join(" ")' "$cj" 2>/dev/null)"
check "N4 record_changes: note text redacted" "saw [redacted] and [redacted]" "$(jq -r '.[]|select(.action=="note")|.text' "$cj" 2>/dev/null)"
check "N5 record_changes: no 12-char token window in changes.json" "clean" "$(nowin "$cj")"
: > "$RD/applied.txt"; : > "$RD/refused.txt"; : > "$RD/helper.log"
( repair_record_changes "$S" "$RD" ) >/dev/null 2>&1
check "N4 record_changes: empty inputs -> empty array" "[]" "$(jq -c '.' "$cj" 2>/dev/null)"
unset CLAUDE_CODE_OAUTH_TOKEN

# ---------------- R4: report ----------------
mkrd; export CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
printf '1\thold\tplugins/Held.jar\n2\tnote\tdiagnosis note\n' > "$RD/helper.log"; mkdir -p "$P/claude-held"; mkjar "$P/claude-held/Held.jar" H x.H
seq 1 100 | sed 's/^/logline /' > "$RD/attempt-1.log"; echo "$FAKETOK leaked, window ${FAKETOK:3:14}" >> "$RD/attempt-1.log"
( repair_record_changes "$S" "$RD" ) >/dev/null 2>&1
repair_report "$S" "$RD" repaired "boot failed $FAKETOK" 1 >/dev/null 2>&1; rc=$?; rp=$RD/report.md
check "R4 report: title, Outcome, Reason (redacted), Attempts" "0 yes yes yes yes" "$rc $(head -n1 "$rp" 2>/dev/null | grep -q "^# Repair $STAMP" && echo yes || echo no) $(grep -q '^Outcome: repaired' "$rp" 2>/dev/null && echo yes || echo no) $(grep -q '^Reason: boot failed \[redacted\]' "$rp" 2>/dev/null && echo yes || echo no) $(grep -q '^Attempts: 1' "$rp" 2>/dev/null && echo yes || echo no)"
check "R4 report: Held plugins section names the jar" "yes" "$(grep -q '^## Held plugins' "$rp" 2>/dev/null && grep -q 'Held.jar' "$rp" && echo yes || echo no)"
check "R4 report: Changes and Diagnosis sections, note, log tail capped at 60" "yes yes yes yes" "$(grep -q '^## Changes' "$rp" 2>/dev/null && echo yes || echo no) $(grep -q '^## Diagnosis' "$rp" 2>/dev/null && echo yes || echo no) $(grep -q 'diagnosis note' "$rp" 2>/dev/null && echo yes || echo no) $(! grep -q 'logline 30$' "$rp" 2>/dev/null && grep -q 'logline 100$' "$rp" 2>/dev/null && echo yes || echo no)"
check "N5 report: no 12-char token window in report.md" "clean" "$(nowin "$rp")"
mkrd; : > "$RD/helper.log"; echo x > "$RD/attempt-1.log"; ( repair_record_changes "$S" "$RD" ) >/dev/null 2>&1
repair_report "$S" "$RD" repaired r 1 >/dev/null 2>&1
check "R4 report: no Held plugins section when nothing is held" "no" "$(grep -q '^## Held plugins' "$RD/report.md" 2>/dev/null && echo yes || echo no)"
unset CLAUDE_CODE_OAUTH_TOKEN

# ---------------- N4: repair_requests ----------------
mkreq() { mkrd; mkdir -p "$RD/session-1/logs"; echo "[12:00:00 ERROR]: [BadPlugin] boom" > "$RD/session-1/logs/latest.log"; rm -f "$REPAIR_WORK/requests.json"; [[ $# -gt 0 ]] && printf '%s' "$1" > "$REPAIR_WORK/requests.json"; return 0; }
rq() { repair_requests "$S" "$RD" 1 >/dev/null 2>&1; }
mkreq '[{"action":"hold-plugin","jar":"BadPlugin.jar"}]'; rc=0; repair_requests "$S" "$RD" 1 >/dev/null 2>&1 || rc=$?
check "N4 requests: hold-plugin moves the jar, rc 0, logged" "0 yes hold|plugins/BadPlugin.jar" "$rc $(yn test -f "$P/claude-held/BadPlugin.jar" -a ! -e "$P/BadPlugin.jar") $(logf)"
mkreq; rq; check "N4 requests: absent requests.json is no requests" "0 yes" "$(rcof repair_requests "$S" "$RD" 1) $(yn test -f "$P/BadPlugin.jar")"
mkjar "$P/ClaudeControl.jar" ClaudeControl x.C
mkreq '[{"action":"hold-plugin","jar":"ClaudeControl.jar"}]'; mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; rq
check "N4 requests: hold ClaudeControl refused, refusal logged" "yes yes" "$(yn test -f "$P/ClaudeControl.jar") $(yn test -s "$RD/requests-1.log")"
mkreq '[{"action":"unhold-plugin","jar":"Old.jar"}]'; mkjar "$P/claude-held/Old.jar" O x.O; rq
check "N4 requests: unhold of a jar not held this incident refused" "yes no" "$(yn test -f "$P/claude-held/Old.jar") $(yn test -e "$P/Old.jar")"
mkreq '[{"action":"hold-plugin","jar":"BadPlugin.jar"},{"action":"unhold-plugin","jar":"BadPlugin.jar"}]'; rq
check "N4 requests: hold then unhold in one list runs in order" "yes no" "$(yn test -f "$P/BadPlugin.jar") $(yn test -e "$P/claude-held/BadPlugin.jar")"
mkreq "$(printf '[{"action":"note","text":"saw sk-ant-abc_DEF-9 and %s end"}]' "$FAKETOK")"; CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK rq
check "N4 requests: note text is redacted before it is logged" "note clean 0" "$(logf | cut -d'|' -f1) $(nowin "$RD/helper.log") $(grep -c 'sk-ant-abc' "$RD/helper.log")"
mkreq "[{\"action\":\"note\",\"text\":\"$(head -c 600 /dev/zero | tr '\0' 'a')\"}]"; rq
check "N4 requests: note cut to 500 characters" "500" "$(awk -F'\t' '{print length($3)}' "$RD/helper.log" 2>/dev/null)"
mkreq '{not json'; rq
check "N4 requests: bad JSON executes nothing, logged" "yes yes" "$(yn test -f "$P/BadPlugin.jar") $(yn test -s "$RD/requests-1.log")"
mkreq '{"action":"hold-plugin","jar":"BadPlugin.jar"}'; rq
check "N4 requests: JSON object (not array) executes nothing" "yes" "$(yn test -f "$P/BadPlugin.jar")"
mkreq '[{"action":"rm","path":"x"},{"action":"hold-plugin","jar":"BadPlugin.jar"},"junk",{"action":"note"}]'; rq
check "N4 requests: unknown action refused and skipped, valid one still runs" "yes yes" "$(yn test -f "$P/claude-held/BadPlugin.jar") $(yn test -s "$RD/requests-1.log")"
mkreq; echo '[{"action":"hold-plugin","jar":"BadPlugin.jar"}]' > "$T/real-requests.json"; ln -s "$T/real-requests.json" "$REPAIR_WORK/requests.json"; rq
check "N4 requests: symlinked requests.json ignored" "yes" "$(yn test -f "$P/BadPlugin.jar")"
mkreq "[{\"action\":\"note\",\"text\":\"$(head -c 70000 /dev/zero | tr '\0' 'a')\"}]"; rq
check "N4 requests: requests.json over 65536 bytes ignored" "0" "$(cat "$RD/helper.log" 2>/dev/null | wc -l | tr -d ' ')"

# O5: a hold the logs do not justify is refused and the jar stays
mkreq '[{"action":"hold-plugin","jar":"BadPlugin.jar"}]'; rm -rf "$RD/session-1/logs"; rq
check "O5 requests: hold of a jar not named in the logs refused, jar not moved, refusal logged" "yes no yes" "$(yn test -f "$P/BadPlugin.jar") $(yn test -e "$P/claude-held/BadPlugin.jar") $(grep -qF 'refused: hold-plugin BadPlugin.jar: not-in-logs' "$RD/requests-1.log" 2>/dev/null && echo yes || echo no)"
mkreq '[{"action":"hold-plugin","jar":"BadPlugin.jar"}]'; printf '1\thold\tplugins/A.jar\n2\thold\tplugins/B.jar\n3\thold\tplugins/C.jar\n' > "$RD/helper.log"; rq
check "O5 requests: fourth hold refused with 'limit'" "yes yes" "$(yn test -f "$P/BadPlugin.jar") $(grep -qF 'refused: hold-plugin BadPlugin.jar: limit' "$RD/requests-1.log" 2>/dev/null && echo yes || echo no)"
mkreq '[{"action":"hold-plugin","jar":"claudecontrol-2.jar"}]'; mkjar "$P/claudecontrol-2.jar" X x.X; echo "claudecontrol-2 boom" >> "$REPAIR_WORK/logs/latest.log"; rq
check "O5 requests: denied name refused with 'denied'" "yes yes" "$(yn test -f "$P/claudecontrol-2.jar") $(grep -qF 'refused: hold-plugin claudecontrol-2.jar: denied' "$RD/requests-1.log" 2>/dev/null && echo yes || echo no)"

mkreq '[{"action":"hold-plugin","jar":"BadPlugin.jar"}]'; rm -rf "$RD/session-1/logs"; mkdir -p "$REPAIR_WORK/logs"; echo "[ERROR] [BadPlugin] planted" > "$REPAIR_WORK/logs/latest.log"; rq
check "O5 requests: name only in the editable work/logs (not the snapshot) is refused with not-in-logs" "yes no yes" "$(yn test -f "$P/BadPlugin.jar") $(yn test -e "$P/claude-held/BadPlugin.jar") $(grep -qF 'refused: hold-plugin BadPlugin.jar: not-in-logs' "$RD/requests-1.log" 2>/dev/null && echo yes || echo no)"

# ---------------- O5: repair_hold_check ----------------
hc() { # JAR -> reason or empty (rc must be 0); expects mkrd state and $REPAIR_WORK/logs
    declare -F repair_hold_check >/dev/null || { echo NOFUNC; return; }
    local out rc=0; out=$(repair_hold_check "$S" "$RD" "$1" 2>/dev/null) || rc=$?
    ((rc == 0)) && echo "$out" || echo "RC$rc"
}
hclog() { rm -rf "$REPAIR_WORK/logs"; mkdir -p "$REPAIR_WORK/logs"; printf '%s\n' "$1" > "$REPAIR_WORK/logs/latest.log"; }
mkrd; hclog "[12:00:00 ERROR]: [BadPlugin] boom"
check "O5 hold_check: allowed when the plugin is named in the logs" "" "$(hc BadPlugin.jar)"
check "O5 hold_check: rc 0 always" "0" "$(rcof repair_hold_check "$S" "$RD" Nope.jar)"
for bad in ../x.jar .hidden.jar 'a b.jar' server.properties a/b.jar BadPlugin.JARX -x.jar; do check "O5 hold_check: '$bad' -> not-a-jar" "not-a-jar" "$(hc "$bad")"; done
check "O5 hold_check: no such jar -> missing" "missing" "$(hc Nope.jar)"
rm -f "$T/o5-t.jar"; echo x > "$T/o5-t.jar"; ln -s "$T/o5-t.jar" "$P/Lnk.jar"
check "O5 hold_check: symlinked jar -> missing" "missing" "$(hc Lnk.jar)"
hclog "[ERROR] [BadPlugin] boom"; printf '1\thold\tplugins/A.jar\n2\thold\tplugins/B.jar\n3\tnote\tn\n4\tunhold\tplugins/B.jar\n' > "$RD/helper.log"
check "O5 hold_check: two holds (plus note/unhold) still allowed" "" "$(hc BadPlugin.jar)"
printf '1\thold\tplugins/A.jar\n2\thold\tplugins/B.jar\n3\thold\tplugins/C.jar\n' > "$RD/helper.log"
check "O5 hold_check: three earlier holds -> limit" "limit" "$(hc BadPlugin.jar)"
: > "$RD/helper.log"
hclog "nothing relevant here"
check "O5 hold_check: not named in any log -> not-in-logs" "not-in-logs" "$(hc BadPlugin.jar)"
hclog "[error] [badplugin] BOOM"
check "O5 hold_check: log match is case-insensitive" "" "$(hc BadPlugin.jar)"
hclog "x"; mkdir -p "$REPAIR_WORK/logs/crash-reports"; echo "at BadPlugin.onEnable" > "$REPAIR_WORK/logs/crash-reports/c.txt"
check "O5 hold_check: any file under logs/ counts" "" "$(hc BadPlugin.jar)"
mkjar "$P/Geyser-Spigot-2.4.1.jar" GS x.G; hclog "[ERROR] Geyser-Spigot failed to enable"
check "O5 hold_check: version suffix stripped (Geyser-Spigot-2.4.1 -> Geyser-Spigot)" "" "$(hc Geyser-Spigot-2.4.1.jar)"
mkjar "$P/Foo_r3.jar" Fo x.F; hclog "[ERROR] [Foo] threw"
check "O5 hold_check: revision suffix stripped (Foo_r3 -> Foo)" "" "$(hc Foo_r3.jar)"
mkjar "$P/weird.jar" RealName x.R; hclog "[ERROR] [RealName] boom"
check "O5 hold_check: plugin.yml name differing from the jar name matches" "" "$(hc weird.jar)"
hclog "[ERROR] [Other] boom"
check "O5 hold_check: same jar with no mention -> not-in-logs" "not-in-logs" "$(hc weird.jar)"
mkjar "$P/ab.jar" ab x.A; hclog "ab ab ab"
check "O5 hold_check: names under 3 chars never match" "not-in-logs" "$(hc ab.jar)"
mkjar "$P/X.jar" LuckPerms x.L; hclog "[ERROR] [LuckPerms] X.jar boom"
check "O5 hold_check: plugin.yml name LuckPerms -> denied" "denied" "$(hc X.jar)"
for dn in ClaudeControl claudecontrol-2 AuthMe MyPermsX LoginSecurity GuardDog ProtectAll BanHammer WhiteListPlus AntiCheatX; do
    mkjar "$P/$dn.jar" "Zed$RANDOM" x.D; hclog "[ERROR] $dn boom"
    check "O5 hold_check: jar name $dn -> denied" "denied" "$(hc "$dn.jar")"
done

# split token across two notes, through requests -> changes.json -> report.md
mkrd; mkdir -p "$REPAIR_WORK"; export CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
printf '[{"action":"note","text":"first half %s"},{"action":"note","text":"%s second half"}]' "${FAKETOK:0:17}" "${FAKETOK:17}" > "$REPAIR_WORK/requests.json"
repair_requests "$S" "$RD" 1 >/dev/null 2>&1; ( repair_record_changes "$S" "$RD" ) >/dev/null 2>&1
echo x > "$RD/attempt-1.log"; repair_report "$S" "$RD" repaired "split token" 1 >/dev/null 2>&1
check "N5 split token: two notes recorded" "2" "$(jq '[.[]|select(.action=="note")]|length' "$RD/changes.json" 2>/dev/null)"
check "N5 split token: no 12-char window survives anywhere under RD" "clean" "$(nowin "$RD")"
unset CLAUDE_CODE_OAUTH_TOKEN

# ---------------- R3/N4/N5: repair_run_claude with a stub claude ----------------
cat > "$T/bin/claude" <<'EOF'
#!/bin/bash
printf '%s\n' "$@" > "$STUB_ARGV"
cat > "$STUB_STDIN"
pwd > "$STUB_ARGV.pwd"
echo called >> "${STUB_CALLS:-/dev/null}"
case ${STUB_MODE:-} in
    leak) echo "token=$CLAUDE_CODE_OAUTH_TOKEN"; echo "err $CLAUDE_CODE_OAUTH_TOKEN ${CLAUDE_CODE_OAUTH_TOKEN:4:15}" >&2 ;;
    sleep) echo $$ > "$STUB_PID"; exec sleep 6124 ;;
    body) eval "$STUB_BODY" ;;
esac
exit 0
EOF
chmod +x "$T/bin/claude"
export STUB_ARGV=$T/stub-argv STUB_STDIN=$T/stub-stdin REPAIR_HOME=$T/rhome REPAIR_PROMPT=$T/prompt.md
export CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
mkrd; mkdir -p "$REPAIR_WORK"; printf '1\tnote\tmynote123\n2\tnote\tleaked %s and %s\n' "$FAKETOK" "${FAKETOK:6:13}" > "$RD/helper.log"; rm -f "$STUB_ARGV" "$STUB_STDIN"
PATH=$T/bin:$saved_path STUB_MODE=leak repair_run_claude "$S" "$RD" 2 "boot failed reason-ZZ" >/dev/null 2>&1; rc=$?
mapfile -t argv < "$STUB_ARGV" 2>/dev/null
argtxt=$(printf '%s\n' "${argv[@]:-}")
flag() { local i; for i in "${!argv[@]}"; do [[ ${argv[i]} == "$1" ]] && { echo "${argv[i+1]:-}"; return; }; done; }
seg() { local i on=0; for i in "${!argv[@]}"; do
        if [[ ${argv[i]} == "$1" ]]; then on=1; continue; fi
        [[ $on == 1 && ${argv[i]} == --* ]] && break
        [[ $on == 1 ]] && echo "${argv[i]}"
    done; }
check "R3 run_claude: rc 0 and claude was invoked" "0 yes" "$rc $(yn test -s "$STUB_ARGV")"
check "R3 run_claude: -p --model opus --effort medium" "yes opus medium" "$(yn grep -qx -e '-p' <<<"$argtxt") $(flag --model) $(flag --effort)"
check "N4 run_claude: permission mode, tools (Write in, Bash out), sources, mcp" "dontAsk Read,Grep,Glob,Edit,Write user yes" "$(flag --permission-mode) $(flag --tools) $(flag --setting-sources) $(yn grep -qx -e '--strict-mcp-config' <<<"$argtxt")"
allowed=$(seg --allowedTools); denied=$(seg --disallowedTools)
rules=$(grep -oE '(Read|Grep|Glob|Edit|Write)\([^)]*\)' <<<"$allowed")
check "N4 run_claude: five path rules (Read Grep Glob Edit Write)" "5" "$(grep -c . <<<"$rules")"
check "N4 run_claude: every path rule starts with //" "0" "$(grep -vcE '^[A-Za-z]+\(//' <<<"$rules")"
check "N4 run_claude: rules point at REPAIR_WORK" "5" "$(grep -cF "($REPAIR_WORK/**)" < <(sed 's#(/#(#' <<<"$rules"))"
check "N4 run_claude: no rule or argument mentions the live DIR" "0" "$(grep -cF "$S" <<<"$argtxt")"
check "N4 run_claude: no Bash rule allowed; Bash, web, NotebookEdit denied" "no yes yes yes yes" "$(grep -q Bash <<<"$allowed" && echo yes || echo no) $(grep -q Bash <<<"$denied" && echo yes || echo no) $(grep -q WebFetch <<<"$denied" && echo yes || echo no) $(grep -q WebSearch <<<"$denied" && echo yes || echo no) $(grep -q NotebookEdit <<<"$denied" && echo yes || echo no)"
check "N4 run_claude: no claude-repair rule anywhere" "0" "$(grep -c 'claude-repair' <<<"$argtxt")"
stdin=$(cat "$STUB_STDIN" 2>/dev/null)
check "R3 run_claude: stdin has prompt, attempt header, reason, work folder, notes" "yes yes yes yes yes" "$(grep -q PROMPT-MARKER-LINE <<<"$stdin" && echo yes || echo no) $(grep -qiE 'attempt:? 2|attempt 2' <<<"$stdin" && echo yes || echo no) $(grep -q 'reason-ZZ' <<<"$stdin" && echo yes || echo no) $(grep -qF "$REPAIR_WORK" <<<"$stdin" && echo yes || echo no) $(grep -q mynote123 <<<"$stdin" && echo yes || echo no)"
check "N5 run_claude: no 12-char token window in the prompt from helper.log" "clean" "$(nowin "$STUB_STDIN")"
check "N4 run_claude: runs from REPAIR_WORK" "$REPAIR_WORK" "$(cat "$STUB_ARGV.pwd" 2>/dev/null)"
check "R3 run_claude: attempt-2.log written, token redacted" "yes clean yes" "$(yn test -f "$RD/attempt-2.log") $(nowin "$RD/attempt-2.log") $(grep -qF '[redacted]' "$RD/attempt-2.log" 2>/dev/null && echo yes || echo no)"

mkrd; mkdir -p "$REPAIR_WORK"; start=$SECONDS
PATH=$T/bin:$saved_path STUB_MODE=sleep REPAIR_TIMEOUT=2 repair_run_claude "$S" "$RD" 1 slow >/dev/null 2>&1; rc=$?
el=$((SECONDS - start))
check "R3 run_claude: timeout returns rc 0 in well under 15 s" "0 yes" "$rc $( ((el < 15)) && echo yes || echo no)"
killserver
unset CLAUDE_CODE_OAUTH_TOKEN

# ---------------- R5: server trial ----------------
cat > "$T/fake-loads.sh" <<'EOF'
echo "Done (1.0s)! For help, type help" >> "$1/logs/latest.log"; echo $$ > "$SRV_PID"; exec sleep 6123
EOF
printf 'exit 1\n' > "$T/fake-dies.sh"
printf 'echo $$ > "$SRV_PID"\nexec sleep 6123\n' > "$T/fake-hangs.sh"
cat > "$T/srv-markerchk.sh" <<'EOF'
d=$1; if [[ -e $d/claude-clean-stop ]]; then echo present > "$d/seen"; else echo absent > "$d/seen"; fi
echo "Done (1.0s)!" >> "$d/logs/latest.log"; echo $$ > "$SRV_PID"; exec sleep 6123
EOF
FAKE=
launch_command() { printf '%s\n' bash "$FAKE" "$1"; }
export REPAIR_STAYUP=2 REPAIR_POLL=1 REPAIR_STOP_GRACE=2

fresh; FAKE=$T/fake-loads.sh; unset REPAIR_SERVER_PID
repair_server_trial "$S" </dev/null >/dev/null 2>&1; rc=$?
alive=no; [[ -n ${REPAIR_SERVER_PID:-} ]] && kill -0 "$REPAIR_SERVER_PID" 2>/dev/null && alive=yes
check "R5 trial: loaded and alive -> rc 0, pid set, left running, log kept" "0 yes yes" "$rc $alive $(grep -q 'Done (' "$L" && echo yes || echo no)"
killserver

fresh; FAKE=$T/fake-dies.sh; unset REPAIR_SERVER_PID
repair_server_trial "$S" </dev/null >/dev/null 2>&1; rc=$?
check "R5 trial: process exits at once -> rc 1" "1" "$rc"
killserver

fresh; FAKE=$T/fake-hangs.sh; unset REPAIR_SERVER_PID
repair_server_trial "$S" </dev/null >/dev/null 2>&1; rc=$?
sleep 1; alive=no; [[ -n ${REPAIR_SERVER_PID:-} ]] && kill -0 "$REPAIR_SERVER_PID" 2>/dev/null && alive=yes
check "R5 trial: never loads -> rc 1 and process stopped" "1 no" "$rc $alive"
killserver

fresh; FAKE=$T/fake-loads.sh; unset REPAIR_SERVER_PID
repair_server_trial "$S" </dev/null >/dev/null 2>&1; marker=$(yn test -e "$S/claude-boot-pending" -o -n "$(grep -c 'Done (' "$L")")
check "R5 trial: pending marker was written (or removed by a loading server)" "yes" "$marker"
killserver
killserver

fresh; FAKE=$T/srv-markerchk.sh; unset REPAIR_SERVER_PID; touch "$S/claude-clean-stop"
repair_server_trial "$S" </dev/null >/dev/null 2>&1
sleep 1
check "O6 trial: claude-clean-stop is removed before the launch" "absent" "$(cat "$S/seen" 2>/dev/null || echo none)"
killserver

# ---------------- R6/N9: rollback (applied.txt only) ----------------
mkrd
echo max-players=abc > "$S/server.properties"; echo "a: server-rewrote" > "$S/bukkit.yml"
printf '1\tserver.properties\n' > "$RD/applied.txt"
mkdir -p "$P/claude-held"; mv "$P/BadPlugin.jar" "$P/claude-held/BadPlugin.jar"
mkjar "$P/claude-held/Was.jar" W x.W; mv "$P/claude-held/Was.jar" "$P/Was.jar"
printf '1\thold\tplugins/BadPlugin.jar\n2\tunhold\tplugins/Was.jar\n' > "$RD/helper.log"
echo fine > "$P/claude-held/Keep.txt"
before_list=$(cd "$S" && find . -type f | LC_ALL=C sort | wc -l)
out=$(repair_rollback "$S" "$RD" 2>&1); rc=$?
check "R6 rollback: applied config restored, held jar returned" "0 max-players=20 yes" "$rc $(cat "$S/server.properties") $(yn test -f "$P/BadPlugin.jar" -a ! -e "$P/claude-held/BadPlugin.jar")"
check "R6 rollback: unhold reversed (jar held again)" "yes" "$(yn test -f "$P/claude-held/Was.jar" -a ! -e "$P/Was.jar")"
check "R6 rollback: file not in applied.txt never touched (even with a before copy)" "a: server-rewrote" "$(cat "$S/bukkit.yml")"
check "R6 rollback: prints 'repair: rolled back <rel>' only for applied files" "yes no" "$(grep -q '^repair: rolled back server.properties' <<<"$out" && echo yes || echo no) $(grep -q 'rolled back bukkit.yml' <<<"$out" && echo yes || echo no)"
check "R6 rollback: deletes nothing" "$before_list yes" "$(cd "$S" && find . -type f | wc -l) $(yn test -f "$P/claude-held/Keep.txt")"
mkrd; printf '1\tserver.properties\n' > "$RD/applied.txt"; echo max-players=20 > "$S/server.properties"; touch -d '1 hour ago' "$S/server.properties"; m1=$(stat -c %Y "$S/server.properties")
repair_rollback "$S" "$RD" >/dev/null 2>&1
check "R6 rollback: live file already equal to before/ is not rewritten" "$m1" "$(stat -c %Y "$S/server.properties")"
mkrd; mkjar "$P/claude-held/BadPlugin.jar" Other x.O; printf '1\thold\tplugins/BadPlugin.jar\n' > "$RD/helper.log"
out=$(repair_rollback "$S" "$RD" 2>&1)
check "R6 rollback: never overwrites, prints 'cannot'" "yes yes" "$(yn test -f "$P/claude-held/BadPlugin.jar") $(grep -q cannot <<<"$out" && echo yes || echo no)"
mkrd; printf '1\tserver.properties\n' > "$RD/applied.txt"; rm -f "$S/server.properties"; echo secret > "$T/outprops2"; ln -s "$T/outprops2" "$S/server.properties"
repair_rollback "$S" "$RD" >/dev/null 2>&1
check "R6 rollback: link-safe (does not write through a symlinked live file)" "secret" "$(cat "$T/outprops2")"

# ---------------- N6: repair_reason ----------------
fresh; export REPAIR_CRASHES=3 REPAIR_CRASH_WINDOW=1800; NOW=1000000
out=$(repair_reason "$S" 2 $NOW 2>/dev/null)
check "N6 reason: rc 2 -> 'failed to start'" "yes" "$(grep -qi 'failed to start' <<<"$out" && echo yes || echo no)"
printf '999000\n999500\n999900\n' > "$S/claude-crash-history"
out=$(repair_reason "$S" 0 $NOW 2>/dev/null)
check "N6 reason: crash threshold -> 'crashed' reason, history emptied" "yes 0" "$(grep -qi 'crashed' <<<"$out" && echo yes || echo no) $(grep -c . "$S/claude-crash-history" 2>/dev/null | head -n1)"
printf '999500\n999900\n' > "$S/claude-crash-history"
out=$(repair_reason "$S" 0 $NOW 2>/dev/null)
check "N6 reason: below threshold -> nothing printed, history kept" "empty 2" "$([[ -z $out ]] && echo empty || echo "$out") $(grep -c . "$S/claude-crash-history" 2>/dev/null || echo 0)"
unset REPAIR_CRASHES REPAIR_CRASH_WINDOW

# ---------------- N7: repair_recover ----------------
mkrd; printf '1\tserver.properties\n' > "$RD/applied.txt"; echo "max-players=edited" > "$S/server.properties"
cp "$RD/before/server.properties" "$T/before.copy"
out=$(repair_recover "$S" 2>&1); rc=$?
check "N7 recover: leftover current -> applied file rolled back, rc 0" "0 max-players=20" "$rc $(cat "$S/server.properties")"
check "N7 recover: current removed, stamp indexed, 'repair:' line" "yes yes yes" "$(yn test ! -e "$S/claude-repair/current") $(grep -qx "$STAMP" "$S/claude-repair/index" 2>/dev/null && echo yes || echo no) $(grep -q '^repair:' <<<"$out" && echo yes || echo no)"
check "N7 recover: report says interrupted; rolled back; before/ not rewritten" "yes yes" "$(grep -q 'interrupted; rolled back' "$RD/report.md" 2>/dev/null && echo yes || echo no) $(yn cmp -s "$RD/before/server.properties" "$T/before.copy")"
mkrd; echo 20260202T020202Z > "$S/claude-repair/current"; h1=$(cd "$S" && find . -path ./claude-repair -prune -o -type f -print | sort | xargs sha256sum | sha256sum)
rc=0; repair_recover "$S" >/dev/null 2>&1 || rc=$?
check "N7 recover: current naming nothing is just removed" "0 yes yes" "$rc $(yn test ! -e "$S/claude-repair/current") $([[ $h1 == "$(cd "$S" && find . -path ./claude-repair -prune -o -type f -print | sort | xargs sha256sum | sha256sum)" ]] && echo yes || echo no)"
mkrd; rm -f "$S/claude-repair/current"; echo max-players=edited2 > "$S/server.properties"; printf '1\tserver.properties\n' > "$RD/applied.txt"
rc=0; repair_recover "$S" >/dev/null 2>&1 || rc=$?
check "N7 recover: no current -> nothing touched, rc 0" "0 max-players=edited2" "$rc $(cat "$S/server.properties")"

# ---------------- N1: repair_clear_acls ----------------
fresh; echo x > "$S/f.yml"
check "N1 clear_acls: rc 0 (setfacl stubbed out)" "0" "$(rcof repair_clear_acls "$S")"
if getent passwd repair >/dev/null 2>&1 && ( unset -f setfacl; command setfacl -m u:repair:r "$S/f.yml" ) 2>/dev/null; then
    ( unset -f setfacl chown; command setfacl -m u:repair:rw "$S/f.yml"; mkdir -p "$S/d"; command setfacl -m u:repair:rx "$S/d" )
    ( unset -f setfacl chown; repair_clear_acls "$S" >/dev/null 2>&1 )
    check "N1 clear_acls: no u:repair entry left under DIR" "0" "$(getfacl -R -P "$S" 2>/dev/null | grep -c 'user:repair')"
else
    echo "SKIP N1 clear_acls real-ACL case: no 'repair' user or no ACL support on this host"
fi

# ---------------- N9: boot guard halt deletes claude-held-on-boot ----------------
fresh; mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; echo 2 > "$S/claude-boot-failures"; touch "$S/claude-boot-pending" "$S/claude-held-on-boot"
rc=0; boot_guard_step "$S" >/dev/null 2>&1 || rc=$?
check "N9 boot guard halt: rc 2, halt file, claude-held-on-boot deleted" "2 yes yes" "$rc $(yn test -f "$S/claude-boot-halted") $(yn test ! -e "$S/claude-held-on-boot")"

# ---------------- O7: repair_put keeps the target's mode; rollback too ----------------
fresh; echo new > "$T/put-src"; chmod 0644 "$T/put-src"; echo old > "$S/put-dst"; chmod 0640 "$S/put-dst"
declare -F repair_put >/dev/null && repair_put "$T/put-src" "$S/put-dst" >/dev/null 2>&1
check "O7 repair_put: content replaced, target's mode (0640) kept" "new 640" "$(cat "$S/put-dst") $(stat -c %a "$S/put-dst")"
mkrd; printf '1\tserver.properties\n' > "$RD/applied.txt"; echo max-players=edited > "$S/server.properties"; chmod 0640 "$S/server.properties"
repair_rollback "$S" "$RD" >/dev/null 2>&1
check "O7 rollback: restored file keeps the live file's mode" "max-players=20 640" "$(cat "$S/server.properties") $(stat -c %a "$S/server.properties")"

# ---------------- O7: repair_uid_pids (fake /proc only) ----------------
FP=$T/fakeproc; rm -rf "$FP"; mkdir -p "$FP/self" "$FP/7"
for pr in 100:1234 30:1234 205:0 300:0:1234 5000:1234; do
    IFS=: read -r pid ru eu <<<"$pr"; mkdir -p "$FP/$pid"
    printf 'Name:\tx\nUid:\t%s\t%s\t%s\t%s\nGid:\t1\t1\t1\t1\n' "$ru" "${eu:-$ru}" "${eu:-$ru}" "${eu:-$ru}" > "$FP/$pid/status"
done
mkdir -p "$FP/abc"; printf 'Uid:\t1234\t1234\t1234\t1234\n' > "$FP/abc/status"; printf 'Uid:\t1234\t1234\t1234\t1234\n' > "$FP/self/status"
getpids() { declare -F repair_uid_pids >/dev/null || { echo NOFUNC; return; }; repair_uid_pids "$@" 2>/dev/null | paste -sd,; }
check "O7 uid_pids: real-uid match, numeric pids only, sorted -n" "30,100,5000" "$(getpids 1234 "$FP")"
check "O7 uid_pids: real uid 0 lists 205 and 300" "205,300" "$(getpids 0 "$FP")"
check "O7 uid_pids: no match -> empty, rc 0" ", 0" "$(getpids 9999 "$FP"), $(rcof repair_uid_pids 9999 "$FP")"

# ---------------- O7: repair_index_add temp dir ----------------
mktemp() { echo "$*" >> "$T/mktemp.calls"; command mktemp "$@"; }
ia() { declare -F repair_index_add >/dev/null || return 99; repair_index_add "$@" >/dev/null 2>&1; }
fresh; mkdir -p "$S/claude-repair"; : > "$T/mktemp.calls"; ia "$S" 20260101T000000Z; rc=$?
check "O7 index_add: index is a 0644 regular file with the stamp, rc 0" "0 644 20260101T000000Z" "$rc $(stat -c %a "$S/claude-repair/index" 2>/dev/null) $(cat "$S/claude-repair/index" 2>/dev/null)"
check "O7 index_add: .index-tmp is a real 0700 dir owned by the caller" "700 $(id -u)" "$(stat -c %a "$S/claude-repair/.index-tmp" 2>/dev/null) $(stat -c %u "$S/claude-repair/.index-tmp" 2>/dev/null)"
check "O7 index_add: every temp file is made inside .index-tmp" "yes" "$([[ -s $T/mktemp.calls ]] && ! grep -qv '\.index-tmp' "$T/mktemp.calls" && echo yes || echo no)"
check "O7 index_add: no stray files in claude-repair/ besides index and .index-tmp" "index" "$(ls -A "$S/claude-repair" 2>/dev/null | grep -vx '\.index-tmp' | paste -sd,)"
ia "$S" 20260102T000000Z
check "O7 index_add: second stamp appended" "20260101T000000Z,20260102T000000Z" "$(paste -sd, "$S/claude-repair/index" 2>/dev/null)"
fresh; mkdir -p "$S/claude-repair" "$T/idx-out"; rm -rf "$T/idx-out"/*; ln -s "$T/idx-out" "$S/claude-repair/.index-tmp"
ia "$S" 20260101T000000Z; rc=$?
check "O7 index_add: symlinked .index-tmp -> nothing written through it, no index, rc 0" "0 0 no" "$rc $(ls -A "$T/idx-out" | wc -l | tr -d ' ') $(yn test -e "$S/claude-repair/index")"
if [[ $(id -u) == 0 ]]; then
    fresh; mkdir -p "$S/claude-repair/.index-tmp"; command chown 12345 "$S/claude-repair/.index-tmp"
    ia "$S" 20260101T000000Z; rc=$?
    check "O7 index_add: .index-tmp owned by someone else -> nothing written, no index, rc 0" "0 0 no" "$rc $(ls -A "$S/claude-repair/.index-tmp" | wc -l | tr -d ' ') $(yn test -e "$S/claude-repair/index")"
else
    echo "SKIP O7 index_add foreign-owner case: needs root to create a dir owned by another uid"
fi
unset -f mktemp

# ---------------- O7: repair_cli_access grant / -x ----------------
CH=$T/home; rm -rf "$CH"; mkdir -p "$CH/u/real/bin" "$CH/u/links" "$CH/u/unrelated"; echo x > "$CH/u/real/bin/claude-real"; chmod +x "$CH/u/real/bin/claude-real"
ln -s ../real/bin/claude-real "$CH/u/links/claude"
facl_dirs() { grep -E "^setfacl .*$1" "$T/calls" | awk '{print $NF}' | while read -r d; do realpath -m -- "$d"; done | LC_ALL=C sort -u | sed "s#^$CH/##" | paste -sd,; }
rec; REPAIR_CLI_ROOT=$CH repair_cli_access "$CH/u/links/claude" >/dev/null 2>&1; rc=$?
granted=$(facl_dirs '-m u:repair:x'); grep -q 'setfacl -m u:repair:x' "$T/calls" || granted=none
check "O7 cli_access: grants x on every dir from the CLI and its symlink target up to (excluding) the root" "0 u,u/links,u/real,u/real/bin" "$rc $granted"
rec; REPAIR_CLI_ROOT=$CH repair_cli_access -x "$CH/u/links/claude" >/dev/null 2>&1; rc=$?
removed=$(facl_dirs '-x u:repair'); grep -q 'setfacl -x u:repair' "$T/calls" || removed=none
check "O7 cli_access -x: removes exactly the granted directories" "0 u,u/links,u/real,u/real/bin" "$rc $removed"
rec; PATH=$CH/u/links:$saved_path REPAIR_CLI_ROOT=$CH repair_cli_access >/dev/null 2>&1
check "O7 cli_access: no argument uses the claude on PATH" "u,u/links,u/real,u/real/bin" "$(facl_dirs '-m u:repair:x')"
unrec
rec; REPAIR_CLI_ROOT=$CH repair_cli_access "$CH/u/links/claude" >/dev/null 2>&1
check "O7 cli_access: never touches the root dir or unrelated dirs" "yes" "$(grep -qF "$CH/u/links" "$T/calls" 2>/dev/null && ! grep -qE "($CH|$CH/u/unrelated)\$" "$T/calls" && echo yes || echo no)"
unrec

# ---------------- O7: repair_prune ----------------
PN=$(date -u -d '2026-10-02 00:00:00' +%s)
mkstamps() { # first-day last-day (YYYYMMDD) ; fresh server with claude-repair/<day>T000000Z
    fresh; mkdir -p "$S/claude-repair"; local d
    for d in "${@}"; do mkdir -p "$S/claude-repair/${d}T000000Z"; done
}
pn() { declare -F repair_prune >/dev/null || return 99; repair_prune "$S" "$PN" >/dev/null 2>&1; }
left() { ls -A "$S/claude-repair" | grep -E '^[0-9]{8}T[0-9]{6}Z$' | paste -sd,; }
mkstamps 20250101 20250102 20250103 20250104 20250105 20250106 20250107 20250108 20250109 20250110 20250111 20250112; pn; rc=$?
check "O7 prune: 12 old stamps -> the 10 newest kept" "0 20250103T000000Z,20250104T000000Z,20250105T000000Z,20250106T000000Z,20250107T000000Z,20250108T000000Z,20250109T000000Z,20250110T000000Z,20250111T000000Z,20250112T000000Z" "$rc $(left)"
mkstamps 20250101 20260920 20260921 20260922 20260923 20260924 20260925 20260926 20260927 20260928 20260929 20260930; pn
check "O7 prune: 11 recent + 1 old -> only the old one (beyond the newest 10) goes" "yes 11" "$(yn test ! -e "$S/claude-repair/20250101T000000Z") $(left | tr ',' '\n' | grep -c .)"
mkstamps 20250101 20250102 20250103 20250104 20250105 20250106 20250107 20250108 20250109 20250110 20250111 20250112; echo 20250101T000000Z > "$S/claude-repair/current"; pn
check "O7 prune: the stamp in 'current' is never deleted" "yes yes" "$(yn test -d "$S/claude-repair/20250101T000000Z") $(yn test ! -e "$S/claude-repair/20250102T000000Z")"
mkstamps 20250101 20250102 20250103 20250104 20250105 20250106 20250107 20250108 20250109 20250110 20250111 20250112
mkdir "$T/prune-target"; echo keep > "$T/prune-target/f"; ln -s "$T/prune-target" "$S/claude-repair/20240101T000000Z"; touch "$S/claude-repair/20240202T000000Z"; mkdir "$S/claude-repair/notes" "$S/claude-repair/20240303T0000Z"; echo i > "$S/claude-repair/index"; pn
check "O7 prune: symlink, file, non-stamp names and index untouched (while the oldest real stamp goes)" "yes yes yes yes yes yes yes" "$(yn test ! -e "$S/claude-repair/20250101T000000Z") $(yn test -L "$S/claude-repair/20240101T000000Z") $(yn test -f "$T/prune-target/f") $(yn test -f "$S/claude-repair/20240202T000000Z") $(yn test -d "$S/claude-repair/notes") $(yn test -d "$S/claude-repair/20240303T0000Z") $(yn test -f "$S/claude-repair/index")"
check "O7 prune: with such extras the real stamp dirs are still cut to 10" "10" "$(find "$S/claude-repair" -maxdepth 1 -type d -regextype posix-extended -regex '.*/[0-9]{8}T[0-9]{6}Z' | wc -l | tr -d ' ')"
mkstamps 20260925 20260926 20260927; pn
check "O7 prune: fewer than 10 stamps -> nothing deleted" "fn 20260925T000000Z,20260926T000000Z,20260927T000000Z" "$(declare -F repair_prune >/dev/null && echo fn || echo nofn) $(left)"
check "O7 prune: rc 0" "0" "$(rcof repair_prune "$S" "$PN")"

# ---------------- N6/N7: repair_incident end to end (DIR REASON MAYHALT) ----------------
repair_kill_all() { echo "live=$(head -n1 "$S/server.properties" 2>/dev/null) jar=$(yn test -f "$S/plugins/BadPlugin.jar") req=$(yn test -e "$REPAIR_WORK/requests.json")" >> "$T/killall.calls"; return 0; }   # never the real one
repair_cli_access() { echo "$*" >> "$T/cliacc.calls"; return 0; }
incident_env() { # attempts
    : > "$T/killall.calls"; : > "$T/cliacc.calls"
    export REPAIR_ATTEMPTS=$1 REPAIR_STAYUP=2 REPAIR_POLL=1 REPAIR_STOP_GRACE=2 CLAUDE_CODE_OAUTH_TOKEN=$FAKETOK
    export STUB_MODE=body STUB_W=$REPAIR_WORK STUB_CALLS=$T/stub-calls
    : > "$STUB_CALLS"; rm -rf "$REPAIR_HOME" "$REPAIR_WORK"; mkdir -p "$REPAIR_HOME"
}
find_rd() { RD=$(find "$S/claude-repair" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*T[0-9]*Z' 2>/dev/null | head -n1); STAMP=${RD##*/}; }
inc() { # REASON MAYHALT ; run in this shell so REPAIR_SERVER_PID survives
    PATH=$T/bin:$saved_path repair_incident "$S" "$1" "$2" </dev/null >/dev/null 2>&1; rc=$?; find_rd
}
cat > "$T/srv-numeric.sh" <<'EOF'
d=$1; grep -qE '^max-players=[0-9]+$' "$d/server.properties" || exit 1
echo "Done (1.0s)!" >> "$d/logs/latest.log"; echo $$ > "$SRV_PID"; exec sleep 6123
EOF
cat > "$T/srv-noplugin.sh" <<'EOF'
d=$1; [[ -e $d/plugins/BadPlugin.jar ]] && exit 1
echo "Done (1.0s)!" >> "$d/logs/latest.log"; echo $$ > "$SRV_PID"; exec sleep 6123
EOF
cat > "$T/srv-crashlate.sh" <<'EOF'
d=$1; echo "Done (1.0s)!" >> "$d/logs/latest.log"
if grep -qE '^max-players=[0-9]+$' "$d/server.properties"; then echo $$ > "$SRV_PID"; exec sleep 6123; fi
sleep 1; exit 1
EOF
printf 'exit 1\n' > "$T/srv-never.sh"
FIXBODY='echo "leak $CLAUDE_CODE_OAUTH_TOKEN ${CLAUDE_CODE_OAUTH_TOKEN:3:14}"; sed -i "s/max-players=abc/max-players=20/" "$STUB_W/config/server.properties"'

# (1) broken config value
mkserver; incident_env 2; FAKE=$T/srv-numeric.sh; unset REPAIR_SERVER_PID; export STUB_BODY=$FIXBODY
inc "boot failed" 0
alive=no; [[ -n ${REPAIR_SERVER_PID:-} ]] && kill -0 "$REPAIR_SERVER_PID" 2>/dev/null && alive=yes
check "N7.1 broken value: rc 0, server running, live file fixed" "0 yes max-players=20" "$rc $alive $(cat "$S/server.properties")"
check "N7.1 report Outcome repaired; applied.txt records the edit" "yes server.properties" "$(grep -q '^Outcome: repaired' "${RD:-/nonexistent}/report.md" 2>/dev/null && echo yes || echo no) $(awk -F'\t' '{print $2}' "${RD:-/nonexistent}/applied.txt" 2>/dev/null)"
check "N7.1 before/ holds the original; changes.json records the edit" "max-players=abc server.properties" "$(cat "${RD:-/nonexistent}/before/server.properties" 2>/dev/null) $(jq -r '.[]|select(.action=="edit")|.path' "${RD:-/nonexistent}/changes.json" 2>/dev/null)"
check "N7.1 current removed, incident recorded, stamp indexed, no halt file" "yes yes yes no" "$(yn test ! -e "$S/claude-repair/current") $(yn test -s "$S/claude-repair/incidents") $(grep -qx "${STAMP:-none}" "$S/claude-repair/index" 2>/dev/null && echo yes || echo no) $(yn test -e "$S/claude-boot-halted")"
check "N5 no 12-char token window anywhere under claude-repair/" "clean" "$(nowin "$S/claude-repair")"
check "O7 incident: repair_kill_all ran once, after the session and before apply (live file still unfixed)" "1 live=max-players=abc" "$(wc -l < "$T/killall.calls" | tr -d ' ') $(sed 's/ jar=.*//' "$T/killall.calls" | head -n1)"
check "O7 incident: repair_cli_access granted first, and -x is the last call" "yes yes" "$(head -n1 "$T/cliacc.calls" | grep -qvx -e '-x' && echo yes || echo no) $([[ $(tail -n1 "$T/cliacc.calls") == -x* ]] && echo yes || echo no)"
killserver

# (1b) repaired with no change at all: does not count as an incident
mkserver; echo max-players=20 > "$S/server.properties"; incident_env 2; FAKE=$T/srv-numeric.sh; unset REPAIR_SERVER_PID; export STUB_BODY='true'
inc "boot failed" 0
check "O7 repaired with nothing applied and no hold: rc 0, incident NOT recorded, no report.md, stamp NOT indexed" "0 no no no" "$rc $(yn test -s "$S/claude-repair/incidents") $(yn test -e "${RD:-/nonexistent}/report.md") $(grep -qx "${STAMP:-none}" "$S/claude-repair/index" 2>/dev/null && echo yes || echo no)"
check "N6 repaired with nothing applied: current removed" "yes" "$(yn test ! -e "$S/claude-repair/current")"
killserver

# (2) third-party plugin throws: claude holds the jar through requests.json
mkserver; mkjar "$P/BadPlugin.jar" BadPlugin x.B; echo "[12:00:00 ERROR]: [BadPlugin] boom" >> "$L"; incident_env 2; FAKE=$T/srv-noplugin.sh; unset REPAIR_SERVER_PID
export STUB_BODY='echo "[{\"action\":\"hold-plugin\",\"jar\":\"BadPlugin.jar\"},{\"action\":\"note\",\"text\":\"it threw\"}]" > "$STUB_W/requests.json"'
inc "plugin threw" 0
check "N7.2 held plugin: rc 0, jar in claude-held" "0 yes" "$rc $(yn test -f "$P/claude-held/BadPlugin.jar" -a ! -e "$P/BadPlugin.jar")"
check "N7.2 report has Held plugins section naming the jar" "yes" "$(grep -q '^## Held plugins' "${RD:-/nonexistent}/report.md" 2>/dev/null && grep -q BadPlugin.jar "$RD/report.md" && echo yes || echo no)"
check "N7.2 changes.json has the hold; a hold counts as an incident" "plugins/BadPlugin.jar yes" "$(jq -r '.[]|select(.action=="hold")|.path' "${RD:-/nonexistent}/changes.json" 2>/dev/null) $(yn test -s "$S/claude-repair/incidents")"
check "O7 incident: kill_all ran before the requests were processed (jar still in plugins, requests.json present)" "jar=yes req=yes" "$(sed 's/^live=[^ ]* //' "$T/killall.calls" | head -n1)"
killserver

# O5 end to end: a hold the logs do not justify is refused
mkserver; mkjar "$P/BadPlugin.jar" BadPlugin x.B; incident_env 1; FAKE=$T/srv-noplugin.sh; unset REPAIR_SERVER_PID
export STUB_BODY='echo "[{\"action\":\"hold-plugin\",\"jar\":\"BadPlugin.jar\"}]" > "$STUB_W/requests.json"'
inc "plugin threw" 0
check "O5 incident: hold of a jar not named in the logs is refused end to end" "yes no yes" "$(yn test -f "$P/BadPlugin.jar") $(yn test -e "$P/claude-held/BadPlugin.jar") $(grep -qF 'refused: hold-plugin BadPlugin.jar: not-in-logs' "${RD:-/nonexistent}/requests-1.log" 2>/dev/null && echo yes || echo no)"
killserver

# (3) crash after load: first attempt does nothing, second fixes
mkserver; incident_env 3; FAKE=$T/srv-crashlate.sh; unset REPAIR_SERVER_PID
export STUB_BODY='[[ $(wc -l < "$STUB_CALLS") -ge 2 ]] && sed -i "s/max-players=abc/max-players=20/" "$STUB_W/config/server.properties"; true'
inc "crash after load" 0
check "N7.3 crash after load: second attempt repairs, rc 0, two attempt logs" "0 2" "$rc $(ls "${RD:-/nonexistent}" 2>/dev/null | grep -c '^attempt-.*\.log$')"
check "N7.3 report says Attempts: 2" "yes" "$(grep -q '^Attempts: 2' "${RD:-/nonexistent}/report.md" 2>/dev/null && echo yes || echo no)"
killserver

# (4) unfixable, MAYHALT=1
mkserver; incident_env 2; FAKE=$T/srv-never.sh; unset REPAIR_SERVER_PID
cp "$S/server.properties" "$T/props.before"; cp "$S/bukkit.yml" "$T/bukkit.before"
export STUB_BODY='echo "# edited" >> "$STUB_W/config/server.properties"; echo "# edited" >> "$STUB_W/config/bukkit.yml"'
inc "always fails" 1
check "N7.4 unfixable MAYHALT=1: rc 1, attempts == REPAIR_ATTEMPTS" "1 2" "$rc $(ls "${RD:-/nonexistent}" 2>/dev/null | grep -c '^attempt-.*\.log$')"
check "O7 incident: repair_kill_all once per attempt" "2" "$(wc -l < "$T/killall.calls" | tr -d ' ')"
check "N7.4 configs rolled back to the originals" "yes yes" "$(yn cmp -s "$S/server.properties" "$T/props.before") $(yn cmp -s "$S/bukkit.yml" "$T/bukkit.before")"
check "N7.4 halt file written (mentions rolled back)" "yes" "$(grep -q 'rolled back' "$S/claude-boot-halted" 2>/dev/null && echo yes || echo no)"
check "N7.4 report says rolled back; current removed; stamp indexed; incident recorded" "yes yes yes yes" "$(grep -q '^Outcome: rolled back' "${RD:-/nonexistent}/report.md" 2>/dev/null && echo yes || echo no) $(yn test ! -e "$S/claude-repair/current") $(grep -qx "${STAMP:-none}" "$S/claude-repair/index" 2>/dev/null && echo yes || echo no) $(yn test -s "$S/claude-repair/incidents")"
check "N5 token window absent from halt file and claude-repair/" "clean clean" "$(nowin "$S/claude-boot-halted") $(nowin "$S/claude-repair")"
killserver

# (4b) unfixable, MAYHALT=0: rolled back, never halts
mkserver; incident_env 2; FAKE=$T/srv-never.sh; unset REPAIR_SERVER_PID
cp "$S/server.properties" "$T/props.before"
inc "crashed after load" 0
check "N6 unfixable MAYHALT=0: rc 1, rolled back, NO halt file, current removed" "1 yes no yes" "$rc $(yn cmp -s "$S/server.properties" "$T/props.before") $(yn test -e "$S/claude-boot-halted") $(yn test ! -e "$S/claude-repair/current")"
check "N6 unfixable MAYHALT=0: report written and indexed" "yes" "$(grep -qx "${STAMP:-none}" "$S/claude-repair/index" 2>/dev/null && yn test -s "$RD/report.md")"
killserver

# no token / no CLI
mkserver; incident_env 2; FAKE=$T/srv-numeric.sh; export CLAUDE_CODE_OAUTH_TOKEN= STUB_BODY='true'
cp "$S/server.properties" "$T/props.before"
inc "no token" 1
check "N6 no token MAYHALT=1: rc 1, halt file, claude never called" "1 yes 0" "$rc $(yn test -s "$S/claude-boot-halted") $(wc -l < "$STUB_CALLS" | tr -d ' ')"
rm -f "$S/claude-boot-halted"
inc "no token" 0
check "N6 no token MAYHALT=0: rc 1, NO halt file, nothing changed" "1 no yes 0" "$rc $(yn test -e "$S/claude-boot-halted") $(yn cmp -s "$S/server.properties" "$T/props.before") $(wc -l < "$STUB_CALLS" | tr -d ' ')"
mkserver; incident_env 2; FAKE=$T/srv-numeric.sh; export STUB_BODY='true'
rc=0; PATH=/usr/bin:/bin repair_incident "$S" "no cli" 0 </dev/null >/dev/null 2>&1 || rc=$?
check "N6 no claude CLI MAYHALT=0: rc 1, NO halt file" "1 no" "$rc $(yn test -e "$S/claude-boot-halted")"

# ---------------- N7: TERM during the session / the trial ----------------
term_run() { # DELAY ; env TERM_STUB / TERM_STAY ; sets TRC (rc) and TEL (seconds from TERM to exit)
    local bg wd
    ( export STUB_MODE=$TERM_STUB REPAIR_STAYUP=${TERM_STAY:-2}; PATH=$T/bin:$saved_path; repair_incident "$S" "term test" 1 ) </dev/null >/dev/null 2>&1 &
    bg=$!; sleep "$1"; kill -TERM "$bg" 2>/dev/null; local start=$SECONDS
    ( sleep 20; kill -KILL "$bg" 2>/dev/null ) >/dev/null 2>&1 & wd=$!
    TRC=0; wait "$bg" 2>/dev/null || TRC=$?
    TEL=$((SECONDS - start)); kill "$wd" 2>/dev/null; wait "$wd" 2>/dev/null
}
mkserver; incident_env 2; FAKE=$T/srv-numeric.sh; unset REPAIR_SERVER_PID; cp "$S/server.properties" "$T/props.before"
TERM_STUB=sleep TERM_STAY=2 term_run 3; find_rd
killserver
check "N7 TERM during session: rc 3 within 10 s" "3 yes" "$TRC $( ((TEL < 10)) && echo yes || echo no)"
check "N7 TERM during session: live files unchanged, no halt file, current removed" "yes no yes" "$(yn cmp -s "$S/server.properties" "$T/props.before") $(yn test -e "$S/claude-boot-halted") $(yn test ! -e "$S/claude-repair/current")"

mkserver; incident_env 2; FAKE=$T/srv-numeric.sh; unset REPAIR_SERVER_PID; cp "$S/server.properties" "$T/props.before"; export STUB_BODY=$FIXBODY
TERM_STUB=body TERM_STAY=30 term_run 5; find_rd
sleep 1; trial_left=$(pidalive "$SRV_PID")
killserver
check "N7 TERM during trial: rc 3 within 10 s, trial server stopped" "3 yes no" "$TRC $( ((TEL < 10)) && echo yes || echo no) $trial_left"
check "N7 TERM during trial: applied edit rolled back, no halt file, current removed" "yes no yes" "$(yn cmp -s "$S/server.properties" "$T/props.before") $(yn test -e "$S/claude-boot-halted") $(yn test ! -e "$S/claude-repair/current")"

# ---------------- strict mode ----------------
strict2() { # run "$@" under strict mode with repair.sh sourced; STRICT_PRE evaluated after sourcing; STRICT_IN is stdin
    local rc=0
    bash -c 'shopt -u nullglob extglob; set -euo pipefail; for f in boot-guard permissions library superseded launch repair; do source "$0/$f.sh"; done
        chown() { :; }; setfacl() { :; }; as_server() { "$@"; }; as_repair() { "$@"; }; repair_kill_all() { :; }; eval "${STRICT_PRE:-}"; "$@"' "$here" "$@" >/dev/null 2>&1 <"${STRICT_IN:-/dev/null}" || rc=$?
    echo "$rc"
}
mkrd; mkdir -p "$REPAIR_WORK"; echo max-players=21 > "$S/server.properties"; printf '1\thold\tplugins/A.jar\n' > "$RD/helper.log"; echo l > "$RD/attempt-1.log"
printf '1\tserver.properties\n' > "$RD/applied.txt"
check "strict repair_crash_step" "0" "$(strict2 repair_crash_step "$S" 1000000)"
check "strict repair_crash_count" "0" "$(strict2 repair_crash_count "$S" 1000000)"
check "strict repair_incident_allowed" "0" "$(strict2 repair_incident_allowed "$S" 1000000)"
check "strict repair_incident_record" "0" "$(strict2 repair_incident_record "$S" 1000000)"
check "strict repair_config_files" "0" "$(strict2 repair_config_files "$S")"
check "strict repair_work_build" "0" "$(strict2 repair_work_build "$S" "$RD" 7)"
check "strict repair_apply" "0" "$(strict2 repair_apply "$S" "$RD" 7)"
check "strict repair_requests" "0" "$(strict2 repair_requests "$S" "$RD" 7)"
check "strict repair_record_changes" "0" "$(strict2 repair_record_changes "$S" "$RD")"
check "strict repair_report" "0" "$(strict2 repair_report "$S" "$RD" repaired reason 1)"
check "strict repair_rollback" "0" "$(strict2 repair_rollback "$S" "$RD")"
check "strict repair_reason" "0" "$(strict2 repair_reason "$S" 2 1000000)"
check "strict repair_recover" "0" "$(strict2 repair_recover "$S")"
check "strict repair_clear_acls" "0" "$(strict2 repair_clear_acls "$S")"
# round O functions under strict mode (set -euo pipefail)
S_SAVE=$S; P_SAVE=$P; L_SAVE=$L
check "strict repair_enabled (on)" "0" "$(CLAUDE_REPAIR=on strict2 repair_enabled)"
check "strict repair_enabled (off -> 1)" "1" "$(CLAUDE_REPAIR=off strict2 repair_enabled)"
printf 'a: 1\npassword: x\n  - z\n# http://u:p@h/\nlast' > "$T/st-in.yml"; printf 'rcon.password=x\nm=1\\\nc\n' > "$T/st-in.properties"
printf '[auth]\nk = 1\n' > "$T/st-in.toml"; printf '{\n "apiKey": "x"\n}' > "$T/st-in.json"; printf 'a\0b' > "$T/st-in.bin"
for ext in yml properties toml json bin; do check "strict repair_mask ($ext)" "0" "$(strict2 repair_mask "x.$ext" "$T/st-in.$ext")"; done
printf 'api-key: s3\nlang: en\n' > "$T/st-old"; printf 'api-key: <hidden>\nlang: de\n' > "$T/st-new"
check "strict repair_check (allowed)" "0" "$(strict2 repair_check plugins/X/config.yml "$T/st-old" "$T/st-new")"
printf 'api-key: abc\n# c\n  - z\n' > "$T/st-new"
check "strict repair_check (refusals)" "0" "$(strict2 repair_check plugins/X/config.yml "$T/st-old" "$T/st-new")"
printf 'a=1\0\n' > "$T/st-new"
check "strict repair_check (binary props)" "0" "$(strict2 repair_check server.properties "$T/st-old" "$T/st-new")"
printf 'api-key: <hidden>\nlang: de\n' > "$T/st-new"
check "strict repair_merge" "0" "$(strict2 repair_merge plugins/X/config.yml "$T/st-old" "$T/st-new")"
mkdir -p "$REPAIR_WORK/logs"; echo "[ERROR] [BadPlugin] boom" > "$REPAIR_WORK/logs/latest.log"; : > "$RD/helper.log"
check "strict repair_hold_check (allowed)" "0" "$(strict2 repair_hold_check "$S" "$RD" BadPlugin.jar)"
check "strict repair_hold_check (refused)" "0" "$(strict2 repair_hold_check "$S" "$RD" Nope.jar)"
check "strict repair_clear_clean_stop" "0" "$(strict2 repair_clear_clean_stop "$S")"
check "strict repair_uid_pids" "0" "$(strict2 repair_uid_pids 1234 "$FP")"
check "strict repair_uid_pids (no match)" "0" "$(strict2 repair_uid_pids 9999 "$FP")"
mkdir -p "$S/claude-repair"
check "strict repair_index_add" "0" "$(strict2 repair_index_add "$S" 20260101T000000Z)"
check "strict repair_cli_access (grant)" "0" "$(REPAIR_CLI_ROOT=$CH strict2 repair_cli_access "$CH/u/links/claude")"
check "strict repair_cli_access (-x)" "0" "$(REPAIR_CLI_ROOT=$CH strict2 repair_cli_access -x "$CH/u/links/claude")"
mkstamps 20250101 20250102 20250103 20250104 20250105 20250106 20250107 20250108 20250109 20250110 20250111 20250112
check "strict repair_prune" "0" "$(strict2 repair_prune "$S" "$PN")"
echo new > "$T/put-src"; echo old > "$S/put-dst"
check "strict repair_put" "0" "$(strict2 repair_put "$T/put-src" "$S/put-dst")"
S=$S_SAVE; P=$P_SAVE; L=$L_SAVE
echo "sk-ant-abc" > "$T/redact-in"
check "strict repair_redact" "0" "$(STRICT_IN=$T/redact-in strict2 repair_redact)"
mkdir -p "$REPAIR_WORK"
check "strict repair_run_claude" "0" "$(PATH=$T/bin:$saved_path STUB_MODE=x strict2 repair_run_claude "$S" "$RD" 1 why)"
STRICT_PRE='launch_command() { printf "%s\n" bash "'"$T"'/fake-loads.sh" "$1"; }'
fresh; check "strict repair_server_trial" "0" "$(STRICT_PRE=$STRICT_PRE strict2 repair_server_trial "$S")"
mkserver; incident_env 1; export STUB_BODY='sed -i "s/max-players=abc/max-players=20/" "$STUB_W/config/server.properties"'
STRICT_PRE='launch_command() { printf "%s\n" bash "'"$T"'/srv-numeric.sh" "$1"; }'
check "strict repair_incident" "0" "$(PATH=$T/bin:$saved_path STRICT_PRE=$STRICT_PRE strict2 repair_incident "$S" why 0)"
unset STRICT_PRE
killserver

# ---------------- restore the shared harness ----------------
eval "$saved_launch"
PATH=$saved_path
unset REPAIR_ATTEMPTS REPAIR_STAYUP REPAIR_POLL REPAIR_STOP_GRACE CLAUDE_CODE_OAUTH_TOKEN STUB_MODE STUB_W STUB_CALLS STUB_BODY STUB_ARGV STUB_STDIN REPAIR_HOME REPAIR_PROMPT REPAIR_WORK REPAIR_HELPER
unrec
