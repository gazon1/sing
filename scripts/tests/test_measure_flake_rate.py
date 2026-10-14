"""Unit tests for measure-flake-rate.py.

Run with: python3 -m unittest discover -s scripts/tests

The two flake signals are kept distinct throughout, because conflating them is
the defect this script exists to avoid:

* **Flake A** — terminal status flips between consecutive runs (reuses
  ``check-flaky-tests.compare`` per consecutive pair).
* **Flake B** — ``<flakyFailure>`` children inside a single merged
  ``<testcase>``, which Gradle's ``mergeReruns=true`` writes once per attempt
  that failed before a later attempt passed.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_scripts_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "scripts"
_spec = importlib.util.spec_from_file_location(
    "measure_flake_rate", _scripts_dir / "measure-flake-rate.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "measure_flake_rate"
_module.__file__ = str(_scripts_dir / "measure-flake-rate.py")
sys.modules["measure_flake_rate"] = _module  # must precede exec_module
_spec.loader.exec_module(_module)
import measure_flake_rate as mfr  # noqa: E402

SUITE = (
    '<testsuite name="{name}" tests="1">'
    '<testcase classname="{cls}" name="{case}">{body}</testcase>'
    "</testsuite>"
)
BODIES = {
    "pass": "",
    "fail": '<failure message="boom"/>',
    "skip": "<skipped/>",
}


def write_run(directory: pathlib.Path, cases) -> pathlib.Path:
    """cases: iterable of (class, case, body_html)."""
    directory.mkdir(parents=True, exist_ok=True)
    for cls, case, body in cases:
        safe = f"{cls}.{case}".replace(" ", "_")
        (directory / f"TEST-{safe}.xml").write_text(
            SUITE.format(name=safe, cls=cls, case=case, body=body),
            encoding="utf-8",
        )
    return directory


class TestFlakyFailureCount(unittest.TestCase):
    def test_absent_dir_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(mfr._flaky_failure_count(pathlib.Path(tmp) / "nope"), {})

    def test_counts_flaky_failure_children(self):
        with tempfile.TemporaryDirectory() as tmp:
            body = '<flakyFailure message="attempt 1"/><flakyFailure message="attempt 2"/>'
            d = write_run(pathlib.Path(tmp), [("A", "one", body)])
            self.assertEqual(mfr._flaky_failure_count(d), {"A::one": 2})

    def test_plain_pass_has_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = write_run(pathlib.Path(tmp), [("A", "one", "")])
            self.assertEqual(mfr._flaky_failure_count(d), {})


class TestMeasure(unittest.TestCase):
    def test_stable_pass_is_not_flaky(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            runs = [
                write_run(root / f"run{i}", [("A", "one", "")]) for i in range(3)
            ]
            result = mfr.measure(runs, attempts=3)
            self.assertEqual(result["tests"]["A::one"]["attempts"], 3)
            self.assertEqual(result["tests"]["A::one"]["passed"], 3)
            self.assertFalse(result["tests"]["A::one"]["flaky"])

    def test_flaky_failure_marks_flaky_b(self):
        # One run, merged retries: passed eventually but failed twice first.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            body = '<flakyFailure message="a1"/><flakyFailure message="a2"/>'
            runs = [write_run(root / "run0", [("A", "one", body)])]
            result = mfr.measure(runs, attempts=1)
            t = result["tests"]["A::one"]
            self.assertTrue(t["flaky"])
            self.assertEqual(t["flaky_failures"], 2)
            self.assertEqual(t["passed"], 1)

    def test_status_flip_marks_flaky_a(self):
        # No <flakyFailure> anywhere, but run1 fails and run2 passes.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            runs = [
                write_run(root / "run0", [("A", "one", "")]),
                write_run(root / "run1", [("A", "one", BODIES["fail"])]),
                write_run(root / "run2", [("A", "one", "")]),
            ]
            result = mfr.measure(runs, attempts=3)
            t = result["tests"]["A::one"]
            self.assertTrue(t["flaky"])
            self.assertTrue(t["flip"])
            self.assertEqual(t["flaky_failures"], 0)

    def test_skipped_flip_is_not_flaky(self):
        # pass -> skipped is a coverage change, not a timing failure.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            runs = [
                write_run(root / "run0", [("A", "one", "")]),
                write_run(root / "run1", [("A", "one", BODIES["skip"])]),
            ]
            result = mfr.measure(runs, attempts=2)
            self.assertFalse(result["tests"]["A::one"]["flaky"])

    def test_missing_run_is_reported(self):
        # Test A appears in only 1 of 2 runs.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            runs = [
                write_run(root / "run0", [("A", "one", "")]),
                write_run(root / "run1", [("A", "two", "")]),
            ]
            result = mfr.measure(runs, attempts=2)
            self.assertEqual(result["tests"]["A::one"]["attempts"], 1)
            self.assertEqual(result["tests"]["A::two"]["attempts"], 1)

    def test_error_element_counts_as_failed_for_flip(self):
        # <error> is a failing outcome; flip must still register.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            err = '<error message="oom"/>'
            runs = [
                write_run(root / "run0", [("A", "one", err)]),
                write_run(root / "run1", [("A", "one", "")]),
            ]
            result = mfr.measure(runs, attempts=2)
            self.assertTrue(result["tests"]["A::one"]["flaky"])
            self.assertTrue(result["tests"]["A::one"]["flip"])


class TestBaselineAck(unittest.TestCase):
    def test_acknowledged_class_suppresses_rate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            baseline = root / "baseline.txt"
            baseline.write_text(
                "com.example.Flammy  # understood nondeterminism\n", encoding="utf-8"
            )
            ack = mfr._load_acknowledged(baseline)
            self.assertTrue(mfr._is_acknowledged("com.example.Flammy::case", ack))
            self.assertFalse(mfr._is_acknowledged("com.example.Sound::case", ack))


if __name__ == "__main__":
    unittest.main()
