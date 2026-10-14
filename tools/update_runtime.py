#!/usr/bin/env python3
"""Apply a compatible ClaudeControl runtime jar without restarting Minecraft.

Runs only when invoked explicitly. Preserves replaced bytes; no deletion or Docker
restart is performed. The existing controller verifies container identity and RCON.
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import re
from pathlib import Path
import sys
import time
import zipfile

PROJECT = Path(__file__).absolute().parents[1]
_spec = importlib.util.spec_from_file_location("_runtime_upgrade", PROJECT / "tools/start_26_3.py")
upgrade = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(upgrade)
PREFIX = "com/lucascosolo/claudecontrol/core/"
MAX_BYTES = 64 * 1024 * 1024


def stable_entry(name):
    return (name == "plugin.yml" or name.startswith(PREFIX + "bootstrap/")
            or name == PREFIX + "ClaudeControlPlugin.class"
            or name.startswith(PREFIX + "ClaudeControlPlugin$"))


def validate(data):
    if len(data) > MAX_BYTES:  # SAFETY-GUARD
        raise ValueError("Plugin jar exceeds runtime limit")
    stable, names, expanded = {}, set(), 0
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for entry in jar.infolist():
            name = entry.filename
            if (name in names or name.startswith("/") or ".." in name or "\\" in name):  # SAFETY-GUARD
                raise ValueError("Invalid plugin jar entry")
            names.add(name)
            expanded += entry.file_size
            if expanded > MAX_BYTES:  # SAFETY-GUARD
                raise ValueError("Expanded plugin jar exceeds runtime limit")
            if not entry.is_dir():
                contents = jar.read(entry)  # Also checks entry CRCs before installation.
                if stable_entry(name):
                    stable[name] = re.sub(rb"(?m)^version:[^\r\n]*(?:\r?\n|$)", b"", contents) if name == "plugin.yml" else contents
    required = {PREFIX + "ClaudeRuntime.class", PREFIX + "ClaudeControlPlugin.class",
                PREFIX + "bootstrap/RuntimeLifecycle.class"}
    if not required <= names:  # SAFETY-GUARD
        raise ValueError("Initial bootstrap update requires the normal stopped-server updater")
    return stable


def install_runtime(root, controller, source=None, *, wait=time.sleep, attempts=60):
    root = Path(root).absolute()
    source = upgrade.confined(root, source or "core/build/libs/ClaudeControl.jar")
    installed = upgrade.confined(root, "run/server/plugins/ClaudeControl.jar")
    next_data = upgrade.read_regular(source)
    old_data = upgrade.read_regular(installed)
    if validate(next_data) != validate(old_data):  # SAFETY-GUARD
        raise ValueError("Bootstrap changed: apply this update with the stopped-server updater")
    before = controller.command("claude runtime-status").strip()
    if not before.startswith("Runtime: ") or "; sha256 none" in before:  # SAFETY-GUARD
        raise RuntimeError("A running compatible ClaudeControl bootstrap is required")
    upgrade.write_preserving(root, "run/server/plugins/ClaudeControl.jar", next_data)
    controller.command("claude runtime-reload")
    expected = hashlib.sha256(next_data).hexdigest()
    for _ in range(attempts):
        wait(0.5)
        status = controller.command("claude runtime-status").strip()
        if status.startswith("Runtime: reloaded;") and status.endswith(expected):
            return expected
        if status.startswith(("Runtime: refused;", "Runtime: rolled back;", "Runtime: halted;")):
            raise RuntimeError("Runtime update refused or rolled back; all installed and archived bytes preserved")
    raise RuntimeError("Runtime update outcome was not confirmed; inspect /claude runtime-status before retrying")


def main():
    sys.path.insert(0, str(PROJECT / "panel"))
    from docker_control import DockerController, ControlError
    try:
        digest = install_runtime(PROJECT, DockerController(PROJECT))
        print("Runtime update confirmed; Minecraft continued running. SHA-256 " + digest)
        return 0
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, ControlError):
        # Never expose private configuration, API keys or RCON credentials.
        print("Runtime update was not applied or confirmed. Existing bytes were preserved; check /claude runtime-status. Bootstrap changes require the stopped-server updater.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
