# ClaudeControl core rebuild and desktop cross-play server

**Goal:** A verified API-key ClaudeControl plugin and a prepared desktop container server for Java and Bedrock, with a basic local console panel and one startup script for the user's final host-only steps.

**Approach:** Build an independent `core/` subproject and leave recovered `src/`, `_early/` and historical deployment scripts in place. The user chose this over reconstructing the missing previous feature set first. Use stable Paper 26.2 build 133 because current Geyser supports Java 26.2; the new core targets Paper, not Folia. Keep the restored advanced code available for later expansion.

**Constraints:** No deletion code; no historical shell-test execution; no real home-directory fixtures. Every test fixture is a fresh scratch directory retained in project build output. API key remains outside git and logs. API-mode support is the first priority. Java and Bedrock must share the world and Claude behavior, preserving Floodgate identities. No old world recovery is required. Public exposure, paid API calls and starting the persistent service require the owner's final approval after local verification.

## Chunk 1: Safe core contracts and tests

Files: `core/src/test/java/com/lucascosolo/claudecontrol/core/*Test.java` and test fixtures.

Contracts:
- `Settings.from(Map<String,Object>, Map<String,String>)` validates API-only settings; trimmed nonempty `ANTHROPIC_API_KEY` overrides config; blank environment falls back to config; absent key loads inactive; strings and errors redact secrets. Default model `claude-opus-5-5`, effort `low`; validate rates, limits and effort.
- `Turn(UUID actorId, String actorName, boolean privileged, String text, boolean event)` carries authority from the server, never from text. Chat JSON preserves Floodgate prefixes and escapes all message content.
- `MemoryStore(Path)` reads/writes bounded text under its own real directory only; rejects links, traversal, absolute paths and oversized files; replacement archives previous content within the store. No file deletion.
- `CommandPolicy(Set<String>)` fails closed on empty/control-containing input, namespaced denylist commands and nested command wrappers; ordinary players cannot use console dispatch at all.
- `ApiAgent` uses an injectable transport and tool executor; mocked tests verify tool-use result IDs/errors, tool round limit, history rollback on transport failure, bounded complete turns and secret-safe failures. No live API calls in tests.

Write tests before implementation; compile failures first, green after chunk 2. Use Gradle JUnit XML under `core/build/test-results/test/`. The test author owns tests; implementers do not edit their contracts.

## Chunk 2: API, configuration and durable memory

Files: core `Settings`, `Turn`, `MemoryStore`, `CommandPolicy`, `ApiAgent`, `ToolExecutor`, `ToolReply` and related pure helpers.

Interfaces: `ToolExecutor.execute(String, com.google.gson.JsonObject, Turn)` returns `ToolReply(String text, boolean error)`; it may throw an exception which is returned as a safe tool error. `ApiAgent` accepts `Settings`, a system prompt, tool schemas and an injected transport for unit tests. Its public `runTurn(Turn, String worldReport, ToolExecutor)` performs a serial bounded Messages API tool loop. Only the real transport sends HTTPS to api.anthropic.com with the key in headers. Write conversation state as complete turns, and archive replaced files. Handle authentication errors without logging keys or raw response bodies. Blocking network runs only on the worker thread.

Verify chunk 1 tests and inspect XML; no billable request is permitted yet.

## Chunk 3: Paper plugin and game tools

Files: core `ClaudeControlPlugin`, `GameTools`, main resource `plugin.yml`, `config.yml`, `core-rules.md`, `system-prompt.md`.

Implement `/claude ask|status|pause|resume|reload|reset|event`; chat addressed to Claude and explicit `/claude ask` feed a bounded serial worker. Capture world snapshots and perform all Bukkit access on the server thread. Operators/configured owners are privileged; a player's turn permits actions only on that player. Keep UUID and exact account name intact, including Floodgate prefixes. No key means useful status with no network activity. Reload validates before replacing settings, reset keeps memory and archives conversation. Stop every plugin-owned task/worker on disable.

Tools: `send_chat`, `survey_area`, `build`, `give_item`, `teleport_player`, `run_commands` (owner only, hard denylist), `memory`. Validate sizes, loaded chunks, world bounds, materials, item quantities, targets and positions before changes. Non-overwriting build preflights the entire volume before placing anything; never partially overwrite protected blocks/containers. Include completed structure locations in memory. Events are disabled by default, small and non-destructive, and require online players. Persona encourages staged ambitious shared projects without increasing authority.

Verify compile, pure policy tests and a local Paper boot with API inactive; manually inspect plugin status and safe tools through local console if possible.

## Chunk 4: Build and preserved restoration

Files: root Gradle settings/build, `core/build.gradle.kts`, wrapper files if safely available, README and AGENTS.

Preserve the recovered build in `recovered/build.gradle.kts`. Root `build` builds only core; historical source/tests are never executed. Pin Paper API `26.2.build.133-stable`; use Java 25 and cached Gson 2.14.0. Produce exactly `core/build/libs/ClaudeControl.jar` with dependencies shaded. Use shared machine Gradle home, do not run clean. `tools/check_restoration.py` continues documenting historical gaps separately from core readiness. Package inspection must prove main class, resources, schemas and dependencies are included.

Verify Gradle test + build; read XML and jar metadata.

## Chunk 5: Fresh desktop server and cross-play

Files: gitignored `run/server/` for live config/worlds/assets, version/checksum manifest and a reversible persistent-service definition in the project.

Download pinned verified Paper jar, Geyser and Floodgate from official services; verify published checksums where available. Configure a fresh survival world, Java authentication on, secure-profile enforcement off as required by Geyser, Geyser floodgate authentication, protected credentials, explicit whitelist and separate TCP/UDP listeners. Preflight desktop resources and ports using approved read-only access. Preserve existing files rather than replacing them blindly.

Verify initial boot on loopback with API inactive and stop it afterward. Check Java server-list handshake and Bedrock UDP status on scratch ports. Show a concrete service/port/config plan before enabling public/persistent operation. The owner supplies an API key in the private config and accepts the Minecraft EULA if not already authorized. Internet router forwarding or tunnel choice remains to be settled. Final live verification includes both client protocols and an approved API tool turn. Record remaining user-only steps without claiming they passed.

## Chunk 6: Local container panel and user startup script

The user explicitly requested container start/stop and console input/output in a basic web panel, authorized doing every available step, and accepted a script for the final host-access steps. Docker's socket is unavailable in the agent environment even after requesting escalation; the script runs in the user's normal terminal. No old world recovery is needed.

Files: `panel/` (Python stdlib backend and plain HTML/CSS/JS), `panel/test_control.py` (independent fake Docker/RCON and loopback HTTP tests), `server/` (container recipe, version hashes and config), `tools/start_server.py`, `tools/test_server_setup.py` (independent scratch-only path, archival and download checks).

Panel: localhost port 8766 only, fixed container `claudecontrol-minecraft`, fixed Compose project/service, no shell interpolation, bounded RCON console on host loopback 25575, bounded live log polling, Host/Origin/session/CSRF validation, credential redaction and accessible mobile controls. Stop never removes the container or data. Tests inject fake Docker/RCON and stop every owned HTTP thread.

Startup script: verify prerequisites, confine every generated path to project `run/`, preserve existing contents in unique archives, verify pinned asset hashes, build/package core, securely collect an API key without argv/logging, ask EULA acceptance, configure exact owner names and whitelisting, build a Java-only container with only its own data directory writable and no host home/socket mount, start it, wait for readiness, add Java/Floodgate whitelist entries, and launch the local panel. Keep real configs and keys ignored by git. Never delete, clean, or run historical scripts. Starting the service is a user-run final step.

Verify core JUnit XML, panel and startup-helper XML, package contents and checksums; render panel once at desktop/mobile if browser capability is available. Report any unavailable live Minecraft/Docker/client/API verification honestly.
