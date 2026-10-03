#!/bin/bash
# Runs as root: a sequence of steps (docs/PRD-v0.3.md C11), each a function in its own file, then the server as mc.
set -euo pipefail
cd /server
lib=$(dirname -- "${BASH_SOURCE[0]}")
for f in permissions boot-guard library superseded launch repair; do
    # shellcheck source=/dev/null
    source "$lib/$f.sh"
done

halt_wait() {
    while [[ -f /server/claude-boot-halted ]]; do sleep 5; done
    echo 0 | server_write /server/claude-boot-failures || true
    echo "boot guard: halt file removed; starting normally"
}

permissions_apply /server
repair_clear_acls /server || true
repair_cli_access -x || true
# An interrupted repair is rolled back even when repair mode has since been turned off.
repair_recover /server || true
# O1: repair mode only with CLAUDE_REPAIR=on; otherwise the boot guard alone decides (A15).
if repair_enabled; then repair_crash_step /server "$(date +%s)"; fi

rc=0
boot_guard_step /server || rc=$?
(( rc == 0 || rc == 2 )) || exit "$rc"

# R1/N6: repair instead of halting at the third failed boot, or after REPAIR_CRASHES crashes after load.
# Only a server that will not boot (rc 2) may halt; after crashes the server always starts.
reason=
if repair_enabled; then reason=$(repair_reason /server "$rc" "$(date +%s)"); fi
mayhalt=0; (( rc == 2 )) && mayhalt=1
if [[ -n $reason ]] && ! repair_incident_allowed /server "$(date +%s)"; then
    echo "repair: $REPAIR_INCIDENTS repairs already ran in the last 24 hours; not repairing ($reason)"
    reason=
elif [[ -n $reason && -e /server/claude-boot-halted ]]; then
    mv -f -T -- /server/claude-boot-halted /server/claude-boot-halted.prev || true
fi
if (( rc == 2 )) && [[ -z $reason ]]; then halt_wait; fi

# The library steps run as the server user: they only move mc's files inside plugins/.
as_server bash "$lib/library.sh" reconcile /server/plugins
as_server bash "$lib/library.sh" shelve /server/plugins "$(date +%s)"

as_server bash "$lib/superseded.sh" /server/plugins
safe_chown /server -R mc:mc /server/plugins/claude-quarantine/superseded

if [[ -n $reason ]]; then
    irc=0; repair_incident /server "$reason" "$mayhalt" || irc=$?
    case $irc in
        0) rc=0; repair_wait_server || rc=$?; exit "$rc" ;;
        3) exit 143 ;;
    esac
    if (( mayhalt )); then halt_wait; fi
fi

mapfile -t cmd < <(launch_command /server)
(( ${#cmd[@]} )) || exit 1
repair_clear_clean_stop /server
umask 0007
exec "${cmd[@]}"
