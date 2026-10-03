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
