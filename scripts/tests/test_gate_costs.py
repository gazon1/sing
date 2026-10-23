"""Tests for `sgate gate-costs` — the cost of the verification loop.

The command exists because the local loop grew by minutes at a time and nobody could
say so. These tests check the three properties that make the number worth reading:

* the measurement is taken from the runs that happened anyway, and both producers
  (the gate suite and the build steps) are read;
* a comparison is only drawn when the two measurements came from the same machine —
  a duration from another machine is not a regression, and saying so is the whole
  point of recording the environment;
* the published page and the recorded baseline cannot drift apart, because the gate
  that compares them is shown failing here.

The last one is the test that matters: a gate nobody has seen fail is a gate whose
verdict nobody can trust.
"""

import json
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

from click.testing import CliRunner  # noqa: E402

import sgate  # noqa: E402
import sgate.commands.gate_costs as costs  # noqa: E402
from sgate.commands.gate_costs import (  # noqa: E402
    REGRESSION_FACTOR,
    compare,
    gate_costs,
    read_measurement,
    render_document,
)


class _Paths:
    """The three files the command reads and writes, pointed at a temporary tree."""

    def __init__(self, directory: pathlib.Path) -> None:
        self.baseline = directory / "gate-benchmarks.json"
        self.document = directory / "gate-costs.md"
        self.gate_run = directory / "gate-run.json"
        self.steps = directory / "cycle-steps.txt"

    def apply(self) -> None:
        """Point the command at these files."""
        sgate.repo.GATE_BENCHMARKS_JSON = self.baseline
        sgate.repo.GATE_COSTS_MD = self.document
        sgate.repo.GATE_RUN_JSON = self.gate_run
        sgate.repo.CYCLE_STEPS_TXT = self.steps


class GateCostsTest(unittest.TestCase):
    """What the command reads, compares, and publishes."""

    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.addCleanup(self._directory.cleanup)
        self.paths = _Paths(pathlib.Path(self._directory.name))
        self._saved = (
            sgate.repo.GATE_BENCHMARKS_JSON,
            sgate.repo.GATE_COSTS_MD,
            sgate.repo.GATE_RUN_JSON,
            sgate.repo.CYCLE_STEPS_TXT,
        )
        self.addCleanup(self._restore)
        self.paths.apply()

    def _restore(self) -> None:
        (
            sgate.repo.GATE_BENCHMARKS_JSON,
            sgate.repo.GATE_COSTS_MD,
            sgate.repo.GATE_RUN_JSON,
            sgate.repo.CYCLE_STEPS_TXT,
        ) = self._saved

    # ── The measurement ───────────────────────────────────────────────────────

    def test_the_steps_file_is_read(self):
        """`check.sh` writes the build steps; the command reads them."""
        self.paths.steps.write_text(
            "# name\tmilliseconds\n"
            "shared:jvmTest\t45000\n"
            "detekt\t12000\n",
            encoding="utf-8",
        )
        measurement = read_measurement()
        self.assertEqual(
            measurement["steps"],
            {"shared:jvmTest": 45000, "detekt": 12000},
        )

    def test_a_malformed_step_line_is_ignored_rather_than_fatal(self):
        """A truncated line is not a number, and a number is not a line."""
        self.paths.steps.write_text(
            "shared:jvmTest\t45000\n"
            "broken line without a tab\n"
            "detekt\tnot-a-number\n",
            encoding="utf-8",
        )
        measurement = read_measurement()
        self.assertEqual(measurement["steps"], {"shared:jvmTest": 45000})

    def test_the_gate_run_file_is_read(self):
        """`sgate run-all` writes the gates; the command reads them."""
        self.paths.gate_run.write_text(
            json.dumps({"schemaVersion": 1, "gates": {"a gate": 1234, "another": 42}}),
            encoding="utf-8",
        )
        measurement = read_measurement()
        self.assertEqual(measurement["gates"], {"a gate": 1234, "another": 42})

    def test_a_measurement_with_no_files_is_empty_rather_than_missing(self):
        """A cycle that was never measured is a fact, not an error."""
        measurement = read_measurement()
        self.assertEqual(measurement["gates"], {})
        self.assertEqual(measurement["steps"], {})

    # ── The comparison ────────────────────────────────────────────────────────

    def test_a_slower_measurement_is_named(self):
        """The point of the whole thing: a step that got slower is named."""
        measurement = {"environment": {"os": "linux"}, "steps": {"shared:jvmTest": 9000}}
        baseline = {
            "environment": {"os": "linux"},
            "steps": {"shared:jvmTest": {"samples": [1000], "medianMs": 1000}},
        }
        verdict = compare(measurement, baseline)
        self.assertEqual(verdict["slower"][0]["name"], "shared:jvmTest")
        self.assertEqual(verdict["slower"][0]["currentMs"], 9000)

    def test_a_measurement_within_the_factor_is_not_a_finding(self):
        measurement = {"environment": {"os": "linux"}, "steps": {"shared:jvmTest": 2000}}
        baseline = {
            "environment": {"os": "linux"},
            "steps": {"shared:jvmTest": {"samples": [1000], "medianMs": 1000}},
        }
        verdict = compare(measurement, baseline)
        self.assertEqual(verdict["slower"], [])

    def test_the_environment_difference_is_named(self):
        """"Another machine" is true but useless when the difference is one interpreter."""
        measurement = {"environment": {"os": "linux", "python": "3.14.8"}, "steps": {}}
        baseline = {"environment": {"os": "linux", "python": "3.12.15"}, "steps": {}}

        verdict = compare(measurement, baseline)

        self.assertFalse(verdict["comparable"])
        self.assertEqual(["python: 3.12.15 → 3.14.8"], verdict["differences"])

    def test_a_measurement_from_another_machine_concludes_nothing(self):
        """A duration from another machine is not a regression."""
        measurement = {"environment": {"os": "linux", "cpus": 8}, "steps": {"a": 9000}}
        baseline = {
            "environment": {"os": "macos", "cpus": 16},
            "steps": {"a": {"samples": [1000], "medianMs": 1000}},
        }
        verdict = compare(measurement, baseline)
        self.assertFalse(verdict["comparable"])
        self.assertEqual(verdict["slower"], [])
        self.assertEqual(verdict["findings"][0]["verdict"], "not-comparable")

    def test_a_step_with_no_baseline_is_new_rather_than_slow(self):
        measurement = {"environment": {}, "steps": {"a new step": 10}}
        verdict = compare(measurement, {"environment": {}, "steps": {}})
        self.assertEqual(verdict["findings"][0]["verdict"], "new")

    # ── The published page ────────────────────────────────────────────────────

    def test_the_page_carries_the_numbers(self):
        baseline = {
            "recordedAt": "2026-10-21T10:00:00Z",
            "environment": {"os": "linux", "cpus": 8},
            "gates": {"a gate": {"samples": [1200], "medianMs": 1200}},
            "steps": {"shared:jvmTest": {"samples": [45000], "medianMs": 45000}},
        }
        text = render_document(baseline)
        self.assertIn("| Gate: a gate | 1.2 s |", text)
        self.assertIn("| Step: shared:jvmTest | 45.0 s |", text)
        self.assertIn("os=linux", text)

    def test_the_gate_fails_when_the_page_is_edited_by_hand(self):
        """The property the gate exists for: a hand-edited number is caught."""
        baseline = {
            "schemaVersion": 1,
            "recordedAt": "2026-10-21T10:00:00Z",
            "environment": {"os": "linux"},
            "gates": {"a gate": {"samples": [1200], "medianMs": 1200}},
            "steps": {},
        }
        self.paths.baseline.write_text(json.dumps(baseline), encoding="utf-8")
        self.paths.document.write_text(render_document(baseline), encoding="utf-8")

        runner = CliRunner()
        clean = runner.invoke(gate_costs, ["--check"], standalone_mode=False)
        self.assertEqual(clean.exit_code, 0, clean.output)

        edited = self.paths.document.read_text(encoding="utf-8").replace(
            "| Gate: a gate | 1.2 s |", "| Gate: a gate | 0.0 s |"
        )
        self.paths.document.write_text(edited, encoding="utf-8")
        dirty = runner.invoke(gate_costs, ["--check"], standalone_mode=False)
        self.assertEqual(dirty.exit_code, 1, dirty.output)

    def test_the_gate_speaks_the_json_contract(self):
        """Every gate dispatched through `sgate` is called with --json."""
        baseline = {"schemaVersion": 1, "environment": {}, "gates": {}, "steps": {}}
        self.paths.baseline.write_text(json.dumps(baseline), encoding="utf-8")
        self.paths.document.write_text(render_document(baseline), encoding="utf-8")

        runner = CliRunner()
        result = runner.invoke(gate_costs, ["--check", "--json"], standalone_mode=False)
        self.assertEqual(result.exit_code, 0, result.output)
        document = json.loads(result.output)
        self.assertTrue(document["passed"])

    def test_the_regression_factor_is_a_factor(self):
        """Three times the baseline, not three times the average of nothing."""
        self.assertEqual(REGRESSION_FACTOR, 3)


class CiCostTest(unittest.TestCase):
    """The CI side of the loop: the last run's jobs, read from the runner's own record.

    These numbers are not comparable with the local ones — a hosted runner is not
    this machine — so they are published as a record of one run, with no median and
    no baseline.
    """

    def test_a_job_duration_is_read_from_its_two_timestamps(self):
        duration = costs._duration_ms(
            '2026-10-21T10:00:00Z', '2026-10-21T10:01:30Z'
        )

        self.assertEqual(90_000, duration)

    def test_a_job_that_has_not_finished_has_no_duration(self):
        self.assertIsNone(costs._duration_ms('2026-10-21T10:00:00Z', None))

    def test_a_timestamp_it_cannot_read_is_not_a_duration(self):
        self.assertIsNone(costs._duration_ms('not a timestamp', 'also not'))

    def test_a_skipped_job_has_no_duration(self):
        """A job that never ran is reported as finishing before it started.

        The runner still carries two timestamps for a skipped job, and they are
        not in order. A negative price is not a price, and a job that did not
        run has nothing to price.
        """
        self.assertIsNone(costs._duration_ms('2026-10-23T14:54:11Z', '2026-10-23T14:54:10Z'))

    def test_a_job_that_took_no_time_has_no_duration(self):
        self.assertIsNone(costs._duration_ms('2026-10-23T14:54:10Z', '2026-10-23T14:54:10Z'))

    def test_the_recorded_run_is_the_branch_run_that_has_finished(self):
        """The price is of the loop, so it is read from the branch's finished run.

        A run that is still going has no duration for the jobs that have not
        finished, and a pull request's run is the price of that pull request —
        not of the loop that blocks every merge.
        """
        asked: list[tuple[str, ...]] = []
        original = costs._gh

        def fake_gh(*args: str) -> str:
            asked.append(args)
            if "list" in args:
                return json.dumps([
                    {
                        "databaseId": 42,
                        "url": "https://example.invalid/run/42",
                        "createdAt": "2026-10-23T14:54:00Z",
                        "conclusion": "success",
                    }
                ])
            return json.dumps({"jobs": [
                {
                    "name": "Static gates",
                    "startedAt": "2026-10-23T14:54:12Z",
                    "completedAt": "2026-10-23T14:56:00Z",
                }
            ]})

        costs._gh = fake_gh
        try:
            run = costs.read_ci_run()
        finally:
            costs._gh = original

        self.assertIsNotNone(run)
        listing = [args for args in asked if "list" in args][0]
        self.assertIn("--status", listing)
        self.assertIn("completed", listing)
        self.assertIn("--branch", listing)
        self.assertIn(costs.CI_BRANCH, listing)

    def test_a_run_whose_jobs_cannot_be_read_is_not_recorded(self):
        """A failed read must not replace a recorded price with nothing.

        The recorded run carries the jobs, and the published section is rendered
        from them. A record with no jobs renders no section — so writing one
        would delete the price of the loop and say nothing about it.
        """
        original = costs._gh

        def fake_gh(*args: str) -> str:
            if "list" in args:
                return json.dumps([
                    {
                        "databaseId": 42,
                        "url": "https://example.invalid/run/42",
                        "createdAt": "2026-10-23T14:54:00Z",
                        "conclusion": "success",
                    }
                ])
            return json.dumps({"jobs": []})

        costs._gh = fake_gh
        try:
            self.assertIsNone(costs.read_ci_run())
        finally:
            costs._gh = original

    def test_a_skipped_job_does_not_reach_the_record(self):
        """A job that never ran has no price, and the record says so."""
        original = costs._gh

        def fake_gh(*args: str) -> str:
            if "list" in args:
                return json.dumps([
                    {
                        "databaseId": 42,
                        "url": "https://example.invalid/run/42",
                        "createdAt": "2026-10-23T14:54:00Z",
                        "conclusion": "success",
                    }
                ])
            return json.dumps({"jobs": [
                {
                    "name": "spec-create",
                    "startedAt": "2026-10-23T14:54:11Z",
                    "completedAt": "2026-10-23T14:54:10Z",
                },
                {
                    "name": "Static gates",
                    "startedAt": "2026-10-23T14:54:12Z",
                    "completedAt": "2026-10-23T14:56:00Z",
                },
            ]})

        costs._gh = fake_gh
        try:
            run = costs.read_ci_run()
        finally:
            costs._gh = original

        self.assertIsNotNone(run)
        jobs = run["jobs"] if isinstance(run, dict) else {}
        self.assertNotIn("spec-create", jobs)
        self.assertIn("Static gates", jobs)

    def test_without_gh_there_is_no_ci_measurement(self):
        """No `gh` is not a crash: the command says so and stops."""
        original = costs.shutil.which
        costs.shutil.which = lambda name: None
        try:
            self.assertIsNone(costs.read_ci_run())
        finally:
            costs.shutil.which = original


class PublishedPageTest(unittest.TestCase):
    """The published page: the local loop and the CI one, both priced."""

    def test_the_ci_section_is_published_when_the_baseline_carries_one(self):
        baseline = {
            "schemaVersion": 1,
            "recordedAt": "2026-10-21T10:00:00Z",
            "environment": {"os": "Linux"},
            "gates": {},
            "steps": {},
            "ci": {
                "runId": 1,
                "url": "https://example.invalid/run/1",
                "createdAt": "2026-10-21T09:00:00Z",
                "jobs": {"JVM tests, detekt, coverage": 1_890_000},
            },
        }

        text = costs.render_document(baseline)

        self.assertIn("### The last finished run of `main`", text)
        self.assertIn("JVM tests, detekt, coverage", text)
        self.assertIn("31 min 30 s", text)

    def test_without_a_ci_run_the_section_is_absent(self):
        baseline = {
            "schemaVersion": 1,
            "recordedAt": "2026-10-21T10:00:00Z",
            "environment": {},
            "gates": {},
            "steps": {},
        }

        text = costs.render_document(baseline)

        self.assertNotIn("### The last finished run", text)

    def test_the_verdict_of_the_recorded_run_is_published(self):
        """A reader must see whether the priced run was green.

        A price next to a failed run, without the verdict, reads as the price
        of a green one — and the price of a green run is the number a reader
        plans against.
        """
        baseline = {
            "schemaVersion": 1,
            "recordedAt": "2026-10-21T10:00:00Z",
            "environment": {},
            "gates": {},
            "steps": {},
            "ci": {
                "runId": 1,
                "url": "https://example.invalid/run/1",
                "createdAt": "2026-10-21T09:00:00Z",
                "conclusion": "failure",
                "jobs": {"JVM tests, detekt, coverage": 1_890_000},
            },
        }

        text = costs.render_document(baseline)

        self.assertIn("**failure**", text)

    def test_recording_the_local_loop_keeps_the_ci_record(self):
        """The CI side is a record, not a baseline: a re-record must not lose it."""
        baseline = {
            "schemaVersion": 1,
            "recordedAt": "2026-10-21T10:00:00Z",
            "environment": {},
            "gates": {"a gate": {"samples": [100], "medianMs": 100}},
            "steps": {},
            "ci": {"runId": 1, "jobs": {"a job": 1000}},
        }
        measurement = {"environment": {}, "gates": {}, "steps": {}}

        recorded = costs._record(baseline, measurement)

        self.assertEqual(baseline["ci"], recorded["ci"])


if __name__ == "__main__":
    unittest.main()


class TheHostLoad(unittest.TestCase):
    """A duration is a property of the gate and the host together."""

    def test_the_load_is_recorded(self):
        load = costs.host_load()
        if not load:
            self.skipTest("this platform does not report a load average")
        self.assertIn("load1m", load)
        self.assertIn("cpus", load)

    def test_the_load_is_not_part_of_the_environment(self):
        """The environment says whether two measurements are comparable at all.

        The load changes every minute; a comparison that included it would
        never hold, and every measurement would read as a different machine.
        """
        self.assertNotIn("load", costs.environment())

    def test_the_load_is_described_in_a_sentence(self):
        described = costs._describe_load({"load1m": 42.7, "cpus": 16})
        self.assertIn("42.7", described)
        self.assertIn("16 CPUs", described)

    def test_a_missing_load_is_described_as_nothing(self):
        self.assertEqual(costs._describe_load({}), "")

    def test_the_measurement_carries_the_host(self):
        measurement = costs.read_measurement()
        self.assertIn("host", measurement)
