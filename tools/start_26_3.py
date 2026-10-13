#!/usr/bin/env python3
"""Desktop-only, archival 26.3 upgrade/start. Importing launches nothing."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import urllib.parse
import urllib.request
import uuid
import zipfile

PROJECT = Path(__file__).absolute().parents[1]
_spec = importlib.util.spec_from_file_location("_upgrade_desktop", PROJECT / "tools/start_desktop.py")
desktop = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(desktop)
confined = desktop.confined
write_preserving = desktop.write_preserving
command = desktop.command
OFFICIAL_HOSTS = frozenset({"fill-data.papermc.io", "download.geysermc.org",
                            "github.com", "release-assets.githubusercontent.com"})
TARGETS = {
    "paper": "run/server/server.jar",
    "geyser": "run/server/plugins/Geyser-Spigot.jar",
    "floodgate": "run/server/plugins/floodgate-spigot.jar",
    "viaversion": "run/server/plugins/ViaVersion.jar",
    "viabackwards": "run/server/plugins/ViaBackwards.jar",
}


def official_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in OFFICIAL_HOSTS
            or parsed.username or parsed.password or parsed.port not in (None, 443)):  # SAFETY-GUARD
        raise ValueError("Download requires an approved official HTTPS host")


class OfficialRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        official_url(newurl)  # SAFETY-GUARD: validate every redirect before connecting.
        return super().redirect_request(request, response, code, message, headers, newurl)


def regular_file(path):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):  # SAFETY-GUARD
        raise ValueError("Symlink paths are refused")
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:  # SAFETY-GUARD
        raise ValueError("Only ordinary files without hard links are accepted")
    return info


def read_regular(path):
    info = regular_file(path)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        current = os.fstat(source.fileno())
        if (not stat.S_ISREG(current.st_mode) or current.st_nlink != 1
                or (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino)):  # SAFETY-GUARD
            raise ValueError("File changed during validation")
        return source.read()


def download_verified(url, destination, sha256, opener=None):
    """Keep failed staging; constrain initial URL and every redirect; check digest."""
    official_url(url)
    if not isinstance(sha256, str) or not re.fullmatch(r"[a-f0-9]{64}", sha256):  # SAFETY-GUARD
        raise ValueError("Invalid SHA-256")
    destination = Path(destination).absolute()
    if any(p.is_symlink() for p in (destination, *destination.parents)):  # SAFETY-GUARD
        raise ValueError("Download paths cannot contain symlinks")
    if destination.exists():
        if hashlib.sha256(read_regular(destination)).hexdigest() != sha256:  # SAFETY-GUARD
            raise ValueError("Existing download differs; preserve it and review before retrying")
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    if any(p.is_symlink() for p in (destination, *destination.parents)):  # SAFETY-GUARD
        raise ValueError("Download path changed")
    staging = destination.with_name(destination.name + ".staging-" + uuid.uuid4().hex)
    if opener is None:
        client = urllib.request.build_opener(OfficialRedirect())
        client.addheaders = [("User-Agent", "ClaudeControl/26.3 (desktop Minecraft starter)")]
        opener = client.open
    digest = hashlib.sha256()
    with opener(url, timeout=60) as response:
        official_url(response.geturl())
        with staging.open("xb") as output:
            os.chmod(staging, 0o600)
            while block := response.read(1024 * 1024):
                output.write(block)
                digest.update(block)
    if digest.hexdigest() != sha256:  # SAFETY-GUARD
        raise ValueError("Checksum mismatch; staging retained")
    if destination.exists() or destination.is_symlink():  # SAFETY-GUARD
        raise ValueError("Destination appeared; staging retained")
    staging.rename(destination)
    return destination


def load_manifest(root=PROJECT):
    assets = json.loads(read_regular(confined(root, "server/versions-26.3.json")))
    if not isinstance(assets, dict) or set(assets) != set(TARGETS):  # SAFETY-GUARD
        raise ValueError("Expected the five pinned cross-play artifacts")
    for asset in assets.values():
        if not isinstance(asset, dict):  # SAFETY-GUARD
            raise ValueError("Invalid artifact entry")
        name = asset.get("filename", "")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+\.jar", name):  # SAFETY-GUARD
            raise ValueError("Invalid artifact filename")
        if not re.fullmatch(r"[a-f0-9]{64}", asset.get("sha256", "")):  # SAFETY-GUARD
            raise ValueError("Invalid artifact checksum")
        official_url(asset.get("url", ""))
    if assets["paper"].get("version") != "26.3" or assets["paper"].get("build") != 181:  # SAFETY-GUARD
        raise ValueError("This starter only installs pinned Paper 26.3 build 181")
    return assets


def require_stopped(controller):
    state = controller.status()  # Identity, Compose labels and exact sole bind mount checked.
    if state["running"] or state["state"] not in ("not-created", "stopped"):  # SAFETY-GUARD
        raise RuntimeError("Verified Minecraft container must be stopped before changing server files")
    return state


def tree_inventory(root):
    """Validate the entire tree before copying anything, including nested links."""
    root = Path(root).absolute()
    if any(p.is_symlink() for p in (root, *root.parents)) or not root.is_dir():  # SAFETY-GUARD
        raise ValueError("Backup source must be a real directory")
    inventory = []
    def visit(directory):
        for entry in sorted(directory.iterdir()):
            info = entry.lstat()
            if stat.S_ISDIR(info.st_mode):
                inventory.append((entry.relative_to(root), "directory", info))
                visit(entry)
            elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                inventory.append((entry.relative_to(root), "file", info))
            else:  # SAFETY-GUARD
                raise ValueError("Backup refuses symlinks, hard links and special files")
    visit(root)
    return inventory


def check_space(root, inventory, extra_bytes=0):
    required = sum(info.st_size for _, kind, info in inventory if kind == "file") + extra_bytes
    # A copy may need allocation for tiny/empty files as well as its actual data.
    required += len(inventory) * 4096 + 64 * 1024 * 1024
    if shutil.disk_usage(root).free < required:  # SAFETY-GUARD
        raise RuntimeError("Insufficient free disk space for a complete server backup and replacement assets")


def backup_server(root, controller):
    """Copy the complete stopped server into a private project archive, never delete."""
    source = confined(root, "run/server")
    require_stopped(controller)
    inventory = tree_inventory(source)
    check_space(root, inventory)
    archive = confined(root, Path(".archive") / ("upgrade-" + uuid.uuid4().hex) / "server")
    archive.mkdir(parents=True, mode=0o700)
    archive.parent.chmod(0o700)
    archive.chmod(0o700)
    require_stopped(controller)
    for relative, kind, before in inventory:
        destination = confined(root, archive.relative_to(root) / relative)
        original = confined(root, source.relative_to(root) / relative)
        now = original.lstat()
        if (now.st_dev, now.st_ino, now.st_mode, now.st_size, now.st_mtime_ns) != (
                before.st_dev, before.st_ino, before.st_mode, before.st_size, before.st_mtime_ns):  # SAFETY-GUARD
            raise ValueError("Server tree changed during backup; incomplete archive retained")
        if kind == "directory":
            destination.mkdir(mode=0o700)
        else:
            regular_file(original)
            descriptor = os.open(original, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as input_file, destination.open("xb") as output:
                opened = os.fstat(input_file.fileno())
                if ((opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)
                        or not stat.S_ISREG(opened.st_mode) or opened.st_nlink != 1):  # SAFETY-GUARD
                    raise ValueError("Server file changed; incomplete archive retained")
                os.chmod(destination, 0o600)
                shutil.copyfileobj(input_file, output, 1024 * 1024)
                output.flush()
                os.fsync(output.fileno())
            os.utime(destination, ns=(before.st_atime_ns, before.st_mtime_ns))
    require_stopped(controller)
    after = tree_inventory(source)
    comparable = lambda rows: [(str(p), k, i.st_dev, i.st_ino, i.st_size, i.st_mtime_ns) for p, k, i in rows]
    if comparable(after) != comparable(inventory):  # SAFETY-GUARD
        raise ValueError("Server tree changed; incomplete archive retained")
    write_preserving(root, archive.parent.relative_to(root) / "backup-complete.json",
                     json.dumps({"complete": True, "files": len(inventory), "source": "run/server"}).encode())
    print(f"Complete stopped server backup: {archive}")
    return archive


def check_version(root):
    """Refuse unknown/newer server versions and remember attempted world migration."""
    marker = confined(root, "run/server/.managed-version.json")
    if marker.exists():
        record = json.loads(read_regular(marker))
        if not isinstance(record, dict) or record.get("minecraft") != "26.3":  # SAFETY-GUARD
            raise RuntimeError("World migration marker is unknown or newer; refusing a downgrade")
    jar = confined(root, "run/server/server.jar")
    if not jar.exists():
        if any(confined(root, "run/server").iterdir()):  # SAFETY-GUARD
            raise RuntimeError("Existing server data lacks its versioned jar; review before upgrading")
        return None
    with zipfile.ZipFile(io.BytesIO(read_regular(jar))) as source:
        info = source.getinfo("version.json")
        if info.file_size > 16384:  # SAFETY-GUARD
            raise ValueError("Server version metadata is too large")
        version = json.loads(source.read(info)).get("id")
    if version not in ("26.2", "26.3"):  # SAFETY-GUARD
        raise RuntimeError("Only existing 26.2 or 26.3 worlds can use this starter; refusing a downgrade")
    if marker.exists() and version != "26.3":  # SAFETY-GUARD
        raise RuntimeError("Migration marker and server jar disagree; preserve data and review privately")
    return version


def install_assets(root, assets, plugin, controller):
    require_stopped(controller)
    check_version(root)
    replacements = {}
    for kind, asset in assets.items():
        downloaded = confined(root, Path("run/downloads") / asset["filename"])
        data = read_regular(downloaded)
        if hashlib.sha256(data).hexdigest() != asset["sha256"]:  # SAFETY-GUARD
            raise ValueError("Staged artifact checksum changed")
        replacements[TARGETS[kind]] = data
    replacements["run/server/plugins/ClaudeControl.jar"] = read_regular(plugin)
    replacements["run/server/.managed-version.json"] = b'{"minecraft":"26.3","paper_build":181}\n'
    # Validate every destination and existing parent before replacing the first jar.
    for relative in (*replacements, ".archive"):
        target = confined(root, relative)
        if target.exists():
            if relative == ".archive":
                if not target.is_dir():  # SAFETY-GUARD
                    raise ValueError("Archive destination must be a directory")
            else:
                regular_file(target)
        for parent in target.parents:
            if parent.exists() and not parent.is_dir():  # SAFETY-GUARD
                raise ValueError("Replacement parents must be ordinary directories")
            if parent == Path(root).absolute():
                break
    check_space(root, [], sum(len(data) for data in replacements.values()))
    require_stopped(controller)
    for relative, data in replacements.items():
        require_stopped(controller)
        write_preserving(root, relative, data)


def main():
    os.umask(0o077)
    if not sys.stdin.isatty():  # SAFETY-GUARD
        raise RuntimeError("Run this starter in your normal interactive desktop terminal")
    if not shutil.which("docker"):
        raise RuntimeError("Install Docker Engine and Compose on this desktop, then rerun")
    # No mutations, downloads or build before Docker preconditions pass.
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    command(["docker", "compose", "version"])
    data_root = confined(PROJECT, "run/server")
    data_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    sys.path.insert(0, str(PROJECT / "panel"))
    from docker_control import DockerController, ControlError
    controller = DockerController(PROJECT)
    try:
        state = controller.status()
    except ControlError as error:
        raise RuntimeError(str(error)) from None
    if state["state"] not in ("running", "stopped", "not-created"):  # SAFETY-GUARD
        raise RuntimeError("Minecraft container needs attention; no running files were changed")
    check_version(PROJECT)
    inventory = tree_inventory(data_root)
    assets = load_manifest()
    env = dict(os.environ, CLAUDE_UID=str(os.getuid()), CLAUDE_GID=str(os.getgid()))
    env.pop("ANTHROPIC_API_KEY", None)
    env["GRADLE_USER_HOME"] = str(Path.home() / ".cache/gradle-home")
    gradle = PROJECT / "gradlew"
    if not gradle.is_file():
        gradle = Path("/home/lucas/.cache/gradle-home/wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0/bin/gradle")
    if not gradle.is_file():
        raise RuntimeError("Gradle 9.8.0 or the project's verified wrapper is required")
    print("Preparing verified 26.3 assets while preserving the existing server. No Claude API request is made.")
    command([str(gradle), ":core:build"], env=env, timeout=600, capture=False)
    plugin_source = confined(PROJECT, "core/build/libs/ClaudeControl.jar")
    plugin = write_preserving(PROJECT, "run/downloads/ClaudeControl-26.3.jar", read_regular(plugin_source))
    for asset in assets.values():
        destination = confined(PROJECT, Path("run/downloads") / asset["filename"])
        download_verified(asset["url"], destination, asset["sha256"])
    extra = sum(confined(PROJECT, Path("run/downloads") / a["filename"]).stat().st_size for a in assets.values()) + plugin.stat().st_size
    check_space(PROJECT, inventory, extra)
    # Reject nested links again before stopping, then copy a stable stopped tree.
    tree_inventory(data_root)
    state = controller.status()
    if state["running"] and state["state"] == "running":
        print("Stopping the verified Minecraft container with a 60-second graceful shutdown.")
        controller.stop()
    require_stopped(controller)
    if any(data_root.iterdir()):
        backup_server(PROJECT, controller)
    for port, protocol in ((25565, socket.SOCK_STREAM), (25575, socket.SOCK_STREAM), (19132, socket.SOCK_DGRAM)):
        desktop.check_port(port, protocol)
    install_assets(PROJECT, assets, plugin, controller)
    # The imported helpers retain accounts, key, EULA and whitelist protections.
    accounts = desktop.player_accounts()
    desktop.configure(accounts)
    require_stopped(controller)
    print("Starting Paper 26.3 with Java/Bedrock cross-play. Do not run an older launcher against this world.")
    command(["docker", "compose", "-p", "claudecontrol", "-f", str(PROJECT / "server/compose.yaml"),
             "up", "-d", "--build", "minecraft"], env=env, timeout=600)
    desktop.finish_start(controller, accounts, env, ControlError)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
    except (Exception, KeyboardInterrupt):
        print("Upgrade stopped safely. Existing files, complete/partial archives and staging were retained. Review private configuration and retry; never downgrade a migrated world.", file=sys.stderr)
        raise SystemExit(1)
