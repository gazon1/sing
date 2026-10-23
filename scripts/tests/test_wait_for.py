"""Tests for `./wait-for` — wait for a line in a log, then print the tail once.

The defect: a long run was watched with a loop of "sleep 30 && tail -20". Each
poll cost a round of context, and each round cost the same whether the run had
thirty seconds left or thirty minutes — the reader could not tell "nothing has
happened yet" from "the run is stuck". The session that added the rule ("a long
run is one background task, not a loop of polls") broke the rule 140 times, and
the fix was not another rule: it was a tool.

So the tool is what the tests pin:

* it waits for the pattern and exits 0 — and it prints the tail once, at the
  moment the answer exists;
* it gives up on its own and exits 1, printing the tail anyway, so a timeout
  is a verdict rather than a hang;
* it refuses nonsense arguments with exit 2 instead of waiting forever.

The waits in these tests are short: the tool's own timeout is what is being
tested, and a test that waits out a real timeout is a slow test.
"""

import json
import os
import pathlib
import re
import subprocess
import tempfile
import time
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
TOOL = REPO / "wait-for"

#: The measured cost of the longest run in this repository.
#:
#: Read from the files that recorded it, so the number the tool is measured
#: against is the number the repository published:
#:
#: * `config/docs/gate-benchmarks.json` — the longest job of the last CI run;
#: * `docs/testing/gate-costs.md` — the instrumented coverage run.
#:
#: A default timeout shorter than the longest run turns one run into two
#: waits, which is the cost the tool exists to remove.


def _longest_ci_job_seconds() -> float:
    """The longest job of the last measured CI run, in seconds."""
    benchmarks = json.loads(
        (REPO / "config" / "docs" / "gate-benchmarks.json").read_text(encoding="utf-8"),
    )
    return max(benchmarks["ci"]["jobs"].values()) / 1000


def _instrumented_run_seconds() -> float:
    """The instrumented coverage run, in seconds — the longest local run."""
    text = (REPO / "docs" / "testing" / "gate-costs.md").read_text(encoding="utf-8")
    match = re.search(r"\|\s*fast\s*\|\s*(\d+)\s*min\s*(\d+)\s*s\s*\|", text)
    if match is None:
        raise AssertionError("the instrumented run cost is not where the test expects it")
    return int(match.group(1)) * 60 + int(match.group(2))


def _run(*arguments: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(TOOL), *arguments],
        capture_output=True,
        text=True,
        timeout=120,
        stdin=subprocess.DEVNULL,
    )


class WaitForTest(unittest.TestCase):
    """The tool waits, prints once, and gives up on its own."""

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="wait-for-test-")
        self.log = pathlib.Path(self.directory.name) / "run.log"

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_the_tool_exists_and_is_executable(self) -> None:
        self.assertTrue(TOOL.is_file(), f"the tool is missing at {TOOL}")
        self.assertTrue(os.access(TOOL, os.X_OK), f"the tool is not executable: {TOOL}")

    def test_a_pattern_that_is_there_is_found(self) -> None:
        self.log.write_text("one\nBUILD SUCCESSFUL\nthree\n", encoding="utf-8")

        result = _run(str(self.log), "BUILD SUCCESSFUL", "--timeout", "5", "--interval", "1")

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("BUILD SUCCESSFUL", result.stdout)
        self.assertIn("one", result.stdout)

    def test_a_pattern_that_never_appears_times_out(self) -> None:
        self.log.write_text("one\ntwo\n", encoding="utf-8")

        result = _run(str(self.log), "never appears", "--timeout", "2", "--interval", "1")

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("never appeared", result.stdout)
        self.assertIn("one", result.stdout)

    def test_the_tail_is_bounded_by_the_lines_option(self) -> None:
        self.log.write_text("\n".join(f"line {index}" for index in range(50)) + "\n", encoding="utf-8")

        result = _run(str(self.log), "line 49", "--timeout", "5", "--interval", "1", "--lines", "5")

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("line 49", result.stdout)
        self.assertNotIn("line 10\n", result.stdout)

    def test_a_log_that_does_not_exist_yet_is_waited_for(self) -> None:
        missing = pathlib.Path(self.directory.name) / "not-there.log"

        result = _run(str(missing), "anything", "--timeout", "2", "--interval", "1")

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("does not exist", result.stdout)

    def test_the_arguments_are_checked(self) -> None:
        self.assertEqual(2, _run(str(self.log)).returncode)
        self.assertEqual(2, _run().returncode)
        self.assertEqual(2, _run(str(self.log), "pattern", "--timeout", "soon").returncode)

    def test_the_default_timeout_covers_the_longest_measured_run(self) -> None:
        """A 40-minute run must cost one wait, not two.

        The default timeout is the number the repository published: the longest
        CI job and the longest local run. A default shorter than those turns
        one run into two waits — the cost this tool exists to remove.
        """
        usage = _run("--help").stdout
        match = re.search(r"\(default (\d+)", usage)
        self.assertIsNotNone(match, f"the usage does not name a default timeout: {usage}")
        default = int(match.group(1))

        self.assertGreaterEqual(
            default,
            _longest_ci_job_seconds(),
            "the default timeout is shorter than the longest job of the last CI run",
        )
        self.assertGreaterEqual(
            default,
            _instrumented_run_seconds(),
            "the default timeout is shorter than the instrumented coverage run",
        )

    def test_a_timeout_of_zero_waits_without_a_limit(self) -> None:
        """The observer has no artificial ceiling.

        The harness caps a poll at 30 seconds, so a waiter that gives up on a
        schedule is a waiter that has to be called again. A timeout of zero
        says: wait until the answer exists.
        """
        log = pathlib.Path(self.directory.name) / "later.log"
        process = subprocess.Popen(
            [str(TOOL), str(log), "DONE", "--timeout", "0", "--interval", "1"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            time.sleep(2)
            log.write_text("DONE\n", encoding="utf-8")
            stdout, stderr = process.communicate(timeout=60)
        finally:
            process.kill()

        self.assertEqual(0, process.returncode, stdout or stderr)
        self.assertIn("DONE", stdout)


if __name__ == "__main__":
    unittest.main()
