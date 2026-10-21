"""Tests for check-openspec-stale.py — an active OpenSpec change that stopped moving.

A change directory under `openspec/changes/` that has not been touched for
fourteen days is usually a change nobody finished: the proposal was written, the
tasks were never ticked, and the branch it was going to become quietly rotted
into a diff nobody re-reads. The gate names the aged changes so somebody
decides — archive it, finish it, or abandon it — instead of leaving the
decision unmade.

The properties asserted here are the ones that decide whether the warning can
be trusted:

* a change directory older than the threshold is reported, with its age in
  days, so the reader can triage rather than count;
* a recently touched change is not reported — a gate that flags every change
  is a gate nobody reads;
* `archive/` is not an active change, however old it is, and neither is a
  hidden directory (`.tmp/` and friends are working files, not proposals);
* a plain file under `openspec/changes/` is not a change either;
* a repository without `openspec/changes/` is skipped, not failed — the gate
  has nothing to say about a repository that does not use OpenSpec.

The change directories are created in a `tempfile.TemporaryDirectory` and the
gate's `__file__` is pointed at that tree, so nothing is written into the
repository and no real change is aged by the test.
"""

import contextlib
import importlib.util
import io
import os
import pathlib
import sys
import tempfile
import time
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_openspec_stale", SCRIPTS / "check-openspec-stale.py"
)
stale = importlib.util.module_from_spec(_spec)
sys.modules["check_openspec_stale"] = stale
_spec.loader.exec_module(stale)


class StaleChangeTest(unittest.TestCase):
    """A temporary `openspec/changes/` tree with aged and fresh changes."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.changes = self.root / "openspec" / "changes"
        self.changes.mkdir(parents=True)
        # The gate locates the change directory relative to its own file, so the
        # module's `__file__` is pointed into the temporary tree.
        patcher = unittest.mock.patch.object(
            stale, "__file__", str(self.root / "scripts" / "check-openspec-stale.py")
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def make_change(self, name, age_days):
        path = self.changes / name
        path.mkdir(parents=True, exist_ok=True)
        when = time.time() - age_days * 86400
        os.utime(path, (when, when))
        return path

    def run_gate(self):
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            code = stale.run(stale.cli.main, [])
        return code, buffer.getvalue()


class StaleTest(StaleChangeTest):
    """An aged change is reported."""

    def test_an_old_change_is_reported(self):
        self.make_change("add-pomodoro", 30)
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("openspec/changes/add-pomodoro/", output)

    def test_the_report_names_the_threshold_and_the_count(self):
        self.make_change("add-pomodoro", 30)
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("older than 14 days", output)
        self.assertIn("1 active change(s)", output)

    def test_the_report_gives_the_age_in_days(self):
        """The age is what makes the report triageable rather than a count."""
        self.make_change("add-pomodoro", 30)
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("days old", output)

    def test_every_stale_change_is_named(self):
        self.make_change("one", 20)
        self.make_change("two", 40)
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("openspec/changes/one/", output)
        self.assertIn("openspec/changes/two/", output)

    def test_a_change_just_under_the_threshold_is_not_reported(self):
        self.make_change("almost-stale", stale.STALE_DAYS - 1)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("younger than", output)


class IgnoredTest(StaleChangeTest):
    """What does not count as an active change."""

    def test_an_archived_change_is_ignored(self):
        """`archive/` is where finished changes go; ageing there is the point."""
        self.make_change("archive", 400)
        self.make_change("archive/old-one", 400)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_a_hidden_directory_is_ignored(self):
        self.make_change(".scratch", 400)
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_a_plain_file_is_ignored(self):
        (self.changes / "README.md").write_text("not a change\n", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_an_empty_changes_directory_passes(self):
        code, output = self.run_gate()
        self.assertEqual(0, code, output)


class MissingDirectoryTest(unittest.TestCase):
    """A repository without OpenSpec is skipped, not failed."""

    def test_a_repository_without_openspec_is_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            with unittest.mock.patch.object(
                stale, "__file__", str(root / "scripts" / "check-openspec-stale.py")
            ):
                buffer = io.StringIO()
                with contextlib.redirect_stdout(buffer):
                    code = stale.run(stale.cli.main, [])
        self.assertEqual(0, code)
        self.assertIn("skipping stale check", buffer.getvalue())


if __name__ == "__main__":
    unittest.main()
