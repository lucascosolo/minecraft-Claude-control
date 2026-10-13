"""Narrow Docker and loopback RCON interface for the local operator panel."""
from __future__ import annotations

import json
import re
import socket
import struct
import subprocess
import threading
import unicodedata
from pathlib import Path

CONTAINER = "claudecontrol-minecraft"
MAX_OUTPUT = 1_048_576


class ControlError(Exception):
    """A safe, operator-facing error that never contains subprocess output."""


def redact(value: str) -> str:
    value = re.sub(r"sk-ant-[A-Za-z0-9_-]+", "[REDACTED]", value)
    value = re.sub(r"(?i)(bearer\s+)[A-Za-z0-9._-]+", r"\1[REDACTED]", value)
    return re.sub(
        r'''(?i)(["']?\b(?:api[-_]?key|anthropic[-_]?api[-_]?key|rcon(?:[._-]password)?|password|key)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)''',
        r"\1[REDACTED]", value,
    )


def validate_command(command: str) -> str:
    if not isinstance(command, str) or not command.strip():
        raise ControlError("Enter a Minecraft console command.")
    if any(unicodedata.category(char).startswith("C") for char in command):
        raise ControlError("Commands must be a single line without control characters.")
    if len(command.encode("utf-8")) > 1024:
        raise ControlError("Command exceeds the 1024-byte limit.")
    command = command.strip().removeprefix("/").strip()
    if not command:
        raise ControlError("Enter a Minecraft console command.")
    return command


class DockerController:
    def __init__(self, project_root: Path, runner=subprocess.run,
                 connection_factory=socket.create_connection):
        self.project_root = Path(project_root).resolve()
        self.runner = runner
        self.connection_factory = connection_factory
        self.lock = threading.Lock()
        self._secrets: set[str] = set()

    def _run(self, args: list[str], timeout: int = 15):
        try:
            return self.runner(args, capture_output=True, text=True,
                               timeout=timeout, check=False)
        except FileNotFoundError:
            raise ControlError("Docker is not installed or is not on PATH.") from None
        except subprocess.TimeoutExpired:
            raise ControlError("Docker timed out. Check Docker and try again.") from None
        except OSError:
            raise ControlError("Cannot run Docker. Check Docker access and try again.") from None

    def status(self) -> dict:
        result = self._run(["docker", "inspect", "--format", "{{json .State}}", CONTAINER])
        if result.returncode:
            # A second read-only query distinguishes absent containers from a dead daemon.
            probe = self._run(["docker", "info", "--format", "{{json .ServerVersion}}"])
            if probe.returncode:
                return {"state": "offline", "running": False,
                        "message": "Docker is unavailable. Start Docker or check your Docker permissions."}
            return {"state": "not-created", "running": False,
                    "message": "Server container has not been created. Finish server setup, then select Start."}
        try:
            data = json.loads(result.stdout)
            if not isinstance(data, dict):
                raise ValueError()
        except (ValueError, TypeError):
            raise ControlError("Docker returned an unreadable container status.") from None
        raw = data.get("Status", "unknown")
        health = (data.get("Health") or {}).get("Status")
        running = data.get("Running") is True
        state = "running" if running else "stopped"
        if raw in ("restarting", "created") or (running and health == "starting"):
            state = "starting"
        elif raw == "paused":
            state = "paused"
        elif raw in ("removing", "dead"):
            state = "offline"
        message = "Container is running. Watch the console for Minecraft's Done message before joining."
        if state == "starting":
            message = "Minecraft is starting. Watch the console for the Done message."
        elif state == "stopped":
            message = "Server is stopped. Select Start to play."
        elif state == "paused":
            message = "Container is paused. Unpause it with Docker before sending commands."
        elif state == "offline":
            message = "Container needs attention. Check Docker before starting it again."
        return {"state": state, "running": running, "message": message}

    def start(self) -> dict:
        with self.lock:
            compose = self.project_root / "server" / "compose.yaml"
            if not compose.is_file():
                raise ControlError("Server setup is missing server/compose.yaml. Run the setup script first.")
            result = self._run(["docker", "compose", "-p", "claudecontrol", "-f",
                                str(compose), "up", "-d", "minecraft"], timeout=180)
            if result.returncode:
                raise ControlError("Docker could not start Minecraft. Check server setup and Docker access.")
            return self.status()

    def stop(self) -> dict:
        with self.lock:
            result = self._run(["docker", "stop", "--time", "30", CONTAINER], timeout=45)
            if result.returncode:
                raise ControlError("Docker could not stop Minecraft. Check the container status and try again.")
            return self.status()

    def safe_output(self, output: str) -> str:
        try:
            self._password()
        except ControlError:
            pass
        for secret in tuple(self._secrets):
            output = output.replace(secret, "[REDACTED]")
        return redact(output)

    def logs(self) -> str:
        result = self._run(["docker", "logs", "--tail", "200", "--timestamps", CONTAINER])
        if result.returncode:
            raise ControlError("Console output is unavailable. Start the server or check Docker access.")
        # Docker sends application stderr separately; neither Docker error messages nor raw
        # subprocess exceptions are surfaced to the browser.
        return self.safe_output((result.stdout + result.stderr)[-MAX_OUTPUT:])

    def _password(self) -> str:
        run_root = self.project_root / "run" / "server"
        properties = run_root / "server.properties"
        if (run_root.resolve() != run_root or properties.resolve().parent != run_root):
            raise ControlError("Server configuration path is invalid.")
        try:
            with properties.open(encoding="utf-8") as source:
                text = source.read(65537)
        except (OSError, UnicodeError):
            raise ControlError("RCON configuration is missing. Finish server setup first.") from None
        if len(text) > 65536:
            raise ControlError("Server configuration is too large to read safely.")
        settings = {}
        for line in text.splitlines():
            if "=" in line and not line.lstrip().startswith(("#", "!")):
                key, value = line.split("=", 1)
                settings[key.strip()] = value.strip()
        password = settings.get("rcon.password", "")
        if settings.get("enable-rcon", "").lower() != "true" or not password:
            raise ControlError("RCON is disabled or has no password. Run the server setup script.")
        if len(password.encode("utf-8")) > 1024 or "\x00" in password:
            raise ControlError("RCON password format is invalid.")
        self._secrets.add(password)
        return password

    @staticmethod
    def _send(connection, request_id: int, kind: int, text: str):
        payload = struct.pack("<ii", request_id, kind) + text.encode("utf-8") + b"\0\0"
        connection.sendall(struct.pack("<i", len(payload)) + payload)

    @staticmethod
    def _read(connection):
        def exact(size):
            data = bytearray()
            while len(data) < size:
                chunk = connection.recv(size - len(data))
                if not chunk:
                    raise ControlError("Minecraft closed the RCON connection. Try again when startup completes.")
                data.extend(chunk)
            return bytes(data)
        length = struct.unpack("<i", exact(4))[0]
        if not 10 <= length <= 65536:
            raise ControlError("Minecraft returned an invalid RCON packet.")
        payload = exact(length)
        if payload[-2:] != b"\0\0":
            raise ControlError("Minecraft returned an invalid RCON packet.")
        request_id, kind = struct.unpack("<ii", payload[:8])
        return request_id, kind, payload[8:-2].decode("utf-8", errors="replace")

    def command(self, command: str) -> str:
        command = validate_command(command)
        password = self._password()
        try:
            with self.connection_factory(("127.0.0.1", 25575), timeout=5) as connection:
                connection.settimeout(5)
                self._send(connection, 1, 3, password)
                for _ in range(2):
                    request_id, kind, _ = self._read(connection)
                    if request_id == -1:
                        raise ControlError("RCON authentication failed. Check server setup.")
                    if request_id != 1 or kind not in (0, 2):
                        raise ControlError("Minecraft returned an unexpected RCON authentication response.")
                    if kind == 2:
                        break
                else:
                    raise ControlError("Minecraft did not confirm RCON authentication.")
                self._send(connection, 2, 2, command)
                # TCP ordering plus a second request establishes the end of all command
                # response frames, including responses split by Minecraft into packets.
                self._send(connection, 3, 2, "")
                chunks, size = [], 0
                for _ in range(256):
                    request_id, kind, text = self._read(connection)
                    if kind != 0 or request_id not in (2, 3):
                        raise ControlError("Minecraft returned an unexpected RCON command response.")
                    if request_id == 3:
                        return self.safe_output("".join(chunks))
                    size += len(text.encode("utf-8"))
                    if size > MAX_OUTPUT:
                        raise ControlError("Command output exceeded the safe size limit.")
                    chunks.append(text)
                raise ControlError("Minecraft returned too many RCON response packets.")
        except (OSError, TimeoutError):
            raise ControlError("Cannot reach Minecraft RCON. Wait for startup, then check server setup.") from None
