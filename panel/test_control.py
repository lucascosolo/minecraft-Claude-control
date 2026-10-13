"""Independent control-panel regressions; all scratch fixtures are retained."""

import http.client
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT))
from panel.docker_control import DockerController
from panel.server import make_server


def scratch():
    base = PROJECT / "panel" / "build" / "test-scratch"
    base.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="control-", dir=base))


class FakeRunner:
    def __init__(self, root, stdout="", returncode=0, absent=False):
        self.calls = []
        self.stdout = stdout
        self.returncode = returncode
        self.absent = absent
        self.inspect_data = {
            "Name": "/claudecontrol-minecraft",
            "Config": {"Labels": {"com.docker.compose.project": "claudecontrol", "com.docker.compose.service": "minecraft"}},
            "Mounts": [{"Type": "bind", "Source": str(root / "run" / "server"), "Destination": "/server", "RW": True}],
            "State": {"Status": "running", "Running": True},
        }

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if not isinstance(args, list) or kwargs.get("shell", False):
            raise AssertionError("Docker invocation must use an argument list without shell")
        if args[:2] == ["docker", "inspect"]:
            if self.absent:
                return subprocess.CompletedProcess(args, 1, "", "Error: No such object: claudecontrol-minecraft")
            return subprocess.CompletedProcess(args, 0, json.dumps(self.inspect_data), "")
        if args[:2] == ["docker", "info"]:
            return subprocess.CompletedProcess(args, 0, '"fake-docker-version"', "")
        if args[:2] == ["docker", "compose"]:
            self.absent = False
        return subprocess.CompletedProcess(args, self.returncode, self.stdout, "")


class FakeRcon:
    """Emulate framed replies while splitting reads to exercise exact-read logic."""
    def __init__(self, *, auth_failure=False, mismatch=False, malformed=False, output="Command completed"):
        self.pending = bytearray()
        self.requests = []
        self.closed = False
        self.auth_failure = auth_failure
        self.mismatch = mismatch
        self.malformed = malformed
        self.output = output

    @staticmethod
    def packet(request_id, packet_type, body):
        payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def sendall(self, packet):
        length = struct.unpack("<i", packet[:4])[0]
        if length != len(packet) - 4:
            raise AssertionError("Invalid request frame length")
        request_id, packet_type = struct.unpack("<ii", packet[4:12])
        body = packet[12:-2].decode("utf-8")
        self.requests.append((request_id, packet_type, body))
        if self.malformed:
            self.pending.extend(struct.pack("<i", 2 ** 30))
        elif packet_type == 3:
            self.pending.extend(self.packet(-1 if self.auth_failure else request_id, 2, ""))
        else:
            self.pending.extend(self.packet(request_id + 100 if self.mismatch else request_id, 0, self.output))

    def recv(self, count):
        result = bytes(self.pending[:min(count, 3)])
        del self.pending[:len(result)]
        return result

    def settimeout(self, timeout):
        self.timeout = timeout

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class DockerControllerTest(unittest.TestCase):
    def setUp(self):
        self.root = scratch()
        server = self.root / "run" / "server"
        server.mkdir(parents=True)
        self.password = "test-rcon-private-value"
        (server / "server.properties").write_text(
            "enable-rcon=true\nrcon.port=25575\nrcon.password=" + self.password + "\n", encoding="utf-8"
        )
        (self.root / "server").mkdir()
        (self.root / "server" / "compose.yaml").write_text("services: {}\n", encoding="utf-8")

    def test_start_and_stop_use_only_fixed_nondestructive_arguments(self):
        runner = FakeRunner(self.root)
        controller = DockerController(self.root, runner=runner)
        self.assertIsInstance(controller.start(), dict)
        self.assertIsInstance(controller.stop(), dict)
        commands = [call[0] for call in runner.calls]
        self.assertIn([
            "docker", "compose", "-p", "claudecontrol", "-f",
            str((self.root / "server" / "compose.yaml").resolve()), "up", "-d", "minecraft",
        ], commands)
        self.assertIn(["docker", "stop", "--time", "60", "claudecontrol-minecraft"], commands)
        stop_calls = [kwargs for command, kwargs in runner.calls if command[:2] == ["docker", "stop"]]
        self.assertTrue(all(kwargs["timeout"] > 60 for kwargs in stop_calls))
        self.assertTrue(all(not any(word in command for word in ("down", "rm", "exec")) for command in commands))

    def test_status_and_logs_use_fake_runner_and_redact_password(self):
        runner = FakeRunner(self.root, stdout="<script>alert('x')</script> rcon.password=" + self.password)
        controller = DockerController(self.root, runner=runner)
        logs = controller.logs()
        self.assertIsInstance(logs, str)
        self.assertIn("<script>alert('x')</script>", logs)
        self.assertNotIn(self.password, logs)
        self.assertIsInstance(controller.status(), dict)
        self.assertTrue(runner.calls)

    def connection(self, fake):
        calls = []
        def factory(address, *args, **kwargs):
            calls.append((address, args, kwargs))
            self.assertEqual(address, ("127.0.0.1", 25575))
            return fake
        return factory, calls

    def test_rcon_authentication_and_fragmented_output(self):
        fake = FakeRcon(output="Town command accepted")
        factory, calls = self.connection(fake)
        controller = DockerController(self.root, runner=FakeRunner(self.root), connection_factory=factory)
        self.assertEqual(controller.command("claude status"), "Town command accepted")
        self.assertEqual(len(calls), 1)
        self.assertEqual(fake.requests[0][1:], (3, self.password))
        self.assertTrue(any(kind == 2 and body == "claude status" for _, kind, body in fake.requests))
        self.assertTrue(fake.closed)

    def test_rcon_echo_never_reveals_password(self):
        fake = FakeRcon(output="echo " + self.password)
        factory, _ = self.connection(fake)
        controller = DockerController(self.root, runner=FakeRunner(self.root), connection_factory=factory)
        self.assertNotIn(self.password, controller.command("say hello"))

    def test_invalid_commands_refuse_before_connection(self):
        calls = []
        def forbidden_connection(*args, **kwargs):
            calls.append((args, kwargs))
            raise OSError("Unexpected connection attempt")
        controller = DockerController(self.root, runner=FakeRunner(self.root), connection_factory=forbidden_connection)
        for command in ("", "  ", "/", " / ", "stop\nstart", "stop\rstart", "say\x00hi", "say\thi", "x" * 1025, "é" * 513):
            with self.subTest(command=repr(command)):
                with self.assertRaises(Exception):
                    controller.command(command)
                self.assertEqual(calls, [], "invalid input must fail before any connection attempt")

    def test_auth_failure_mismatched_ids_and_huge_frames_fail_safely(self):
        for parameters in ({"auth_failure": True}, {"mismatch": True}, {"malformed": True}):
            with self.subTest(parameters=parameters):
                fake = FakeRcon(**parameters)
                factory, _ = self.connection(fake)
                controller = DockerController(self.root, runner=FakeRunner(self.root), connection_factory=factory)
                with self.assertRaises(Exception) as caught:
                    controller.command("claude status")
                self.assertNotIn(self.password, str(caught.exception))
                self.assertTrue(fake.closed)

    def assert_no_effects(self, runner, connections):
        self.assertEqual(connections, [], "refusal must precede RCON connection")
        self.assertTrue(all(args[:2] not in (["docker", "compose"], ["docker", "stop"], ["docker", "logs"])
                            for args, _ in runner.calls), "refusal must precede container actions or log reads")

    def test_foreign_container_labels_name_and_bind_mounts_refuse_every_operation(self):
        wrong_source = self.root / "foreign-data"
        wrong_source.mkdir()
        variants = [
            ("project-label", lambda data: data["Config"]["Labels"].update({"com.docker.compose.project": "foreign"})),
            ("service-label", lambda data: data["Config"]["Labels"].update({"com.docker.compose.service": "foreign"})),
            ("name", lambda data: data.update({"Name": "/other-container"})),
            ("source", lambda data: data["Mounts"][0].update({"Source": str(wrong_source)})),
            ("destination", lambda data: data["Mounts"][0].update({"Destination": "/other-data"})),
            ("readonly", lambda data: data["Mounts"][0].update({"RW": False})),
            ("volume", lambda data: data["Mounts"][0].update({"Type": "volume"})),
            ("extra-bind", lambda data: data["Mounts"].append({"Type": "bind", "Source": str(wrong_source), "Destination": "/extra", "RW": True})),
            ("missing-mount", lambda data: data.update({"Mounts": []})),
        ]
        for name, change in variants:
            for operation in ("status", "start", "stop", "logs", "command"):
                with self.subTest(variant=name, operation=operation):
                    runner = FakeRunner(self.root)
                    change(runner.inspect_data)
                    connections = []
                    def factory(*args, **kwargs):
                        connections.append(args)
                        raise OSError("connection must not occur")
                    controller = DockerController(self.root, runner=runner, connection_factory=factory)
                    with self.assertRaises(Exception):
                        getattr(controller, operation)(*( ["claude status"] if operation == "command" else []))
                    self.assert_no_effects(runner, connections)

    def test_absent_container_status_and_start_allowed_but_other_actions_refuse(self):
        runner = FakeRunner(self.root, absent=True)
        controller = DockerController(self.root, runner=runner)
        status = controller.status()
        self.assertEqual(status["state"], "not-created")
        self.assertFalse(status["running"])
        self.assertTrue(controller.start()["running"])
        self.assertTrue(any(args[:2] == ["docker", "compose"] for args, _ in runner.calls))
        for operation in ("stop", "logs", "command"):
            with self.subTest(operation=operation):
                absent = FakeRunner(self.root, absent=True)
                connections = []
                def factory(*args, **kwargs):
                    connections.append(args)
                    raise OSError("connection must not occur")
                controller = DockerController(self.root, runner=absent, connection_factory=factory)
                with self.assertRaises(Exception):
                    getattr(controller, operation)(*( ["claude status"] if operation == "command" else []))
                self.assert_no_effects(absent, connections)

    def test_symlinked_project_or_server_ancestors_refuse_before_effects(self):
        fixture = scratch()
        root_link = fixture / "project-link"
        root_link.symlink_to(self.root, target_is_directory=True)
        parent_link = fixture / "ancestor-link"
        parent_link.symlink_to(self.root.parent, target_is_directory=True)
        run_link_root = fixture / "run-link-project"
        run_link_root.mkdir()
        (run_link_root / "run").symlink_to(self.root / "run", target_is_directory=True)
        server_link_root = fixture / "server-link-project"
        (server_link_root / "run").mkdir(parents=True)
        (server_link_root / "run" / "server").symlink_to(self.root / "run" / "server", target_is_directory=True)
        roots = [root_link, parent_link / self.root.name, run_link_root, server_link_root]
        for candidate in (run_link_root, server_link_root):
            (candidate / "server").mkdir()
            (candidate / "server" / "compose.yaml").write_text("services: {}\n")
        properties = self.root / "run" / "server" / "server.properties"
        before = properties.read_bytes()
        for candidate in roots:
            for operation in ("start", "stop", "logs", "command"):
                with self.subTest(path=candidate, operation=operation):
                    runner = FakeRunner(candidate)
                    connections = []
                    def factory(*args, **kwargs):
                        connections.append(args)
                        raise OSError("connection must not occur")
                    with self.assertRaises(Exception):
                        controller = DockerController(candidate, runner=runner, connection_factory=factory)
                        getattr(controller, operation)(*( ["claude status"] if operation == "command" else []))
                    self.assert_no_effects(runner, connections)
        self.assertEqual(properties.read_bytes(), before)


class FakeController:
    def __init__(self):
        self.actions = []

    def status(self):
        return {"state": "running", "running": True, "message": "Ready"}

    def start(self):
        self.actions.append(("start",))
        return self.status()

    def stop(self):
        self.actions.append(("stop",))
        return {"state": "stopped", "running": False, "message": "Stopped"}

    def logs(self):
        return '<script>alert("unsafe log text")</script>\nordinary log'

    def command(self, command):
        self.actions.append(("command", command))
        return "Accepted " + command


class HttpPanelTest(unittest.TestCase):
    def setUp(self):
        self.controller = FakeController()
        self.server = make_server(self.controller, host="127.0.0.1", port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05})
        self.thread.start()
        self.addCleanup(self.close_server)
        self.port = self.server.server_port
        self.host = f"localhost:{self.port}"
        self.origin = "http://" + self.host
        self.cookie = None
        self.token = None

    def close_server(self):
        try:
            self.server.shutdown()
        finally:
            self.server.server_close()
            self.thread.join(timeout=5)
        self.assertFalse(self.thread.is_alive(), "owned HTTP server thread must stop")

    def request(self, method, path, data=None, *, headers=None, session=False, raw=None):
        supplied = {"Host": self.host}
        if method == "POST":
            supplied.update({"Origin": self.origin, "Content-Type": "application/json"})
        if session:
            supplied.update({"Cookie": self.cookie, "X-CSRF-Token": self.token})
        if headers:
            supplied.update(headers)
        supplied = {key: value for key, value in supplied.items() if value is not None}
        body = raw if raw is not None else (json.dumps(data if data is not None else {}).encode() if method == "POST" else None)
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        try:
            connection.request(method, path, body=body, headers=supplied)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read().decode("utf-8")
        finally:
            connection.close()

    def establish_session(self):
        status, headers, body = self.request("GET", "/api/session")
        self.assertEqual(status, 200, body)
        self.token = json.loads(body)["csrf"]
        self.assertIsInstance(self.token, str)
        self.assertGreaterEqual(len(self.token), 32)
        cookie = headers["Set-Cookie"]
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=Strict", cookie)
        self.assertIn("Path=/", cookie)
        self.cookie = cookie.split(";", 1)[0]
        self.assertTrue(self.cookie.startswith("claudecontrol_session="))

    def test_session_cookie_is_private_and_token_is_reused_for_same_session(self):
        self.establish_session()
        status, headers, body = self.request("GET", "/api/session", session=True)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["csrf"], self.token)
        self.assertNotIn(self.token, self.cookie)
        self.assertEqual(headers["Cache-Control"], "no-store")

    def test_host_and_origin_rejection_prevents_mutation(self):
        self.establish_session()
        for headers in ({"Host": "attacker.example"}, {"Host": self.host + ".attacker.example"},
                        {"Origin": "https://attacker.example"}, {"Origin": "null"}, {"Origin": None}):
            with self.subTest(headers=headers):
                status, _, _ = self.request("POST", "/api/start", session=True, headers=headers)
                self.assertEqual(status, 403)
        self.assertEqual(self.controller.actions, [])

    def test_missing_session_and_missing_or_wrong_csrf_prevent_mutation(self):
        self.establish_session()
        for headers in ({"Cookie": None}, {"X-CSRF-Token": None}, {"X-CSRF-Token": "wrong-token"},
                        {"Cookie": "claudecontrol_session=unknown"}):
            with self.subTest(headers=headers):
                status, _, _ = self.request("POST", "/api/stop", session=True, headers=headers)
                self.assertEqual(status, 403)
        self.assertEqual(self.controller.actions, [])

    def test_authenticated_start_stop_and_command_call_exact_controller_actions(self):
        self.establish_session()
        for path, data in (("/api/start", {}), ("/api/stop", {}),
                           ("/api/command", {"command": "claude ask Dream up a shared city"})):
            status, headers, body = self.request("POST", path, data, session=True)
            self.assertEqual(status, 200, body)
            self.assertTrue(headers["Content-Type"].startswith("application/json"))
        self.assertEqual(self.controller.actions, [
            ("start",), ("stop",), ("command", "claude ask Dream up a shared city"),
        ])

    def test_status_and_logs_require_session_and_logs_remain_json_text(self):
        for path in ("/api/status", "/api/logs"):
            status, _, _ = self.request("GET", path)
            self.assertEqual(status, 403)
        self.establish_session()
        status, _, body = self.request("GET", "/api/status", session=True)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["state"], "running")
        status, headers, body = self.request("GET", "/api/logs", session=True)
        self.assertEqual(status, 200)
        self.assertTrue(headers["Content-Type"].startswith("application/json"))
        self.assertEqual(json.loads(body)["output"], self.controller.logs())
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")

    def test_body_bounds_content_type_and_command_shape_fail_before_actions(self):
        self.establish_session()
        cases = [
            ("/api/start", b"x" * 4097, {}, 413),
            ("/api/start", b"{}", {"Content-Type": "text/plain"}, 415),
            ("/api/start", b"not json", {}, 400),
            ("/api/start", b"[]", {}, 400),
            ("/api/command", b'{"command":42}', {}, 400),
            ("/api/command", b'{"command":"stop","extra":true}', {}, 400),
        ]
        for path, body, headers, expected in cases:
            with self.subTest(path=path, body=body[:40]):
                status, _, _ = self.request("POST", path, session=True, headers=headers, raw=body)
                self.assertEqual(status, expected)
        self.assertEqual(self.controller.actions, [])

    def test_remote_bind_is_rejected_before_any_socket_is_created(self):
        with patch("panel.server.ThreadingHTTPServer", side_effect=AssertionError("Socket construction forbidden")) as factory:
            for host in ("0.0.0.0", "192.0.2.1", "attacker.example"):
                with self.subTest(host=host):
                    with self.assertRaises(ValueError):
                        make_server(self.controller, host=host, port=0)
            factory.assert_not_called()

    def test_frontend_renders_console_output_as_text(self):
        status, headers, page = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        status, _, script = self.request("GET", "/app.js")
        self.assertEqual(status, 200)
        self.assertIn("textContent", script)
        self.assertNotIn("innerHTML", script)


class XmlResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.cases = []

    def record(self, test, outcome=None, detail=None):
        case = ET.Element("testcase", classname=type(test).__name__, name=test.id())
        if outcome:
            ET.SubElement(case, outcome).text = detail
        self.cases.append(case)

    def addSuccess(self, test):
        super().addSuccess(test)
        self.record(test)

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self.record(test, "failure", self._exc_info_to_string(err, test))

    def addError(self, test, err):
        super().addError(test, err)
        self.record(test, "error", self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self.record(test, "skipped", reason)

    def addSubTest(self, test, subtest, err):
        super().addSubTest(test, subtest, err)
        if err is not None:
            kind = "failure" if issubclass(err[0], test.failureException) else "error"
            self.record(subtest, kind, self._exc_info_to_string(err, subtest))

    def write_report(self):
        path = PROJECT / "panel" / "build" / "test-results" / "control.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", name="ControlPanel", tests=str(self.testsRun),
            failures=str(len(self.failures)), errors=str(len(self.errors)), skipped=str(len(self.skipped)))
        suite.extend(self.cases)
        ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult).run(suite)
    result.write_report()
    raise SystemExit(0 if result.wasSuccessful() else 1)
