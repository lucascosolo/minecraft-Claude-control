# Simple managed Minecraft experiences

Goal: Claude can create and hot-reload interactive experiences with managed event
listeners, simple progress rules, custom JavaScript callbacks, persistent state,
and a small game-only action API. Also provide companion dismissal and a backed-up
26.3 update preserving Java/Bedrock cross-play. User prioritizes simple delivery
so both players can start playing soon.

Use JSON definitions plus JavaScript functions. Rules and scripts coexist. Scripts
receive only plain event/state data and emit validated actions; no Bukkit objects,
Java classes, files, networking, commands, or provider calls. A bounded interpreter
worker keeps script loops off the tick thread. Keep one listener dispatcher and
one timer, invalidate stale callbacks on reload, and preserve prior working
definitions when validation fails. Players explicitly join an experience before
its mutating callbacks affect them. Owners can install/reload/disable definitions.

## Definition contract

`ExperienceSpec.parse(JsonObject)` validates id `[a-z][a-z0-9_-]{0,31}`, title
1..80 characters, optional description <=512, script <=16384 characters,
script_events (unique supported event names), and rules <=16. At least one rule
or script event is required. Rules have unique id, event, optional contains,
material, world, area `{x,y,z,radius}` (radius1..64), threshold1..1000 default1,
once defaulttrue, cooldown_seconds1..3600 default2, optional shared defaultfalse,
and function (JavaScript identifier). Timer rules additionally interval_seconds
5..3600 and threshold1. Rule parameters are plain JSON <=4096 bytes. Script must
define called functions; scripts use `onEvent(event,state,game)` for script_events
and named callbacks `(event,state,game,params)` for managed rules.

Events: join, chat, block_break, block_place, interact, enter_area, timer, manual,
death, respawn. Event input includes trusted player UUID/name/world/position and
bounded event-specific strings. Event data grants no authority. Supported game
emissions: chat, title, sound, particle, give_item, build, world_effect, companion.
Each emission is `{type,args}`; only existing typed game tools or bounded effects
are used. Maximum16 emissions/callback,16 blocks/build,16 items/gift; no console.

## Chunk 1: Independent pure tests

New core tests: ExperienceSpecTest, ExperienceRulesTest, ScriptRunnerTest.
Interfaces agreed directly with implementers before tests are authored. Verify
strict schemas, threshold/once/cooldown/player/shared state, named callback rules,
invalid reload preserving state, Java/files/network unavailability, bounded loops
and script outputs. Filesystem tests retain project scratch. Tests never call API.

## Chunk 2: Pure definition and rule engine

New ExperienceSpec and ExperienceRules. Pure evaluation accepts validated spec,
trusted event JSON, prior state JSON and injected wall-clock milliseconds; returns
updated state and named function calls. No mutation of caller state, no Bukkit or
filesystem. State JSON <=32768 bytes; definition <=32768 bytes. Limits fail closed.

## Chunk 3: Game-only JavaScript worker

New ScriptRunner and ScriptWorker; pinned current Rhino, safe standard objects,
class shutter refusing all Java classes, no host wrappers, bounded instruction
count and elapsed time. Prefer fixed Java child with128MiB heap and bounded JSON
stdin/stdout for protection from single native allocation and loops; fixed args,
cleared environment, no shell. No file/network APIs exposed to scripts. Maximum
one worker and bounded queue. No actions execute until whole output validates.
Named calls and onEvent share explicit state. Worker stopped on plugin disable.

## Chunk 4: Paper runtime, tools and examples

New ExperienceManager; integrate ClaudeControlPlugin/GameTools and prompts.
Use MemoryStore under experiences/ for archival definitions and state. Tools
list/read/save/reload/enable/disable, owner-only writes. Atomic validated reload
retains current state and cancels old generation callbacks. Commands addons
list/join/leave/trigger/status/reload/disable. Online player identity and permissions
rechecked before every effect; world effects still current-owner only. No API
request from event callbacks. One-second timers, bounded participant/event queues.
Example cooperative discovery experience combines an interact rule and script.

## Chunk 5: Companion dismissal and 26.3 preparation

Dismiss loaded nearby body owner-only; stop physical job and movement, archive
identity before removing entity, preserve all structures/notes, permit resummoning.
Build/test package. No live jar replacement while server is running.

New start_26_3 helper and checksum manifest: Paper26.3build181, existing verified
Geyser/Floodgate, ViaVersion5.12.1 and ViaBackwards5.12.1. Verify downloads before
stopping, validate exact container ownership/mounts, gracefully stop, copy complete
server directory to private project archive with symlink refusal, then replace jars
archivally and start. Never downgrade migrated world. Desktop Docker inaccessible
to agent; user runs one prepared script. Preserve key/EULA/accounts/whitelist.

## Verification and handoff

Run core Gradle test/build and inspect JUnit XML/package; run upgrade-helper scratch
tests with XML; review script isolation, identity, reload and backup boundaries.
Stage checksummed downloads where possible. Commit and verify remote backup. The
user runs one desktop upgrade/start command, joins, and tests dismissal, example
experience and Claude responses. Live tests remain unproven until observed.
