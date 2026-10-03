# ClaudeControl

A Folia 26.1.2 plugin that forwards server chat to one long-lived Claude agent. Claude can run console commands (with a denylist), speak in chat, inspect players and keep notes. Most chat gets no response; Claude acts only when something calls for it.

## Install

1. `./gradlew build`, then copy `build/libs/ClaudeControl-<version>.jar` into the server's `plugins/` folder.
2. Start the server once. This creates `plugins/ClaudeControl/config.yml` and `system-prompt.md`.
3. Set `api-key` in `config.yml`, or set the `ANTHROPIC_API_KEY` environment variable (the variable wins). Then run `/claude reload`.

Without a key the plugin still loads, logs one warning and ignores chat.

## Config (`config.yml`)

| Key | Default | Meaning |
|---|---|---|
| `model` | `claude-opus-5-5` | Model id |
| `effort` | `medium` | `low`, `medium`, `high`, `xhigh` or `max`. Higher means more thinking, more cost and slower turns |
| `batch-window-ms` | 1500 | How long to collect chat before starting a turn |
| `max-turns-per-minute` | 6 | Rate limit. Chat is held back until a turn is allowed, never dropped |
| `max-queued-triggers` | 200 | Chat lines held beyond this are dropped |
| `max-triggers-per-player-per-minute` | 12 | Chat lines accepted per player per minute; extra lines from that player are dropped |
| `max-tool-iterations` | 12 | Tool rounds per turn |
| `max-commands-per-call` | 20 | Commands per `run_commands` call |
| `command-timeout-ms` | 5000 | Per-command timeout |
| `chat-prefix` | `[Claude] ` | Prefix on Claude's chat messages |
| `events.enabled` | `true` | Claude starts small random events on its own while players are online (not while paused) |
| `events.min-minutes` / `events.max-minutes` | 20 / 45 | Random gap between events. The minimum is at least 1; a maximum below the minimum means a fixed gap |
| `events.themes` | treasure hunt, surprise gift, … | Themes suggested to Claude, picked at random |
| `denied-commands` | stop, op, ban, whitelist, reload, function, schedule, datapack, plugins, sudo, lp, … (see `config.yml`) | Commands Claude may never run. This also applies inside `execute … run` and `return run` |

**Add the admin or sudo-style commands of every other plugin you have installed to `denied-commands`.** Claude has console authority, and the built-in list only covers vanilla, Paper and a few well-known permission plugins. Commands that embed other commands (anything containing `command_block` or `Command:`) are always blocked.

The plugin's fixed core rules (trust, events, tool use) live inside the jar and are not copied to your server. `system-prompt.md` holds your own persona, tone and server notes, and is sent after the core rules. `/claude reload` re-reads it and the config without clearing history. If anything in them is invalid the reload changes nothing and tells you why. Keys missing from an older `config.yml` fall back to the bundled defaults.

## Commands

All require `claudecontrol.admin` (ops by default).

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
