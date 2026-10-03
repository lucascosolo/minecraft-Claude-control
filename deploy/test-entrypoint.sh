#!/bin/bash
# Table tests for boot-guard.sh in a scratch dir. No Docker. Usage: bash deploy/test-entrypoint.sh
set -u
here=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=boot-guard.sh
source "$here/boot-guard.sh"
for f in permissions library superseded launch; do
    # shellcheck disable=SC1090
    source "$here/$f.sh"
done
[[ -f $here/repair.sh ]] && source "$here/repair.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0; cases=0; failed=0

mkjar() { # file name main
    local d; d=$(mktemp -d -p "$T")
    printf 'name: %s\nmain: %s\nversion: 1\n' "$2" "$3" > "$d/plugin.yml"
    rm -f "$1"
    if command -v zip >/dev/null; then (cd "$d" && zip -q "$1" plugin.yml)
    else (cd "$d" && jar cf "$1" plugin.yml); fi
}
fresh() { # new scratch server; sets S, P, L
    S=$(mktemp -d -p "$T"); P=$S/plugins; L=$S/logs/latest.log
    mkdir -p "$P" "$S/logs"; : > "$L"
    OLD=$(date -d '2 hours ago' +%s)
}
check() { # name expected actual
    cases=$((cases + 1))
    if [[ $2 == "$3" ]]; then echo "PASS $1"; else echo "FAIL $1: expected '$2' got '$3'"; fail=1; failed=$((failed + 1)); fi
}
yn() { if "$@"; then echo yes; else echo no; fi; }
chown() { :; }   # not root; individual tests replace this with a recorder
setfacl() { :; }
old() { touch -d '3 hours ago' "$@"; }

fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo com.x.foo.FooMain; mkjar "$P/ClaudeBar.jar" ClaudeBar com.x.bar.BarMain
old "$P/ClaudeFoo.jar" "$P/ClaudeBar.jar"; echo "[12:00:00 ERROR]: [ClaudeBar] failed to enable" > "$L"
check "evidence by [Name]" "quarantine $P/ClaudeBar.jar" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"

fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo com.x.foo.FooMain; mkjar "$P/ClaudeBar.jar" ClaudeBar com.x.bar.BarMain
old "$P/ClaudeFoo.jar" "$P/ClaudeBar.jar"
printf '[12:00:00 ERROR]: Error occurred\njava.lang.NullPointerException\n\tat com.x.foo.FooMain.onEnable(FooMain.java:9)\n' > "$L"
check "evidence by package in stack trace" "quarantine $P/ClaudeFoo.jar" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"

fresh; mkjar "$P/ClaudeAaa.jar" ClaudeAaa claude.example.AaaMain; mkjar "$P/ClaudeZzz.jar" ClaudeZzz claude.example.ZzzMain
old "$P/ClaudeAaa.jar" "$P/ClaudeZzz.jar"
printf '[12:00:00 ERROR]: [ClaudeZzz] Error occurred\n\tat claude.example.ZzzMain.onEnable(ZzzMain.java:9)\n' > "$L"
check "shared package: the named jar is blamed, not the first" "quarantine $P/ClaudeZzz.jar" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"
printf '[12:00:00 ERROR]: Error occurred\n\tat claude.example.ZzzMain.onEnable(ZzzMain.java:9)\n' > "$L"
check "shared package alone is no evidence" "none" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"

fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo com.x.foo.FooMain; mkjar "$P/ClaudeBar.jar" ClaudeBar com.x.bar.BarMain
old "$P/ClaudeFoo.jar"; touch -d '30 minutes ago' "$P/ClaudeBar.jar"; echo "[12:00:00 ERROR]: [Other] boom" > "$L"
check "no evidence, newest since last ok" "quarantine $P/ClaudeBar.jar" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"

fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo com.x.foo.FooMain; old "$P/ClaudeFoo.jar"
check "nothing to blame" "none" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"

fresh; mkjar "$P/ClaudeControl.jar" ClaudeControl com.lucascosolo.claudecontrol.Main
echo "[12:00:00 ERROR]: [ClaudeControl] boom at com.lucascosolo.claudecontrol.Main" > "$L"
check "ClaudeControl never blamed" "none" "$(boot_guard_decide 1 "$L" "$P" 0)"

fresh; mkjar "$P/ClaudeFoo_r3.jar" ClaudeFoo com.x.foo.FooMain; old "$P/ClaudeFoo_r3.jar"; echo "[12:00:00 ERROR]: [ClaudeFoo] boom" > "$L"
check "revision suffix handled" "quarantine $P/ClaudeFoo_r3.jar" "$(boot_guard_decide 1 "$L" "$P" "$OLD")"

fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo com.x.foo.FooMain
check "failures 2 -> hold" "hold" "$(boot_guard_decide 2 "$L" "$P" 0)"
fresh; mkjar "$P/ClaudeControl.jar" ClaudeControl com.lucascosolo.claudecontrol.Main
check "failures 2, no Claude jars -> halt" "halt" "$(boot_guard_decide 2 "$L" "$P" 0)"
check "failures 3 -> halt" "halt" "$(boot_guard_decide 3 "$L" "$P" 0)"
check "failures 0 -> none" "none" "$(boot_guard_decide 0 "$L" "$P" 0)"

# Stage actions
fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; echo "line1" > "$L"
boot_guard_apply "quarantine $P/ClaudeFoo.jar" "$S" | grep -q '^boot guard:' || { echo "FAIL apply quarantine: no message"; fail=1; }
check "apply quarantine moves jar" "yes" "$([[ -f $P/claude-quarantine/ClaudeFoo.jar && ! -e $P/ClaudeFoo.jar && -f $P/ClaudeControl.jar ]] && echo yes || echo no)"
check "apply quarantine hand-off file" "ClaudeFoo.jar line1" "$(tr '\n' ' ' < "$S/claude-quarantined-on-boot" | sed 's/ $//')"

fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkjar "$P/ClaudeBar.jar" ClaudeBar x.B; mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; echo 2 > "$S/claude-boot-failures"
boot_guard_apply hold "$S" >/dev/null
check "apply hold moves Claude jars only" "yes" "$([[ -f $P/claude-held/ClaudeFoo.jar && -f $P/claude-held/ClaudeBar.jar && -f $P/ClaudeControl.jar && ! -e $P/claude-held/ClaudeControl.jar ]] && echo yes || echo no)"
check "apply hold hand-off file" "held after 2 failed boots,ClaudeBar.jar,ClaudeFoo.jar" "$(sort -k1,1r "$S/claude-held-on-boot" | head -n1 | tr -d '\n'),$(tail -n +2 "$S/claude-held-on-boot" | sort | paste -sd,)"

echo 3 > "$S/claude-boot-failures"; echo "tail line" > "$L"
boot_guard_apply halt "$S" >/dev/null
check "apply halt restores held jars" "yes" "$([[ -f $P/ClaudeFoo.jar && -f $P/ClaudeBar.jar && -z $(ls "$P/claude-held") ]] && echo yes || echo no)"
check "apply halt writes marker with log" "tail line" "$(tail -n 1 "$S/claude-boot-halted")"

# ---- library_shelve_expired ----
libfresh() { fresh; L2=$P/claude-library; Q=$P/claude-quarantine/superseded; mkdir -p "$L2"; }

libfresh
mkjar "$P/ClaudeOld.jar" ClaudeOld x.O; mkjar "$P/ClaudeLive.jar" ClaudeLive x.L
mkjar "$P/ClaudeRev.jar" ClaudeRev x.R; mkjar "$P/ClaudeRev_r2.jar" ClaudeRev x.R; mkjar "$P/ClaudeRev_r10.jar" ClaudeRev x.R
printf '%s ClaudeOld\n%s ClaudeLive\n%s ClaudeRev\n%s ClaudeGone\n' 100 99999999999 200 300 > "$L2/lifetimes.txt"
echo ClaudePrev > "$L2/shelved-at-boot.txt"
out=$(library_shelve_expired "$P" 1000); rc=$?
check "shelve returns 0" "0" "$rc"
check "shelve expired jar moved to library" "yes" "$(yn test -f "$L2/ClaudeOld.jar" -a ! -e "$P/ClaudeOld.jar")"
check "shelve unexpired jar kept" "yes" "$(yn test -f "$P/ClaudeLive.jar" -a ! -e "$L2/ClaudeLive.jar")"
check "shelve newest revision goes to library" "yes" "$(yn test -f "$L2/ClaudeRev.jar" -a ! -e "$P/ClaudeRev_r10.jar")"
check "shelve other revisions go to superseded" "yes" "$(yn test -f "$Q/ClaudeRev.jar" -a -f "$Q/ClaudeRev_r2.jar" -a ! -e "$P/ClaudeRev.jar" -a ! -e "$P/ClaudeRev_r2.jar")"
check "shelve missing jar tolerated, expired lines dropped" "99999999999 ClaudeLive" "$(cat "$L2/lifetimes.txt")"
check "shelve shelved-at-boot appended, old lines kept" "ClaudeOld ClaudePrev ClaudeRev" "$(sort "$L2/shelved-at-boot.txt" | paste -sd' ')"
check "shelve leaves no temp files" "ClaudeOld.jar ClaudeRev.jar lifetimes.txt shelved-at-boot.txt" "$(ls "$L2" | paste -sd' ')"

libfresh; mkjar "$P/ClaudeEdge.jar" ClaudeEdge x.E; echo "1000 ClaudeEdge" > "$L2/lifetimes.txt"
library_shelve_expired "$P" 1000 >/dev/null
check "shelve epoch == NOW is expired" "yes" "$(yn test -f "$L2/ClaudeEdge.jar")"

libfresh; mkjar "$P/ClaudeRev.jar" ClaudeRev x.R; mkjar "$P/ClaudeRev_r2.jar" ClaudeRev x.R; mkdir -p "$Q"; echo prior > "$Q/ClaudeRev.jar"
echo "5 ClaudeRev" > "$L2/lifetimes.txt"
library_shelve_expired "$P" 1000 >/dev/null
check "shelve superseded name collision keeps old file" "prior" "$(cat "$Q/ClaudeRev.jar")"
check "shelve superseded name collision gets epoch prefix" "1" "$(ls "$Q" | grep -cE '^[0-9]+-ClaudeRev\.jar$')"

libfresh; mkjar "$P/NomadCairn.jar" NomadCairn x.N; mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; mkjar "$P/ClaudeControlX.jar" ClaudeControlX x.X
printf '%s NomadCairn\n%s ClaudeControl\n%s ClaudeControlX\n%s Claude-bad\n%s ClaudeLive\n' 1 1 1 1 99999999999 > "$L2/lifetimes.txt"
out=$(library_shelve_expired "$P" 1000)
check "shelve ignored line is reported with name" "yes" "$(grep -i 'ignor' <<<"$out" | grep -q NomadCairn && echo yes || echo no)"
check "shelve ignored bases untouched" "yes" "$(yn test -f "$P/NomadCairn.jar" -a -f "$P/ClaudeControl.jar" -a -f "$P/ClaudeControlX.jar" -a -z "$(ls "$L2" | grep -v -e lifetimes.txt -e shelved-at-boot.txt)")"
check "shelve ignored lines dropped from lifetimes" "99999999999 ClaudeLive" "$(cat "$L2/lifetimes.txt")"

libfresh; echo secret > "$T/elsewhere.jar"; ln -s "$T/elsewhere.jar" "$P/ClaudeLnk.jar"; mkjar "$P/ClaudeLnk_r2.jar" ClaudeLnk x.L
echo "1 ClaudeLnk" > "$L2/lifetimes.txt"
out=$(library_shelve_expired "$P" 1000)
check "shelve symlinked jar refused and left" "yes" "$(yn test -L "$P/ClaudeLnk.jar" -a -f "$T/elsewhere.jar" -a ! -e "$Q/ClaudeLnk.jar" -a ! -L "$L2/ClaudeLnk.jar")"
check "shelve symlinked jar prints refus" "yes" "$(grep -qi refus <<<"$out" && echo yes || echo no)"
check "shelve newest regular jar still shelved" "yes" "$(yn test -f "$L2/ClaudeLnk.jar" -a ! -L "$L2/ClaudeLnk.jar" -a ! -e "$P/ClaudeLnk_r2.jar")"

libfresh; rmdir "$L2"; mkdir "$T/realdir"; ln -s "$T/realdir" "$L2"
mkjar "$P/ClaudeOld.jar" ClaudeOld x.O; echo "1 ClaudeOld" > "$T/realdir/lifetimes.txt"
out=$(library_shelve_expired "$P" 1000); rc=$?
check "shelve symlinked library dir refused" "0 yes yes" "$rc $(grep -qi refus <<<"$out" && echo yes || echo no) $(yn test -f "$P/ClaudeOld.jar" -a "$(ls "$T/realdir")" = lifetimes.txt)"
rm -rf "$T/realdir"

libfresh; mkjar "$P/ClaudeOld.jar" ClaudeOld x.O; echo "1 ClaudeOld" > "$T/real-lifetimes.txt"; ln -s "$T/real-lifetimes.txt" "$L2/lifetimes.txt"
out=$(library_shelve_expired "$P" 1000); rc=$?
check "shelve symlinked lifetimes.txt refused" "0 yes yes" "$rc $(grep -qi refus <<<"$out" && echo yes || echo no) $(yn test -f "$P/ClaudeOld.jar" -a "$(cat "$T/real-lifetimes.txt")" = "1 ClaudeOld")"

libfresh; mkjar "$P/ClaudeOld.jar" ClaudeOld x.O; echo "1 ClaudeOld" > "$L2/lifetimes.txt"; echo keep > "$T/real-shelved.txt"; ln -s "$T/real-shelved.txt" "$L2/shelved-at-boot.txt"
out=$(library_shelve_expired "$P" 1000); rc=$?
check "shelve symlinked shelved-at-boot refused" "0 yes yes" "$rc $(grep -qi refus <<<"$out" && echo yes || echo no) $(yn test -f "$P/ClaudeOld.jar" -a "$(cat "$T/real-shelved.txt")" = keep)"

libfresh; library_shelve_expired "$P" 1000 >/dev/null; check "shelve no lifetimes file returns 0" "0" "$?"
check "shelve no lifetimes file does nothing" "" "$(ls "$L2")"

# ---- library_reconcile ----
libfresh
mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; mkjar "$L2/ClaudeControl.jar" ClaudeControl x.C
mkjar "$L2/ClaudeDup.jar" ClaudeDup x.D; mkjar "$P/ClaudeDup.jar" ClaudeDup x.D; mkjar "$P/ClaudeDup_r3.jar" ClaudeDup x.D
mkjar "$L2/ClaudeSolo.jar" ClaudeSolo x.S; mkjar "$P/ClaudeLoose.jar" ClaudeLoose x.L
echo x > "$L2/a.tmp"; echo x > "$T/target.tmp"; ln -s "$T/target.tmp" "$L2/b.tmp"
out=$(library_reconcile "$P")
check "reconcile deletes regular .tmp files" "yes" "$(yn test ! -e "$L2/a.tmp")"
check "reconcile leaves symlink .tmp alone" "yes" "$(yn test -L "$L2/b.tmp" -a -f "$T/target.tmp")"
check "reconcile library copy wins" "yes" "$(yn test -f "$L2/ClaudeDup.jar" -a ! -e "$P/ClaudeDup.jar" -a ! -e "$P/ClaudeDup_r3.jar" -a -f "$Q/ClaudeDup.jar" -a -f "$Q/ClaudeDup_r3.jar")"
check "reconcile prints reconcile and Base" "yes" "$(grep -i reconcile <<<"$out" | grep -q ClaudeDup && echo yes || echo no)"
check "reconcile untouched: ClaudeControl, lone jars" "yes" "$(yn test -f "$P/ClaudeControl.jar" -a -f "$L2/ClaudeControl.jar" -a -f "$L2/ClaudeSolo.jar" -a -f "$P/ClaudeLoose.jar")"
check "reconcile consistent state prints nothing" "" "$(library_reconcile "$P")"

# ---- superseded_cleanup ----
fresh; Q=$P/claude-quarantine/superseded
mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; mkjar "$P/ClaudeControl_r2.jar" ClaudeControl x.C
mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkjar "$P/ClaudeFoo_r2.jar" ClaudeFoo x.F; mkjar "$P/ClaudeFoo_r10.jar" ClaudeFoo x.F
mkjar "$P/ClaudeBar.jar" ClaudeBar x.B; mkjar "$P/ClaudeSolo.jar" ClaudeSolo x.S
superseded_cleanup "$P" >/dev/null
check "superseded only highest revision stays" "ClaudeBar.jar ClaudeControl.jar ClaudeControl_r2.jar ClaudeFoo_r10.jar ClaudeSolo.jar" "$(cd "$P" && ls *.jar | paste -sd' ')"
check "superseded older revisions quarantined" "ClaudeFoo.jar ClaudeFoo_r2.jar" "$(ls "$Q" | paste -sd' ')"
check "superseded ClaudeControl untouched" "yes" "$(yn test -f "$P/ClaudeControl.jar" -a -f "$P/ClaudeControl_r2.jar")"
check "superseded idempotent" "ClaudeFoo.jar ClaudeFoo_r2.jar" "$(superseded_cleanup "$P" >/dev/null; ls "$Q" | paste -sd' ')"

fresh; Q=$P/claude-quarantine/superseded; mkdir -p "$Q"
for i in 1 2 3 4 5; do mkjar "$Q/ClaudeFoo_r$((i + 1)).jar" ClaudeFoo x.F; touch -d "$((10 - i)) hours ago" "$Q/ClaudeFoo_r$((i + 1)).jar"; done
mkjar "$Q/ClaudeBar.jar" ClaudeBar x.B; touch -d '20 hours ago' "$Q/ClaudeBar.jar"
mkjar "$Q/ClaudeFoo.jar" ClaudeFoo x.F; touch -d '30 hours ago' "$Q/ClaudeFoo.jar"
echo keep > "$P/claude-quarantine/ClaudeManual.jar"
superseded_cleanup "$P" >/dev/null
check "superseded keeps 3 newest per base, deletes older" "ClaudeBar.jar ClaudeFoo_r4.jar ClaudeFoo_r5.jar ClaudeFoo_r6.jar" "$(ls "$Q" | paste -sd' ')"
check "superseded never deletes outside superseded/" "yes" "$(yn test -f "$P/claude-quarantine/ClaudeManual.jar")"

# ---- permissions_apply ----
fresh; : > "$T/calls"
chown() { echo "chown $*" >> "$T/calls"; }
setfacl() { echo "setfacl $*" >> "$T/calls"; }
mkdir -p "$P/ClaudeControl/cli-home" "$P/ClaudeControl/cli-run"
echo s > "$S/server.properties"; chmod 666 "$S/server.properties"
echo w > "$S/world-writable.txt"; chmod 666 "$S/world-writable.txt"
permissions_apply "$S" >/dev/null 2>&1
check "permissions ClaudeControl dir is 0751" "751" "$(stat -c %a "$P/ClaudeControl")"
check "permissions cli-home is 3770" "3770" "$(stat -c %a "$P/ClaudeControl/cli-home")"
check "permissions creates library and superseded dirs" "yes" "$(yn test -d "$P/claude-library" -a -d "$P/claude-quarantine/superseded")"
check "permissions strips o+w from files" "yes" "$(yn test -z "$(find "$S" ! -type l -perm -o+w)")"
check "permissions server.properties not world-readable" "0" "$(( $(stat -c %a "$S/server.properties") % 10 ))"
check "permissions chown mc:mc on claude-library" "yes" "$(grep '^chown ' "$T/calls" | grep 'mc:mc' | grep -q 'claude-library' && echo yes || echo no)"
check "permissions setfacl called for mc" "yes" "$(grep -q '^setfacl .*u:mc' "$T/calls" && echo yes || echo no)"
chown() { :; }; setfacl() { :; }

# ---- boot_guard_step ----
fresh
rc=0; boot_guard_step "$S" >/dev/null || rc=$?
check "step no marker: rc 0, counter 0, marker made" "0 0 yes" "$rc $(cat "$S/claude-boot-failures") $(yn test -e "$S/claude-boot-pending")"

fresh; mkjar "$P/ClaudeBar.jar" ClaudeBar x.B; touch -d '3 hours ago' "$S/claude-boot-ok"
echo 0 > "$S/claude-boot-failures"; touch "$S/claude-boot-pending"
echo "[12:00:00 ERROR]: [ClaudeBar] failed to enable" > "$L"
rc=0; boot_guard_step "$S" >/dev/null || rc=$?
check "step failed boot: counter 1, jar quarantined, rc 0" "0 1 yes yes" "$rc $(cat "$S/claude-boot-failures") $(yn test -f "$P/claude-quarantine/ClaudeBar.jar" -a ! -e "$P/ClaudeBar.jar") $(yn test -e "$S/claude-boot-pending")"

fresh; echo "halted" > "$S/claude-boot-halted"; echo 3 > "$S/claude-boot-failures"; touch "$S/claude-boot-pending"
rc=0; boot_guard_step "$S" >/dev/null || rc=$?
check "step halt file: moved to .prev, counter 0, rc 0" "0 0 yes yes" "$rc $(cat "$S/claude-boot-failures") $(yn test -f "$S/claude-boot-halted.prev" -a ! -e "$S/claude-boot-halted") $(yn test -e "$S/claude-boot-pending")"

fresh; mkjar "$P/ClaudeControl.jar" ClaudeControl x.C; echo 2 > "$S/claude-boot-failures"; touch "$S/claude-boot-pending"
rc=0; boot_guard_step "$S" >/dev/null || rc=$?
check "step third failure: rc 2, halt file, marker kept" "2 yes yes" "$rc $(yn test -f "$S/claude-boot-halted") $(yn test -e "$S/claude-boot-pending")"

# ---- launch_command / as_server ----
fresh; echo a > "$S/folia-old.jar"; echo b > "$S/folia-new.jar"; touch -d '2 days ago' "$S/folia-old.jar"
mapfile -t words < <(launch_command "$S")
n=${#words[@]}
check "launch starts with setpriv --reuid=mc" "setpriv --reuid=mc" "${words[0]:-} ${words[1]:-}"
check "launch ends with -jar newest folia --nogui" "-jar $S/folia-new.jar --nogui" "${words[n-3]:-} ${words[n-2]:-} ${words[n-1]:-}"

fresh
err=$(launch_command "$S" 2>&1 >/dev/null); rc=$?
check "launch without folia jar fails with message" "yes yes" "$([[ $rc -ne 0 ]] && echo yes || echo no) $([[ $err == *folia* ]] && echo yes || echo no)"

check "as_server dry run prints setpriv command" "setpriv --reuid=mc --regid=mc --init-groups env HOME=/home/mc echo hi" "$(AS_SERVER_DRYRUN=1 as_server echo hi)"

# ---- every step function survives entrypoint.sh's `set -euo pipefail` ----
strict() { # run "$@" in a subshell under strict mode, nullglob/extglob off; print its exit code
    local rc=0
    bash -c 'shopt -u nullglob extglob; set -euo pipefail; for f in boot-guard permissions library superseded launch; do source "$0/$f.sh"; done
        chown() { :; }; setfacl() { :; }; "$@"' "$here" "$@" >/dev/null 2>&1 || rc=$?
    echo "$rc"
}
fresh; mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkjar "$P/ClaudeFoo_r2.jar" ClaudeFoo x.F; mkjar "$P/ClaudeBar.jar" ClaudeBar x.B
check "strict superseded_cleanup, two revisions, nullglob off" "0" "$(strict superseded_cleanup "$P")"
fresh
check "strict superseded_cleanup, empty plugins" "0" "$(strict superseded_cleanup "$P")"

libfresh; mkjar "$L2/ClaudeDup.jar" ClaudeDup x.D; mkjar "$P/ClaudeDup.jar" ClaudeDup x.D; echo x > "$L2/a.tmp"
check "strict library_reconcile" "0" "$(strict library_reconcile "$P")"
libfresh
check "strict library_reconcile, consistent" "0" "$(strict library_reconcile "$P")"

libfresh; mkjar "$P/ClaudeRev.jar" ClaudeRev x.R; mkjar "$P/ClaudeRev_r2.jar" ClaudeRev x.R; echo "5 ClaudeRev" > "$L2/lifetimes.txt"
check "strict library_shelve_expired, expired base with two revisions" "0" "$(strict library_shelve_expired "$P" 1000)"
libfresh
check "strict library_shelve_expired, missing lifetimes file" "0" "$(strict library_shelve_expired "$P" 1000)"

fresh; mkdir -p "$P/ClaudeControl/cli-home" "$P/ClaudeControl/cli-run"; echo s > "$S/server.properties"; : > "$T/calls"
chown() { echo "chown $*" >> "$T/calls"; }; setfacl() { echo "setfacl $*" >> "$T/calls"; }
check "strict permissions_apply" "0" "$(strict permissions_apply "$S")"
chown() { :; }; setfacl() { :; }

fresh
check "strict boot_guard_step, no marker" "0" "$(strict boot_guard_step "$S")"

# ---- D1: no root step follows a link (docs/PRD-v0.3.md D1) ----
rec() { chown() { echo "chown $*" >> "$T/calls"; }; setfacl() { echo "setfacl $*" >> "$T/calls"; }; : > "$T/calls"; }
unrec() { chown() { :; }; setfacl() { :; }; }
has() { grep -q -- "$1" "$T/calls" && echo yes || echo no; }
saved_as_server=$(declare -f as_server)
as_server() { "$@"; }

# a) plugins/claude-library -> elsewhere
fresh; rec; rm -rf "$T/elsewhere-lib"; mkdir "$T/elsewhere-lib"; chmod 0700 "$T/elsewhere-lib"
mkdir -p "$P/ClaudeControl/cli-home" "$P/ClaudeControl/cli-run"; ln -s "$T/elsewhere-lib" "$P/claude-library"
out=$(permissions_apply "$S" 2>&1); rc=$?
check "D1 link: permissions_apply on linked claude-library returns 0" "0" "$rc"
check "D1 link: permissions_apply says refusing for claude-library" "yes" "$(grep -q refusing <<<"$out" && echo yes || echo no)"
check "D1 link: no chown mentions linked claude-library" "no" "$(has claude-library)"
check "D1 link: linked claude-library target mode unchanged" "700" "$(stat -c %a "$T/elsewhere-lib")"
unrec

# b) cli-home/settings.json -> elsewhere
fresh; rec; rm -f "$T/elsewhere-settings"; echo '{}' > "$T/elsewhere-settings"; chmod 0666 "$T/elsewhere-settings"
mkdir -p "$P/ClaudeControl/cli-home" "$P/ClaudeControl/cli-run"; ln -s "$T/elsewhere-settings" "$P/ClaudeControl/cli-home/settings.json"
out=$(permissions_apply "$S" 2>&1); rc=$?
check "D1 link: permissions_apply on linked settings.json returns 0" "0" "$rc"
check "D1 link: no chown mentions linked settings.json" "no" "$(has settings.json)"
check "D1 link: linked settings.json target mode unchanged" "666" "$(stat -c %a "$T/elsewhere-settings")"
unrec

# c) boot guard marker and counter through links
fresh; rec; rm -f "$T/elsewhere-halted"; ln -s "$T/elsewhere-halted" "$S/claude-boot-halted"
echo 2 > "$S/claude-boot-failures"; touch "$S/claude-boot-pending"
out=$(boot_guard_step "$S" 2>&1); rc=$?
check "D1 link: boot_guard_step does not write through dangling halt link" "no" "$(yn test -e "$T/elsewhere-halted")"
check "D1 link: boot_guard_step says refusing for halt link" "yes" "$(grep -q refusing <<<"$out" && echo yes || echo no)"
unrec
fresh; rec; echo x > "$T/elsewhere-counter"; ln -s "$T/elsewhere-counter" "$S/claude-boot-failures"; touch "$S/claude-boot-pending"
out=$(boot_guard_step "$S" 2>&1); rc=$?
check "D1 link: boot_guard_step does not write through counter link" "x" "$(cat "$T/elsewhere-counter")"
unrec

# d) path_safe
fresh; mkdir -p "$T/p2/claude-library"; rmdir "$P"; ln -s "$T/p2" "$P"
out=$(path_safe "$S" "$P/claude-library" 2>&1); rc=$?
check "D1 path_safe: link in middle component -> rc 1" "1" "$rc"
check "D1 path_safe: refusal names the link" "yes" "$(grep -q refusing <<<"$out" && grep -q "$P" <<<"$out" && echo yes || echo no)"
fresh; mkdir -p "$P/claude-library"
path_safe "$S" "$P/claude-library" >/dev/null 2>&1; check "D1 path_safe: plain nested path -> 0" "0" "$?"
path_safe "$S" "$S/nope/missing.txt" >/dev/null 2>&1; check "D1 path_safe: non-existent path -> 0" "0" "$?"

# e) safe_chown only when the owner differs, and with -h
fresh; rec; echo f > "$S/plain.txt"
safe_chown "$S" "$(id -un):$(id -gn)" "$S/plain.txt" >/dev/null 2>&1
check "D1 safe_chown: same owner calls no chown" "" "$(cat "$T/calls")"
safe_chown "$S" mc:mc "$S/plain.txt" >/dev/null 2>&1; rc=$?
check "D1 safe_chown: other owner records chown -h" "0 chown -h mc:mc $S/plain.txt" "$rc $(cat "$T/calls")"
unrec

# f) server_write
fresh; : > "$T/calls"; echo keep > "$T/elsewhere-sw"; ln -s "$T/elsewhere-sw" "$S/linked-out"
out=$(echo new | server_write "$S/linked-out" 2>&1); rc=$?
check "D1 server_write: link refused, rc 1, target untouched" "1 yes keep" "$rc $(grep -q refusing <<<"$out" && echo yes || echo no) $(cat "$T/elsewhere-sw")"
echo hello | server_write "$S/plain-out" >/dev/null 2>&1
check "D1 server_write: normal path gets stdin" "hello" "$(cat "$S/plain-out" 2>/dev/null)"

# g) a failing mv is logged and does not abort, also under strict
strictout() { # like strict, but prints "<rc> <output>" on one line
    local o rc=0
    o=$(bash -c 'shopt -u nullglob extglob; set -euo pipefail; for f in boot-guard permissions library superseded launch; do source "$0/$f.sh"; done
        chown() { :; }; setfacl() { :; }; "$@"' "$here" "$@" 2>/dev/null) || rc=$?
    echo "$rc $(tr '\n' ' ' <<<"$o")"
}
if [[ $(id -u) -eq 0 ]]; then
    echo "SKIP D1 mv failure cases (running as root, directory modes do not stop mv)"
else
    fresh; Q=$P/claude-quarantine/superseded; mkdir -p "$Q"; mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkjar "$P/ClaudeFoo_r2.jar" ClaudeFoo x.F
    chmod 0555 "$Q"; res=$(strictout superseded_cleanup "$P"); chmod 0755 "$Q"
    check "D1 mv failure: superseded_cleanup rc 0 under strict" "0" "${res%% *}"
    check "D1 mv failure: superseded_cleanup logs cannot" "yes" "$(grep -q cannot <<<"$res" && echo yes || echo no)"
    check "D1 mv failure: superseded_cleanup leaves jar in plugins" "yes" "$(yn test -f "$P/ClaudeFoo.jar")"
    libfresh; mkdir -p "$Q"; mkjar "$L2/ClaudeDup.jar" ClaudeDup x.D; mkjar "$P/ClaudeDup.jar" ClaudeDup x.D
    chmod 0555 "$Q"; res=$(strictout library_reconcile "$P"); chmod 0755 "$Q"
    check "D1 mv failure: library_reconcile rc 0 under strict" "0" "${res%% *}"
    check "D1 mv failure: library_reconcile logs cannot" "yes" "$(grep -q cannot <<<"$res" && echo yes || echo no)"
    check "D1 mv failure: library_reconcile leaves jar in plugins" "yes" "$(yn test -f "$P/ClaudeDup.jar")"
fi

# h) D10 shared reconcile table
table=$here/../src/test/resources/library-reconcile-cases.txt
if [[ ! -f $table ]]; then check "reconcile table file present" "yes" "no"; else
while IFS='|' read -r name inp inl mark exp; do
    [[ -z $name || $name == \#* ]] && continue
    libfresh; Q=$P/claude-quarantine/superseded
    [[ $inp == yes ]] && mkjar "$P/ClaudeTbl.jar" ClaudeTbl x.T
    [[ $inl == yes ]] && mkjar "$L2/ClaudeTbl.jar" ClaudeTbl x.T
    case $mark in
        match) sha256sum "$L2/ClaudeTbl.jar" | cut -d' ' -f1 > "$L2/ClaudeTbl.activating" ;;
        mismatch) printf '%064d\n' 0 > "$L2/ClaudeTbl.activating" ;;
        match-older) mkjar "$P/ClaudeTbl.jar" ClaudeTbl x.Older; mkjar "$P/ClaudeTbl_r2.jar" ClaudeTbl x.T; sha256sum "$L2/ClaudeTbl.jar" | cut -d' ' -f1 > "$L2/ClaudeTbl.activating" ;;
        plugins-differs) sha256sum "$L2/ClaudeTbl.jar" | cut -d' ' -f1 > "$L2/ClaudeTbl.activating"; head -c 20 "$P/ClaudeTbl.jar" > "$P/ClaudeTbl.trunc" && mv -f "$P/ClaudeTbl.trunc" "$P/ClaudeTbl.jar"; trunc_sha=$(sha256sum "$P/ClaudeTbl.jar" | cut -d' ' -f1) ;;
    esac
    library_reconcile "$P" >/dev/null 2>&1
    case $exp in
        library) got=$(yn test -f "$L2/ClaudeTbl.jar" -a ! -e "$P/ClaudeTbl.jar" -a -n "$(ls "$Q" 2>/dev/null | grep ClaudeTbl)"); want=yes ;;
        activated) got=$(yn test -f "$P/ClaudeTbl.jar" -a ! -e "$L2/ClaudeTbl.jar" -a ! -e "$L2/ClaudeTbl.activating"); want=yes ;;
        activated-older) got=$(yn test -f "$P/ClaudeTbl_r2.jar" -a ! -e "$L2/ClaudeTbl.jar" -a ! -e "$L2/ClaudeTbl.activating" -a ! -e "$P/ClaudeTbl.jar" -a -n "$(ls "$Q" 2>/dev/null | grep ClaudeTbl)"); want=yes ;;
        none) got=$([[ $(yn test -f "$P/ClaudeTbl.jar") == "$inp" && $(yn test -f "$L2/ClaudeTbl.jar") == "$inl" && -z $(ls "$Q" 2>/dev/null) ]] && echo yes || echo no); want=yes ;;
        *) got="bad expected '$exp'"; want=yes ;;
    esac
    check "reconcile table: $name" "$want" "$got"
    if [[ $mark == plugins-differs ]]; then
        check "reconcile table: $name (superseded holds the truncated plugins jar)" "yes" "$(yn test -n "$(find "$Q" -name '*ClaudeTbl*' -type f 2>/dev/null | head -n1)" -a "$(sha256sum "$(find "$Q" -name '*ClaudeTbl*' -type f 2>/dev/null | head -n1)" 2>/dev/null | cut -d' ' -f1)" = "$trunc_sha")"
    fi
done < "$table"
fi

# ---- E4: root steps and links (docs/PRD-v0.3.md round E) ----
# a) superseded_cleanup goes through as_server and checks path_safe on the quarantine dirs
check "E4 superseded: entrypoint runs the cleanup through as_server" "yes" "$(grep -E '^[[:space:]]*as_server .*superseded' "$here/entrypoint.sh" >/dev/null && echo yes || echo no)"
fresh; rm -rf "$T/outside-q"; mkdir -p "$T/outside-q/superseded"
for i in 1 2 3 4 5; do mkjar "$T/outside-q/superseded/ClaudeBaz_r$((i + 1)).jar" ClaudeBaz x.B; touch -d "$((10 - i)) hours ago" "$T/outside-q/superseded/ClaudeBaz_r$((i + 1)).jar"; done
echo old > "$T/outside-q/ClaudeManual.jar"
before=$(cd "$T/outside-q" && find . -type f -exec sha256sum {} + | sort | sha256sum)
ln -s "$T/outside-q" "$P/claude-quarantine"
mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkjar "$P/ClaudeFoo_r2.jar" ClaudeFoo x.F
out=$(superseded_cleanup "$P" 2>&1); rc=$?
check "E4 superseded: linked claude-quarantine, rc 0" "0" "$rc"
check "E4 superseded: linked claude-quarantine, outside dir unchanged" "$before" "$(cd "$T/outside-q" && find . -type f -exec sha256sum {} + | sort | sha256sum)"
check "E4 superseded: linked claude-quarantine, jar left in plugins" "yes" "$(yn test -f "$P/ClaudeFoo.jar")"
check "E4 superseded: linked claude-quarantine, refusal logged" "yes" "$(grep -qi refus <<<"$out" && echo yes || echo no)"

# b) permissions_apply removes multiply-linked regular files under agent/ and cli-home/
fresh; rec; rm -f "$T/outside-hl"; echo precious > "$T/outside-hl"; chmod 0644 "$T/outside-hl"
mkdir -p "$P/ClaudeControl/agent" "$P/ClaudeControl/cli-home" "$P/ClaudeControl/cli-run"
ln "$T/outside-hl" "$P/ClaudeControl/agent/hl.txt"; echo ok > "$P/ClaudeControl/agent/plain.txt"
out=$(permissions_apply "$S" 2>&1); rc=$?
check "E4 permissions: hard-linked agent file removed" "yes" "$(yn test ! -e "$P/ClaudeControl/agent/hl.txt")"
check "E4 permissions: outside file keeps content and mode" "precious 644" "$(cat "$T/outside-hl") $(stat -c %a "$T/outside-hl")"
check "E4 permissions: outside file owner unchanged" "$(id -un)" "$(stat -c %U "$T/outside-hl")"
check "E4 permissions: removal logged with the path" "yes" "$(grep -q 'agent/hl.txt' <<<"$out" && echo yes || echo no)"
check "E4 permissions: single-link agent file kept" "yes" "$(yn test -f "$P/ClaudeControl/agent/plain.txt")"
unrec

# c) boot_guard_apply does not clobber a planted link at the destination name
fresh; rm -f "$T/outside-mv"; echo secret > "$T/outside-mv"
mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkdir -p "$P/claude-quarantine"; ln -s "$T/outside-mv" "$P/claude-quarantine/ClaudeFoo.jar"
out=$(boot_guard_apply "quarantine $P/ClaudeFoo.jar" "$S" 2>&1); rc=$?
check "E4 boot guard: quarantine onto planted link, rc 0, outside untouched" "0 secret" "$rc $(cat "$T/outside-mv")"
check "E4 boot guard: quarantine planted link still a link to the outside file" "yes" "$(yn test -L "$P/claude-quarantine/ClaudeFoo.jar" -a "$(readlink "$P/claude-quarantine/ClaudeFoo.jar")" = "$T/outside-mv")"
check "E4 boot guard: quarantined jar kept (renamed in folder, or left with a log line)" "yes" "$( { [[ -f $P/ClaudeFoo.jar ]] && grep -q cannot <<<"$out"; } || [[ -n $(find "$P/claude-quarantine" -maxdepth 1 -type f -name '*ClaudeFoo*' ) ]] && echo yes || echo no)"

fresh; rm -f "$T/outside-mv2"; echo secret2 > "$T/outside-mv2"
mkjar "$P/ClaudeFoo.jar" ClaudeFoo x.F; mkdir -p "$P/claude-held"; ln -s "$T/outside-mv2" "$P/claude-held/ClaudeFoo.jar"; echo 2 > "$S/claude-boot-failures"
out=$(boot_guard_apply hold "$S" 2>&1); rc=$?
check "E4 boot guard: hold onto planted link, rc 0, outside untouched" "0 secret2" "$rc $(cat "$T/outside-mv2")"
check "E4 boot guard: hold planted link still a link to the outside file" "yes" "$(yn test -L "$P/claude-held/ClaudeFoo.jar" -a "$(readlink "$P/claude-held/ClaudeFoo.jar")" = "$T/outside-mv2")"
check "E4 boot guard: held jar kept (renamed in folder, or left with a log line)" "yes" "$( { [[ -f $P/ClaudeFoo.jar ]] && grep -q cannot <<<"$out"; } || [[ -n $(find "$P/claude-held" -maxdepth 1 -type f -name '*ClaudeFoo*' ) ]] && echo yes || echo no)"
eval "$saved_as_server"

source "$here/test-repair.sh"
echo "$cases cases, $failed failed"
exit "$fail"
