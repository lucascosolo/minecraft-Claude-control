"""Safety regression tests. Scratch fixtures are preserved for inspection."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


PROJECT = Path(__file__).resolve().parents[1]
DEPLOY = PROJECT / "deploy"
SCRATCH_BASE = PROJECT / "build" / "safety-tests"


class TestHarnessSafety(unittest.TestCase):
    def setUp(self):
        SCRATCH_BASE.mkdir(parents=True, exist_ok=True)
        self.scratch = Path(tempfile.mkdtemp(prefix="regression-", dir=SCRATCH_BASE))

    def bash(self, program, *args, environment=None):
        env = {"PATH": os.defpath, "LANG": "C"}
        if environment:
            env.update(environment)
        return subprocess.run(
            ["bash", "--noprofile", "--norc", "-c", program, "safety-test", *map(str, args)],
            cwd=self.scratch, env=env, capture_output=True, text=True, timeout=15,
        )

    def assert_repair_guard(self):
        """Fail statically before allowing any historical repair script execution."""
        text = (DEPLOY / "test-repair.sh").read_text()
        body = text.index("as_server()")
        prefix = text[:body]
        self.assertRegex(prefix, r'BASH_SOURCE\[0\].*\$0')
        self.assertRegex(prefix, r'\bexit\s+[1-9][0-9]*')
        guard_lines = [line for line in prefix.splitlines() if "BASH_SOURCE" in line]
        self.assertTrue(any("SAFETY-GUARD" in line for line in guard_lines))
        # The direct-execution check must precede assignments, sourcing and functions.
        first = next(line for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#"))
        self.assertIn("BASH_SOURCE", first)
        self.assertRegex(prefix, r'test_scratch|TEST_SCRATCH')
        return prefix

    def helper(self, program, *args):
        helper = DEPLOY / "test-scratch.sh"
        self.assertTrue(helper.is_file(), "safe scratch module is missing")
        return self.bash(
            'set -euo pipefail; source "$1"; test_scratch_init "$2"; ' + program,
            helper, PROJECT, *args,
        )

    def test_repair_guard_precedes_body(self):
        self.assert_repair_guard()

    def test_direct_repair_refuses_unset_empty_and_injected_context(self):
        self.assert_repair_guard()
        canary = self.scratch / "canary"
        canary.write_text("preserve")
        injected = "$(touch " + str(self.scratch / "injected") + ")"
        for value in (None, "", str(self.scratch), injected):
            with self.subTest(value=value):
                env = {} if value is None else {"T": value}
                result = self.bash('bash "$1"', DEPLOY / "test-repair.sh", environment=env)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(canary.read_text(), "preserve")
                self.assertFalse((self.scratch / "injected").exists())
                self.assertEqual(sorted(p.name for p in self.scratch.iterdir()), ["canary"])

    def test_sourced_repair_refuses_uninitialized_context(self):
        self.assert_repair_guard()
        canary = self.scratch / "canary"
        canary.write_text("preserve")
        result = self.bash('source "$1"', DEPLOY / "test-repair.sh", environment={"T": str(self.scratch)})
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(canary.read_text(), "preserve")
        self.assertEqual(sorted(p.name for p in self.scratch.iterdir()), ["canary"])

    def test_scratch_initialized_inside_project(self):
        result = self.helper('printf "%s\\n" "$T"')
        self.assertEqual(result.returncode, 0, result.stderr)
        root = Path(result.stdout.strip().splitlines()[-1])
        self.assertTrue(root.is_dir())
        self.assertTrue(root.resolve().is_relative_to(SCRATCH_BASE.resolve()))
        self.assertNotEqual(root.resolve(), SCRATCH_BASE.resolve())

    def test_retire_preserves_files_in_unique_slots(self):
        result = self.helper(
            'printf first > "$T/item"; test_retire "$T/item"; '
            'printf second > "$T/item"; test_retire "$T/item"; printf "%s\\n" "$T"'
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        root = Path(result.stdout.strip().splitlines()[-1])
        self.assertFalse((root / "item").exists())
        contents = sorted(p.read_text() for p in (root / ".retired").rglob("*") if p.is_file())
        self.assertEqual(contents, ["first", "second"])

    def test_retire_refuses_root_outside_and_dotdot_escape(self):
        outside = self.scratch / "outside"
        outside.write_text("preserve")
        result = self.helper(
            'if test_retire "$T"; then exit 61; fi; '
            'if test_retire "$3"; then exit 62; fi; '
            'if test_retire "$T/../$(basename "$3")"; then exit 63; fi; '
            'test -d "$T"; printf "%s\\n" "$T"', outside,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outside.read_text(), "preserve")

    def test_retire_refuses_symlink_parent_escape(self):
        outside = self.scratch / "outside"
        outside.mkdir()
        canary = outside / "canary"
        canary.write_text("preserve")
        result = self.helper(
            'ln -s "$3" "$T/escape"; '
            'if test_retire "$T/escape/canary"; then exit 64; fi', outside,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(canary.read_text(), "preserve")

    def test_retire_archives_final_symlinks_without_following(self):
        target = self.scratch / "target"
        target.write_text("preserve")
        missing = self.scratch / "missing"
        result = self.helper(
            'ln -s "$3" "$T/link"; ln -s "$4" "$T/dangling"; '
            'test_retire "$T/link" "$T/dangling"; printf "%s\\n" "$T"', target, missing,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        root = Path(result.stdout.strip().splitlines()[-1])
        self.assertFalse((root / "link").is_symlink())
        self.assertFalse((root / "dangling").is_symlink())
        links = [p for p in (root / ".retired").rglob("*") if p.is_symlink()]
        self.assertEqual(sorted(os.readlink(p) for p in links), sorted([str(target), str(missing)]))
        self.assertEqual(target.read_text(), "preserve")
        self.assertFalse(missing.exists())

    def test_entrypoint_missing_module_fails_before_suite(self):
        original = (DEPLOY / "test-entrypoint.sh").read_text()
        self.assertIn("test_scratch_init", original)
        self.assertIn("test-scratch.sh", original)
        # Run an unchanged copy only after verifying it uses the safe initializer.
        # Its dependencies are deliberately absent, so no historical suite can run.
        copy = self.scratch / "test-entrypoint.sh"
        copy.write_text(original)
        result = self.bash('bash "$1"', copy)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("PASS ", result.stdout)
        self.assertEqual(sorted(p.name for p in self.scratch.iterdir()), ["test-entrypoint.sh"])


class XmlResult(unittest.TextTestResult):
    """Record each unittest outcome without depending on additional packages."""

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
        report = PROJECT / "build" / "test-results" / "test-test-safety.xml"
        report.parent.mkdir(parents=True, exist_ok=True)
        suite = ET.Element(
            "testsuite", name="TestHarnessSafety", tests=str(self.testsRun),
            failures=str(len(self.failures)), errors=str(len(self.errors)),
            skipped=str(len(self.skipped)),
        )
        suite.extend(self.cases)
        ET.ElementTree(suite).write(report, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(TestHarnessSafety)
    result = unittest.TextTestRunner(verbosity=2, resultclass=XmlResult).run(suite)
    result.write_report()
    raise SystemExit(0 if result.wasSuccessful() else 1)
