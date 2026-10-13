# ClaudeControl

Claude joins your Minecraft conversations through the Anthropic API, inspects
nearby terrain, builds on clear ground, gives basic supplies, and keeps durable
notes. The rebuilt core runs on Paper 26.2 with Geyser and Floodgate for Java
and Bedrock players. The recovered advanced implementation is preserved separately.

## Start on this desktop

Run this in your normal terminal:

```sh
python3 /home/lucas/Workspaces/minecraft-Claude-control/tools/start_desktop.py
```

The helper checks Docker, builds the plugin, verifies official server downloads,
asks for your two player identities and privately accepts your API key. After
you accept the Minecraft EULA, it starts the server container and the console
panel at <http://127.0.0.1:8766>. It makes no Claude API request.
See [server setup](server/README.md) for joining, ports and stopping the server.

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
Use `/claude companion summon` on clear level ground, then
`/claude companion follow`, `wave`, `stay` or `status`. These direct controls
make no API calls. Through conversation, Claude can perform small physical
building jobs, or gesture while conjuring larger structures and changing time
or weather. It is kind, playful and powerful, and seeks fun things you can do
together without imposing a role or title on itself.

Physical walking currently requires safe loaded ground on one level; stairs
and jumping are not implemented. Physical jobs place up to 16 blocks in sequence
and report partial work honestly. Larger builds use the instant bounded build
tool. Owner-authorized world effects can reach all server worlds; remote building
uses loaded chunks and protects existing structures. Both client renderings and
physical animations still need a live check.

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

Current verification: 52 core tests, 17 panel tests and 21 startup-helper tests
passed, and the shaded plugin jar was built and inspected. This does not replace
the first live desktop session. Historical shell tests remain excluded.

Historical `src/`, `_early/` and `deploy/` are incomplete recovery material.
The old build and README are in `recovered/`; `MANIFEST.tsv` records provenance.
`python3 tools/check_restoration.py` inventories missing historical files without
running them. The rebuilt core is a replacement, not a full restoration.

**Do not run historical deployment or repair tests.** They still contain unsafe
deletion code, including the failure pattern that wiped the home directory.
They are excluded from the active build and startup path. A safety hook blocked
their guard repairs; it must be repaired before those scripts can be reconsidered.
