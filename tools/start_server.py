#!/usr/bin/env python3
"""User-run desktop setup. Importing this module performs no setup or networking."""
from __future__ import annotations

import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid

PROJECT = Path(__file__).absolute().parents[1]
CONTAINER = "claudecontrol-minecraft"
OFFICIAL_HOSTS = {"fill-data.papermc.io", "download.geysermc.org"}


def confined(root: Path, relative: str | Path) -> Path:
    root = Path(root).absolute()
    if not root.is_dir() or any(p.is_symlink() for p in (root, *root.parents)):  # SAFETY-GUARD
        raise ValueError("Root must be a real directory without symlink ancestors")
    relative = Path(relative)
    if str(relative) == "." or relative.is_absolute() or ".." in relative.parts:  # SAFETY-GUARD
        raise ValueError("Path must be nonempty, relative and below the project")
    result = root / relative
    for path in (result, *result.parents):
        if path.is_symlink():  # SAFETY-GUARD
            raise ValueError("Symlink paths are refused")
        if path == root:
            break
    return result


def write_preserving(root: Path, relative: str | Path, data: bytes, mode=0o600) -> Path:
    path = confined(root, relative)
    if path.exists():
        if not path.is_file():  # SAFETY-GUARD
            raise ValueError("Destination is not a regular file")
        if path.read_bytes() == data:
            path.chmod(mode)
            return path
        archive = confined(root, Path(".archive") / uuid.uuid4().hex / Path(relative))
        archive.parent.mkdir(parents=True, exist_ok=True)
        confined(root, archive.relative_to(root))
        print(f"Preserving prior file: {path} -> {archive}")
        path.rename(archive)
        archive.chmod(mode)
    path.parent.mkdir(parents=True, exist_ok=True)
    confined(root, relative)
    # Exclusive creation avoids following a leaf introduced between validation and open.
    with path.open("xb") as output:
        os.chmod(path, mode)
        output.write(data)
    return path


def _official(url: str):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in OFFICIAL_HOSTS
            or parsed.username or parsed.password or parsed.port not in (None, 443)):  # SAFETY-GUARD
        raise ValueError("Download must use an approved official HTTPS host")


def download_verified(url: str, destination: Path, sha256: str,
                      opener=urllib.request.urlopen) -> Path:
    _official(url)
    if not re.fullmatch(r"[a-f0-9]{64}", sha256):  # SAFETY-GUARD
        raise ValueError("Invalid SHA-256")
    destination = Path(destination).absolute()
    if any(p.is_symlink() for p in (destination, *destination.parents)):  # SAFETY-GUARD
        raise ValueError("Download paths cannot contain symlinks")
    if destination.exists():
        if not destination.is_file() or hashlib.sha256(destination.read_bytes()).hexdigest() != sha256:  # SAFETY-GUARD
            raise ValueError("Existing asset differs; preserve it and choose another destination")
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = destination.with_name(destination.name + ".staging-" + uuid.uuid4().hex)
    digest = hashlib.sha256()
    with opener(url, timeout=60) as response:
        if hasattr(response, "geturl"):
            _official(response.geturl())
        with staging.open("xb") as output:
            os.chmod(staging, 0o600)
            while block := response.read(1024 * 1024):
                output.write(block)
                digest.update(block)
    if digest.hexdigest() != sha256:  # SAFETY-GUARD
        raise ValueError("Checksum mismatch; staging retained for review")
    if destination.exists() or destination.is_symlink():  # SAFETY-GUARD
        raise ValueError("Destination appeared during download; staging retained")
    staging.rename(destination)
    return destination


def command(args, *, env=None, timeout=30, capture=True):
    result = subprocess.run(args, cwd=PROJECT, env=env, timeout=timeout,
                            check=False, capture_output=capture, text=True)
    if result.returncode:
        # Captured Docker output can include private metadata; do not echo it.
        raise RuntimeError(f"{args[0]} failed. Check installation, permissions and service availability.")
    return result.stdout if capture else ""


def yes(prompt):
    return input(prompt + " [yes/no]: ").strip().lower() == "yes"


def player_accounts():
    path = confined(PROJECT, "run/player-accounts.json")
    if path.exists():
        accounts = json.loads(path.read_text())
    else:
        accounts = []
        for number in range(1, 3):
            platform = input(f"Player {number} platform (java/bedrock): ").strip().lower()
            name = input(f"Player {number} exact Java name or Xbox gamertag: ").strip()
            accounts.append({"platform": platform, "name": name})
    if not isinstance(accounts, list) or len(accounts) != 2:  # SAFETY-GUARD
        raise ValueError("Expected exactly two player accounts")
    for account in accounts:
        platform, name = account.get("platform"), account.get("name", "")
        pattern = r"[A-Za-z0-9_]{3,16}" if platform == "java" else r"[A-Za-z0-9][A-Za-z0-9 _-]{0,31}"
        if platform not in ("java", "bedrock") or not isinstance(name, str) or not re.fullmatch(pattern, name):  # SAFETY-GUARD
            raise ValueError("Use a valid Java name or Xbox gamertag (letters, numbers, spaces, _, -)")
    write_preserving(PROJECT, "run/player-accounts.json", json.dumps(accounts).encode())
    return accounts


def configure(accounts):
    config = confined(PROJECT, "run/server/plugins/ClaudeControl/config.yml")
    fresh = not config.exists()
    if fresh or yes("Update the existing private Claude API key?"):
        key = getpass.getpass("Anthropic API key (hidden; blank leaves Claude inactive): ").strip()
        if not key and not yes("Continue with Claude inactive until you add a key?"):
            raise RuntimeError("Setup stopped before server launch")
        source = (PROJECT / "core/src/main/resources/config.yml").read_text() if fresh else config.read_text()
        if not re.search(r"(?m)^api-key:.*$", source):  # SAFETY-GUARD
            raise ValueError("Existing config lacks api-key; edit it privately before retrying")
        source = re.sub(r"(?m)^api-key:.*$", lambda _: "api-key: " + json.dumps(key), source)
        if fresh:
            owners = [a["name"] if a["platform"] == "java" else "." + a["name"].replace(" ", "_") for a in accounts]
            source = re.sub(r"(?m)^owners:.*$", "owners: " + json.dumps(owners), source)
        write_preserving(PROJECT, config.relative_to(PROJECT), source.encode())
    else:
        config.chmod(0o600)
    properties = confined(PROJECT, "run/server/server.properties")
    if not properties.exists():
        settings = {"server-port": "25565", "online-mode": "true", "enforce-secure-profile": "false",
                    "white-list": "true", "enforce-whitelist": "true", "gamemode": "survival",
                    "difficulty": "normal", "view-distance": "8", "simulation-distance": "6",
                    "level-name": "world", "motd": "Claude's shared world", "enable-rcon": "true",
                    "rcon.port": "25575", "rcon.password": secrets.token_urlsafe(32),
                    "broadcast-rcon-to-ops": "false", "spawn-protection": "0"}
        write_preserving(PROJECT, properties.relative_to(PROJECT),
                         "".join(f"{k}={v}\n" for k, v in settings.items()).encode())
    else:
        properties.chmod(0o600)
        values = dict(line.split("=", 1) for line in properties.read_text().splitlines() if "=" in line and not line.startswith("#"))
        required = {"online-mode": "true", "enforce-secure-profile": "false", "white-list": "true",
                    "enforce-whitelist": "true", "enable-rcon": "true", "rcon.port": "25575", "server-port": "25565"}
        if any(values.get(k) != v for k, v in required.items()) or not values.get("rcon.password"):  # SAFETY-GUARD
            raise RuntimeError("Existing server.properties differs from safe cross-play settings; review it privately")
    geyser = confined(PROJECT, "run/server/plugins/Geyser-Spigot/config.yml")
    if not geyser.exists():
        # Pinned Geyser 2.11.3 uses configuration version 9 and the java section.
        # Plugin integration loads Floodgate's key directly; no separate key copy.
        write_preserving(PROJECT, geyser.relative_to(PROJECT), b"config-version: 9\nbedrock:\n  address: 0.0.0.0\n  port: 19132\n  clone-remote-port: false\njava:\n  auth-type: floodgate\n")
    else:
        text = geyser.read_text()
        if not re.search(r"(?m)^\s+auth-type:\s*floodgate\s*$", text) or not re.search(r"(?m)^\s+port:\s*19132\s*$", text):  # SAFETY-GUARD
            raise RuntimeError("Existing Geyser config lacks Floodgate authentication or Bedrock port 19132; review it privately")
    eula = confined(PROJECT, "run/server/eula.txt")
    if not eula.exists() or "eula=true" not in eula.read_text().splitlines():
        if not yes("Accept https://www.minecraft.net/eula to start this Minecraft server?"):
            raise RuntimeError("EULA not accepted; nothing started")
        write_preserving(PROJECT, "run/server/eula.txt", b"eula=true\n")


def check_port(port, protocol=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, protocol) as probe:
        try:
            probe.bind(("0.0.0.0", port))
        except OSError:
            raise RuntimeError(f"Port {port} is occupied. Stop the known conflicting service yourself; no processes were killed.") from None


def join_details():
    address = "this PC's LAN address"
    try:
        # UDP connect selects a route without sending any packet or making a lookup.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect(("192.0.2.1", 9))
            candidate = route.getsockname()[0]
            if not candidate.startswith("127."):
                address = candidate
    except OSError:
        pass
    print(f"Join on your home network: Java {address}:25565; Bedrock address {address}, port 19132.")


def launch_panel(env):
    import urllib.error
    try:
        check_port(8766)
    except RuntimeError:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8766/", timeout=3) as response:
                body = response.read(65536).decode()
                expected = (PROJECT / "panel/static/index.html").read_text()
                if response.status == 200 and body == expected:
                    with urllib.request.urlopen("http://127.0.0.1:8766/api/session", timeout=3) as session:
                        data = json.loads(session.read(4096))
                        if isinstance(data.get("csrf"), str) and len(data["csrf"]) > 20:
                            return
        except (OSError, UnicodeError):
            pass
        raise RuntimeError("Panel port 8766 is occupied by an unverified process; it was left running") from None
    log = confined(PROJECT, "run/control-panel/panel.log")
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("ab") as output:
        log.chmod(0o600)
        process = subprocess.Popen([sys.executable, str(PROJECT / "panel/server.py"), "--port", "8766",
                                    "--project-root", str(PROJECT)], cwd=PROJECT, env=env,
                                   stdin=subprocess.DEVNULL, stdout=output, stderr=output,
                                   start_new_session=True)
    write_preserving(PROJECT, "run/control-panel/panel.pid", str(process.pid).encode())
    time.sleep(0.5)
    if process.poll() is not None:
        raise RuntimeError("Panel could not start; review run/control-panel/panel.log")


def main():
    os.umask(0o077)
    if not sys.stdin.isatty():
        raise RuntimeError("Run this helper in your normal interactive desktop terminal")
    if not shutil.which("docker"):
        raise RuntimeError("Install Docker Engine and Compose on this desktop, then rerun")
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    command(["docker", "compose", "version"])
    env = dict(os.environ, CLAUDE_UID=str(os.getuid()), CLAUDE_GID=str(os.getgid()))
    env.pop("ANTHROPIC_API_KEY", None)  # Use the private plugin file; don't propagate a shell key to the panel.
    inspect = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", CONTAINER],
                             capture_output=True, text=True, check=False, timeout=15)
    if inspect.returncode == 0:
        labels = json.loads(command(["docker", "inspect", "--format", "{{json .Config.Labels}}", CONTAINER]) or "null")
        if not isinstance(labels, dict) or labels.get("com.docker.compose.project") != "claudecontrol" or labels.get("com.docker.compose.service") != "minecraft":  # SAFETY-GUARD
            raise RuntimeError("The fixed container name belongs to another setup; it was left untouched")
    if inspect.returncode == 0 and inspect.stdout.strip() == "true":
        print("Minecraft is already running; reusing it without modifying live configuration.")
        launch_panel(env)
        print("Control panel: http://127.0.0.1:8766")
        join_details()
        return
    for port, protocol in ((25565, socket.SOCK_STREAM), (25575, socket.SOCK_STREAM), (19132, socket.SOCK_DGRAM)):
        check_port(port, protocol)
    gradle = PROJECT / "gradlew"
    if gradle.is_file():
        build_command = [str(gradle), ":core:build"]
    else:
        gradle = Path("/home/lucas/.cache/gradle-home/wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0/bin/gradle")
        if not gradle.is_file():
            raise RuntimeError("Gradle 9.8.0 or the project's verified wrapper is required")
        build_command = [str(gradle), ":core:build"]
    env["GRADLE_USER_HOME"] = str(Path.home() / ".cache/gradle-home")
    command(build_command, env=env, timeout=600, capture=False)
    versions = json.loads((PROJECT / "server/versions.json").read_text())
    for kind, asset in versions.items():
        destination = confined(PROJECT, Path("run/downloads") / asset["filename"])
        download_verified(asset["url"], destination, asset["sha256"])
        target = "run/server/server.jar" if kind == "paper" else "run/server/plugins/" + ("Geyser-Spigot.jar" if kind == "geyser" else "floodgate-spigot.jar")
        write_preserving(PROJECT, target, destination.read_bytes())
    write_preserving(PROJECT, "run/server/plugins/ClaudeControl.jar",
                     confined(PROJECT, "core/build/libs/ClaudeControl.jar").read_bytes())
    accounts = player_accounts()
    configure(accounts)
    print("Starting your desktop survival world: Java TCP 25565, Bedrock UDP 19132. Claude requests use your paid API key; automatic events stay off.")
    command(["docker", "compose", "-p", "claudecontrol", "-f", str(PROJECT / "server/compose.yaml"),
             "up", "-d", "--build", "minecraft"], env=env, timeout=600)
    sys.path.insert(0, str(PROJECT / "panel"))
    from docker_control import DockerController, ControlError
    controller = DockerController(PROJECT)
    deadline = time.monotonic() + 180
    ready = False
    while time.monotonic() < deadline:
        try:
            if 'Done (' in controller.logs():
                ready = True
                break
            if not controller.status()["running"]:
                break
        except ControlError:
            pass
        time.sleep(3)
    launch_panel(env)
    print("Control panel: http://127.0.0.1:8766")
    if not ready:
        print("Startup is not confirmed. Watch the panel; whitelist players manually after the Done message.")
        return
    for account in accounts:
        name = account["name"]
        request = "whitelist add " + name if account["platform"] == "java" else "fwhitelist add " + json.dumps(name)
        try:
            output = controller.command(request)
            print(f"Whitelist response for {name}: {output}")
        except ControlError as error:
            print(f"Whitelist not confirmed for {name}: {error}. Use the panel to retry.")
    print("Minecraft reports ready. Router forwarding is only needed outside your home network.")
    join_details()


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, KeyboardInterrupt):
        # Avoid exception values: parser/subprocess messages can contain private data.
        print("Setup stopped safely. Existing files and failed download staging were preserved. Check prerequisites and local configuration; no data was deleted.", file=sys.stderr)
        raise SystemExit(1)
