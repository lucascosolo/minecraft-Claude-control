# ClaudeControl

A Folia 26.1.2 plugin that forwards server chat to one long-lived Claude agent. Claude can run console commands (with a denylist), speak in chat, inspect players and keep notes. Most chat gets no response; Claude acts only when something calls for it.

## Restoration status

This checkout is a partial recovery from the home-directory wipe. It is not yet buildable or ready to install: production classes (including `PluginSettings`, `AgentService`, `MemoryStore`, `ToolDispatcher` and `WorldReport`), build files, CLI resources and deployment modules are missing. `MANIFEST.tsv` is the original recovery record; `python3 tools/check_restoration.py` reports which files are still absent without executing the plugin or its historical tests. The Gradle build also refuses missing production files before compilation.

**Do not run `deploy/test-entrypoint.sh` or `deploy/test-repair.sh`.** The recovered tests still contain unsafe deletion cleanup, including the failure pattern that caused the wipe. Direct-execution guards and scratch-only archival cleanup must be added and verified before those tests can run. Never run the repair test fragment on its own. The deployment scripts also contain deletion code and require a safety review before execution.

New installations now default to `backend: api`. Once the missing production code is recovered and verified, the intended setup is `backend: api` with `api-key` in `plugins/ClaudeControl/config.yml`; API mode requires no Claude Code login. Existing configurations must explicitly select `api` if they previously used `cli`. The original credential-bearing server configuration has not been located or verified. The bundled config is a template with an empty key. Keep a real config outside version control.

The new bundled `system-prompt.md` gives Claude ambitious aspirations for shared towns, infrastructure, adventures and ongoing stories, with staged execution and respect for player consent and existing builds. It is a replacement persona, not a recovered copy. Existing servers retain their own persona file, which must be reviewed before incorporating this text.

## Install (after restoration and verification)

1. `./gradlew build`, then copy `build/libs/ClaudeControl.jar` into the server's `plugins/` folder (keep that exact name, replacing any older `ClaudeControl-<version>.jar`: the container boot guard treats every other `Claude*.jar` as Claude-written).
2. Start the server once. This creates `plugins/ClaudeControl/config.yml` and `system-prompt.md`.
3. Select `backend: api` and set `api-key` in `config.yml`, or set the `ANTHROPIC_API_KEY` environment variable (the variable wins). Then run `/claude reload`.

Without a key the plugin still loads, logs one warning and ignores chat.

## Config (`config.yml`)

| Key | Default | Meaning |
|---|---|---|
| `model` | `claude-opus-5-5` | Model id |
| `effort` | `low` | `low`, `medium`, `high`, `xhigh` or `max`. Higher means more thinking, more cost and slower turns |
| `batch-window-ms` | 500 | How long to collect chat before starting a turn |
| `max-turns-per-minute` | 6 | Rate limit. Chat is held back until a turn is allowed, never dropped |
| `max-queued-triggers` | 200 | Chat lines held beyond this are dropped |
| `max-triggers-per-player-per-minute` | 12 | Chat lines accepted per player per minute; extra lines from that player are dropped |
| `max-tool-iterations` | 12 | Tool rounds per turn |
| `max-commands-per-call` | 20 | Commands per `run_commands` call |
| `command-timeout-ms` | 5000 | Per-command timeout |
| `chat-prefix` | `<Claude> ` | Prefix on Claude's chat messages |
| `events.enabled` | `true` | Claude starts small random events on its own while players are online (not while paused) |
| `events.min-minutes` / `events.max-minutes` | 20 / 45 | Random gap between events. The minimum is at least 1; a maximum below the minimum means a fixed gap |
| `events.themes` | treasure hunt, surprise gift, … | Themes suggested to Claude, picked at random |
| `denied-commands` | stop, op, ban, whitelist, reload, function, schedule, datapack, plugins, sudo, lp, … (see `config.yml`) | Commands Claude may never run. This also applies inside `execute … run` and `return run` |

**Add the admin or sudo-style commands of every other plugin you have installed to `denied-commands`.** Claude has console authority, and the built-in list only covers vanilla, Paper and a few well-known permission plugins. Commands that embed other commands (anything containing `command_block` or `Command:`) are always blocked.

The plugin's fixed core rules (trust, events, tool use) live inside the jar and are not copied to your server. `system-prompt.md` holds your own persona, tone and server notes, and is sent after the core rules. `/claude reload` re-reads it and the config without clearing history. If anything in them is invalid the reload changes nothing and tells you why. Keys missing from an older `config.yml` fall back to the bundled defaults.

## Backends

Each turn is served by one of two backends (`backend` in `config.yml`). New configurations default to `api`; older configurations may still select `cli`:

- `cli` (default): a long-lived [Claude Code](https://claude.com/claude-code) process (`cli.binary`) started in `plugins/ClaudeControl/agent/`, using your Claude subscription. Authenticate it with `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`) in the server's environment or `cli.oauth-token`. It runs with its own config directory (`plugins/ClaudeControl/cli-home`, or `cli.config-dir`), never your own `~/.claude`, and `ANTHROPIC_API_KEY` is removed from its environment. The game tools reach it over a loopback-only MCP server with a random token; it edits its memory files in `agent/memory/` with its file tools and has no shell or web access. Its session id is kept in `cli-session-id` and resumed after a restart. If the process cannot start, dies, is not logged in or a turn passes `cli.turn-timeout-seconds`, the turn falls back to the API (when a key is set) and the CLI is retried after 5 minutes.
- `api`: the Anthropic API directly with `api-key`, as in v0.1.

`/claude status` shows the backend that served the last turn, the CLI session and the last turn's cost; `/claude reset` clears both backends' conversations; `/claude reload` restarts the CLI process (resuming its session) before the next turn. The memory directory moved from `plugins/ClaudeControl/memory` to `plugins/ClaudeControl/agent/memory` (done automatically once).

## Claude-written plugins (workshop mode)

With `workshop.enabled: true` **and** the server running in a container (`deploy/`), the Claude Code backend gets a shell and can write small plugins from `plugins/ClaudeControl/agent/workshop/template/`, build them with Gradle, and install them with `install_plugin`. Outside a container the setting is ignored with a warning; there is no override. `/claude plugins` lists them with their watch state and collected problems; `/claude plugins disable <name>` is your kill switch (disables it and moves the jar to `plugins/claude-quarantine/`).

- **Only when asked.** `install_plugin` and `restart_server` are refused in a turn that only random events started; Claude suggests the idea in chat and waits for a yes. A `plugin-watch` turn may reinstall only the plugin it names.
- **Hot replace.** A new version of a loaded plugin replaces the running one without a restart: the old instance is disabled and its jar quarantined, and the new jar is loaded under a revision name (`ClaudeHello_r2`), because Folia 26.1.2 refuses a second plugin of the same name in one run. Everything you see shows the base name. On the next start only the newest revision is kept. A restart is the fallback only when the hot replace throws.
- **Restarts** are warned in chat at 30, 10 and 5 seconds with the reason, then the server saves and stops and the container starts it again. Budget: `workshop.min-minutes-between-restarts` (30) and `workshop.max-restarts-per-day` (4); the history is kept in `restart-history.txt`, so it survives the restarts it counts.
- **Watch.** After each install a plugin is on watch for 15 minutes of its uptime. Its warnings and errors (de-duplicated, at most 20) reach Claude as a `plugin-watch` trigger at most once a minute; a clean window is reported once. After three reinstalls that still fail, the plugin is disabled and quarantined and players are told. Install records are in `workshop-installs.json`.
- **Boot failures.** If a start never finishes, the container quarantines the newest Claude jar and, on the next start, Claude gets the jar name and the last 60 lines of the failed start's log.

What actually protects your world and inventory data, plainly:

- The Claude CLI session runs as a separate `agent` user. Its shell and file tools are limited by file permissions (it owns only `agent/` and the contents of `cli-home/`; `config.yml`, `server.properties`, `ops.json` and the plugin's own files are not readable to it) and by its tool rules (file tools scoped to `memory/`, plus `workshop/` in workshop mode; `/proc`, `cli-run`, `cli-home` and `.claude` denied). In workshop mode it has Bash, and the server's environment, including the Claude token, is inherited by the session.
- **A plugin Claude writes is not sandboxed.** It runs inside the server JVM with the server's rights: it can run console commands, read the token from the environment, and read or write any file the server can, including the world and other plugins' data. The rules telling it to stay in its own data folder are instructions, not enforcement.
- Before install, the jar is read once (at most 5 MiB, 500 entries, 2 MiB per entry, no nested jars), its `plugin.yml` may use only `name`, `version`, `main`, `api-version`, `folia-supported`, `description`, `author` and `commands`, and a static scan (`JarScan`) refuses classes that reference processes, the network, reflection, `sun.misc.Unsafe` or server internals, or strings naming `NomadLabs`, `NomadCairn`, `ClaudeControl`, `ops.json`, `server.properties` or `..`. Exactly the checked bytes are installed. This is a tripwire for mistakes and careless code, not a defence against a determined adversary.
- What does protect you: the container boundary (only the server directory is mounted, no docker socket, few capabilities), the whitelist (keep it on in workshop mode, so only people you know can talk to Claude), and the host-side backups below, which the container cannot reach. There is no disk quota on the bind mount; watch free space on the host.
- If a boot never finishes, the container moves the newest `plugins/Claude*.jar` to quarantine before the next start.
- The real safety net is the host-side backup (`deploy/backup.sh`, run by a host timer the container cannot reach): half-hourly hard-linked snapshots of `world*/`, `plugins/NomadLabs/`, `plugins/NomadCairn/` and `plugins/ClaudeControl/`.

## Commands

All require `claudecontrol.admin` (ops by default).

- `/claude plugins` / `/claude plugins disable <name>`: list Claude-written plugins / disable one and quarantine its jar
- `/claude status`: configured, paused, queue length, turns this minute, model, last error
- `/claude pause` and `/claude resume`: ignore chat, or start listening again
- `/claude reset`: clear the conversation history (memory files are kept)
- `/claude event`: start a random event now (needs a player online)
- `/claude reload`: re-read `config.yml` and `system-prompt.md`; restarts the event timer with the new settings

If the API rejects the key, the plugin pauses itself. Fix the key, then run `/claude reload` and `/claude resume`.

## Building tools

Besides running commands, Claude has `build` (places a whole structure in one call through the block API, at an explicit corner or on the flattest free ground near a player, then optionally moves that player inside and announces it), `survey_area` (reads terrain heights, surface blocks, nearby mobs and flat building sites around a spot) and `teleport_player` (moves a player to an exact block).

On Folia, console commands can only be dispatched from the global tick thread, so every `run_commands` batch runs there; Folia carries world-changing commands (`setblock`, `fill`, `summon`, `time`, ...) to the region they affect and their output arrives a tick or more later. Commands that read the world (`execute if block`, `data get`) do not work; Claude reads the world through `survey_area`, `get_player_details` and the world report instead.

## What Claude sees each turn

Every turn starts with a world report: per world with players, the day, clock time, phase and weather; per online player, their details plus surroundings (biome, light, ground, water or lava, hostile mobs, player-built blocks and free flat sites nearby). Deaths, joins, quits, respawns and advancements since the previous turn are listed under `<recent>`. On the first turn after a start, `/claude reset` or `/claude reload`, the text of all memory files is included too (up to 16 000 characters).

## How memory works

- **Conversation:** one shared history for the whole server, saved to `plugins/ClaudeControl/conversation.json` after every turn. Server-side compaction summarises old turns when the history gets long, and messages before the latest summary are dropped from the file.
- **Memory files:** Claude writes durable notes (bases, promises, preferences) through the memory tool into `plugins/ClaudeControl/memory/`. These survive restarts and `/claude reset`. The folder is capped at 200 files, 64 KB per file and 4 MB in total. You can read and edit the files by hand.

## Cost

Each turn is billed by Anthropic to your API key. A turn sends the system prompt, the tools and the whole conversation so far, and prompt caching makes the repeated part cheap. Every chat line can start a turn, so a busy server costs more. Use `effort`, `max-turns-per-minute` and `/claude pause` to control spending, and watch usage in the Anthropic Console.

## Folia notes

Commands run as the console on the global region thread. Some vanilla commands behave differently on Folia. For example, entity selectors such as `@e` may not see entities in other regions. Claude is told to read each command's output and adapt.
