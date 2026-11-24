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


class CorpusBlockTest(unittest.TestCase):
    """The `corpus` block is checked, not just kept.

    The block records the size of the corpus the floors were measured against.
    It is metadata, and metadata nobody reads rots: on `main` it still said
    133 specs / 260 claimed cells / 103 carriers while the tree measured
    141 / 161 / 92, and every registered gate stayed green throughout. So the
    three numbers are recomputed from the traceability package and a mismatch
    is a failure rather than a silent difference nobody notices.
    """

    def test_the_recorded_corpus_matches_the_measured_corpus(self):
        """The committed block is the one the tree actually has."""
        result = _run_gate(config_path=FLOOR_FILE)
        self.assertEqual(0, result.returncode, result.stdout)

    def test_a_stale_corpus_count_fails_the_gate(self):
        config = _recorded_floors()
        config["corpus"]["claimed_cells"] = 282
        result = _run_gate(json.dumps(config))
        self.assertEqual(1, result.returncode)
        self.assertIn("`corpus` block does not match", result.stdout)
        self.assertIn("claimed_cells: recorded 282", result.stdout)

    def test_a_stale_corpus_count_fails_even_when_the_floors_are_met(self):
        """Corpus drift is not masked by floors that pass.

        The floors and the corpus are separate claims about the same tree. A
        gate that only looked at the floors would keep a stale corpus green.
        """
        config = _recorded_floors()
        config["corpus"]["specs"] = 999
        result = _run_gate(json.dumps(config))
        self.assertEqual(1, result.returncode)
        self.assertIn("specs: recorded 999", result.stdout)

    def test_a_floor_file_without_a_corpus_block_is_still_accepted(self):
        """The block is optional; a file without one is not a broken file."""
        config = _recorded_floors()
        config.pop("corpus", None)
        result = _run_gate(json.dumps(config))
        self.assertEqual(0, result.returncode, result.stdout)

    def test_accept_growth_does_not_excuse_a_stale_corpus(self):
        """The escape hatch covers holes, not a corpus claim that cannot be compared.

        `--accept-growth` exists for the commit that knowingly opens holes. A
        stale corpus is a different thing: it is a number the gate cannot
        compare, and the gate that printed the mismatch must not exit 0.
        """
        config = _recorded_floors()
        # A metric below its floor would be growth the flag excuses; the
        # corpus is wrong at the same time.
        for floor in config["floors"]:
            floor["max"] = 10_000
        for floor in config["floors"]:
            if floor["metric"] == "dark_scenarios":
                floor["max"] = 0
        config["corpus"]["specs"] = 999
        result = _run_gate(json.dumps(config), "--accept-growth")
        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("specs: recorded 999", result.stdout)

    def test_accept_growth_still_excuses_a_growing_metric(self):
        """The escape hatch keeps working for the case it was made for."""
        config = _recorded_floors()
        for floor in config["floors"]:
            floor["max"] = 10_000
        for floor in config["floors"]:
            if floor["metric"] == "dark_scenarios":
                floor["max"] = 0
        result = _run_gate(json.dumps(config), "--accept-growth")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("dark_scenarios grew", result.stdout)

    def test_a_malformed_corpus_block_is_an_error_not_a_skipped_check(self):
        """A block that is there must be usable; a broken one is not ignored.

        Skipping a malformed block would be the same failure the check exists
        to catch: a corpus claim that is never compared. The block being
        optional is a different thing from the block being broken.
        """
        for broken in (None, "141/161/94", 42, ["141", "161", "94"]):
            config = _recorded_floors()
            config["corpus"] = broken
            result = _run_gate(json.dumps(config))
            self.assertEqual(1, result.returncode, repr(broken))
            self.assertIn("`corpus` block", result.stdout)
            self.assertNotIn("Traceback", result.stdout)

    def test_a_corpus_block_missing_a_field_is_an_error(self):
        """A claim with no number in it cannot be compared, so it is an error."""
        config = _recorded_floors()
        config["corpus"].pop("carriers")
        result = _run_gate(json.dumps(config))
        self.assertEqual(1, result.returncode)
        self.assertIn("has no carriers", result.stdout)
        self.assertNotIn("Traceback", result.stdout)

    def test_a_non_integer_corpus_value_is_an_error(self):
        """The corpus is counted in integers; a word in that field is a mistake."""
        config = _recorded_floors()
        config["corpus"]["specs"] = "unknown"
        result = _run_gate(json.dumps(config))
        self.assertEqual(1, result.returncode)
        self.assertIn("non-integer", result.stdout)
        self.assertNotIn("Traceback", result.stdout)


if __name__ == "__main__":
    unittest.main()
