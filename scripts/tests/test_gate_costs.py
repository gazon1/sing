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


if __name__ == "__main__":
    unittest.main()
