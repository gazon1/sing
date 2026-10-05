"""Self-tests for scripts/check-gate-honesty.py.

The script's job is to fail when a gate cannot tell a clean tree from a broken one. That makes it
the one script in the repository where a bug is indistinguishable from a pass, so its own logic is
tested here rather than left to the two-minute run it normally performs.

Nothing in this file shells out to Gradle. `probe_once` is exercised against a fake detekt that
writes a report, so the tests run in milliseconds and can cover the branches the real run cannot
reach cheaply — above all the branch that matters most, which is "the report does not mention the
rule, so this is a failure of the check".
"""

import pathlib
import subprocess
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "check_gate_honesty", SCRIPTS / "check-gate-honesty.py"
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

Probe = _mod.Probe
probe_once = _mod.probe_once


class FakeDetekt:
    """Stands in for the Gradle run: writes (or does not write) a report mentioning a rule id."""

    def __init__(self, report_rule_id, write_report=True, exit_code=1):
        self.report_rule_id = report_rule_id
        self.write_report = write_report
        self.exit_code = exit_code
        self.calls = 0

    def __call__(self):
        self.calls += 1
        if self.write_report and self.report_rule_id:
            _mod.REPORT.write_text(f"### app-error-code, {self.report_rule_id} (1)\n")
        return self.exit_code, "fake detekt output"


def with_fake_detekt(fake, fn, *args, **kwargs):
    original = _mod.run_detekt
    _mod.run_detekt = fake
    try:
        return fn(*args, **kwargs)
    finally:
        _mod.run_detekt = original


class TestProbeMechanics(unittest.TestCase):

    def setUp(self):
        self.probe = Probe(
            name="unittestprobe",
            rule_id="SomeRule",
            body="package com.singularity.todo.core.observability\n",
            why="self-test",
        )

    def tearDown(self):
        if self.probe.path.exists():
            self.probe.path.unlink()
        for stray in _mod.PROBE_DIR.glob("GateHonestyProbe*"):
            stray.unlink()

    def test_a_firing_probe_reports_success(self):
        fired, detail = with_fake_detekt(FakeDetekt("SomeRule"), probe_once, self.probe)
        self.assertTrue(fired, detail)
        self.assertIn("SomeRule", detail)

    def test_a_probe_that_does_not_fire_is_a_failure_not_a_pass(self):
        # The whole point. A script that reported success here would be the exact defect it
        # exists to catch, so this asserts the negative explicitly.
        fired, detail = with_fake_detekt(FakeDetekt("SomeOtherRule"), probe_once, self.probe)
        self.assertFalse(fired, "a silent gate must not be reported as honest")
        self.assertIn("does not mention SomeRule", detail)

    def test_a_missing_report_is_a_failure(self):
        fired, detail = with_fake_detekt(
            FakeDetekt(None, write_report=False), probe_once, self.probe
        )
        self.assertFalse(fired)
        self.assertIn("no report", detail)

    def test_the_failure_message_names_the_daemon_workaround(self):
        # #136's remedy is one command, and the person reading a failure at 11pm should not have
        # to go and look it up.
        fired, detail = with_fake_detekt(FakeDetekt("Other"), probe_once, self.probe)
        self.assertIn("./gw --stop", detail)

    def test_the_probe_file_is_removed_afterwards(self):
        with_fake_detekt(FakeDetekt("SomeRule"), probe_once, self.probe)
        self.assertFalse(self.probe.path.exists(), "the probe must not survive its own run")

    def test_the_probe_file_is_removed_even_when_the_probe_fails_to_fire(self):
        with_fake_detekt(FakeDetekt("Other"), probe_once, self.probe)
        self.assertFalse(
            self.probe.path.exists(),
            "a failing run is exactly when a leaked probe file is most likely",
        )

    def test_a_stale_report_is_removed_before_the_run(self):
        # Otherwise a report left by a previous run would be read as this run's evidence.
        _mod.REPORT.parent.mkdir(parents=True, exist_ok=True)
        _mod.REPORT.write_text("### app-error-code, SomeRule (1)\n")
        fake = FakeDetekt("Other")
        with_fake_detekt(fake, probe_once, self.probe)
        self.assertEqual(fake.calls, 1)
        self.assertFalse(_mod.REPORT.exists(), "a stale report must not outlive the run")


class TestProbesAreSelfContained(unittest.TestCase):

    def test_no_probe_imports_a_type_from_a_feature_package(self):
        # The first version of the unwiredReporter probe pointed at `TaskTimeSlot`, which is not
        # ViewModel-shaped. The rule correctly stayed quiet and the probe reported DID NOT FIRE —
        # the script working as designed, and a reminder that a probe pointing at the real
        # codebase rots the day someone renames a class, with a failure that names neither the
        # rule nor the rename.
        #
        # `core.error.AppError` is exempt: naming it IS the appErrorCode probe's whole point.
        for probe in _mod.PROBES:
            for line in probe.body.splitlines():
                if not line.startswith("import com.singularity"):
                    continue
                self.assertIn(
                    "com.singularity.todo.core.error.AppError",
                    line,
                    f"probe {probe.name} imports a real project type: {line.strip()}",
                )

    def test_every_probe_declares_a_unique_type(self):
        names = [p.path.name for p in _mod.PROBES]
        self.assertEqual(len(names), len(set(names)), "two probes would collide on one file")

    def test_every_probe_targets_a_rule_that_exists_in_source(self):
        src = (SCRIPTS.parent / "detekt-rules/src/main/kotlin/com/singularity/todo/detekt").glob(
            "*.kt"
        )
        corpus = "\n".join(p.read_text(encoding="utf-8") for p in src)
        for probe in _mod.PROBES:
            self.assertIn(
                probe.rule_id,
                corpus,
                f"probe {probe.name} expects {probe.rule_id}, which no rule file mentions",
            )

    def test_every_probe_states_why_it_exists(self):
        for probe in _mod.PROBES:
            self.assertGreater(len(probe.why), 60, f"probe {probe.name} has a stub explanation")


if __name__ == "__main__":
    unittest.main()
