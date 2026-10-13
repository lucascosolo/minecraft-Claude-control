# Local Minecraft control panel

Run from the project root after server setup:

```sh
python3 panel/server.py
```

Open **http://127.0.0.1:8766** on this desktop. Start creates or starts the
`claudecontrol-minecraft` container through `server/compose.yaml`. Stop sends
Docker's stop signal with a 60-second grace period so Minecraft can save. It does
not remove containers, volumes, worlds or files. The console polls Docker logs
once per second and keeps up to 2,000 lines in the browser tab.

Console commands use RCON at `127.0.0.1:25575`; the panel reads its password from
`run/server/server.properties`. RCON must be enabled, and its Docker port must be
published to loopback only. Commands have full Minecraft console privileges.
Use the in-game plugin's player authorization settings to control Claude;
the panel is the local server operator's interface.

The panel uses Python 3's standard library. Docker with Compose must be installed
and available to your desktop account. Ctrl+C stops the panel; Minecraft keeps
running until you select Stop or stop its container. `--port` changes the web
port. The panel refuses public binds. Keep it local; do not publish it through a
proxy or tunnel.

Java connects to `localhost:25565` from this PC. Bedrock uses address `localhost`
and port `19132`; another device on the LAN uses this PC's local IP instead.
Cross-play requires the configured Geyser and Floodgate plugins to load.

## Backend contracts

`panel.server.make_server(controller, host='127.0.0.1', port=8766)` returns a
`ThreadingHTTPServer`; `create_server` is an alias. It does not start serving until
the caller runs `serve_forever`. Tests can inject a controller. `DockerController`
accepts injected subprocess `runner` and socket `connection_factory` functions.

| Route | Result |
| --- | --- |
| `GET /api/session` | `{"csrf":"…"}` plus a local HttpOnly, SameSite=Strict session cookie |
| `GET /api/status` | `{"state":"running", "running":true, "message":"…"}` |
| `GET /api/logs` | `{"output":"…"}` with at most the latest 200 Docker log lines |
| `POST /api/start` | Updated container status; body `{}` |
| `POST /api/stop` | Updated container status; body `{}` |
| `POST /api/command` | `{"output":"…"}`; body `{"command":"list"}` |

API reads require the session cookie. Mutations additionally require matching
local Host and Origin headers and the `X-CSRF-Token` session token. JSON bodies
are limited to 4 KiB; commands to 1,024 UTF-8 bytes and one line without control
characters. RCON packet identifiers, sizes and response counts are checked.
There is no shell execution or arbitrary container endpoint. Every operation
verifies real project paths, Compose ownership labels
and the container's sole bind mount from this project's `run/server` to `/server`.
Containers with unexpected binds or volumes are refused, even if their name matches.
Errors omit Docker and socket diagnostic strings. Output redacts known RCON passwords, Anthropic
token patterns, bearer tokens and named key/password assignments; arbitrary
unlabeled secrets in third-party plugin logs cannot be recognized reliably.
