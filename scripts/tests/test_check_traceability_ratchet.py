"""Tests for check-traceability-ratchet.py — the configuration side of the ratchet.

The scenario matrix reached nineteen specs, thirty-four claimed cells and three
carriers, and nothing anywhere compared the hole count to anything. A hole is
not a mistake — it is the point of the matrix, the honest statement of what the
suite does not verify — but a *growing* hole count is a regression nobody
notices, and it was not hypothetical here: one tranche landed with zero carriers
and took the matrix from two holes to thirty-two in a single green commit.

The metric computation and the growth verdict are covered by
`test_traceability_ratchet.py`. What is pinned here is the part around it — the
floor file the gate reads, and the way the gate treats a floor it cannot find:

* a floor file that does not exist, or is not JSON, is a failure and not a
  silent pass — a gate that cannot read its input is not a green gate;
* a floor file that records no floor for one of the ratcheted metrics is a
  failure, because a metric with no recorded number cannot fail: raising the
  missing floor is then the only way to make the gate compare anything, and it
  happens silently;
* every ratcheted metric is compared independently, so growth in one of them
  is reported even while the others sit exactly at their floor — a gate that
  failed only when *all* metrics moved would let a regression hide behind the
  metrics that happened to improve;
* the default floor file is the one in this repository, and it records a floor
  for every ratcheted metric.

The gate is run as a subprocess, because the exit code is the contract CI acts
on. The corpus is never mutated: growth is simulated by pointing `--config` at
a temporary copy of the floor file.
"""

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_traceability_ratchet_config", SCRIPTS / "check-traceability-ratchet.py"
)
ratchet = importlib.util.module_from_spec(_spec)
sys.modules["check_traceability_ratchet_config"] = ratchet
_spec.loader.exec_module(ratchet)

FLOOR_FILE = ROOT / "config/docs/traceability-ratchet.json"


def _run_gate(config_text=None, *args, config_path=None):
    """Run the gate as a subprocess with the given floor file."""
    with tempfile.TemporaryDirectory() as tmp:
        if config_path is None:
            config_path = pathlib.Path(tmp) / "ratchet.json"
            config_path.write_text(config_text or "", encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "check-traceability-ratchet.py"),
             "--config", str(config_path), *args],
            capture_output=True,
            text=True,
            cwd=ROOT,
            check=False,
        )


def _recorded_floors():
    return json.loads(FLOOR_FILE.read_text(encoding="utf-8"))


def _with_max(config, cap):
    for floor in config["floors"]:
        floor["max"] = cap
    return json.dumps(config)


class ConfigErrorTest(unittest.TestCase):
    """A floor file the gate cannot read is a failure, not a pass."""

    def test_a_missing_floor_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = pathlib.Path(tmp) / "not-here.json"
            result = _run_gate(config_path=missing)
        self.assertEqual(1, result.returncode)
        self.assertIn("ratchet file not found", result.stdout)

    def test_a_floor_file_that_is_not_json_fails(self):
        result = _run_gate("{not json")
        self.assertEqual(1, result.returncode)
        self.assertIn("is not valid JSON", result.stdout)

    def test_a_floor_file_with_no_floor_for_a_metric_fails(self):
        """A metric with no recorded number cannot fail.

        The metric is still computed, still compared to nothing, and the gate
        would stay green while the matrix grew.
        """
        config = _recorded_floors()
        config["floors"] = [
            floor for floor in config["floors"] if floor["metric"] != "dark_scenarios"
        ]
        result = _run_gate(json.dumps(config))
        self.assertEqual(1, result.returncode)
        self.assertIn("has no floor for dark_scenarios", result.stdout)

    def test_a_floor_file_with_no_floors_at_all_fails(self):
        result = _run_gate(json.dumps({"floors": []}))
        self.assertEqual(1, result.returncode)
        self.assertIn("has no floor for", result.stdout)


class PerMetricComparisonTest(unittest.TestCase):
    """Each ratcheted metric is compared on its own."""

    def test_growth_in_one_metric_is_reported_while_the_others_pass(self):
        """A metric that improved must not hide a metric that grew.

        The gate compares each metric against its own floor; a gate that only
        failed when every metric moved would let a real regression hide behind
        the metrics that happened to improve.
        """
        config = _recorded_floors()
        for floor in config["floors"]:
            floor["max"] = 10_000
        for floor in config["floors"]:
            if floor["metric"] == "dark_scenarios":
                floor["max"] = 0
        result = _run_gate(json.dumps(config))
        self.assertEqual(1, result.returncode)
        self.assertIn("dark_scenarios grew", result.stdout)

    def test_a_metric_below_its_floor_is_reported_as_an_advisory(self):
        """A floor left above reality stops catching, and the gate says so."""
        result = _run_gate(_with_max(_recorded_floors(), 10_000))
        self.assertEqual(0, result.returncode)
        self.assertIn("below its floor", result.stdout)

    def test_the_green_run_names_every_ratcheted_metric(self):
        """A metric that is compared but never printed is decorative."""
        config = _recorded_floors()
        for floor in config["floors"]:
            floor["max"] = 10_000
        result = _run_gate(json.dumps(config))
        self.assertEqual(0, result.returncode)
        green = [line for line in result.stdout.splitlines() if "OK —" in line]
        self.assertEqual(1, len(green), result.stdout)
        for metric in ratchet.METRICS:
            self.assertIn(metric.key, green[0])


class MetricDeclarationTest(unittest.TestCase):
    """The metrics the gate ratchets, and the floor file that records them."""

    def test_the_metric_keys_are_unique(self):
        keys = [metric.key for metric in ratchet.METRICS]
        self.assertEqual(len(keys), len(set(keys)))

    def test_every_metric_grows_in_the_higher_direction(self):
        """A hole count rises by getting worse, so `higher` is the regression."""
        for metric in ratchet.METRICS:
            self.assertEqual("higher", metric.worse_when, metric.key)

    def test_the_default_floor_file_exists(self):
        self.assertTrue(FLOOR_FILE.is_file())
        self.assertEqual(
            str(ROOT / ratchet.DEFAULT_CONFIG),
            str(FLOOR_FILE),
        )

    def test_the_floor_file_records_a_floor_for_every_metric(self):
        recorded = {floor["metric"] for floor in _recorded_floors()["floors"]}
        self.assertEqual({metric.key for metric in ratchet.METRICS}, recorded)


if __name__ == "__main__":
    unittest.main()
