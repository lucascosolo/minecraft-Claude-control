# Running the server in a container

The container runs Folia as `mc` (your uid/gid) and the Claude CLI as a separate `agent` user. File permissions let
`agent` write in `plugins/ClaudeControl/agent/` and create its own files in `plugins/ClaudeControl/cli-home/` (whose
`settings.json` the plugin writes and `agent` cannot change); the server's config files are not readable to it. Plugins
Claude installs run inside the server as `mc`, with the server's rights (see "Claude-written plugins" in the main README).
The plugin jar must be named exactly `ClaudeControl.jar`; before each start the entrypoint quarantines a suspect jar after
a failed boot and moves superseded `_rN` revisions to `plugins/claude-quarantine/superseded/` (3 kept per plugin). `mc` may run exactly one program as `agent`: the
claude binary, through sudo. Only the server directory is mounted; no docker socket, not privileged, all capabilities
dropped except the few the entrypoint and sudo need.

## Owner steps

1. Create a subscription token once, on any machine where you are logged in to Claude: `claude setup-token`.
2. `cp claude.env.example claude.env && chmod 600 claude.env`, then paste the token after `CLAUDE_CODE_OAUTH_TOKEN=`.
3. In `plugins/ClaudeControl/config.yml`:
   ```yaml
   backend: cli
   cli:
     binary: /usr/local/bin/claude
     command-prefix: [sudo, -n, -u, agent, "--preserve-env=CLAUDE_CODE_OAUTH_TOKEN,CLAUDE_CONFIG_DIR", --]
   workshop:
     enabled: true        # optional: lets Claude write and install its own plugins
   ```
4. `SERVER_DIR=/path/to/server docker compose up -d` (from this directory; `HEAP` defaults to 4G; see Resources below).
5. Console: `docker attach <container>` (type `stop` to stop; detach with Ctrl-P Ctrl-Q). `docker compose stop` also saves and stops cleanly.

The host-run `start.sh` stays as the fallback: `docker compose down`, then start it by hand as before.

## Resources and start on boot

`compose.yaml` caps the container at `MEM_LIMIT` (default 8g, swap included) and reserves `MEM_RESERVATION` (6g) with
`cpus: CPUS` (4) and a doubled CPU share, so the server wins against other containers. `JAVA_OPTS` defaults to
`-XX:+AlwaysPreTouch` (the heap is committed at start). The ceiling covers the heap plus the JVM, the Claude CLI and plugin
builds: a Gradle build inside a container capped at 4g with a 2G heap ran without an OOM.

The service has `restart: unless-stopped` and docker.service is enabled on this host, so the server comes back after a
reboot or a crash, and after `restart_server`. `docker compose stop` keeps it down, across reboots too; `docker compose
up -d` re-arms it.

## Backups (host side)

`backup.sh <server dir>` snapshots `world*/`, `plugins/NomadLabs/`, `plugins/NomadCairn/` and `plugins/ClaudeControl/`
to `~/Backups/<server dir name>/<UTC timestamp>/` with hard links to the previous snapshot, skips when nothing changed,
and keeps 48 recent plus the newest of each of the last 14 days. Pruned snapshots go to `.trash/` and are deleted from
there a day later. Run it from a host timer every 30 minutes (for example a systemd user timer); the container cannot
reach it. It copies a live world, so a snapshot can catch a region file mid-write; the next one will not.

## Cutover checklist

1. Stop the host-run server (`stop` in its console) and confirm no java process holds the world. Never two JVMs on one world.
2. Take one full copy of the server directory (`cp -a` or `rsync -a`) before the first container start.
3. Install the backup timer and run `backup.sh` once by hand.
4. Decide on the whitelist: with chat able to reach a shell (workshop mode), turn `white-list` on, or confirm the ports are not exposed to strangers.
5. Ports: 25565/tcp and 19132/udp+tcp (ViaVersion is needed for Bedrock joins). Bridge networking hides client IPs and breaks Bedrock LAN discovery; direct IP still works.
6. First start: check the log for `workshop mode active` (or `off`) and `Server finished loading; removed claude-boot-pending`.
7. After the first container run, `plugins/ClaudeControl/agent/` and `cli-home/` are owned by the container's `agent` uid; an ACL keeps them readable and writable for your own user.

## Repair mode

When the server will not stay up, the start script can run a headless Claude session to fix it (docs/PRD-v0.3.md
"Repair mode", rounds N and O). It is **off by default**; with it off the start script behaves as before: after three
failed boots the boot guard quarantines, tries safe mode, then halts (A15).

What it does when on: at the third failed boot, or after three crashes within 30 minutes of a successful load (a run
that left no clean-stop marker, or left a crash report newer than its load), the start script copies the allowed
configuration files (top-level `*.yml`/`*.properties` except permissions, commands and help, `config/**`,
`plugins/<Name>/config.yml` except NomadLabs, NomadCairn, ClaudeControl and permission plugins) and the newest logs
into a private work folder, with secret-looking values replaced by `<hidden>` and the token redacted. Claude may only
read and edit that folder (no shell). Afterwards the start script applies a changed file only if every changed line is
a plain `key: value`/`key=value` with a simple value, no protected key is touched, `server.properties` changes only
keys from a short performance list, and no `<hidden>` line was edited; `.json`/`.toml` are read-only. It may hold
(move to `plugins/claude-held/`) up to three third-party jars that the logs name, never ClaudeControl or a
permission, login, protection, ban, whitelist or anti-cheat plugin. Then it starts the server; if it stays up 5
minutes the repair stands, otherwise up to 3 attempts, then every change is rolled back. At most 2 repairs a day.

Turn it on for one start: `CLAUDE_REPAIR=on SERVER_DIR=... docker compose up -d` (or put `CLAUDE_REPAIR=on` in the
`.env` file next to `compose.yaml`; `claude.env` does not work, the compose `environment` default wins over it).

`CLAUDE_REPAIR_ENV_SCRUB` (default 1) sets `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB` for the session. With 1 the Claude CLI
strips its subprocess environment, but in this container it then refuses to start ("bubblewrap is required"), so
repair attempts fail and are rolled back. With 0 the CLI runs; the session still has no shell tool and its rules allow
only its work folder, so the token in its environment is exposed only if the CLI itself misbehaves. The owner chooses;
the probe in round O8 checks the denials before it is turned on.

Reports: `claude-repair/<UTC time>/report.md` (diagnosis, changes, held plugins), with `before/` (the original of
every applied file), `changes.json`, `refused.txt` and per-attempt logs. On the next successful start ClaudeControl
tells players about it once. Folders older than 30 days are pruned (the newest 10 stay).

Clearing a halt: read `claude-boot-halted` (it carries the repair's diagnosis), fix the cause, then delete the file;
the waiting container starts the server within 5 seconds. A held plugin is returned by moving its jar from
`plugins/claude-held/` back to `plugins/`.
