"""Local-only, dependency-free Minecraft control panel."""
from __future__ import annotations

import argparse
import hmac
import ipaddress
import json
import secrets
import threading
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    from .docker_control import ControlError, DockerController, redact, validate_command
except ImportError:
    from docker_control import ControlError, DockerController, redact, validate_command

STATIC = Path(__file__).resolve().parent / "static"


def make_server(controller, host="127.0.0.1", port=8766):
    if host not in ("127.0.0.1", "localhost"):
        try:
            valid = ipaddress.ip_address(host).is_loopback and ":" not in host
        except ValueError:
            valid = False
        if not valid:
            raise ValueError("The panel must bind to IPv4 loopback only (127.0.0.1).")
    sessions = {}
    session_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, format, *args):
            # Never log URL/query input, request bodies, credentials or raw exceptions.
            return

        def _headers(self, status, length, content_type="application/json", cookie=None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(length))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            self.send_header("Connection", "close")
            if cookie:
                self.send_header("Set-Cookie", cookie)
            self.end_headers()
            self.close_connection = True

        def _json(self, status, data, cookie=None):
            payload = json.dumps(data, ensure_ascii=True).encode("utf-8")
            self._headers(status, len(payload), cookie=cookie)
            self.wfile.write(payload)

        def _host_valid(self):
            # Exact hostname and port comparison defeats DNS rebinding through aliases.
            host_values = self.headers.get_all("Host", [])
            allowed = {f"localhost:{self.server.server_port}",
                       f"127.0.0.1:{self.server.server_port}"}
            if self.server.server_port == 80:
                allowed.update(("localhost", "127.0.0.1"))
            if len(host_values) != 1 or host_values[0] not in allowed:
                self._json(403, {"error": "Open the panel using its localhost address."})
                return False
            origins = self.headers.get_all("Origin", [])
            if len(origins) > 1:
                self._json(403, {"error": "Requests must originate from this local panel."})
                return False
            origin = self.headers.get("Origin")
            if origin is not None and origin != "http://" + host_values[0]:
                self._json(403, {"error": "Requests must originate from this local panel."})
                return False
            return True

        def _session(self):
            try:
                cookie = SimpleCookie(self.headers.get("Cookie", ""))
                session_id = cookie["claudecontrol_session"].value
            except (KeyError, ValueError):
                return None
            with session_lock:
                current = sessions.get(session_id)
                if current and current[1] > time.monotonic():
                    return current[0]
            return None

        def do_GET(self):
            if not self._host_valid():
                return
            if self.path == "/api/session":
                token = self._session()
                cookie = None
                if not token:
                    session_id, token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
                    with session_lock:
                        now = time.monotonic()
                        stale = [key for key, value in sessions.items() if value[1] <= now]
                        for key in stale:
                            sessions.pop(key, None)
                        if len(sessions) >= 128:
                            self._json(503, {"error": "Too many panel sessions. Close old tabs and try again later."})
                            return
                        sessions[session_id] = (token, now + 43200)
                    cookie = "claudecontrol_session=" + session_id + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200"
                self._json(200, {"csrf": token}, cookie=cookie)
                return
            assets = {"/": ("index.html", "text/html; charset=utf-8"),
                      "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                      "/style.css": ("style.css", "text/css; charset=utf-8")}
            if self.path in assets:
                name, content_type = assets[self.path]
                payload = (STATIC / name).read_bytes()
                self._headers(200, len(payload), content_type)
                self.wfile.write(payload)
                return
            if not self._session():
                self._json(403, {"error": "Reload the panel to open a local session."})
                return
            try:
                if self.path == "/api/status":
                    self._json(200, controller.status())
                elif self.path == "/api/logs":
                    self._json(200, {"output": redact(controller.logs())})
                else:
                    self._json(404, {"error": "Unknown endpoint."})
            except ControlError as error:
                self._json(503, {"error": redact(str(error))})
            except Exception:
                self._json(500, {"error": "The panel could not read the server. Check setup and try again."})

        def do_POST(self):
            if not self._host_valid():
                return
            if self.headers.get("Origin") != "http://" + self.headers["Host"]:
                self._json(403, {"error": "Requests must originate from this local panel."})
                return
            token = self._session()
            supplied = self.headers.get("X-CSRF-Token", "")
            if not token or not hmac.compare_digest(token, supplied):
                self._json(403, {"error": "Session expired. Reload the panel and try again."})
                return
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(lengths) != 1:
                self._json(400, {"error": "Send one bounded JSON request."})
                return
            try:
                length = int(lengths[0])
            except ValueError:
                length = -1
            if not 0 <= length <= 4096:
                self._json(413, {"error": "Request exceeds the 4096-byte limit."})
                return
            if self.headers.get_content_type() != "application/json":
                self._json(415, {"error": "Request must use application/json."})
                return
            try:
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError()
            except (ValueError, UnicodeError, OSError):
                self._json(400, {"error": "Request must contain a JSON object."})
                return
            try:
                if self.path == "/api/start":
                    self._json(200, controller.start())
                elif self.path == "/api/stop":
                    self._json(200, controller.stop())
                elif self.path == "/api/command":
                    if set(data) != {"command"} or not isinstance(data["command"], str):
                        self._json(400, {"error": "Supply one command string."})
                        return
                    self._json(200, {"output": redact(controller.command(validate_command(data["command"])))})
                else:
                    self._json(404, {"error": "Unknown endpoint."})
            except ControlError as error:
                self._json(400, {"error": redact(str(error))})
            except Exception:
                self._json(500, {"error": "The server action failed. Check setup and try again."})

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


create_server = make_server


def main():
    parser = argparse.ArgumentParser(description="Local Minecraft control panel")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        server = make_server(DockerController(args.project_root), args.host, args.port)
    except (ValueError, OSError):
        parser.exit(1, "Cannot bind the panel. Use 127.0.0.1 and an available port.\n")
    print(f"Claude Control: http://127.0.0.1:{server.server_port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
