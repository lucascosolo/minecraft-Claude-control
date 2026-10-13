"""Upgrade preservation contracts; fixtures remain inside ignored project scratch."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
SCRIPT = PROJECT / "tools/start_26_3.py"
OFFICIAL = "https://fill-data.papermc.io/v1/objects/fixture/paper.jar"


def jar_bytes(version):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("version.json", json.dumps({"id": version}))
    return stream.getvalue()


class Response(io.BytesIO):
    def __init__(self, body, url=OFFICIAL):
        super().__init__(body)
        self.url = url

    def geturl(self):
        return self.url


class UpgradeTest(unittest.TestCase):
    def setUp(self):
        base = PROJECT / "tools/build/test-scratch"
        base.mkdir(parents=True, exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="upgrade-26.3-", dir=base))
        self.root = self.fixture / "project"
        self.server = self.root / "run/server"
        self.server.mkdir(parents=True)
        spec = importlib.util.spec_from_file_location("_tested_upgrade_26_3", SCRIPT)
        self.helper = importlib.util.module_from_spec(spec)
        with patch("subprocess.run", side_effect=AssertionError("Import launched a command")), \
             patch("urllib.request.build_opener", side_effect=AssertionError("Import contacted network")), \
             patch("getpass.getpass", side_effect=AssertionError("Import asked for a key")), \
             patch("builtins.input", side_effect=AssertionError("Import prompted")):
            spec.loader.exec_module(self.helper)
        self.controller = Mock()
        self.controller.status.return_value = {"running": False, "state": "stopped"}

    def put(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def snapshot(self, directory):
        return {str(p.relative_to(directory)): p.read_bytes()
                for p in directory.rglob("*") if p.is_file() and not p.is_symlink()}

    def staged(self):
        assets, bodies = {}, {}
        for kind in self.helper.TARGETS:
            data = jar_bytes("26.3") if kind == "paper" else (kind + "-fixture").encode()
            filename = kind + ".jar"
            self.put("run/downloads/" + filename, data)
            assets[kind] = {"filename": filename, "sha256": hashlib.sha256(data).hexdigest()}
            bodies[kind] = data
        plugin = self.put("run/downloads/ClaudeControl.jar", b"rebuilt plugin fixture")
        return assets, bodies, plugin

    def test_full_backup_preserves_world_private_config_and_empty_directories(self):
        self.put("run/server/world/region/r.0.0.mca", bytes(range(256)) * 4)
        self.put("run/server/plugins/ClaudeControl/config.yml", b"api-key: private-api-stub-canary\n")
        self.put("run/server/server.properties", b"rcon.password=private-rcon-stub\n")
        self.put("run/server/whitelist.json", b"[]")
        (self.server / "world/empty").mkdir()
        before = self.snapshot(self.server)
        with patch("builtins.print"):
            backup = self.helper.backup_server(self.root, self.controller)
        self.assertEqual(self.snapshot(backup), before)
        self.assertEqual(self.snapshot(self.server), before)
        self.assertTrue((backup / "world/empty").is_dir())
        self.assertTrue(json.loads((backup.parent / "backup-complete.json").read_bytes())["complete"])
        for path in [backup, backup.parent, *backup.rglob("*")]:
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)

    def test_nested_dangling_root_and_hard_links_refuse_before_backup_copy(self):
        for variant in ("nested", "dangling", "root", "hard"):
            with self.subTest(variant=variant):
                root = self.fixture / variant
                server = root / "run/server"
                server.mkdir(parents=True)
                canary = self.fixture / (variant + "-canary")
                canary.write_bytes(b"preserve-external-fixture")
                (server / "world").mkdir()
                if variant == "root":
                    linked = self.fixture / "linked-project"
                    linked.symlink_to(root, target_is_directory=True)
                    root = linked
                elif variant == "hard":
                    os.link(canary, server / "world/linked")
                else:
                    (server / "world/linked").symlink_to(canary if variant == "nested" else self.fixture / "absent")
                with self.assertRaises(ValueError):
                    self.helper.backup_server(root, self.controller)
                self.assertEqual(canary.read_bytes(), b"preserve-external-fixture")
                self.assertFalse((root / ".archive").exists())

    def test_running_and_uncertain_state_refuse_before_copy_or_install(self):
        self.put("run/server/server.jar", jar_bytes("26.2"))
        assets, _, plugin = self.staged()
        before = self.snapshot(self.root)
        for state in ({"running": True, "state": "running"}, {"running": False, "state": "restarting"}):
            with self.subTest(state=state):
                self.controller.status.return_value = state
                with self.assertRaises(RuntimeError):
                    self.helper.backup_server(self.root, self.controller)
                with self.assertRaises(RuntimeError):
                    self.helper.install_assets(self.root, assets, plugin, self.controller)
                self.assertEqual(self.snapshot(self.root), before)
                self.assertFalse((self.root / ".archive").exists())

    def test_container_starting_during_backup_refuses_before_copy(self):
        self.put("run/server/world/level.dat", b"world canary")
        self.controller.status.side_effect = [
            {"running": False, "state": "stopped"}, {"running": True, "state": "running"}]
        with self.assertRaises(RuntimeError):
            self.helper.backup_server(self.root, self.controller)
        self.assertEqual(self.snapshot(self.server), {"world/level.dat": b"world canary"})
        self.assertFalse(any(p.is_file() for p in (self.root / ".archive").rglob("*")))

    def test_source_versions_accept_26_2_and_26_3_but_refuse_newer(self):
        for version in ("26.2", "26.3", "26.4"):
            root = self.fixture / ("version-" + version)
            (root / "run/server").mkdir(parents=True)
            path = root / "run/server/server.jar"
            data = jar_bytes(version)
            path.write_bytes(data)
            if version == "26.4":
                with self.assertRaises(RuntimeError):
                    self.helper.check_version(root)
            else:
                self.assertEqual(self.helper.check_version(root), version)
            self.assertEqual(path.read_bytes(), data)

    def test_migration_marker_refuses_downgrade_and_accepts_matching_26_3(self):
        self.put("run/server/.managed-version.json", b'{"minecraft":"26.3"}')
        self.put("run/server/server.jar", jar_bytes("26.2"))
        with self.assertRaises(RuntimeError):
            self.helper.check_version(self.root)
        # Each input belongs to a separate retained fixture; no world is replaced.
        root = self.fixture / "migrated"
        (root / "run/server").mkdir(parents=True)
        (root / "run/server/server.jar").write_bytes(jar_bytes("26.3"))
        (root / "run/server/.managed-version.json").write_bytes(b'{"minecraft":"26.3"}')
        self.assertEqual(self.helper.check_version(root), "26.3")

    def test_successful_install_preserves_prior_jars_and_private_world_bytes(self):
        old = jar_bytes("26.2")
        self.put("run/server/server.jar", old)
        self.put("run/server/plugins/Geyser-Spigot.jar", b"old geyser")
        world = self.put("run/server/world/level.dat", b"world fixture")
        config = self.put("run/server/plugins/ClaudeControl/config.yml", b"api-key: private-api-stub-canary")
        assets, bodies, plugin = self.staged()
        self.helper.install_assets(self.root, assets, plugin, self.controller)
        for kind, target in self.helper.TARGETS.items():
            self.assertEqual((self.root / target).read_bytes(), bodies[kind])
        archived = list(self.snapshot(self.root / ".archive").values())
        self.assertIn(old, archived)
        self.assertIn(b"old geyser", archived)
        self.assertEqual(world.read_bytes(), b"world fixture")
        self.assertEqual(config.read_bytes(), b"api-key: private-api-stub-canary")
        self.assertEqual((self.server / "plugins/ClaudeControl.jar").read_bytes(), plugin.read_bytes())
        self.assertEqual(self.helper.check_version(self.root), "26.3")

    def test_first_and_late_staged_hash_mismatch_preserve_all_installed_assets(self):
        for kind, target in self.helper.TARGETS.items():
            old = jar_bytes("26.2") if kind == "paper" else ("old " + kind).encode()
            self.put(target, old)
        assets, _, plugin = self.staged()
        before = self.snapshot(self.server)
        for mismatched in ("paper", "viabackwards"):
            with self.subTest(mismatched=mismatched):
                invalid = {kind: dict(asset) for kind, asset in assets.items()}
                invalid[mismatched]["sha256"] = hashlib.sha256(b"different expected asset").hexdigest()
                with self.assertRaises(ValueError):
                    self.helper.install_assets(self.root, invalid, plugin, self.controller)
                self.assertEqual(self.snapshot(self.server), before)
                self.assertFalse((self.server / "plugins/ClaudeControl.jar").exists())
                self.assertFalse((self.root / ".archive").exists())

    def test_download_verifies_exact_bytes_and_reuses_matching_asset_without_network(self):
        data = b"verified downloadable fixture"
        destination = self.root / "run/downloads/paper.jar"
        digest = hashlib.sha256(data).hexdigest()
        opener = Mock(side_effect=lambda *args, **kwargs: Response(data))
        self.helper.download_verified(OFFICIAL, destination, digest, opener=opener)
        self.assertEqual(destination.read_bytes(), data)
        self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
        no_network = Mock(side_effect=AssertionError("Existing asset opened network"))
        self.helper.download_verified(OFFICIAL, destination, digest, opener=no_network)
        self.assertEqual(opener.call_count, 1)
        no_network.assert_not_called()

    def test_download_hash_mismatch_never_installs_and_retains_staging(self):
        destination = self.root / "run/downloads/paper.jar"
        data = b"unverified bytes"
        with self.assertRaises(ValueError):
            self.helper.download_verified(OFFICIAL, destination, hashlib.sha256(b"expected").hexdigest(),
                                          opener=lambda *args, **kwargs: Response(data))
        self.assertFalse(destination.exists())
        self.assertIn(data, self.snapshot(destination.parent).values())

    def test_external_initial_urls_and_redirects_refuse_without_install(self):
        destination = self.root / "run/downloads/paper.jar"
        opener = Mock(side_effect=AssertionError("Untrusted host contacted"))
        for url in ("http://fill-data.papermc.io/paper.jar", "https://untrusted.invalid/a.jar",
                    "https://fill-data.papermc.io.evil.invalid/a.jar", "https://user@fill-data.papermc.io/a.jar"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.helper.download_verified(url, destination, hashlib.sha256(b"fixture").hexdigest(), opener=opener)
        opener.assert_not_called()
        request = urllib.request.Request(OFFICIAL)
        with self.assertRaises(ValueError):
            self.helper.OfficialRedirect().redirect_request(request, None, 302, "redirect", {}, "https://untrusted.invalid/a.jar")
        with self.assertRaises(ValueError):
            self.helper.download_verified(OFFICIAL, destination, hashlib.sha256(b"fixture").hexdigest(),
                                          opener=lambda *args, **kwargs: Response(b"fixture", "https://untrusted.invalid/a.jar"))
        self.assertFalse(destination.exists())

    def test_existing_different_download_and_hardlink_destination_preserve_canary(self):
        canary = self.put("canary.jar", b"preserve")
        destination = self.put("run/downloads/existing.jar", b"old asset")
        linked = self.root / "run/downloads/hardlinked.jar"
        os.link(canary, linked)
        opener = Mock(side_effect=AssertionError("Refused asset contacted network"))
        for path in (destination, linked):
            with self.subTest(path=path.name), self.assertRaises(ValueError):
                self.helper.download_verified(OFFICIAL, path, hashlib.sha256(b"new asset").hexdigest(), opener=opener)
        opener.assert_not_called()
        self.assertEqual(destination.read_bytes(), b"old asset")
        self.assertEqual(canary.read_bytes(), b"preserve")

    def test_pinned_manifest_has_required_assets_and_exact_paper_target(self):
        assets = self.helper.load_manifest(PROJECT)
        self.assertEqual(set(assets), {"paper", "geyser", "floodgate", "viaversion", "viabackwards"})
        self.assertEqual((assets["paper"]["version"], assets["paper"]["build"]), ("26.3", 181))


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
        path = PROJECT / "tools/build/test-results/upgrade.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", name="Upgrade26.3", tests=str(self.testsRun),
                           failures=str(len(self.failures)), errors=str(len(self.errors)))
        suite.extend(self.cases)
        ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult).run(suite)
    result.write_report()
    raise SystemExit(0 if result.wasSuccessful() else 1)
