# Desktop cross-play server

From a normal terminal in the project, run `python3 tools/start_26_3.py`.
The helper checks Docker and exact container ownership/mounts first, builds the
core plugin and verifies all pinned official downloads before stopping Minecraft.
For an existing world it gracefully stops the server, checks free disk space and
copies the **entire stopped `run/server/` directory** into a private
`.archive/upgrade-<unique-id>/server/` backup before replacing any jar. Symlinks,
hard links and special files are refused. Complete backups have an adjacent
`backup-complete.json`; partial backups and failed downloads stay preserved.
Existing jars are archived separately as they are replaced.

The same command starts a new server. Initial setup asks for two player identities,
a private API key using hidden input, and acceptance of the
[Minecraft EULA](https://www.minecraft.net/eula). Saved accounts, whitelist, EULA,
worlds and configuration are retained; the helper offers an explicit key update.
It then starts the container and the local console panel at
<http://127.0.0.1:8766>. The script never makes a Claude API request.

Use Minecraft Java **26.3**, connecting to this PC's LAN address on port **25565**.
In Bedrock, add the same LAN address with port **19132**. Geyser and Floodgate
provide Bedrock access without requiring the Bedrock player to own Java.
Paper 26.3 build 181, Geyser 2.11.3 build 1251, Floodgate 2.2.5 build 141,
ViaVersion 5.12.1 and ViaBackwards 5.12.1 are pinned with SHA-256 in
`server/versions-26.3.json`. ViaBackwards provides the 26.3 to 26.2 protocol bridge
for the pinned Geyser; actual Java and Bedrock joins still need desktop verification.
The upstream artifacts are documented by
[Paper's build metadata](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/181),
[ViaVersion's release](https://github.com/ViaVersion/ViaVersion/releases/tag/5.12.1)
and [ViaBackwards' release](https://github.com/ViaVersion/ViaBackwards/releases/tag/5.12.1).
Console editions that do not offer custom server entry need a supported custom
server connection method. Both player identities are added to the whitelist;
saved valid UUIDs must match both accounts before player access is confirmed.
Bedrock owners use the exact Floodgate name (`.` plus gamertag, spaces replaced
by underscores). Bedrock gamertags are limited to 16 raw characters. Check the
launcher's final whitelist confirmation before joining.

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
The panel itself must be started again with the helper after reboot. Running this
starter prepares and installs the current built plugin, so it stops and backs up
an already running Minecraft server before restarting it.

After a world has started on 26.3, **never launch it on 26.2**. The old
`start_desktop.py`, guarded `start_server.py` and `server/versions.json` are retained
unchanged as historical 26.2 setup material; do not run either older launcher
against this world. The new starter refuses unknown/newer source versions and
records the target version before starting. If startup fails, it leaves the
server data and backups in place; fix the cause and rerun the same 26.3 starter.
To return to 26.2, first stop Minecraft and preserve the failed/new server data,
then restore a **complete pre-upgrade backup** through a separately reviewed
procedure. Replacing only the Paper jar cannot safely downgrade a migrated world.

On the same home network, no router changes are needed. For friends outside your
network, forward **TCP 25565** and **UDP 19132** to this PC and permit those game
ports through your firewall. Never forward 25575 or 8766. Port checks preserve
other running services; the helper never kills processes or removes containers.
Docker boot, both real clients, and a paid Claude turn must still be verified on
your desktop. Container memory is capped at 6 GB, with a 4 GB Java heap.
