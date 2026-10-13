# Desktop cross-play server

From a normal terminal in the project, run `python3 tools/start_server.py`.
The helper checks Docker first, builds the core plugin, verifies pinned official
downloads, asks for two player identities, accepts a private API key using hidden
input, and asks you to accept the [Minecraft EULA](https://www.minecraft.net/eula).
It then starts the container and the local console panel at
<http://127.0.0.1:8766>. The script never makes a Claude API request.

Use Minecraft Java **26.2**, connecting to this PC's LAN address on port **25565**.
In Bedrock, add the same LAN address with port **19132**. Geyser and Floodgate
provide Bedrock access without requiring the Bedrock player to own Java.
Console editions that do not offer custom server entry need a supported custom
server connection method. Both player identities are added to the whitelist.
Bedrock owners use the exact Floodgate name (`.` plus gamertag, spaces replaced
by underscores). Check the script's whitelist responses before joining.

Live world and configuration live under gitignored `run/server/`. Claude's key is
in `run/server/plugins/ClaudeControl/config.yml`, mode 600. Existing configuration
is preserved; the helper offers an explicit key update. Replaced files are moved
to unique project `.archive/` entries. Do not share either directory.
Automatic Claude events default off; talking to Claude with a configured API key
incurs usage. API mode has no host workshop shell.

Only the Minecraft data directory is mounted in the container. The panel runs
on the host, binds localhost only, and controls one fixed container. RCON is
published only on localhost. Stop with the panel or
`docker stop --time 60 claudecontrol-minecraft`; data stays in place. Docker's
restart policy starts a previously running server after a desktop reboot once
Docker starts. A server you stopped manually stays stopped.
The panel itself must be started again with the helper after reboot.

On the same home network, no router changes are needed. For friends outside your
network, forward **TCP 25565** and **UDP 19132** to this PC and permit those game
ports through your firewall. Never forward 25575 or 8766. Port checks preserve
other running services; the helper never kills processes or removes containers.
Docker boot, both real clients, and a paid Claude turn must still be verified on
your desktop. Container memory is capped at 6 GB, with a 4 GB Java heap.
