# ClaudeControl

Claude joins your Minecraft conversations through the Anthropic API, inspects
nearby terrain, builds on clear ground, gives basic supplies, and keeps durable
notes. The rebuilt core runs on Paper 26.3 with Geyser, Floodgate and protocol bridges for Java
and Bedrock players. The recovered advanced implementation is preserved separately.

## Start on this desktop

Run this in your normal terminal:

```sh
python3 /home/lucas/Workspaces/minecraft-Claude-control/tools/start_26_3.py
```

The helper checks Docker, builds the plugin, verifies official server downloads,
asks for your two player identities and privately accepts your API key. After
you accept the Minecraft EULA, it starts the server container and the console
panel at <http://127.0.0.1:8766>. It makes no Claude API request.
See [server setup](server/README.md) for joining, ports and stopping the server.
On an existing server, it verifies downloads, stops gracefully, preserves a
complete private backup, and installs the update before restarting. Existing
accounts, key, whitelist and world remain in place. Use this launcher for future
starts; the historical 26.2 launchers are superseded.

The key lives in the private, gitignored file
`run/server/plugins/ClaudeControl/config.yml`, under `api-key`. A nonblank
`ANTHROPIC_API_KEY` environment variable overrides the file. No Claude Code login
is needed. The pre-wipe credential-bearing configuration has **not been recovered**.

Talk to Claude by addressing it in chat, or use `/claude ask <request>`.
For example: `Claude, survey the ground and help us plan a village together.`
Configured owners can use `/claude status`, `/claude pause`, `/claude resume`,
`/claude reload`, `/claude reset` and `/claude event`. The persona encourages
ambitious towns, transport, adventures and ongoing stories, built in practical
stages with you.

Claude has a visible character without requiring another Minecraft account.
Use `/claude companion summon` on clear level ground; Claude then keeps nearby
players company automatically. Use `wave`, `stay`, `dismiss` or `status`;
`/claude companion follow` resumes companionship after `stay`. These direct controls
make no API calls. Through conversation, Claude can perform small physical
building jobs, or gesture while conjuring larger structures and changing time
or weather. It is kind, playful and powerful, and seeks fun things you can do
together without imposing a role or title on itself.

Claude walks and jumps up or down single-block changes on checked loaded terrain.
Its local behavior looks toward friends and chooses occasional short nearby walks.
Through conversation it can choose a 10–60 second wandering or movement plan,
which runs without additional API calls and yields to new requests and jobs.
When terrain blocks
companionship, Claude can catch up beside a nearby permitted player on verified
clear loaded ground, with a small particle effect. When friends travel farther
away or into another world, the existing loaded body can also catch up there;
a 15-second cooldown prevents constant teleporting. It never moves players,
loads chunks or duplicates an unloaded body. Physical jobs place up to 16 blocks in sequence
and report partial work honestly. Larger builds use the instant bounded build
tool. Owner-authorized world effects can reach all server worlds; remote building
uses loaded chunks and protects existing structures. Both client renderings and
physical animations still need a live check.

Claude also chooses an occasional new activity through the API every 30–45 minutes
of active play. This is the owner's selected pacing; it starts enabled after this
update, honors a saved explicit off setting, and pauses when no owner is online,
the body is unavailable, Claude is paused, or a player request/job takes priority.
Those planning turns use your configured API key; wandering and catch-up do not.
Use `/claude initiative status` or `/claude initiative off` to inspect or stop it.
Initiative permits one small gift or protected build per turn, and can stage new
opt-in experiences. It cannot run console commands, move players, alter time or
weather, or overwrite existing addons. New requests interrupt autonomous effects.
Its long-term direction is gentle, optional readiness for the Ender Dragon,
without deadlines or reminders when you want to enjoy something else.

The initial runtime-loader update uses `python3 tools/start_26_3.py` and requires
one server restart. Thereafter, build the plugin and run
`python3 tools/update_runtime.py` to install compatible Java runtime updates while
Minecraft and player sessions keep running. It archives replaced bytes, validates
the stable loader, stops old listeners and workers, and verifies the active jar.
`/claude runtime-status` reports the outcome; `/claude runtime-reload` is owner-only.
Loader, permissions, or dependency changes still require a server restart.
Settings and managed addons continue to use `/claude reload` and
`/claude addons reload` respectively. Live runtime switching still needs a server check.

## Managed interactive experiences

Claude can create JSON addon definitions with event listeners, simple progress
rules and JavaScript callbacks. Rules can invoke named script functions; scripts
can also listen directly to events. Saved state supports shared puzzles, games,
performances and other interactive experiences. Reload works while playing and
keeps progress; an invalid replacement keeps the previous working definition.

Scripts receive event data, state and `game.emit({type,args})` for managed chat,
titles, sounds, particles, basic gifts, small protected builds, owner-authorized
world effects and immediate companion actions. Scripts run in a bounded Java
worker and have no Java classes, files, network, console commands or API access.
Callbacks incur no Anthropic usage. Claude's requests to author them do.

Both players can test the bundled example without an API request:

```text
/claude addons list
/claude addons join shared_sparks
```

Right-click a crafting table three times between you to trigger the scripted
celebration. Use `/claude addons trigger shared_sparks` for a hint and
`/claude addons leave shared_sparks` to opt out. Owners can reload, enable,
disable or reset an experience with `/claude addons <action> <id>`.
You can ask Claude to create a new experience through the `experience` tool.
Definitions and progress live privately under `plugins/ClaudeControl/experiences/`.

## Tools and limits

Each turn includes a fresh nearby-world snapshot: health, food, inventory,
position, biome, weather and nearby entities. Claude can refresh it with
`observe_world` and inspect terrain with `survey_area`. API replies take network
and model time; this is near-real-time interaction, not frame-by-frame vision.

The bounded `build` tool protects existing blocks and containers and refuses
hazardous materials. Item gifts and teleports are limited; console commands
require current owner authority and a permanent denylist. Memory and conversation
survive restarts, with bounded files and archival replacement. API mode gives
Claude no host shell or workshop plugin installation.

Prompt caching covers stable instructions and repeated conversation. Actual
provider cache hits require a live API turn. Automatic events default off;
direct requests incur Anthropic usage. `/claude pause` stops new requests.

The local panel starts/stops one fixed container, displays recent output, and
sends game console commands through loopback-only RCON. The container mounts
only `run/server/`, with no home directory or Docker socket.

## Development and restoration

Active code is in `core/`; root Gradle builds and stages
`build/libs/ClaudeControl.jar`. Use system Java 25 and the shared Gradle cache.
The startup helper locates the installed cached Gradle distribution.

Core tests mock the provider and exercise authorization, bounded state, tool
handling and caching. Panel and startup tests use scratch data and fake
Docker/RCON. Docker boot, real Java/Bedrock clients and a paid Claude turn still
need validation on your desktop.

Current verification: 121 core tests, 17 panel tests, 25 startup-helper tests,
18 upgrade-helper tests and 12 runtime-update tests passed. The shaded plugin and its script worker were
built and checked. This does not replace
the first live desktop session. Historical shell tests remain excluded.

Historical `src/`, `_early/` and `deploy/` are incomplete recovery material.
The old build and README are in `recovered/`; `MANIFEST.tsv` records provenance.
`python3 tools/check_restoration.py` inventories missing historical files without
running them. The rebuilt core is a replacement, not a full restoration.

**Do not run historical deployment or repair tests.** They still contain unsafe
deletion code, including the failure pattern that wiped the home directory.
They are excluded from the active build and startup path. A safety hook blocked
their guard repairs; it must be repaired before those scripts can be reconsidered.
