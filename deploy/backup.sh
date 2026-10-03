#!/bin/bash
# Host-side snapshots of world and custody data, run by a host timer every 30 minutes, outside the container.
# Usage: backup.sh <server dir> [backup root]     (default root: ~/Backups/<server dir name>)
# Keeps the newest KEEP_RECENT (48) snapshots plus the newest one of each of the last KEEP_DAILY (14) days.
# Pruned snapshots go to <root>/.trash first and are deleted from there a day later.
set -euo pipefail
shopt -s nullglob

src=$(realpath -- "${1:?usage: backup.sh <server dir> [backup root]}")
root=${2:-$HOME/Backups/$(basename -- "$src")}
if [[ -f $src/claude-boot-halted ]]; then
    echo "backup.sh: $src/claude-boot-halted exists: the server halted after repeated failed boots and is not running. Fix the cause and remove that file." >&2
    exit 3
fi
keep_recent=${KEEP_RECENT:-48}
keep_daily=${KEEP_DAILY:-14}
mkdir -p -- "$root/.trash"
root=$(realpath -- "$root")
trash="$root/.trash"

cd -- "$src"
paths=()
for p in world*/ plugins/NomadLabs/ plugins/NomadCairn/; do
    [[ -d $p ]] && paths+=("$p")
done
# The agent tree goes last and separately: parts of it may be unreadable to the host user.
agent_tree=()
[[ -d plugins/ClaudeControl/ ]] && agent_tree=(plugins/ClaudeControl/)
(( ${#paths[@]} + ${#agent_tree[@]} )) || { echo "nothing to back up in $src" >&2; exit 1; }

snapshots() { for s in "$root"/20*Z; do [[ -d $s ]] && echo "$s"; done; }

latest=$(snapshots | sort | tail -n 1)
# rsync exit 23/24 (partial transfer, vanished files) is tolerated for the agent tree only.
rsync_agent() { # rsync_agent <extra rsync args> <dest>
    local rc=0
    rsync -aR "${@:1:$#-1}" "${agent_tree[@]}" "${!#}" || rc=$?
    (( rc == 23 || rc == 24 )) && { echo "warning: agent tree partly unreadable (rsync exit $rc); tolerated" >&2; return 0; }
    return "$rc"
}
rsync_all() { # rsync_all <extra rsync args> <dest>
    local dest=${!#}
    local -a args=("${@:1:$#-1}")
    (( ${#paths[@]} == 0 )) || rsync -aR "${args[@]}" "${paths[@]}" "$dest"
    (( ${#agent_tree[@]} == 0 )) || rsync_agent "${args[@]}" "$dest"
}

if [[ -n $latest && -z $(rsync_all --delete --dry-run --itemize-changes "$latest/" 2>/dev/null) ]]; then
    echo "no changes since $latest; skipping"
else
    ts=$(date -u +%Y-%m-%dT%H%M%SZ)
    partial="$root/.partial-$ts"
    rsync_all --delete ${latest:+--link-dest="$latest"} "$partial/"
    mv -- "$partial" "$root/$ts"
    echo "snapshot $root/$ts"
fi

# Prune: newest first; keep the first $keep_recent and the newest of each of the first $keep_daily days.
declare -A days=()
i=0
while read -r s; do
    name=$(basename -- "$s")
    day=${name:0:10}
    keep=0
    (( i < keep_recent )) && keep=1
    if [[ -z ${days[$day]:-} ]] && (( ${#days[@]} < keep_daily )); then days[$day]=1; keep=1; fi
    if (( ! keep )); then
        mv -- "$s" "$trash/"
        touch -- "$trash/$name"
        echo "moved to trash: $trash/$name"
    fi
    i=$((i + 1))
done < <(snapshots | sort -r)

# Separate step: delete only trash entries that have been there for more than a day.
for d in "$trash"/*; do
    r=$(realpath -- "$d")
    [[ $r == "$trash"/* ]] || { echo "skipping $r: not inside $trash"; continue; }
    if [[ -n $(find "$r" -maxdepth 0 -mmin +1440) ]]; then
        echo "deleting $r"
        rm -rf -- "$r"
    fi
done
