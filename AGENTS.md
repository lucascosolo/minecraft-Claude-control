# Project safety and restoration

Read `/home/lucas/.agents/AGENTS.md` before work. Its deletion rules apply.

This is an incomplete recovered checkout. Run the read-only inventory
`python3 tools/check_restoration.py` before treating it as buildable. Consult
`MANIFEST.tsv` for provenance; do not describe newly written replacements as recovered files.

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
Actual API-key loading remains unverified while `PluginSettings.java` and its
dependencies are missing. Do not make a billable API call without approval.

The user's requested persona is ambitious about towns, infrastructure, adventures
and ongoing stories. Preserve all existing trust, authorization and world-protection
rules when extending it. API mode does not supply the CLI workshop shell.
