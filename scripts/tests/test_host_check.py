"""Tests for the `host-check` command — is there room for another run?

The failure this answers: a Gradle build died in the middle of a compile with
"No space left on device", and the space was not the disk. The scratch
directory is a tmpfs with 1,048,576 inodes, and the drafts of previous
sessions had taken 700,450 of them. `host-load` did not see it — it measures
the CPU, and the CPU was idle. The diagnosis cost six calls and a restart of
a 35-minute run.

So the second question is answered by a command that reads the real state of
the machine. The tests pin the parts that make the answer trustworthy:

* the verdict follows the measurement — the inode pressure of a filesystem is
  read with the same call `df -i` uses, and the threshold is the one the
  command's own documentation quotes;
* the prune command is safe — it names the drafts that nothing has touched
  and never the ones a session is writing to, and the command deletes
  nothing at all;
* the answer is machine-readable — `--json` says the same thing the prose
  says.

The filesystem pressure is mocked; the draft scan runs against a real
temporary directory, because "is this directory dead" is a question about the
filesystem and a mock would only test the mock.
"""

import json
import os
import sys
import tempfile
import time
import unittest
from unittest import mock

SCRIPTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path.insert(0, os.path.join(SCRIPTS, "src"))

from click.testing import CliRunner  # noqa: E402

from sgate.commands import host_load  # noqa: E402


def _statvfs(total_inodes: int, free_inodes: int, blocks: int = 1024):
    """A stand-in for the numbers `os.statvfs` returns."""
    return os.statvfs_result(
        (4096, 4096, blocks, blocks, blocks, total_inodes, free_inodes, free_inodes, 0, 0, 255),
    )


class HostCheckMeasurementTest(unittest.TestCase):
    """The measurement and the verdict it produces."""

    def test_the_reading_carries_the_inodes_of_the_filesystem(self) -> None:
        with mock.patch.object(host_load.os, "statvfs", return_value=_statvfs(1_048_576, 347_668)):
            reading = host_load.measure_filesystem("/tmp")

        self.assertEqual(1_048_576, reading["totalInodes"])
        self.assertEqual(347_668, reading["freeInodes"])
        self.assertEqual(700_908, reading["usedInodes"])
        self.assertEqual(66.8, reading["usedPercent"])

    def test_a_filesystem_at_the_threshold_is_tight(self) -> None:
        # 1,048,576 inodes with 20 % left — fewer than four session drafts.
        with mock.patch.object(host_load.os, "statvfs", return_value=_statvfs(1_048_576, 209_715)):
            reading = host_load.measure_filesystem("/tmp")

        self.assertEqual("tight", reading["state"])

    def test_a_filesystem_below_the_threshold_is_ok(self) -> None:
        with mock.patch.object(host_load.os, "statvfs", return_value=_statvfs(1_048_576, 700_000)):
            reading = host_load.measure_filesystem("/tmp")

        self.assertEqual("ok", reading["state"])

    def test_a_filesystem_that_allocates_its_inodes_has_no_limit(self) -> None:
        # btrfs reports zero inodes: not "no inodes", but "no limit".
        with mock.patch.object(host_load.os, "statvfs", return_value=_statvfs(0, 0)):
            reading = host_load.measure_filesystem("/home")

        self.assertEqual("no-inode-limit", reading["state"])
        self.assertIsNone(reading["usedPercent"])

    def test_a_filesystem_that_cannot_be_read_says_so(self) -> None:
        with mock.patch.object(host_load.os, "statvfs", side_effect=OSError("not there")):
            with self.assertRaises(SystemExit) as raised:
                host_load.measure_filesystem("/nowhere")

        self.assertEqual(2, raised.exception.code)

    def test_the_threshold_is_the_one_the_documentation_quotes(self) -> None:
        self.assertEqual(80, host_load.TIGHT_INODE_PERCENT)
        self.assertIn("80 %", host_load.__doc__ or "")

    def test_the_draft_cost_is_the_one_the_documentation_quotes(self) -> None:
        self.assertEqual(54_912, host_load.DRAFT_INODE_COST)
        self.assertIn("54,912", host_load.__doc__ or "")


class HostCheckDraftsTest(unittest.TestCase):
    """The drafts: which of them are dead, and which are still in use."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="host-check-test-")
        self.scratch = self.directory.name

    def tearDown(self) -> None:
        self.directory.cleanup()

    def _make_draft(self, name: str, files: int = 3, age_hours: float = 0.0) -> str:
        path = os.path.join(self.scratch, name)
        os.makedirs(path, exist_ok=True)
        for index in range(files):
            with open(os.path.join(path, f"file{index}"), "w", encoding="utf-8") as handle:
                handle.write("x")
        if age_hours:
            moment = time.time() - age_hours * 3600
            for index in range(files):
                os.utime(os.path.join(path, f"file{index}"), (moment, moment))
            os.utime(path, (moment, moment))
        return path

    def test_a_draft_nothing_touched_is_dead(self) -> None:
        self._make_draft("old-draft", age_hours=48)

        drafts = host_load.scan_drafts(self.scratch, limit=5, stale_hours=24)

        self.assertEqual(1, len(drafts))
        self.assertTrue(drafts[0]["dead"])
        self.assertTrue(drafts[0]["path"].endswith("old-draft"))

    def test_a_draft_a_session_is_writing_to_is_live(self) -> None:
        self._make_draft("live-draft")

        drafts = host_load.scan_drafts(self.scratch, limit=5, stale_hours=24)

        self.assertEqual(1, len(drafts))
        self.assertFalse(drafts[0]["dead"])

    def test_a_hidden_entry_is_never_a_draft(self) -> None:
        self._make_draft(".hidden-draft", age_hours=48)

        drafts = host_load.scan_drafts(self.scratch, limit=5, stale_hours=24)

        self.assertEqual([], drafts)

    def test_the_prune_command_names_the_dead_drafts_only(self) -> None:
        dead = self._make_draft("old-draft", age_hours=48)
        self._make_draft("live-draft")

        drafts = host_load.scan_drafts(self.scratch, limit=5, stale_hours=24)
        command = host_load.prune_command(drafts)

        self.assertIn(dead, command)
        self.assertNotIn("live-draft", command)

    def test_the_prune_command_quotes_a_path_with_a_space(self) -> None:
        dead = self._make_draft("old draft", age_hours=48)

        drafts = host_load.scan_drafts(self.scratch, limit=5, stale_hours=24)
        command = host_load.prune_command(drafts)

        self.assertIn(f"'{dead}'", command)

    def test_nothing_is_pruned_when_nothing_is_dead(self) -> None:
        self._make_draft("live-draft")

        drafts = host_load.scan_drafts(self.scratch, limit=5, stale_hours=24)

        self.assertEqual("", host_load.prune_command(drafts))


class HostCheckCommandTest(unittest.TestCase):
    """The command as the caller meets it."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="host-check-cmd-")
        self.scratch = self.directory.name

    def tearDown(self) -> None:
        self.directory.cleanup()

    def _make_draft(self, name: str, age_hours: float) -> str:
        path = os.path.join(self.scratch, name)
        os.makedirs(path, exist_ok=True)
        with open(os.path.join(path, "file"), "w", encoding="utf-8") as handle:
            handle.write("x")
        if age_hours:
            moment = time.time() - age_hours * 3600
            os.utime(os.path.join(path, "file"), (moment, moment))
            os.utime(path, (moment, moment))
        return path

    def test_the_json_carries_the_verdict_and_the_thresholds(self) -> None:
        runner = CliRunner()
        result = runner.invoke(
            host_load.host_check,
            ["--json", "--scratch", self.scratch],
        )

        self.assertEqual(0, result.exit_code, result.output)
        payload = json.loads(result.output)
        self.assertIn(payload["state"], ("ok", "tight"))
        self.assertEqual(80, payload["thresholds"]["tightInodePercent"])
        self.assertEqual(24, payload["thresholds"]["staleHours"])
        self.assertEqual(54_912, payload["thresholds"]["draftInodeCost"])

    def test_the_prose_names_the_filesystems_and_the_drafts(self) -> None:
        runner = CliRunner()
        result = runner.invoke(host_load.host_check, ["--scratch", self.scratch])

        self.assertEqual(0, result.exit_code, result.output)
        self.assertIn(self.scratch, result.output)
        self.assertIn("inodes", result.output)

    def test_the_quiet_output_is_one_line(self) -> None:
        runner = CliRunner()
        result = runner.invoke(host_load.host_check, ["--quiet", "--scratch", self.scratch])

        self.assertEqual(0, result.exit_code, result.output)
        self.assertEqual(1, len([line for line in result.output.splitlines() if line.strip()]))

    def test_the_command_deletes_nothing(self) -> None:
        dead = self._make_draft("old-draft", age_hours=48)
        runner = CliRunner()

        result = runner.invoke(host_load.host_check, ["--scratch", self.scratch])

        self.assertEqual(0, result.exit_code, result.output)
        self.assertTrue(os.path.isdir(dead), "the command deleted a directory it only reported")

    def test_the_prune_command_is_in_the_report(self) -> None:
        dead = self._make_draft("old-draft", age_hours=48)
        runner = CliRunner()

        result = runner.invoke(host_load.host_check, ["--scratch", self.scratch])

        self.assertIn("rm -rf", result.output)
        self.assertIn(dead, result.output)

    def test_a_tight_host_fails_only_when_the_caller_asks_for_it(self) -> None:
        runner = CliRunner()
        tight = _statvfs(1_048_576, 1000)

        with mock.patch.object(host_load.os, "statvfs", return_value=tight):
            reported = runner.invoke(host_load.host_check, ["--scratch", self.scratch])
            failed = runner.invoke(
                host_load.host_check,
                ["--scratch", self.scratch, "--fail-when-tight"],
            )

        self.assertEqual(0, reported.exit_code, reported.output)
        self.assertEqual(1, failed.exit_code, failed.output)


if __name__ == "__main__":
    unittest.main()
