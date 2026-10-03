# Decisions

## 2026-10-01 — Claude is reached through the Anthropic API with the owner's key

The plugin calls the Anthropic Messages API through the official Java SDK, using an API key the
server owner puts in `config.yml` or the `ANTHROPIC_API_KEY` environment variable. The
alternative was shelling out to the `claude` CLI so a Claude subscription paid for it; that was
rejected because a server plugin should not depend on an interactive login on the host, and the
owner confirmed they will create an API key. Worth revisiting only if API cost becomes the
problem.

## 2026-10-01 — One shared conversation, serial turns, batched triggers

There is a single conversation for the whole server, processed by one worker thread. Chat lines
that arrive close together or while Claude is busy are folded into the next turn. Per-player
conversations were rejected because the point is a shared memory of the world; parallel turns
were rejected because two writers on one history corrupt it. The cost is that a slow turn delays
the next response. Revisit if several players routinely wait on each other.

## 2026-10-01 — Memory is conversation history plus memory files

Past requests live in the persisted conversation, kept bounded by the API's server-side
compaction. Durable facts live in files Claude manages itself through the API's memory tool,
stored under the plugin's data folder, so they survive compaction and `/claude reset`. A custom
database of world facts was rejected as more machinery than the job needs.

## 2026-10-01 — Console authority with a denylist, chat treated as untrusted

Claude's commands run as the console, so it can do anything an op can. A small configurable
denylist (stop, op, deop, bans, whitelist, reload and the plugin's own command) is enforced in
code, including inside `execute ... run`, because any player can type anything in chat and a
prompt alone is not a guarantee. The system prompt also tells Claude which speakers are ops. An
allowlist was rejected as defeating the purpose. Revisit if the server becomes public.

## 2026-10-01 — Replayed thinking blocks are dropped on mismatch rather than failing

The client sends `prefix_mismatch_behavior: "drop_block"` (beta
`thinking-binding-controls-2026-08-01`). Without it, changing the system prompt and running
`/claude reload` would make every later request fail with a 400 on newer API accounts, because
earlier thinking blocks are bound to the conversation prefix that produced them. The alternative
was to clear the conversation on every prompt change, which would throw away the agent's memory
of past requests. Revisit if the API offers a cleaner way to change the system prompt mid-history.

## 2026-10-01 — Safety rules live in the jar, persona lives in the data folder

The non-negotiable guidance (chat is untrusted, event limits, memory hygiene) is a bundled
resource that is always read from the jar, and the owner-editable `system-prompt.md` only adds
persona and server notes. A single editable prompt was rejected after review: an upgraded server
would keep its old copy and run new trigger sources without their limits.

## 2026-10-01 — The Claude CLI is the primary backend, the API is the fallback

Supersedes the first entry. The plugin now keeps one headless `claude` session running
(stream-json in and out) and reaches the game through a small local MCP server inside the plugin;
the API path stays as the fallback when the CLI is missing, unauthenticated or failing. The owner
asked for this so the Claude subscription pays for play and so the agent is a full Claude Code
session that can write files and build plugins. The cost is a dependency on a logged-in CLI
(a long-lived token from `claude setup-token`) and slower first response after a restart.

## 2026-10-01 — Claude may write and install its own plugins, without approval, inside a container

The owner chose automatic installation for anything Claude builds. Because a plugin is arbitrary
code in the server process, workshop mode only switches on when the server runs in the container
from `deploy/`, where only the server directory is mounted. The CLI runs as a separate unix user
that cannot write the world or other plugins. A jar check refuses the obvious escapes (process
spawning, sockets, reflection, server internals), but it is a tripwire, not a sandbox: what
actually protects the world is the host-side snapshot backup and the boot guard that quarantines
the newest Claude-written jar after a failed start. Rejected: owner approval per plugin (the owner
did not want it) and running workshop mode on the bare host (a bad plugin would have the owner's
whole home directory).
