#!/usr/bin/env python3
"""User-run launcher with verified container identity and saved whitelisting.

Importing this module or its tested setup helpers launches nothing.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid

PROJECT = Path(__file__).absolute().parents[1]
_spec = importlib.util.spec_from_file_location("_desktop_setup_helpers", PROJECT / "tools/start_server.py")
_helpers = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_helpers)
confined = _helpers.confined
write_preserving = _helpers.write_preserving
download_verified = _helpers.download_verified
command = _helpers.command
configure = _helpers.configure
check_port = _helpers.check_port
launch_panel = _helpers.launch_panel
join_details = _helpers.join_details


def account_name(account):
    return account["name"] if account["platform"] == "java" else "." + account["name"].replace(" ", "_")


def validate_accounts(accounts):
    if not isinstance(accounts, list) or len(accounts) != 2:  # SAFETY-GUARD
        raise ValueError("Expected exactly two player accounts")
    for account in accounts:
        if not isinstance(account, dict):  # SAFETY-GUARD
            raise ValueError("Invalid player account")
        platform, name = account.get("platform"), account.get("name")
        pattern = r"[A-Za-z0-9_]{3,16}" if platform == "java" else r"[A-Za-z0-9][A-Za-z0-9 _-]{0,15}"
        if platform not in ("java", "bedrock") or not isinstance(name, str) or not re.fullmatch(pattern, name):  # SAFETY-GUARD
            raise ValueError("Use a valid Java name or Bedrock gamertag of at most 16 characters")
    return accounts


def player_accounts():
    path = confined(PROJECT, "run/player-accounts.json")
    if path.exists():
        accounts = json.loads(path.read_text())
    else:
        accounts = []
        for number in range(1, 3):
            platform = input(f"Player {number} platform (java/bedrock): ").strip().lower()
            name = input(f"Player {number} exact Java name or Xbox gamertag (maximum 16 characters): ").strip()
            accounts.append({"platform": platform, "name": name})
    validate_accounts(accounts)
    write_preserving(PROJECT, "run/player-accounts.json", json.dumps(accounts).encode())
    return accounts


def whitelist_confirmed(accounts, entries):
    """Configured names whose persisted UUID/name pairs identify the right platform."""
    confirmed = set()
    if not isinstance(entries, list):
        return confirmed
    for account in accounts:
        if not isinstance(account, dict) or account.get("platform") not in ("java", "bedrock") or not isinstance(account.get("name"), str):
            continue
        expected = account_name(account)
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("name"), str) or not isinstance(entry.get("uuid"), str):
                continue
            if entry["name"].casefold() != expected.casefold():
                continue
            try:
                identity = uuid.UUID(entry["uuid"])
            except (ValueError, AttributeError):
                continue
            if identity.int == 0:
                continue
            if account["platform"] == "bedrock" and identity.int >> 64 != 0:
                continue
            confirmed.add(expected)
    return confirmed


def wait_whitelist(accounts, timeout=30):
    expected = {account_name(account) for account in accounts}
    deadline = time.monotonic() + timeout
    confirmed = set()
    while True:
        path = confined(PROJECT, "run/server/whitelist.json")
        if path.exists():
            if not path.is_file():  # SAFETY-GUARD
                raise ValueError("Whitelist must be a regular confined file")
            with path.open("rb") as source:
                data = source.read(1_048_577)
            if len(data) > 1_048_576:  # SAFETY-GUARD
                raise ValueError("Whitelist exceeds the bounded file size")
            try:
                confirmed = whitelist_confirmed(accounts, json.loads(data))
            except (ValueError, UnicodeError):
                # A transient partial write grants no access.
                confirmed = set()
        if confirmed == expected or time.monotonic() >= deadline:
            return confirmed
        time.sleep(0.5)


def finish_start(controller, accounts, env, control_error):
    deadline = time.monotonic() + 180
    ready = False
    while time.monotonic() < deadline:
        try:
            if not controller.status()["running"]:
                break
            if "Done (" in controller.logs():
                controller.command("list")
                ready = True
                break
        except control_error:
            pass
        time.sleep(3)
    launch_panel(env)
    print("Control panel: http://127.0.0.1:8766")
    if not ready:
        print("Minecraft readiness is not confirmed. Watch the panel and retry this launcher after the Done message.")
        return
    for account in accounts:
        name = account["name"]
        request = "whitelist add " + name if account["platform"] == "java" else "fwhitelist add " + json.dumps(name)
        try:
            response = controller.command(request)
            print(f"Whitelist command response for {name}: {response}")
        except control_error as error:
            print(f"Whitelist request failed for {name}: {error}")
    confirmed = wait_whitelist(accounts)
    missing = {account_name(account) for account in accounts} - confirmed
    if missing:
        print("Player access is not confirmed for: " + ", ".join(sorted(missing)) + ". Watch the panel, correct the whitelist if needed, and rerun this launcher.")
        return
    print("Minecraft is ready and both saved whitelist identities are confirmed. Router forwarding is only needed outside your home network.")
    join_details()


def main():
    os.umask(0o077)
    if not sys.stdin.isatty():
        raise RuntimeError("Run this launcher in your normal interactive desktop terminal")
    if not shutil.which("docker"):
        raise RuntimeError("Install Docker Engine and Compose on this desktop, then rerun")
    command(["docker", "info", "--format", "{{.ServerVersion}}"])
    command(["docker", "compose", "version"])
    # Controller identity checks require real confined folders before first setup.
    data_root = confined(PROJECT, "run/server")
    data_root.mkdir(parents=True, exist_ok=True)
    confined(PROJECT, "run/server")
    sys.path.insert(0, str(PROJECT / "panel"))
    from docker_control import DockerController, ControlError
    controller = DockerController(PROJECT)
    try:
        state = controller.status()
    except ControlError as error:
        raise RuntimeError(str(error)) from None
    if state["state"] == "offline":
        raise RuntimeError("Docker or the server container needs attention; no container was changed")
    env = dict(os.environ, CLAUDE_UID=str(os.getuid()), CLAUDE_GID=str(os.getgid()))
    env.pop("ANTHROPIC_API_KEY", None)
    if state["running"]:
        print("Minecraft is already running; reusing its verified container without changing live configuration.")
        finish_start(controller, player_accounts(), env, ControlError)
        return
    if state["state"] not in ("not-created", "stopped"):
        raise RuntimeError("Container must be stopped or absent before setup; it was left untouched")
    for port, protocol in ((25565, socket.SOCK_STREAM), (25575, socket.SOCK_STREAM), (19132, socket.SOCK_DGRAM)):
        check_port(port, protocol)
    gradle = PROJECT / "gradlew"
    if not gradle.is_file():
        gradle = Path("/home/lucas/.cache/gradle-home/wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0/bin/gradle")
    if not gradle.is_file():
        raise RuntimeError("Gradle 9.8.0 or the project's verified wrapper is required")
    env["GRADLE_USER_HOME"] = str(Path.home() / ".cache/gradle-home")
    command([str(gradle), ":core:build"], env=env, timeout=600, capture=False)
    versions = json.loads((PROJECT / "server/versions.json").read_text())
    for kind, asset in versions.items():
        destination = confined(PROJECT, Path("run/downloads") / asset["filename"])
        download_verified(asset["url"], destination, asset["sha256"])
        target = "run/server/server.jar" if kind == "paper" else "run/server/plugins/" + ("Geyser-Spigot.jar" if kind == "geyser" else "floodgate-spigot.jar")
        write_preserving(PROJECT, target, destination.read_bytes())
    write_preserving(PROJECT, "run/server/plugins/ClaudeControl.jar", confined(PROJECT, "core/build/libs/ClaudeControl.jar").read_bytes())
    accounts = player_accounts()
    configure(accounts)
    print("Starting your desktop survival world: Java TCP 25565, Bedrock UDP 19132. Claude requests use your paid API key; automatic events stay off.")
    try:
        state = controller.status()
    except ControlError as error:
        raise RuntimeError(str(error)) from None
    if state["running"] or state["state"] not in ("not-created", "stopped"):
        raise RuntimeError("Container state changed during setup; it was left untouched")
    command(["docker", "compose", "-p", "claudecontrol", "-f", str(PROJECT / "server/compose.yaml"),
             "up", "-d", "--build", "minecraft"], env=env, timeout=600)
    finish_start(controller, accounts, env, ControlError)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, subprocess.TimeoutExpired, KeyboardInterrupt):
        print("Setup stopped safely. Existing files and failed staging were preserved. Check prerequisites and private configuration; no data was deleted.", file=sys.stderr)
        raise SystemExit(1)
