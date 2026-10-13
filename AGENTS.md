# Project safety and restoration

Read `/home/lucas/.agents/AGENTS.md` before work. Its deletion rules apply.

The active, independently rebuilt plugin is in `core/`; root Gradle builds only
that subproject. The historical `src/`, `_early/` and `deploy/` remain incomplete
recovery material, preserved for later expansion. Run the read-only inventory
`python3 tools/check_restoration.py` when auditing historical restoration. Consult
`MANIFEST.tsv` for provenance; do not describe replacements as recovered files.

Do not execute `deploy/test-entrypoint.sh`, `deploy/test-repair.sh`, container
entrypoints or repair/deployment scripts until their deletion code is repaired
and reviewed. The recovered repair test fragment is the source of the home-directory
wipe: it constructs a path from an uninitialized scratch variable. Never source
or execute historical fragments to discover what they do.

Before those shell tests can run, add guards that refuse independent execution
of fragments, create a fresh scratch root inside this project, validate every
computed path and preserve cleanup targets by moving them within that scratch
root. Test refusal with scratch canaries while keeping every guard active.
The current safety hook rejected the guard patches; repair the hook/access setup
before retrying. Never bypass it.

`tools/test_test_safety.py` is a regression specification for that repair. Its
static guard checks must pass before it may invoke either historical script.
Its scratch fixtures are preserved. Passing this suite alone does not approve
the deployment scripts, which have separate deletion code.

Keep API keys, OAuth tokens and recovered live server config out of git and tool
output. The bundled config has an empty key and defaults to `backend: api`.
The core's config-key loading, tool loop and caching have mock-based tests. Live
API acceptance and provider cache hits still require the user's private key and
an approved billable request. The pre-wipe live config was not recovered.
Do not make a billable API call without approval.

The prepared startup path is `tools/start_desktop.py`, `server/compose.yaml` and
the localhost-only `panel/`. It uses fresh `run/server/` data, pinned checksummed
Paper/Geyser/Floodgate assets, private config and archival replacements. Only
the server data folder is mounted into Minecraft; never add the host home or
Docker socket. The host panel controls only `claudecontrol-minecraft` and its
RCON port is loopback-only. Current agent Docker access is unavailable; the
user runs the startup helper in their normal terminal for the final host steps.

The guarded original `tools/start_server.py` remains a tested helper library;
its main is superseded by `start_desktop.py`. The safety hook refused edits to
the original launcher. Keep its guards intact. The new entry point confirms
container mounts and asynchronous whitelist results before claiming join readiness.

The user's requested persona is ambitious about towns, infrastructure, adventures
and ongoing stories. Preserve all existing trust, authorization and world-protection
rules when extending it. API mode does not supply the CLI workshop shell.
