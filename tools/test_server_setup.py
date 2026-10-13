"""Startup helper contracts using retained project scratch and mocked downloads."""

import ast
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


PROJECT = Path(__file__).resolve().parents[1]
SCRIPT = PROJECT / "tools" / "start_server.py"
OFFICIAL = "https://fill-data.papermc.io/v1/objects/test/paper.jar"


def import_helpers(script=SCRIPT):
    spec = importlib.util.spec_from_file_location("_tested_" + script.stem, script)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    # Importing helpers must not launch anything, contact a service or ask for a key.
    with patch("subprocess.run", side_effect=AssertionError("Import started subprocess")), \
         patch("urllib.request.urlopen", side_effect=AssertionError("Import opened network")), \
         patch("getpass.getpass", side_effect=AssertionError("Import asked for secret")), \
         patch("builtins.input", side_effect=AssertionError("Import asked for input")):
        spec.loader.exec_module(module)
    return module


class Response(io.BytesIO):
    def __init__(self, body, url=OFFICIAL):
        super().__init__(body)
        self.url = url

    def geturl(self):
        return self.url


class ServerSetupTest(unittest.TestCase):
    def setUp(self):
        base = PROJECT / "tools" / "build" / "test-scratch"
        base.mkdir(parents=True, exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="startup-", dir=base))
        self.root = self.fixture / "project"
        self.root.mkdir()
        self.helpers = import_helpers()

    def files(self):
        return [path for path in self.root.rglob("*") if path.is_file() and not path.is_symlink()]

    def test_confined_accepts_only_relative_paths_below_real_root(self):
        path = self.helpers.confined(self.root, "run/server/config.yml")
        self.assertEqual(path, self.root / "run" / "server" / "config.yml")
        canary = self.fixture / "canary"
        canary.write_bytes(b"preserve")
        for relative in ("", "../canary", "run/../canary", str(canary)):
            with self.subTest(relative=relative):
                with self.assertRaises(Exception):
                    self.helpers.confined(self.root, relative)
        self.assertEqual(canary.read_bytes(), b"preserve")

    def test_confined_refuses_root_intermediate_leaf_and_dangling_links(self):
        canary = self.fixture / "canary"
        canary.write_bytes(b"preserve")
        root_link = self.fixture / "linked-root"
        root_link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(Exception):
            self.helpers.confined(root_link, "new.txt")
        (self.root / "escape").symlink_to(self.fixture, target_is_directory=True)
        (self.root / "leaf").symlink_to(canary)
        (self.root / "dangling").symlink_to(self.fixture / "absent")
        for relative in ("escape/canary", "leaf", "dangling"):
            with self.subTest(relative=relative):
                with self.assertRaises(Exception):
                    self.helpers.confined(self.root, relative)
        self.assertEqual(canary.read_bytes(), b"preserve")
        self.assertTrue((self.root / "dangling").is_symlink())

    def test_confined_refuses_non_directory_root(self):
        file_root = self.fixture / "file"
        file_root.write_bytes(b"preserve")
        with self.assertRaises(Exception):
            self.helpers.confined(file_root, "config.yml")
        self.assertEqual(file_root.read_bytes(), b"preserve")

    def test_write_creates_private_file_and_leaves_identical_file_unchanged(self):
        path = self.helpers.write_preserving(self.root, "run/server/config.yml", b"api-key: private-fixture\n")
        self.assertEqual(path, self.root / "run" / "server" / "config.yml")
        self.assertEqual(path.read_bytes(), b"api-key: private-fixture\n")
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        before = path.stat()
        listed = sorted(str(file) for file in self.files())
        self.assertEqual(self.helpers.write_preserving(self.root, "run/server/config.yml", path.read_bytes()), path)
        after = path.stat()
        self.assertEqual((before.st_ino, before.st_mtime_ns), (after.st_ino, after.st_mtime_ns))
        self.assertEqual(listed, sorted(str(file) for file in self.files()))

    def test_write_replacements_preserve_every_prior_version_without_archive_collisions(self):
        first = self.helpers.write_preserving(self.root, "run/server/config.yml", b"first")
        self.helpers.write_preserving(self.root, "run/server/config.yml", b"second")
        self.helpers.write_preserving(self.root, "run/server/config.yml", b"third")
        self.assertEqual(first.read_bytes(), b"third")
        archive = self.root / ".archive"
        archived = [path.read_bytes() for path in archive.rglob("*") if path.is_file()]
        self.assertEqual(sorted(archived), [b"first", b"second"])
        self.assertTrue(all(path.resolve().is_relative_to(self.root.resolve()) for path in self.files()))

    def test_write_rejects_leaf_parent_and_archive_links_without_touching_canaries(self):
        canary = self.fixture / "canary"
        canary.write_bytes(b"preserve")
        (self.root / "leaf").symlink_to(canary)
        (self.root / "outside").symlink_to(self.fixture, target_is_directory=True)
        for relative in ("leaf", "outside/canary"):
            with self.subTest(relative=relative):
                with self.assertRaises(Exception):
                    self.helpers.write_preserving(self.root, relative, b"changed")
        original = self.root / "existing.txt"
        original.write_bytes(b"original")
        (self.root / ".archive").symlink_to(self.fixture, target_is_directory=True)
        with self.assertRaises(Exception):
            self.helpers.write_preserving(self.root, "existing.txt", b"replacement")
        self.assertEqual(original.read_bytes(), b"original")
        self.assertEqual(canary.read_bytes(), b"preserve")
        self.assertTrue((self.root / "leaf").is_symlink())

    def test_verified_download_writes_exact_bytes_from_official_source(self):
        data = b"verified-jar-fixture"
        expected = hashlib.sha256(data).hexdigest()
        destination = self.root / "paper.jar"
        calls = []
        def opener(url, *args, **kwargs):
            calls.append(url)
            return Response(data)
        result = self.helpers.download_verified(OFFICIAL, destination, expected, opener=opener)
        self.assertEqual(result, destination)
        self.assertEqual(destination.read_bytes(), data)
        self.assertEqual(len(calls), 1)

    def test_existing_matching_download_never_contacts_network(self):
        data = b"existing verified jar"
        destination = self.root / "paper.jar"
        destination.write_bytes(data)
        before = destination.stat()
        def no_network(*args, **kwargs):
            raise AssertionError("Matching asset must not contact network")
        result = self.helpers.download_verified(OFFICIAL, destination, hashlib.sha256(data).hexdigest(), opener=no_network)
        self.assertEqual(result, destination)
        self.assertEqual(destination.stat().st_ino, before.st_ino)
        self.assertEqual(destination.read_bytes(), data)

    def test_existing_different_download_refuses_without_network_or_replacement(self):
        destination = self.root / "paper.jar"
        destination.write_bytes(b"old jar preserve")
        calls = []
        def opener(*args, **kwargs):
            calls.append(args)
            return Response(b"new jar")
        with self.assertRaises(Exception):
            self.helpers.download_verified(OFFICIAL, destination, hashlib.sha256(b"new jar").hexdigest(), opener=opener)
        self.assertEqual(calls, [])
        self.assertEqual(destination.read_bytes(), b"old jar preserve")

    def test_checksum_mismatch_preserves_download_staging_for_review(self):
        downloaded = b"unexpected published bytes"
        destination = self.root / "paper.jar"
        with self.assertRaises(Exception):
            self.helpers.download_verified(OFFICIAL, destination, hashlib.sha256(b"expected jar").hexdigest(),
                opener=lambda *args, **kwargs: Response(downloaded))
        self.assertFalse(destination.exists())
        self.assertTrue(any(path.read_bytes() == downloaded for path in self.files()), "failed download staging must be retained")

    def test_download_rejects_unofficial_or_insecure_urls_before_network(self):
        destination = self.root / "paper.jar"
        calls = []
        def opener(*args, **kwargs):
            calls.append(args)
            return Response(b"fixture")
        for url in ("http://fill-data.papermc.io/paper.jar", "https://example.invalid/paper.jar",
                    "https://fill-data.papermc.io.evil.invalid/paper.jar", "file:paper.jar"):
            with self.subTest(url=url):
                with self.assertRaises(Exception):
                    self.helpers.download_verified(url, destination, hashlib.sha256(b"fixture").hexdigest(), opener=opener)
        self.assertEqual(calls, [])
        self.assertFalse(destination.exists())

    def test_geyser_official_host_is_supported_and_redirects_cannot_escape_allowlist(self):
        data = b"geyser fixture"
        destination = self.root / "geyser.jar"
        url = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
        self.helpers.download_verified(url, destination, hashlib.sha256(data).hexdigest(),
            opener=lambda *args, **kwargs: Response(data, url))
        self.assertEqual(destination.read_bytes(), data)
        redirected = self.root / "redirected.jar"
        with self.assertRaises(Exception):
            self.helpers.download_verified(OFFICIAL, redirected, hashlib.sha256(data).hexdigest(),
                opener=lambda *args, **kwargs: Response(data, "https://untrusted.invalid/asset.jar"))
        self.assertFalse(redirected.exists())

    def test_download_refuses_symlink_destination_without_touching_target(self):
        canary = self.fixture / "canary"
        canary.write_bytes(b"preserve")
        destination = self.root / "paper.jar"
        destination.symlink_to(canary)
        calls = []
        def opener(*args, **kwargs):
            calls.append(args)
            return Response(b"replacement")
        with self.assertRaises(Exception):
            self.helpers.download_verified(OFFICIAL, destination, hashlib.sha256(b"replacement").hexdigest(), opener=opener)
        self.assertEqual(calls, [])
        self.assertEqual(canary.read_bytes(), b"preserve")
        self.assertTrue(destination.is_symlink())

    def test_secret_input_is_hidden_and_subprocess_calls_do_not_enable_shell(self):
        tree = ast.parse(SCRIPT.read_text(encoding="utf-8"))
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
        self.assertTrue(any(isinstance(node.func, ast.Attribute) and node.func.attr == "getpass"
                            or isinstance(node.func, ast.Name) and node.func.id == "getpass" for node in calls),
                        "API key entry must use getpass")
        for node in calls:
            if isinstance(node.func, ast.Attribute) and node.func.attr in ("run", "Popen", "call", "check_call", "check_output"):
                self.assertFalse(any(keyword.arg == "shell" and isinstance(keyword.value, ast.Constant)
                                     and keyword.value.value is True for keyword in node.keywords), "shell=True is forbidden")


class ServerAccountTest(unittest.TestCase):
    def setUp(self):
        base = PROJECT / "tools" / "build" / "test-scratch"
        base.mkdir(parents=True, exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="desktop-accounts-", dir=base))
        self.root = self.fixture / "project"
        self.root.mkdir()
        self.helpers = import_helpers(PROJECT / "tools" / "start_desktop.py")

    def test_account_names_preserve_java_identity_and_floodgate_prefix(self):
        self.assertEqual(self.helpers.account_name({"platform": "java", "name": "JavaOwner"}), "JavaOwner")
        self.assertEqual(self.helpers.account_name({"platform": "bedrock", "name": "Bedrock Owner"}), ".Bedrock_Owner")
        self.assertEqual(self.helpers.account_name({"platform": "bedrock", "name": "Owner_2"}), ".Owner_2")

    def test_whitelist_confirms_only_exact_names_with_valid_identity_uuids(self):
        accounts = [{"platform": "java", "name": "JavaOwner"}, {"platform": "bedrock", "name": "Bedrock Owner"}]
        entries = [
            {"name": "javaowner", "uuid": "123e4567-e89b-42d3-a456-426614174000"},
            {"name": ".bEdRoCk_OwNeR", "uuid": "00000000-0000-0000-0009-000000000123"},
        ]
        self.assertEqual(self.helpers.whitelist_confirmed(accounts, entries), {"JavaOwner", ".Bedrock_Owner"})
        self.assertEqual(self.helpers.whitelist_confirmed(accounts, entries[:1]), {"JavaOwner"})
        self.assertEqual(self.helpers.whitelist_confirmed(accounts, entries[1:]), {".Bedrock_Owner"})
        self.assertEqual(self.helpers.whitelist_confirmed(accounts, []), set())

    def test_whitelist_rejects_prefixless_long_or_java_identity_bedrock_entries(self):
        account = [{"platform": "bedrock", "name": "Bedrock Owner"}]
        variants = [
            {"name": "Bedrock_Owner", "uuid": "00000000-0000-0000-0009-000000000123"},
            {"name": ".Bedrock Owner", "uuid": "00000000-0000-0000-0009-000000000123"},
            {"name": ".Bedrock_Owner_extra", "uuid": "00000000-0000-0000-0009-000000000123"},
            {"name": ".Bedrock_Owner", "uuid": "123e4567-e89b-42d3-a456-426614174000"},
            {"name": ".Bedrock_Owner", "uuid": "00000000-0000-0001-0009-000000000123"},
            {"name": ".Bedrock_Owner", "uuid": "00000000-0000-0000-0000-000000000000"},
        ]
        for entry in variants:
            with self.subTest(entry=entry):
                self.assertEqual(self.helpers.whitelist_confirmed(account, [entry]), set())

    def test_malformed_whitelist_entries_are_ignored_without_hiding_valid_entries(self):
        accounts = [{"platform": "java", "name": "JavaOwner"}, {"platform": "bedrock", "name": "Bedrock Owner"}]
        malformed = [None, 42, True, "JavaOwner", [], {}, {"name": "JavaOwner"},
            {"name": "JavaOwner", "uuid": "invalid"}, {"name": "JavaOwner", "uuid": None},
            {"name": "JavaOwner", "uuid": "00000000-0000-0000-0000-000000000000"},
            {"name": 42, "uuid": "123e4567-e89b-42d3-a456-426614174000"},
            {"name": "JavaOwner ", "uuid": "123e4567-e89b-42d3-a456-426614174000"},
            {"name": "JavaOwner_extra", "uuid": "123e4567-e89b-42d3-a456-426614174000"}]
        self.assertEqual(self.helpers.whitelist_confirmed(accounts, malformed), set())
        valid = {"name": "JAVAOWNER", "uuid": "123e4567-e89b-42d3-a456-426614174000"}
        self.assertEqual(self.helpers.whitelist_confirmed(accounts, malformed + [valid]), {"JavaOwner"})
        for invalid_container in (None, "not json entries", {"name": "JavaOwner", "uuid": valid["uuid"]}):
            with self.subTest(container=invalid_container):
                self.assertEqual(self.helpers.whitelist_confirmed(accounts, invalid_container), set())

    def test_floodgate_uuid_accepts_nonzero_lower_half_boundaries(self):
        accounts = [{"platform": "bedrock", "name": "Bedrock Owner"}]
        for identity in ("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-ffff-ffffffffffff"):
            with self.subTest(identity=identity):
                self.assertEqual(self.helpers.whitelist_confirmed(accounts,
                    [{"name": ".Bedrock_Owner", "uuid": identity}]), {".Bedrock_Owner"})

    def test_bedrock_raw_gamertag_limit_is_enforced_before_accounts_are_written(self):
        canary = self.root / "canary"
        canary.write_bytes(b"preserve")
        with patch.object(self.helpers, "PROJECT", self.root), \
             patch("builtins.input", side_effect=["java", "JavaOwner", "bedrock", "B" * 17]):
            with self.assertRaises(ValueError):
                self.helpers.player_accounts()
        self.assertFalse((self.root / "run" / "player-accounts.json").exists())
        self.assertEqual(canary.read_bytes(), b"preserve")

    def test_bedrock_sixteen_character_boundary_is_accepted_but_saved_long_names_refuse(self):
        accounts = [{"platform": "java", "name": "JavaOwner"}, {"platform": "bedrock", "name": "B" * 16}]
        with patch.object(self.helpers, "PROJECT", self.root), \
             patch("builtins.input", side_effect=["java", "JavaOwner", "bedrock", "B" * 16]):
            self.assertEqual(self.helpers.player_accounts(), accounts)
        saved = self.root / "run" / "player-accounts.json"
        self.assertEqual(json.loads(saved.read_text()), accounts)
        # Preserve the accepted fixture, then install a separate invalid input fixture.
        saved.rename(saved.with_name("accepted-player-accounts.json"))
        invalid = [{"platform": "java", "name": "JavaOwner"}, {"platform": "bedrock", "name": "B" * 17}]
        saved.write_text(json.dumps(invalid))
        before = saved.read_bytes()
        with patch.object(self.helpers, "PROJECT", self.root), \
             patch("builtins.input", side_effect=AssertionError("Saved invalid account must fail, not prompt")):
            with self.assertRaises(ValueError):
                self.helpers.player_accounts()
        self.assertEqual(saved.read_bytes(), before)
        self.assertTrue(saved.with_name("accepted-player-accounts.json").exists())


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
            outcome = "failure" if issubclass(err[0], test.failureException) else "error"
            self.record(subtest, outcome, self._exc_info_to_string(err, subtest))

    def write_report(self):
        report = PROJECT / "tools" / "build" / "test-results" / "server-setup.xml"
        report.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element("testsuite", name="ServerSetup", tests=str(self.testsRun),
            failures=str(len(self.failures)), errors=str(len(self.errors)), skipped=str(len(self.skipped)))
        suite.extend(self.cases)
        ET.ElementTree(suite).write(report, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult).run(suite)
    result.write_report()
    raise SystemExit(0 if result.wasSuccessful() else 1)
