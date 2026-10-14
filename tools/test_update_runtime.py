"""Runtime updater contracts; all scratch fixtures are retained inside this project."""

import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch, call
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).absolute().parents[1]
PREFIX = "com/lucascosolo/claudecontrol/core/"
INSTALLED = "run/server/plugins/ClaudeControl.jar"
SOURCE = "core/build/libs/ClaudeControl.jar"


def entries(runtime=b"old implementation"):
    return {
        PREFIX + "ClaudeRuntime.class": runtime,
        PREFIX + "ClaudeControlPlugin.class": b"stable entrypoint",
        PREFIX + "ClaudeControlPlugin$Helper.class": b"stable helper",
        PREFIX + "bootstrap/RuntimeLifecycle.class": b"stable lifecycle",
        PREFIX + "bootstrap/RuntimeArchive.class": b"stable archive",
    }


def jar_bytes(contents):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as jar:
        for name, data in contents.items():
            jar.writestr(name, data)
    return stream.getvalue()


class RuntimeUpdateTest(unittest.TestCase):
    def setUp(self):
        base = PROJECT / "tools/build/test-scratch"
        base.mkdir(parents=True, exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="runtime-update-", dir=base))
        self.root = self.fixture / "project"
        self.root.mkdir()
        spec = importlib.util.spec_from_file_location("_tested_update_runtime", PROJECT / "tools/update_runtime.py")
        self.helper = importlib.util.module_from_spec(spec)
        with patch("subprocess.run", side_effect=AssertionError("Import executed a process")), \
             patch("urllib.request.build_opener", side_effect=AssertionError("Import contacted network")), \
             patch("builtins.input", side_effect=AssertionError("Import prompted")), \
             patch("getpass.getpass", side_effect=AssertionError("Import requested credentials")):
            spec.loader.exec_module(self.helper)
        self.old = jar_bytes(entries())
        self.new = jar_bytes(entries(b"new implementation"))
        self.installed = self.put(INSTALLED, self.old)
        self.source = self.put(SOURCE, self.new)
        self.world = self.put("run/server/world/region/r.0.0.mca", b"world canary")
        self.config = self.put("run/server/plugins/ClaudeControl/config.yml", b"api-key: private-stub-canary")
        self.properties = self.put("run/server/server.properties", b"rcon.password=private-stub-canary")
        self.controller = Mock(spec=["command"])
        self.wait = Mock()
        self.digest = hashlib.sha256(self.new).hexdigest()
        self.before = "Runtime: active; sha256 " + hashlib.sha256(self.old).hexdigest()

    def put(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def snapshot(self):
        return {str(path.relative_to(self.root)): path.read_bytes()
                for path in self.root.rglob("*") if path.is_file() and not path.is_symlink()}

    def archived(self):
        return [path.read_bytes() for path in (self.root / ".archive").rglob("*") if path.is_file()]

    def success(self):
        self.controller.command.side_effect = [self.before, "Runtime: pending", "Runtime: reloaded; sha256 " + self.digest]

    def install(self, **kwargs):
        source = kwargs.get("source")
        if isinstance(source, Path) and source.is_relative_to(self.root):
            kwargs["source"] = source.relative_to(self.root)
        return self.helper.install_runtime(self.root, self.controller, wait=self.wait, attempts=3, **kwargs)

    def assert_private_files_preserved(self):
        self.assertEqual(self.world.read_bytes(), b"world canary")
        self.assertEqual(self.config.read_bytes(), b"api-key: private-stub-canary")
        self.assertEqual(self.properties.read_bytes(), b"rcon.password=private-stub-canary")

    def test_success_archives_old_bytes_and_confirms_active_digest_without_container_actions(self):
        self.success()
        self.assertEqual(self.install(), self.digest)
        self.assertEqual(self.installed.read_bytes(), self.new)
        self.assertEqual(self.source.read_bytes(), self.new)
        self.assertIn(self.old, self.archived())
        self.assert_private_files_preserved()
        self.assertEqual(self.controller.mock_calls, [call.command("claude runtime-status"),
                         call.command("claude runtime-reload"), call.command("claude runtime-status")])
        self.wait.assert_called_once_with(0.5)

    def test_stable_class_changes_additions_and_removals_refuse_before_rcon_or_copy(self):
        variants = []
        for name in ("ClaudeControlPlugin.class", "ClaudeControlPlugin$Helper.class", "bootstrap/RuntimeArchive.class"):
            changed = entries(b"new")
            changed[PREFIX + name] = b"incompatible"
            variants.append(changed)
            missing = entries(b"new")
            missing.pop(PREFIX + name)
            variants.append(missing)
        added = entries(b"new")
        added[PREFIX + "bootstrap/NewStable.class"] = b"added"
        variants.append(added)
        for index, content in enumerate(variants):
            with self.subTest(variant=index):
                source = self.put("candidate-" + str(index) + ".jar", jar_bytes(content))
                before = self.snapshot()
                with self.assertRaises(ValueError):
                    self.install(source=source)
                self.assertEqual(self.snapshot(), before)
                self.controller.command.assert_not_called()
                self.assertFalse((self.root / ".archive").exists())

    def test_descriptor_version_change_is_compatible_but_main_change_is_refused(self):
        baseline = entries()
        baseline["plugin.yml"] = b"name: ClaudeControl\nversion: 1\nmain: fixture.Bootstrap\n"
        self.installed.write_bytes(jar_bytes(baseline))
        candidate = entries(b"changed implementation")
        candidate["plugin.yml"] = b"name: ClaudeControl\nversion: 2\nmain: fixture.Bootstrap\n"
        self.source.write_bytes(jar_bytes(candidate))
        self.digest = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.success()
        self.assertEqual(self.install(), self.digest)
        candidate["plugin.yml"] = b"name: ClaudeControl\nversion: 3\nmain: fixture.DifferentBootstrap\n"
        incompatible = self.put("changed-descriptor.jar", jar_bytes(candidate))
        before = self.snapshot()
        self.controller.reset_mock()
        with self.assertRaises(ValueError):
            self.install(source=incompatible)
        self.assertEqual(self.snapshot(), before)
        self.controller.command.assert_not_called()

    def test_missing_required_entries_and_malformed_archives_refuse_before_rcon(self):
        for index, required in enumerate(("ClaudeRuntime.class", "ClaudeControlPlugin.class", "bootstrap/RuntimeLifecycle.class")):
            content = entries()
            content.pop(PREFIX + required)
            candidate = self.put("missing-" + str(index) + ".jar", jar_bytes(content))
            with self.subTest(required=required), self.assertRaises(ValueError):
                self.install(source=candidate)
        candidate = self.put("malformed.jar", b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            self.install(source=candidate)
        self.controller.command.assert_not_called()
        self.assertEqual(self.installed.read_bytes(), self.old)

    def test_identity_check_failure_preserves_every_file_before_install(self):
        before = self.snapshot()
        self.controller.command.side_effect = RuntimeError("fixture container identity mismatch")
        with self.assertRaises(RuntimeError):
            self.install()
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(self.controller.mock_calls, [call.command("claude runtime-status")])
        self.assertFalse((self.root / ".archive").exists())

    def test_unavailable_or_uninitialized_runtime_refuses_before_copy(self):
        before = self.snapshot()
        for status in ("Unknown command", "Runtime: active; sha256 none"):
            with self.subTest(status=status):
                self.controller.reset_mock()
                self.controller.command.side_effect = None
                self.controller.command.return_value = status
                with self.assertRaises(RuntimeError):
                    self.install()
                self.assertEqual(self.snapshot(), before)
                self.assertEqual(self.controller.mock_calls, [call.command("claude runtime-status")])

    def test_refusal_rollback_and_halt_preserve_installed_and_archived_bytes(self):
        for status in ("Runtime: refused; review", "Runtime: rolled back; prior retained", "Runtime: halted; error"):
            with self.subTest(status=status):
                self.controller.reset_mock()
                self.controller.command.side_effect = [self.before, "queued", status]
                with self.assertRaisesRegex(RuntimeError, "preserved"):
                    self.install()
                self.assertEqual(self.installed.read_bytes(), self.new)
                self.assertIn(self.old, self.archived())
                self.assert_private_files_preserved()

    def test_pending_and_wrong_digest_never_claim_success(self):
        for status in ("Runtime: pending; sha256 " + self.digest,
                       "Runtime: reloaded; sha256 " + hashlib.sha256(b"wrong runtime").hexdigest()):
            with self.subTest(status=status):
                self.controller.reset_mock()
                self.wait.reset_mock()
                self.controller.command.side_effect = [self.before, "queued", status, status, status]
                with self.assertRaisesRegex(RuntimeError, "not confirmed"):
                    self.install()
                self.assertEqual(self.wait.call_args_list, [call(0.5)] * 3)
                self.assertEqual(self.controller.command.call_args_list,
                                 [call("claude runtime-status"), call("claude runtime-reload")] + [call("claude runtime-status")] * 3)
                self.assertIn(self.old, self.archived())
                self.assert_private_files_preserved()

    def test_source_and_installed_links_and_ancestors_refuse_before_rcon(self):
        source_link = self.root / "source-link.jar"
        source_link.symlink_to(self.source)
        ancestor = self.root / "source-parent-link"
        ancestor.symlink_to(self.source.parent, target_is_directory=True)
        for candidate in (source_link, ancestor / self.source.name):
            with self.subTest(candidate=candidate.name), self.assertRaises(ValueError):
                self.install(source=candidate)
        saved = self.installed.with_name("preserved-installed.jar")
        self.installed.rename(saved)
        self.installed.symlink_to(saved)
        with self.assertRaises(ValueError):
            self.install()
        self.assertEqual(saved.read_bytes(), self.old)
        self.controller.command.assert_not_called()
        self.assertFalse((self.root / ".archive").exists())

    def test_installed_ancestor_root_and_outside_source_refuse_before_rcon(self):
        server = self.root / "run/server"
        saved_server = self.root / "preserved-server"
        server.rename(saved_server)
        server.symlink_to(saved_server, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.install()
        linked_root = self.fixture / "linked-project"
        linked_root.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.helper.install_runtime(linked_root, self.controller, wait=self.wait)
        outside = self.fixture / "outside.jar"
        outside.write_bytes(self.new)
        with self.assertRaises(ValueError):
            self.install(source=outside)
        self.assertEqual((saved_server / "plugins/ClaudeControl.jar").read_bytes(), self.old)
        self.assertEqual(outside.read_bytes(), self.new)
        self.controller.command.assert_not_called()

    def test_unsafe_duplicates_and_expansion_limit_are_rejected(self):
        for name in ("../escape", "/absolute", "nested\\escape"):
            content = entries()
            content[name] = b"canary"
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.helper.validate(jar_bytes(content))
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as jar:
            for name, value in entries().items():
                jar.writestr(name, value)
            with patch("warnings.warn"):
                jar.writestr(PREFIX + "ClaudeRuntime.class", b"duplicate")
        with self.assertRaises(ValueError):
            self.helper.validate(stream.getvalue())
        content = entries()
        content["large.bin"] = bytes(self.helper.MAX_BYTES + 1)
        with self.assertRaises(ValueError):
            self.helper.validate(jar_bytes(content))

    def test_reload_command_failure_preserves_old_and_new_bytes(self):
        self.controller.command.side_effect = [self.before, RuntimeError("fixture transport failure")]
        with self.assertRaises(RuntimeError):
            self.install()
        self.assertEqual(self.installed.read_bytes(), self.new)
        self.assertIn(self.old, self.archived())
        self.assert_private_files_preserved()


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

    def addSubTest(self, test, subtest, err):
        super().addSubTest(test, subtest, err)
        if err is not None:
            self.record(subtest, "failure" if issubclass(err[0], test.failureException) else "error",
                        self._exc_info_to_string(err, subtest))

    def write_report(self):
        path = PROJECT / "tools/build/test-results/update-runtime.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", name="RuntimeUpdate", tests=str(self.testsRun),
                           failures=str(len(self.failures)), errors=str(len(self.errors)))
        suite.extend(self.cases)
        ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult).run(suite)
    result.write_report()
    raise SystemExit(0 if result.wasSuccessful() else 1)
