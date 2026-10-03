# ClaudeControl — PRD v0.1

A Folia 26.1.2 plugin. Everything said in chat is forwarded to one long-lived Claude agent that
remembers the world and past requests. When Claude judges that something should happen, it acts
through tools with console (op-level) authority: running commands, speaking in chat, giving
items, changing the world. Chat is the first trigger; others will be added, so every trigger goes
through one entry point.

- Plugin name `ClaudeControl`, package `com.lucascosolo.claudecontrol`, Java, Gradle (Kotlin DSL),
  shaded jar. Build: `./gradlew build` → `build/libs/ClaudeControl-<version>.jar`.
- Backend: Anthropic API through the official Java SDK (`com.anthropic:anthropic-java`), key
  supplied by the server owner. No other provider, no raw HTTP.

## Flow

1. A trigger source (chat listener today) builds a `Trigger` and calls `AgentService.submit`.
   This never blocks the calling thread.
2. `AgentService` owns one worker thread. It waits `batch-window-ms` after the first pending
   trigger, drains everything pending into **one** user turn, and runs a turn. Triggers arriving
   mid-turn queue for the next turn. Turns are strictly serial: one conversation, one writer.
3. A turn = one user message (fresh world snapshot + the batched triggers) followed by the
   tool-use loop until Claude stops calling tools or `max-tool-iterations` is hit.
4. Claude's final plain text is **not** shown to players (it is logged at FINE). Players only
   see what Claude sends with `send_chat` or commands. Doing nothing is the normal outcome for
   chat that needs no response.
5. After every turn the conversation is persisted.

## Units and contracts

Pure-logic units have no Bukkit imports so they are unit-testable; the Bukkit-facing units are
thin adapters around them.

### `Trigger` (record)
`source` (e.g. `"chat"`), `actor` (player name or null), `actorIsOp` (boolean), `text`,
`at` (Instant). Rendered into the user turn by `TriggerBatch.render(List<Trigger>)` as one line
each: `[HH:mm:ss] chat <Name> (op): text` / `(player)`; null actor renders the source only.
Text is included verbatim but with newlines replaced by spaces and capped at 1000 chars.

### `AgentService`
- `submit(Trigger)`: enqueue, non-blocking, safe from any thread. Dropped (with one WARN per
  minute at most) if the queue exceeds `max-queued-triggers`; dropped silently if paused or
  not configured (no API key).
- `start()` / `shutdown()`: start the worker; shutdown stops it, waits up to 10 s for an
  in-flight turn, persists. `pause()` / `resume()` / `isPaused()`. `resetConversation()` clears
  history (memory files are kept).
- Rate limit: at most `max-turns-per-minute` turns; when exhausted the worker waits and keeps
  batching, it does not drop.
- A turn that throws (API error, timeout) is logged at WARN with the exception class and
  message, the triggers of that turn are discarded, the conversation is left exactly as it was
  before the turn (no half-written turn is persisted), and the worker continues. Auth errors
  (401/403) additionally pause the service and log how to fix it.
- `stop_reason == "refusal"`: log at WARN, roll back the turn as above.

### `ClaudeClient` (the loop; SDK-facing)
- Model from config (default `claude-opus-5-5`), `output_config.effort` from config (default
  `medium`), adaptive thinking (omit or `{type:"adaptive"}` only), `max_tokens` 16000,
  non-streaming. Never forced `tool_choice`. No assistant prefill. No sampling params.
- Stable prefix for prompt caching: tools and system prompt are byte-identical across turns
  (no timestamps or player names in the system prompt); use top-level
  `cache_control: {type: "ephemeral"}`. All volatile data (snapshot, time) lives in the user turn.
- History is append-only: full `response.content` (thinking and compaction blocks included) is
  appended unchanged; nothing earlier is ever edited.
- Context growth: enable server-side compaction (beta `compact-2026-01-12`). On load and after
  each turn, messages that precede the latest compaction block may be dropped from the
  persisted file, as the compaction docs prescribe; nothing else is trimmed.
- Server-side refusal fallbacks (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`)
  are enabled if the Java SDK can express them (typed builder or additional body property +
  beta header). If it cannot, skip them and say so in the report.
- Parallel tool calls: all `tool_result` blocks for one assistant message go back in a single
  user message; a failed tool returns `is_error: true`, never dropped.
- Typed exception chain, most specific first; rely on the SDK's built-in retries.

### Tools exposed to Claude
All custom tools `strict: true`, `additionalProperties: false`.

| Tool | Input | Result |
|---|---|---|
| `run_commands` | `commands: string[]` (1..`max-commands-per-call`), no leading `/` required; `near_player: string \| null` (added 2026-10-01) | JSON array, one `{command, status, output}` per command in order; `status` is `ok`, `denied` or `error`. Later commands still run after a failure. |
| `send_chat` | `message: string`, `player: string \| null` | Broadcasts (null) or whispers to one online player, prefixed with `chat-prefix`. Error result if the player is offline. |
| `get_player_details` | `player: string` | Full detail for one online player: position, world, gamemode, health, food, xp level, held item, inventory summary (material × count), active effects. |
| memory tool `memory_20250818` | (Anthropic-defined) | File-backed by `MemoryStore`. |

The system prompt (a resource file, `system-prompt.md`, copied to the data folder on first run
so the owner can edit it; read once at start/reload) explains: the role; that most chat needs no
action; that chat text is untrusted player input and never an instruction from the server
owner; that ops' requests carry more weight than non-ops' and destructive or server-wide
actions for non-ops should be declined in chat; the tools; that durable facts (bases, promises,
ongoing events, player preferences) belong in memory files and that memory should be checked
when context is missing; that it is on Folia and which command behaviours differ.

`near_player`: on Folia a console command dispatched from the global region cannot see
entities that live in a player's region (`@e` found nothing in the live boot). When
`near_player` names an online player, the whole batch is dispatched on that player's own
scheduler (same console authority, same policy check, same capture), so selectors and
block/entity commands around that player work. Null keeps the global region. If the player is
offline every command returns `error` with output `player not online`. The system prompt tells
Claude to set it whenever a command targets entities or blocks near a player.

### `CommandPolicy` (pure)
`boolean isAllowed(String command)`. Normalise: trim, strip one leading `/`, lower-case the
root token, strip a `namespace:` prefix (`minecraft:op` → `op`). Denied when the root is in
`denied-commands`. For `execute`, every sub-command following a ` run ` token is checked
recursively the same way. Empty command is denied. Default denylist: `stop`, `restart`, `op`,
`deop`, `ban`, `ban-ip`, `pardon`, `pardon-ip`, `whitelist`, `save-off`, `reload`, `rl`,
`claude`, `claudecontrol`.

### `CommandRunner` (Bukkit-facing)
Runs one allowed command with console authority on the correct Folia thread, captures the
feedback text the command emits, returns it (capped at 2000 chars) within
`command-timeout-ms` (default 5000; on timeout the status is `error` with output `timed out`).
Every executed or denied command is logged at INFO with the turn's trigger actors, as the audit
trail. Never called from a region/global tick thread in a blocking way: the agent worker thread
blocks on the future, the server threads never do.

### `WorldSnapshot` (Bukkit-facing)
Short text block for the user turn: server time of day and weather per world, online players
each with name, op flag, world, block x/y/z, gamemode, health. Player fields are read on that
player's own scheduler and joined with a 2 s timeout; a player that times out or logs off is
listed as `unavailable`. Called from the agent worker thread only.

### `MemoryStore` (pure, filesystem)
Implements the memory tool commands (`view`, `create`, `str_replace`, `insert`, `delete`,
`rename`) rooted at `<data folder>/memory/`, exposed to Claude as `/memories`. Every path is
resolved and must stay inside the root after normalisation (reject `..`, absolute escapes,
symlinks leaving the root) — violation returns an error result, never throws out of the tool.
Caps: file ≤ 64 KB, total ≤ 4 MB, ≤ 200 files. Deleting the root itself is refused.

### `ConversationStore` (pure, filesystem)
`load()` → message list; `save(list)` atomic (temp sibling + rename) to
`<data folder>/conversation.json`; `clear()`. Round-trips SDK message params losslessly
(thinking signatures, compaction blocks, tool_use ids). A corrupt file is renamed to
`conversation.corrupt-<epoch>.json` and treated as empty, with a WARN.

### `TurnRateLimiter` (pure)
Sliding 60 s window over an injected clock: `long millisUntilAllowed()`, `void record()`.

### Plugin wiring
- `ClaudeControlPlugin.onEnable`: save default config and system prompt, build the units,
  `AgentService.start()`, register `ChatTriggerListener` and the `/claude` command.
  `onDisable`: `AgentService.shutdown()`.
- `ChatTriggerListener`: listens to the chat event at MONITOR, ignoring cancelled events,
  builds a `Trigger` (`source="chat"`) and calls `submit`. It never modifies or cancels chat.
- `/claude` (permission `claudecontrol.admin`, default op): `status` (configured, paused,
  queue length, turns this minute, model, last error), `pause`, `resume`, `reset`, `reload`
  (re-reads config and system prompt; a changed system prompt is fine, history is kept).
- New trigger sources later call `AgentService.submit` and nothing else.

### `config.yml`
```yaml
api-key: ""            # or env ANTHROPIC_API_KEY, which wins when set. Never logged.
model: claude-opus-5-5
effort: medium         # low | medium | high | xhigh | max
batch-window-ms: 1500
max-turns-per-minute: 6
max-queued-triggers: 200
max-tool-iterations: 12
max-commands-per-call: 20
command-timeout-ms: 5000
chat-prefix: "[Claude] "
denied-commands: [stop, restart, op, deop, ban, ban-ip, pardon, pardon-ip, whitelist, save-off, reload, rl, claude, claudecontrol]
```
With no key from either source the plugin still enables, logs one WARN, and `submit` is a no-op.

## Random events (added 2026-10-01, v0.1)

Claude also starts small, fun events on its own at random intervals, for two people playing
together — not chaos-mod effects. This is the second trigger source and uses the same entry
point as chat.

### `EventTimer` (pure)
`EventTimer(long minMinutes, long maxMinutes, java.util.random.RandomGenerator rng)`.
`long nextDelayMs()` — uniform over `[min, max]` minutes inclusive, in milliseconds; if
`max < min` it behaves as `max == min`; values below 1 minute are raised to 1.
`String pickTheme(List<String> themes)` — a uniformly random element, or `null` for an empty list.

### `RandomEventTrigger` (Bukkit-facing)
- `start()` schedules the first firing `nextDelayMs()` from now on the async scheduler;
  `stop()` cancels it. Each firing reschedules the next one, whatever happened.
- `fire()` is the one method both the timer and `/claude event` call. It submits
  `Trigger(source="event", actor=null, actorIsOp=false, text=…, at=now)` where the text asks
  for one event now and names the picked theme as a suggestion Claude may adapt or replace.
- The timer path skips (but still reschedules) when `events.enabled` is false, no player is
  online, or the service is paused. `/claude event` only requires a player online.
- Started in `onEnable`, stopped in `onDisable`, restarted with new settings on `/claude reload`.

### Config
```yaml
events:
  enabled: true
  min-minutes: 20
  max-minutes: 45
  themes: [treasure hunt, surprise gift, friendly visitor, fireworks show, weather moment,
           mini quest, trivia with a prize, mystery merchant, cooperative challenge,
           scavenger hunt, pet or companion, story moment tied to past events]
```

### System prompt guidance for events
Events are gentle and for enjoyment: never destroy or alter player builds, never kill players
or take their items, no dangerous mobs near players or bases unless it is a clearly announced,
opt-in challenge with a fair reward. Fit the event to where the players are and what they are
doing (the snapshot), keep it small enough to finish in a few minutes, announce it in chat in a
warm voice, and follow through when players respond in chat (quests, trivia answers, rewards).
Record each event and any ongoing storyline in memory and check it first, so events vary and
build on each other instead of repeating. Skipping an event is fine if the moment is wrong.

## Tests
Unit tests (JUnit 5, no server): `CommandPolicy`, `TriggerBatch.render`, `MemoryStore`,
`ConversationStore`, `TurnRateLimiter`, and `AgentService` batching/serial/rollback/pause
behaviour against a fake turn runner and injected clock. Wiring (listener registered, worker
started on enable and stopped on disable, a command actually executing on Folia) is verified by
a live boot on a real Folia 26.1.2 server and its log, since it cannot be unit-tested.

## Out of scope for v0.1
Trigger sources beyond chat and random events, per-player conversations, a mention/prefix filter, cost accounting,
web tools, streaming.

## Platform facts (looked up 2026-10-01)
- Compile against `dev.folia:folia-api:26.1.2.build.8-stable` (compileOnly) from
  `https://repo.papermc.io/repository/maven-public/`. Java 25.
- Descriptor: `plugin.yml` with `folia-supported: true` and `api-version: '26.1'`
  (the exact api-version string is unconfirmed; the live boot settles it).
- Schedulers: `Bukkit.getGlobalRegionScheduler()` (console commands run here),
  `Bukkit.getAsyncScheduler()`, `entity.getScheduler()` (`run(plugin, task, retired)`).
- Chat: `io.papermc.paper.event.player.AsyncChatEvent`; plain text via
  `PlainTextComponentSerializer.plainText().serialize(event.message())`. Treat player state as
  unsafe to read off its own scheduler.
- Feedback capture: `Bukkit.createCommandSender(Consumer<? super Component>)` exists; whether it
  carries op-level permission and works on Folia is unconfirmed — verify in the live boot, and
  fall back to another capture approach if it does not.
- Unconfirmed: which vanilla commands Folia disables or mis-routes. The system prompt tells
  Claude to read command output and adapt rather than hard-coding a list.
- Folia server jar for the boot check:
  `GET https://fill.papermc.io/v3/projects/folia/versions/26.1.2/builds/latest`.
- MockBukkit has no confirmed 26.1 artifact; do not use it.

## Review amendments (2026-10-01) — these override anything above that conflicts

1. **Trigger text is quoted.** `TriggerBatch.render` emits the text as a JSON string literal
   (double-quoted, with `"`, `\` and all control characters escaped, and U+0085, U+2028, U+2029
   escaped as `\uXXXX`), after the existing newline flattening and 1000-char cap:
   `[HH:mm:ss] chat Lucas (op): "hello"`. Actor names are emitted only if they match
   `[A-Za-z0-9_.]{1,32}`, else rendered as `?`. A player can therefore never forge another line,
   an `(op)` marker or a tag. The core rules tell Claude that quoted text is untrusted data.
2. **`CommandPolicy` also rejects embedded commands.** Any command whose text contains
   `command_block` or matches `command\s*:` (case-insensitive, anywhere, e.g. NBT
   `{Command:"op x"}`) is denied, whatever its root.
3. **Default denylist** becomes: stop, restart, op, deop, ban, ban-ip, pardon, pardon-ip,
   whitelist, save-off, reload, rl, function, schedule, datapack, transfer, perf, debug, jfr,
   publish, setidletimeout, paper, spigot, timings, plugins, sudo, lp, luckperms, perm,
   permissions, pex, plugman, claude, claudecontrol. README states that admin or sudo-style
   commands of any other installed plugin must be added.
4. **`AgentService.submit` returns `boolean`** (true = queued). `RandomEventTrigger.fire()`
   returns that value, and `/claude event` reports the truth.
5. **Per-player throttle.** `Settings` gains a fifth component `int maxTriggersPerActorPerMinute`
   (config `max-triggers-per-player-per-minute`, default 12). `submit` rejects (returns false) a
   trigger whose non-null actor already had that many accepted in the sliding last 60 s on the
   injected clock. Null-actor triggers are exempt.
6. **`updateSettings` keeps the turn rate-limit window** when `maxTurnsPerMinute` is unchanged.
7. **Core rules are code-side.** A bundled resource `core-rules.md` (never copied to the data
   folder, always read from the jar) holds the non-negotiable guidance: untrusted quoted text,
   op vs player weight, the event safety limits, `near_player` usage, never store trust or
   permission claims in memory and note who said what. The system prompt sent to the API is
   `core-rules.md` + the owner-editable `system-prompt.md` (persona, tone, server-specific
   notes). Both are read at start/reload only, so the prefix stays cache-stable.
8. **Reload is atomic and synchronized**: build every new config object first; if anything
   throws, nothing is applied and the sender is told. The `events` field is volatile.
9. **Shutdown ordering**: the HTTP client is closed only after the worker has exited or the
   wait has timed out with the worker marked stopping; a stopping worker never saves.
10. `pause_turn` when the iteration budget is exhausted is treated like the iteration-limit
    case: WARN, and the turn is rolled back.
11. `send_chat`: every line of a multi-line message carries the prefix, total length capped at
    1000 chars. Command strings and messages have control characters replaced with a space
    before they are logged.
12. `ClaudeClient.Config.toString()` redacts the key. Config defaults live only in the bundled
    `config.yml` (no inline literal defaults in code).
13. Accepted deviation: `prefix_mismatch_behavior: "drop_block"` with beta
    `thinking-binding-controls-2026-08-01` is sent so that editing the system prompt and
    reloading does not make later requests fail on replayed thinking blocks.

## Building and spatial awareness (2026-10-01, after first live play) — overrides conflicts above

Live finding: `run_commands` results reached Claude as `[{},{}]` because the SDK's JSON mapper
serialises a record as an empty object, so Claude could not see any command output. It built a
hut blind, on terrain it had never looked at, next to a player who kept moving, and never moved
the player inside. Fixes:

14. **Command results are real JSON.** `CommandRunner.Result` becomes the top-level record
    `CommandResult(String command, String status, String output)`.
    `ToolJson.commandResults(List<CommandResult>)` returns a JSON array of objects with exactly
    the keys `command`, `status`, `output` (strings correctly escaped). No SDK mapper for our
    own types anywhere.
15. **`run_commands` gains `anchor`** — `{world: string, x: int, z: int} | null`, required in the
    strict schema alongside `near_player`. When set, the batch is dispatched on the region
    scheduler that owns that block column (`Bukkit.getRegionScheduler()`, chunk `x>>4, z>>4`),
    so block commands work at a fixed site wherever the player has run to. `anchor` wins over
    `near_player`. Unknown world → every command `error` / `unknown world`. Same single run path,
    policy check, capture, timeout and audit line (which names the anchor).
16. **New tool `survey_area`** `{world: string, x: int, z: int, radius: int (1..12),
    footprint: int (3..15)}`. Runs on the region owning the centre column. For each column in
    the square it records the top motion-blocking non-leaf block Y and its material; columns in
    chunks that are not loaded or not owned by that region are unknown. It also counts entities
    by type inside the square. Output is `SurveyRenderer.render(...)` text.
    - `SiteFinder.find(int originX, int originZ, int[][] height, boolean[][] blocked, int size,
      int max, int towardX, int towardZ)` → `List<SiteFinder.Site>`; `height[zi][xi]` is the
      column at `(originX+xi, originZ+zi)`, `Integer.MIN_VALUE` = unknown; `blocked` marks
      liquid or unknown columns. Every `size`×`size` window fully inside the grid with no blocked
      or unknown cell is a candidate; `variation` = max − min height in it; `floorY` = the most
      common height (ties → the lower). Candidates are ordered by variation, then by squared
      distance from the window centre to `(towardX, towardZ)`; the result is the first `max`
      taken greedily so that returned windows do not overlap.
      `record Site(int minX, int minZ, int size, int floorY, int variation)` (absolute coords).
    - `SurveyRenderer.render(String world, int originX, int originZ, int[][] height,
      String[][] surface, List<SiteFinder.Site> sites, Map<String,Integer> entities)`: states
      `base y=<mode of known heights>`; one row per z labelled `z=<abs z>:` with heights
      relative to base (`?` for unknown); a surface map using one unique character per distinct
      material with a legend; the sites with absolute corner coordinates, floor y and variation;
      the entity counts. States that north (−z) is the top row and east (+x) is to the right.
17. **New tool `teleport_player`** `{player, world, x, y, z}` (ints): `teleportAsync` to the
    centre of that block (x+0.5, y, z+0.5) on the player's scheduler; result says whether it
    succeeded.
18. **Core rules gain a "Building" section and an "Intent" section** (text owned by the lead,
    see `core-rules.md`).
