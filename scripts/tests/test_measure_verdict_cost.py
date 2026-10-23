"""Tests for `measure-verdict-cost.py` and for the page it feeds.

The price of the pre-push verdict used to be prose: a table in a generated page
whose numbers were typed into the generator's template. These tests check the two
properties that make the number worth reading again:

* the measurer writes a record, and the record carries what was measured, where
  it was measured, and how long ago;
* the published page is rendered from that record — a page with no record says
  so instead of showing nothing, and a page with a stale record says *stale*
  instead of showing an old number as a fresh one.

Nothing here runs Gradle. The measurement is exercised against a stub command
that answers immediately, because the machine is shared and a test that starts a
Gradle daemon is a test that fights for the slots.
"""

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
REPO = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS / "src"))

import sgate.commands.gate_costs as costs  # noqa: E402

MEASURER = SCRIPTS / "measure-verdict-cost.py"
RECORD = REPO / "docs" / "testing" / "verdict-cost.json"


def run_measurer(*args: str) -> subprocess.CompletedProcess:
    """Run the measurer the way a person runs it."""
    return subprocess.run(
        [sys.executable, str(MEASURER), *args],
        capture_output=True,
        text=True,
        check=False,
        stdin=subprocess.DEVNULL,
    )


class _FakeGradle:
    """A Gradle command that answers immediately.

    The measurer must be testable without a build: a stub that exits zero stands
    in for `./gw`, and the price of the stub is a few milliseconds.
    """

    def __init__(self, directory: pathlib.Path) -> None:
        self.path = directory / "fake-gradle.py"
        self.path.write_text(
            "import sys\n"
            "sys.exit(0)\n",
            encoding="utf-8",
        )

    @property
    def command(self) -> str:
        return f"{sys.executable} {self.path}"


class _FakeVerdict:
    """A verdict that answers with a report, without judging anything."""

    def __init__(self, directory: pathlib.Path) -> None:
        self.path = directory / "fake-verdict.py"
        self.path.write_text(
            "import json\n"
            "print(json.dumps({\n"
            "    'sha': 'deadbeef',\n"
            "    'tree': '.',\n"
            "    'tree_label': 'the current worktree',\n"
            "    'tasks': [':shared:detekt'],\n"
            "    'coverage_reason': 'the tasks were given on the command line',\n"
            "    'base': None,\n"
            "    'failed_tasks': [],\n"
            "    'mutated': False,\n"
            "    'clean': True,\n"
            "    'judged': True,\n"
            "    'log_dir': '/tmp/fake',\n"
            "}))\n",
            encoding="utf-8",
        )


class MeasureVerdictCostTest(unittest.TestCase):
    """The measurer: what it runs, and what it writes down."""

    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.addCleanup(self._directory.cleanup)
        self.tmp = pathlib.Path(self._directory.name)
        self.out = self.tmp / "verdict-cost.json"
        self.fake_gradle = _FakeGradle(self.tmp)

    def _record(self) -> dict:
        return json.loads(self.out.read_text(encoding="utf-8"))

    def test_the_phases_are_measured_and_written_down(self):
        """A measurement that is not written down did not happen."""
        result = run_measurer(
            "--repo", str(self.tmp),
            "--out", str(self.out),
            "--gradle-cmd", self.fake_gradle.command,
            "--phases", "detekt,auto-correct,together",
            "--sha", "deadbeef",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        record = self._record()
        self.assertEqual(record["schemaVersion"], 1)
        self.assertEqual(
            [phase["name"] for phase in record["phases"]],
            ["detekt", "auto-correct", "together"],
        )
        for phase in record["phases"]:
            self.assertIsInstance(phase["durationMs"], int)
            self.assertGreaterEqual(phase["durationMs"], 0)
            self.assertEqual(phase["exitCode"], 0)
            self.assertIn(":shared:detekt", phase["command"])

    def test_the_record_says_where_and_when_it_was_measured(self):
        """A duration without its machine and its moment is not a fact."""
        run_measurer(
            "--repo", str(self.tmp),
            "--out", str(self.out),
            "--gradle-cmd", self.fake_gradle.command,
            "--phases", "detekt",
            "--sha", "deadbeef",
        )

        record = self._record()
        self.assertEqual(record["sha"], "deadbeef")
        self.assertTrue(record["measuredAt"])
        self.assertEqual(record["status"], "fresh")
        self.assertIn("os", record["environment"])
        self.assertIn("cpus", record["environment"])

    def test_the_verdict_phase_measures_the_verdict_itself(self):
        """The end-to-end number is the number the push actually waits for."""
        fake = _FakeVerdict(self.tmp)
        result = run_measurer(
            "--repo", str(self.tmp),
            "--out", str(self.out),
            "--gradle-cmd", self.fake_gradle.command,
            "--verdict-script", str(fake.path),
            "--phases", "verdict",
            "--sha", "deadbeef",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        record = self._record()
        phases = [phase["name"] for phase in record["phases"]]
        self.assertIn("verdict", phases)
        self.assertEqual(record["verdict"]["tasks"], [":shared:detekt"])
        self.assertTrue(record["verdict"]["clean"])

    def test_an_unknown_phase_is_refused(self):
        """A phase the measurer cannot measure is not a phase it guesses at."""
        result = run_measurer(
            "--out", str(self.out),
            "--phases", "telepathy",
        )

        self.assertEqual(result.returncode, 1)
        self.assertIn("telepathy", result.stderr)

    def test_the_digest_phase_measures_the_digest(self):
        """The digest is the verdict's floor: it runs even when nothing changed."""
        if subprocess.run(["git", "--version"], capture_output=True).returncode != 0:
            self.skipTest("git is not available")
        repo = self.tmp / "tree"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        (repo / "A.kt").write_text("fun a() = 1\n", encoding="utf-8")

        result = run_measurer(
            "--repo", str(repo),
            "--out", str(self.out),
            "--phases", "digest",
            "--sha", "deadbeef",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        record = self._record()
        self.assertEqual(record["phases"][0]["name"], "digest")
        self.assertGreaterEqual(record["phases"][0]["durationMs"], 0)
        self.assertTrue(record["treeDigest"])

    def test_a_record_that_is_too_old_is_stale(self):
        """A price measured weeks ago describes a tree that has moved on."""
        self.out.write_text(
            json.dumps({
                "schemaVersion": 1,
                "measuredAt": "2020-01-01",
                "status": "fresh",
                "staleReason": "",
                "expiresDays": 30,
                "sha": "deadbeef",
                "treeDigest": "",
                "environment": {},
                "host": {},
                "verdict": {},
                "phases": [],
                "notes": [],
            }),
            encoding="utf-8",
        )

        result = run_measurer("--out", str(self.out), "--refresh", "--repo", str(self.tmp))

        self.assertEqual(result.returncode, 0, result.stderr)
        record = self._record()
        self.assertEqual(record["status"], "stale")
        self.assertIn("days ago", record["staleReason"])

    def test_a_record_whose_tree_moved_is_stale(self):
        """The numbers describe the tree they were measured on, not this one."""
        if subprocess.run(["git", "--version"], capture_output=True).returncode != 0:
            self.skipTest("git is not available")
        repo = self.tmp / "tree"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        (repo / "A.kt").write_text("fun a() = 1\n", encoding="utf-8")

        self.out.write_text(
            json.dumps({
                "schemaVersion": 1,
                "measuredAt": "2999-01-01",
                "status": "fresh",
                "staleReason": "",
                "expiresDays": 30,
                "sha": "deadbeef",
                "treeDigest": "a-digest-of-another-tree",
                "environment": {},
                "host": {},
                "verdict": {},
                "phases": [],
                "notes": [],
            }),
            encoding="utf-8",
        )

        result = run_measurer("--out", str(self.out), "--refresh", "--repo", str(repo))

        self.assertEqual(result.returncode, 0, result.stderr)
        record = self._record()
        self.assertEqual(record["status"], "stale")
        self.assertIn("the tree changed", record["staleReason"])


class ThePublishedVerdictSectionTest(unittest.TestCase):
    """The page: rendered from the record, and honest about what it has."""

    def setUp(self) -> None:
        self.baseline = {
            "schemaVersion": 1,
            "recordedAt": "2026-10-21T15:00:39Z",
            "environment": {"os": "Linux", "cpus": 16},
            "gates": {},
            "steps": {},
        }

    def _record(self, **overrides) -> dict:
        record = {
            "schemaVersion": 1,
            "measuredAt": "2026-10-23",
            "status": "fresh",
            "staleReason": "",
            "expiresDays": 30,
            "sha": "5896686b",
            "treeDigest": "",
            "environment": {},
            "host": {},
            "verdict": {},
            "phases": [
                {
                    "name": "detekt",
                    "what": "the analysis the verdict waits for",
                    "command": "./gw :shared:detekt --rerun",
                    "durationMs": 307000,
                    "exitCode": 0,
                    "note": "",
                },
                {
                    "name": "auto-correct",
                    "what": "the same run with `--auto-correct`",
                    "command": "./gw :shared:detekt --auto-correct --rerun",
                    "durationMs": 423000,
                    "exitCode": 0,
                    "note": "",
                },
            ],
            "notes": [],
        }
        record.update(overrides)
        return record

    def test_the_numbers_come_from_the_record(self):
        """The point of the whole change: the page reads the record."""
        text = costs.render_document(self.baseline, self._record())

        self.assertIn("| detekt | 5 min 07 s |", text)
        self.assertIn("| auto-correct | 7 min 03 s |", text)
        self.assertIn("1 min 56 s", text)

    def test_a_different_record_gives_a_different_page(self):
        """If the number were written in the template, this would fail."""
        fast = self._record()
        fast["phases"] = [dict(fast["phases"][0], durationMs=1000)]

        self.assertNotEqual(
            costs.render_document(self.baseline, fast),
            costs.render_document(self.baseline, self._record()),
        )

    def test_without_a_record_the_page_says_the_price_is_unknown(self):
        """A missing measurement is not a price of zero."""
        text = costs.render_document(self.baseline, None)

        self.assertIn("No verdict measurement is recorded", text)
        self.assertIn("unknown", text)
        self.assertIn("python3 scripts/measure-verdict-cost.py", text)
        self.assertNotIn("| detekt |", text)

    def test_a_stale_record_is_published_as_stale(self):
        """An old number may be shown, but never as a fresh one."""
        record = self._record(
            status="stale",
            staleReason="the tree changed since the measurement",
        )

        text = costs.render_document(self.baseline, record)

        self.assertIn("**This record is stale**", text)
        self.assertIn("the tree changed since the measurement", text)
        self.assertIn("| detekt | 5 min 07 s |", text)

    def test_the_committed_record_says_what_the_page_needs(self):
        """The record in the repository is the one the page is rendered from."""
        self.assertTrue(RECORD.is_file(), f"{RECORD} is missing")
        record = json.loads(RECORD.read_text(encoding="utf-8"))

        self.assertEqual(record["schemaVersion"], 1)
        for key in ("measuredAt", "status", "sha", "environment", "host", "phases"):
            self.assertIn(key, record)
        self.assertTrue(record["phases"], "the committed record carries no phase")
        for phase in record["phases"]:
            for key in ("name", "what", "command", "durationMs", "exitCode"):
                self.assertIn(key, phase)

    def test_the_committed_record_renders_a_page_with_numbers(self):
        """The committed record and the committed page agree."""
        record = json.loads(RECORD.read_text(encoding="utf-8"))
        text = costs.render_document(self.baseline, record)

        self.assertIn("### The price of the pre-push verdict", text)
        for phase in record["phases"]:
            self.assertIn(f"| {phase['name']} |", text)


if __name__ == "__main__":
    unittest.main()
