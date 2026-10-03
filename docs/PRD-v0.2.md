# ClaudeControl v0.2 — Claude Code as the brain, and self-written plugins

Builds on `docs/PRD.md`; where they conflict this file wins. Decisions from the owner
(2026-10-01): the agent runs as a first-class Claude Code CLI session with the Anthropic API as
fallback; Claude may write and install its own server plugins automatically, on request or on
its own initiative; the server runs in a container so that this is contained.

## Measured CLI facts (Claude Code 2.1.287, captured 2026-10-01)

- Long-lived session: `claude -p --input-format stream-json --output-format stream-json --verbose`.
  One JSON object per line each way. A user turn is
  `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"…"}]}}`.
- Stdout events have a `type`: `system` (subtypes `init`, `thinking_tokens`, hook events…),
  `assistant`, `user`, `rate_limit_event`, `result`. A turn ends at `type:"result"` with
  `subtype`, `is_error`, `result` (text), `session_id`, `total_cost_usd`, `num_turns`,
  `stop_reason`. `system/init` carries `session_id` and `model`. Unknown types must be ignored.
  A real capture is in `src/test/resources/cli-sample.jsonl`.
- Resume with `--resume <session-id>`. `--model`, `--effort low|medium|high|xhigh|max`,
  `--system-prompt-file`, `--mcp-config <file>`, `--strict-mcp-config`, `--allowedTools`,
  `--disallowedTools`, `--tools`, `--permission-mode`, `--add-dir`.
- MCP HTTP entry: `{"mcpServers":{"game":{"type":"http","url":"http://127.0.0.1:PORT/mcp","headers":{"Authorization":"Bearer …"}}}}`;
  tools appear to the model as `mcp__game__<tool>`.
- The CLI loads the invoking user's `~/.claude` (instructions, hooks, skills, memory). The game
  session must not: it runs with `CLAUDE_CONFIG_DIR` pointed at its own directory.
  (`--bare` is not usable: it does not accept subscription login.)
- Subscription auth in a container: `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token`
  (interactive, run once by the owner). `ANTHROPIC_API_KEY`, if present in the CLI's
  environment, would win — so the plugin never passes it to the CLI process.

## Architecture

```
triggers ─▶ AgentService ─▶ FallbackBackend ─▶ CliBackend ──stdin/stdout──▶ claude (subprocess)
                                   │                                         │ MCP over HTTP
                                   └────▶ ApiBackend (existing ClaudeClient)  ▼
                                                    └──────────▶ ToolDispatcher ◀── McpServer
```

### `ToolDispatcher` (one rule, one method)
The single place a tool call is executed, for both backends:
`ToolResult call(String name, JsonNode input, String actors)` with
`record ToolResult(String text, boolean isError)`. `ToolCatalog` holds the single list of tool
definitions (name, description, JSON schema) from which the API tool params and the MCP
`tools/list` response are both rendered. Tools: `run_commands`, `send_chat`,
`get_player_details`, `survey_area`, `teleport_player`, `build`, and the workshop tools below.
The API backend additionally keeps the Anthropic memory tool; the CLI backend uses its own file
tools on the memory directory.

### `AgentBackend`
`interface AgentBackend { void runTurn(String userTurn, String actors) throws Exception; void reset(); void close(); String describe(); }`
- `ApiBackend`: the existing `ClaudeClient` behaviour, unchanged apart from using
  `ToolDispatcher`/`ToolCatalog`.
- `CliBackend`: below.
- `FallbackBackend(primary, secondary, Logger)`: `runTurn` tries primary; if it throws
  `BackendUnavailable` (unchecked; binary missing, process died and could not be restarted,
  auth failure, turn timeout) it logs one WARN and runs the same turn on secondary; any other
  exception propagates. After a failure primary is skipped for 5 minutes (injected clock), then
  tried again. `secondary` may be null (then the exception propagates). `describe()` names the
  backend that served the last turn.
- Config `backend: cli | api` (default `cli`). With `cli`, the API backend is the secondary
  when an API key is configured.

### `CliBackend` / `CliSession`
- Spawns one `claude` process and keeps it for the plugin's lifetime; one user-message line per
  turn; reads events until `result`. `result.is_error` → the turn fails (logged, WARN).
  No result within `cli.turn-timeout-seconds` (default 300) → kill the process,
  `BackendUnavailable`.
- Session id from `system/init` is stored in `<data>/cli-session-id`; a later start passes
  `--resume <id>`. If resume fails (process exits before `init`), start fresh once. `reset()`
  kills the process and deletes the id.
- Process env: inherits the server's, plus `CLAUDE_CONFIG_DIR=<data>/cli-home`, minus
  `ANTHROPIC_API_KEY`; `CLAUDE_CODE_OAUTH_TOKEN` from config `cli.oauth-token` if set (else
  whatever the server's environment provides).
- Working directory `<data>/agent/`. It contains `memory/` (the same directory the API
  backend's memory tool uses — move `<data>/memory` there once, on first start) and
  `workshop/`.
- Arguments: `--model`/`--effort` from config; `--system-prompt-file` = core rules + owner
  notes + (CLI only) `cli-rules.md`, written to a file at start/reload; `--mcp-config` pointing
  at the plugin's MCP server with `--strict-mcp-config`.
- Tool permissions, two modes decided at start:
  - **Game mode** (default): `--allowedTools` = the `mcp__game__*` tools plus `Read`, `Write`,
    `Edit`, `Glob`, `Grep`; `--disallowedTools Bash,WebFetch,WebSearch,Agent`;
    `--permission-mode acceptEdits`. File tools are confined by the CLI to the working directory.
  - **Workshop mode**: only when `workshop.enabled: true` **and** `Container.detected()` is
    true. Adds `Bash` and uses `--permission-mode bypassPermissions`. If `workshop.enabled`
    is true outside a container the plugin logs a WARN, stays in game mode and the workshop
    tools return an error explaining why. There is no override.
- `CliEvents.parse(String line)` (pure) → a small sealed type: `Init(sessionId, model)`,
  `Result(isError, text, sessionId, costUsd, subtype)`, `Other(type)`; malformed or non-JSON
  lines → `Other("invalid")`, never an exception.
- `CliArgs.build(...)` (pure) → the argument list for the two modes, testable without a process.
- Every turn's cost, duration and backend are logged at INFO.

### `McpServer` (minimal, no SDK)
JDK `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an ephemeral port (config
`mcp.port`, default 0), path `/mcp`. JSON-RPC 2.0 over HTTP POST, plain `application/json`
responses (no SSE). Requires `Authorization: Bearer <token>` where the token is 32 random bytes
generated at start; anything else → 401. Methods: `initialize` (echo the client's
`protocolVersion`, capabilities `{tools:{}}`, serverInfo), `notifications/initialized` (202, no
body), `ping`, `tools/list`, `tools/call` (→ `ToolDispatcher`; result
`{content:[{type:"text",text}],isError}`); unknown method → JSON-RPC error −32601; malformed
JSON → −32700; GET → 405. Constructor takes the catalog and a
`BiFunction<String, JsonNode, ToolResult>` so it is unit-testable with the JDK `HttpClient`.
`start()` / `stop()`; `port()`; `token()`. Tool calls run on the HTTP worker thread and block
on the game schedulers exactly as the agent worker does today.

### Workshop: Claude-written plugins
Layout under `<data>/agent/workshop/`: `template/` (copied from the jar on first start: a
minimal Gradle project with wrapper, `folia-api` compileOnly, a `plugin.yml` with
`folia-supported: true`, and a tiny example listener), and one directory per plugin Claude
creates by copying the template. Claude builds with `./gradlew build` using its Bash tool.

Tools (in `ToolCatalog`, served to both backends, erroring outside workshop mode):
- `install_plugin {name}` — `PluginInstaller`: finds the single jar in
  `workshop/<name>/build/libs/`, reads its `plugin.yml` and validates via the pure
  `PluginManifest.validate(String yaml, Set<String> loadedPluginNames)`: `name` must match
  `[A-Za-z0-9_]{3,32}`, start with `Claude`, not equal `ClaudeControl`; `main` present;
  `folia-supported: true`; no `paper-plugin.yml`. Copies it to `plugins/<Name>.jar`. If no
  plugin of that name is loaded, loads and enables it at runtime on the global region thread
  (`PluginManager.loadPlugin` + `enablePlugin`) and reports the outcome including the exception
  text on failure (on failure the jar is moved to `plugins/claude-quarantine/`). If one is
  already loaded, the jar goes to the update folder and the result says a restart is needed.
- `list_plugins {}` — Claude-written plugins: name, version, loaded/enabled/pending/disabled.
- `disable_plugin {name}` — only plugins whose name starts with `Claude` and is not
  `ClaudeControl`: disables it at runtime if loaded and moves its jar to
  `plugins/claude-quarantine/` so it does not load again.
- `restart_server {reason}` — broadcasts the reason and a 30-second warning, then
  `Bukkit.shutdown()`; the container restarts the server. At most once per 10 minutes
  (pure `RestartLimiter(Clock)`); refused with an explanation otherwise.
- `/claude plugins` lists them; `/claude plugins disable <name>` is the owner's kill switch.
- Boot guard: the plugin deletes `<server>/claude-boot-pending` when the server finishes
  loading (`ServerLoadEvent`). The container entrypoint creates that file before every start;
  if it already exists (the previous boot never finished) it first moves the most recently
  modified `plugins/Claude*.jar` (never `ClaudeControl.jar`) to `plugins/claude-quarantine/`.
- `Container.detected()` — pure over injected file readers: true when `/.dockerenv` exists or
  `/proc/1/cgroup` mentions docker/containerd/kubepods/podman, or env `container` is set.

`cli-rules.md` (bundled, CLI only) explains: memory lives in `memory/` and is edited with the
file tools; the game tools are the `mcp__game__*` tools; in workshop mode, how to create a
plugin (copy the template, keep it small and Folia-safe: region/entity/global schedulers only,
no legacy scheduler, no blocking on tick threads, no file or network access outside its own
data folder, no reflection into server internals), build it, read compiler errors, install it,
check the result, tell the players, and record it in memory; prefer commands and `build` when
they are enough — a plugin is for behaviour that must persist or react to events; never modify
`ClaudeControl` itself or other plugins' files; restarts interrupt play, so ask in chat first
unless nobody else is online.

### Container (`deploy/`)
`Dockerfile` (Temurin 25 JDK base; installs the Claude CLI with the official installer;
non-root user with the host owner's uid/gid), `compose.yaml` (mounts only the server directory
at `/server`; publishes 25565/tcp and 19132/udp; `restart: unless-stopped`; memory limit;
`env_file` for `CLAUDE_CODE_OAUTH_TOKEN`; no other host mounts, no docker socket, not
privileged), `entrypoint.sh` (boot guard above, then `exec java … -jar <folia jar> --nogui`),
`README.md` with the three owner steps (create token, fill env file, `docker compose up -d`).

## Config additions
```yaml
backend: cli            # cli | api
cli:
  binary: claude
  model: opus
  effort: low
  turn-timeout-seconds: 300
  oauth-token: ""       # or CLAUDE_CODE_OAUTH_TOKEN in the server's environment
mcp:
  port: 0
workshop:
  enabled: false        # only takes effect inside a container
```

## Tests
Unit: `CliEvents`, `CliArgs`, `FallbackBackend`, `McpServer` (real HTTP on localhost),
`PluginManifest`, `Container`, `RestartLimiter`. Live: a real CLI turn on the throwaway server
(game mode) proving MCP tool calls reach the game; the container image booting the throwaway
server; a workshop plugin built, installed and enabled inside the container.

## Deploy facts from the server's own session (2026-10-01) — binding on `deploy/`

- Today the server is started by hand with `start.sh` (`java -Xms4G -Xmx4G -jar
  folia-26.1.2-8.jar --nogui`), stopped by typing `stop`; no service, no auto-start. `start.sh`
  stays in place as the fallback. Never two JVMs on one world: the cutover requires the host-run
  server to be stopped first.
- Ports 25565/tcp and 19132 (publish udp and tcp). ViaVersion is required for Bedrock joins.
- Container: memory limit 6G+ (heap is 4G) with CLI and Gradle headroom → 8G; `java` is the
  entrypoint's `exec` target so SIGTERM reaches it; `stop_grace_period: 60s`;
  `stdin_open: true` and `tty: true` so `docker attach` gives the console and `stop` still works.
- Bridge networking hides client IPs and breaks Bedrock LAN auto-discovery; direct-IP still works.
- `world*/`, `plugins/NomadLabs/` and `plugins/NomadCairn/` hold world and player-inventory
  custody data. They are off-limits to the agent:
  - The CLI runs as a separate unix user inside the container. Config `cli.command-prefix`
    (string list, default empty) is prepended to the CLI command line; the image sets it to
    `sudo -n -u agent --preserve-env=CLAUDE_CODE_OAUTH_TOKEN,CLAUDE_CONFIG_DIR --`. The image
    creates user `agent`, a sudoers rule allowing the server user to run only the `claude`
    binary as `agent`, makes `<data>/agent/` and `<data>/cli-home/` owned by `agent` and
    group-accessible to the server user, and leaves everything else in `/server` not writable
    by `agent`. So the agent's shell cannot modify the world, other plugins or their data.
  - `cli-rules.md` states that generated plugins must never read or write other plugins' data
    folders or world files directly.
  - The cutover procedure backs up the server directory first.
- `white-list=false` on the real server. With chat able to reach a shell, the cutover checklist
  requires the owner to turn the whitelist on (or confirm the ports are not exposed).

## What actually protects world and inventory data (stated plainly)

The separate `agent` user only constrains the CLI's own shell and file tools. A plugin Claude
writes is loaded into the server JVM and runs as the server user with full write access to
`/server`. For generated plugin code the rules in `cli-rules.md` are a prompt-level control,
not an enforced one. The enforceable protections are:

1. **Host-side recurring backup** (`deploy/backup.sh`, run by a host timer outside the
   container, which the container cannot reach or delete): every 30 minutes while the server
   directory has changed, snapshot `world*/`, `plugins/NomadLabs/`, `plugins/NomadCairn/` and
   `plugins/ClaudeControl/` to `~/Backups/HannahLucasMinecraft/<UTC timestamp>/` with hard-link
   de-duplication (`rsync --link-dest`), keeping 48 half-hourly and 14 daily snapshots. Plus one
   full copy of the server directory before the cutover.
2. **Static jar check before install** — pure `JarScan.scan(Map<String, byte[]> classFiles)`
   → list of findings; `install_plugin` refuses a jar with findings and returns them to Claude.
   It reads each class file's constant pool and flags: references to `java/lang/Runtime`,
   `java/lang/ProcessBuilder`, `java/net/` (any class), `java/lang/reflect/`,
   `sun/misc/Unsafe`, `net/minecraft/`, `org/bukkit/craftbukkit/`; and string constants
   containing `NomadCairn`, `NomadLabs`, `ClaudeControl`, `ops.json`, `server.properties`,
   `..`. This catches mistakes and careless code, not a determined adversary.
3. The boot guard and `/claude plugins disable`.

## Plugin watch (owner request, 2026-10-01; overrides earlier text where they differ)

Owner's words: "Anytime claude adds a plugin, it should warn the players before restarting the
server, then watch and fix any problems that arise."

22. **Warned restarts.** Any restart Claude causes is announced in chat first by the plugin, not
    left to the model: a broadcast at 30 s, 10 s and 5 s naming the reason, then a save and stop.
    `restart_server` already does the 30 s wait; this adds the countdown lines and makes the
    reason mandatory and shown to players. The once-per-10-minutes limit stays.
23. **Install record.** Every `install_plugin` success appends a record to
    `<data>/workshop-installs.json`: plugin name, jar sha-256, time, whether it loaded at runtime
    or awaits a restart. `PluginWatch` (pure, clock injected) owns this state.
24. **Watch window.** A plugin is "on watch" for 15 minutes of server uptime after it is first
    enabled (at runtime or after the restart that loads it). The window survives a restart: it is
    counted in uptime while that plugin is enabled, stored in the install record.
25. **What is watched.** A `java.util.logging` handler on the root logger collects WARNING and
    SEVERE records whose logger name, message or stack trace names a Claude-written plugin (its
    name or its main-class package), plus enable/disable failures. Records are de-duplicated by
    (plugin, first stack frame, message with digits masked) and capped at 20 distinct problems
    per plugin per window, each trimmed to 1 500 characters.
26. **Feeding Claude.** Problems for a plugin on watch are delivered as a trigger with source
    `plugin-watch` and no actor, at most one per 60 s per plugin, containing the plugin name and
    the new distinct problems. After the window ends with no problems, one `plugin-watch` trigger
    says the plugin ran clean, so Claude can note it in memory. Outside the window, problems are
    still collected and appear in `list_plugins` output and in the `<events>` block, but do not
    start turns on their own.
27. **Boot failures.** When the entrypoint boot guard quarantines a jar, it writes the jar name to
    `<server>/claude-quarantined-on-boot`. On the next successful start the plugin reads and
    deletes that file and sends a `plugin-watch` trigger saying that plugin stopped the server
    from starting and was quarantined, with the tail (last 60 lines) of the previous log if
    present.
28. **Giving up.** `PluginWatch` counts fix attempts: each `install_plugin` of the same name
    within its watch window is an attempt. After 3 attempts with problems still arriving, the
    plugin disables that plugin itself (quarantine), tells players in chat, and sends one final
    trigger saying so. This stops a fix loop from restarting the server all evening.
29. **Rules text** (`cli-rules.md`, Workshop section): tell players what is being added before
    installing; if a restart is needed, say so and prefer a quiet moment; on a `plugin-watch`
    trigger, read the problems, fix the source, rebuild and reinstall, or disable the plugin if
    the fix is not obvious; always tell players the outcome in one line.

Pure, test-first: `PluginWatch` (windows, de-duplication, caps, rate limit, attempt counting,
persistence round-trip) and the log-record matcher. Thin, verified live: the logging handler,
the countdown, the boot-quarantine hand-off in `entrypoint.sh`.

30. **Plugins are rare and asked for** (owner: "claude should rarely do plugins randomly
    unprompted. I don't want the server restarting every few minutes"). Enforced in code, not
    only in the rules text:
    - `install_plugin` and `restart_server` are refused in a turn whose triggers are all
      `random-event` (no player asked). `plugin-watch` turns may reinstall only the plugin under
      watch.
    - Unprompted plugin ideas go to chat as a suggestion; Claude builds only after a player says
      yes.
    - Restart budget: at most one Claude-caused restart per 30 minutes and four per day
      (`RestartLimiter`, replacing the 10-minute rule), config `workshop.min-minutes-between-restarts`
      and `workshop.max-restarts-per-day`. Refusals say when the next one is allowed.
    - Prefer loading without a restart when the server allows it; batch several changes into one
      restart.
    - Rules text: commands and the `build` tool first; a plugin only when a player asked for
      behaviour that commands cannot give.

31. **Hot replace before restart** (owner: plugins can be loaded and reloaded at runtime if built
    correctly). Installing a new version of an already-loaded Claude plugin first tries a hot
    replace on the global region thread: disable the old instance (its listeners, commands and
    scheduled tasks are unregistered by the server), then load and enable the new jar. If the
    server refuses a second plugin of the same name in one run, the loaded identity carries a
    revision suffix (`ClaudeHello` → jar/plugin name `ClaudeHello_r2`) while tools, records and
    `/claude plugins` keep showing the base name; only the newest revision is enabled and old
    revision jars are moved to quarantine on the next start. Verify live which of the two the
    server needs; do not guess. A warned restart is the fallback only when hot replace throws, and
    it counts against the restart budget. The template and rules text require plugins to be
    reload-safe: no static state that must survive, everything registered through the plugin
    instance, all tasks via the plugin's schedulers, cleanup in `onDisable`.

## Review amendments, v0.2 (2026-10-01; override earlier text)

Security:
32. `PluginManifest` allows only these plugin.yml keys: `name`, `version`, `main`, `api-version`,
    `folia-supported`, `description`, `author`, `commands`. Anything else (`permissions`,
    `libraries`, `provides`, `depend`, `softdepend`, `loadbefore`, …) is refused by name. Inside
    `commands` only `description`, `usage`, `aliases`, `permission` are allowed. Duplicate YAML
    keys are refused. Names starting with `ClaudeControl` are refused. `nameOf` uses the same
    parser.
33. CLI tool scope. Game mode: file tools limited to `memory/**` under the agent dir; denied
    `/proc/**`, `/server/**` outside the agent dir, `.claude/**`, `cli-home/**`. Only user
    settings are loaded (`--setting-sources user`), and `cli-home` settings are written by the
    plugin and not writable by the session. Workshop mode adds `workshop/**` and Bash, same
    denials for reads of config/secrets. `config.yml`, `server.properties`, `ops.json`,
    `cli-run/` are not readable by `agent` (entrypoint sets modes). Verify each live.
34. `install_plugin` reads the jar once into memory (max 5 MiB, max 2 MiB per entry, max 500
    entries, no nested jars), scans and validates those bytes, and writes those same bytes.
    `JarScan` bounds every length it reads.
35. Boot guard quarantines any `plugins/Claude*.jar` except exactly `ClaudeControl.jar`.
36. `backup.sh` does not abort the world snapshot when part of the agent tree is unreadable:
    world and other-plugin data are copied first; rsync exit 23/24 on the ClaudeControl tree is
    reported and tolerated.
37. `McpServer` rejects request bodies over 1 MiB.
38. `build`: at most 4 096 cells changed per call across all ops.
39. `MemoryStore.dump` does not follow symlinks and only reads files whose real path is under
    the memory root.
40. Journal text in `<recent>` is quoted and escaped the same way as trigger text.
41. Documented, accepted (README "Claude-written plugins", honest wording): the jar check is a
    tripwire; a Claude-written plugin runs with the server's rights inside the container, can
    run console commands, read the token from the environment, and write any file the server
    can. Protection is the container boundary, the whitelist, and host-side backups. Remove the
    claim that the agent "may write only…". Backups keep the host copy out of the container's
    reach. Add a compose-level size guard if cheap (tmpfs/quotas are not available on a bind
    mount; document instead).

Architecture:
42. A CLI turn that fails after any tool call ran is abandoned (`TurnAbandoned`), never replayed
    on the API. Only failures before the first tool call fall back.
43. When the serving backend changes, the next turn carries a `<handoff>` block with the last
    10 rendered user turns and a fresh `<memory>` block.
44. A failed turn restores its drained journal entries (`WorldJournal.restore`).
45. Reload is build-then-apply: prompt text and files are written only after everything that can
    throw has succeeded; each config key is read once into a settings record; `mcp.port` is
    documented as start-only.

46. **Only the latest revision survives a restart** (owner request). The cleanup happens in
    `entrypoint.sh` before Java starts, so superseded revisions are never loaded at all (today
    they load and are then disabled by ClaudeControl). For each base name the highest `_rN` jar
    stays in `plugins/`; older ones go to `plugins/claude-quarantine/superseded/`. That folder
    keeps the 3 most recent superseded jars per plugin and older ones are deleted, so it cannot
    grow without bound. Jars quarantined for failing (boot guard, give-up, disable) are kept
    apart and never auto-deleted. The in-plugin cleanup stays as a second line for hosts not
    using the container.

## First live session in the container (2026-10-01 23:28Z)

Observed: first turn took 19 s to its first action (CLI cold start on the first chat line), with no
sign to the player that anything was happening. `kill @e[type=zombie,x=..,distance=..16]`,
`execute as <player> at @s run kill @e[...,distance=..]` and `effect give @e[...,distance=..]` all
failed with "Cannot getEntities asynchronously": any selector with a position or distance filter
does a region-bound entity lookup, which the global thread may not do. The player died to a spider
Claude had tried to remove.

47. **Native entity tool** `entities {near: player name | {world,x,y,z}, radius ≤ 64, types:
    [ids or groups "hostile","animals","items","all_mobs"], action: "list" | "remove" | "effect",
    effect?: {type, seconds, amplifier}, limit ≤ 200}`. Runs on the region thread owning the
    centre (RegionScheduler), uses the Bukkit API, never touches players for `remove`, skips
    named/tamed entities for `remove` unless `include_named: true`. Returns counts per type.
    Entities outside the owning region are skipped and reported as such.
48. **Selector guard.** `run_commands` refuses, before dispatch, a command whose `@e`/`@n`
    selector has `distance`, `x`, `y`, `z`, `dx`, `dy`, `dz`, or that uses `execute at/positioned`
    followed by an `@e` selector, with an error pointing at the `entities` tool. Unfiltered
    `@e[type=…]` and player selectors stay allowed. Core rules say the same.
49. **Warm start.** The CLI process is started when the plugin enables (and after reload/reset),
    not on the first chat line, so the first turn does not pay for process start.
50. **Working indicator.** While a turn is running, players whose trigger is in it see an action
    bar line ("Claude is thinking…", then "Claude is working: <tool>" on each tool call, and
    "Claude is building a plugin…" while Bash runs in workshop mode), refreshed every 2 s and
    cleared at the end. If a turn fails or is abandoned, the actor gets one chat line saying so.
51. Danger rule in core rules: when a player is in danger, first make them safe with actions that
    cannot fail on Folia (effects on the player by name: resistance, regeneration, instant_health;
    or `teleport_player`), then deal with the mobs through `entities`.
