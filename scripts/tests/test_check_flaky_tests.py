"""Unit tests for check-flaky-tests.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_scripts_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "scripts"
_spec = importlib.util.spec_from_file_location(
    "check_flaky_tests", _scripts_dir / "check-flaky-tests.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "check_flaky_tests"
_module.__file__ = str(_scripts_dir / "check-flaky-tests.py")
sys.modules["check_flaky_tests"] = _module  # must precede exec_module
_spec.loader.exec_module(_module)
import check_flaky_tests as cft

SUITE = (
    '<testsuite name="{name}" tests="1">'
    '<testcase classname="{cls}" name="{case}">{body}</testcase>'
    "</testsuite>"
)
BODIES = {"pass": "", "fail": "<failure message=\"boom\"/>", "skip": "<skipped/>"}


def write_run(directory: pathlib.Path, cases) -> pathlib.Path:
    """cases: iterable of (class, case, state)."""
    directory.mkdir(parents=True, exist_ok=True)
    for cls, case, state in cases:
        safe = f"{cls}.{case}".replace(" ", "_")
        (directory / f"TEST-{safe}.xml").write_text(
            SUITE.format(name=safe, cls=cls, case=case, body=BODIES[state]),
            encoding="utf-8",
        )
    return directory


class TestReadReport(unittest.TestCase):
    def test_absent_directory_is_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(cft.read_report(pathlib.Path(tmp) / "nope"))

    def test_maps_every_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = write_run(pathlib.Path(tmp), [
                ("A", "one", "pass"),
                ("A", "two", "fail"),
                ("A", "three", "skip"),
            ])
            self.assertEqual(
                cft.read_report(d),
                {"A::one": "passed", "A::two": "failed", "A::three": "skipped"},
            )

    def test_error_element_counts_as_failed(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            d.mkdir(exist_ok=True)
            (d / "x.xml").write_text(
                '<testsuite name="s" tests="1"><testcase classname="A" name="one">'
                '<error message="oom"/></testcase></testsuite>',
                encoding="utf-8",
            )
            self.assertEqual(cft.read_report(d), {"A::one": "failed"})


class TestCompare(unittest.TestCase):
    def test_buckets_are_mutually_sensible(self):
        buckets = cft.compare(
            {
                "A::passed_now": "passed",     # stays green
                "A::broke": "passed",          # -> new failure
                "A::flaky": "failed",          # -> recovered: the flake witness
                "A::chronic": "failed",       # -> still failing
                "A::vanished": "passed",      # -> gone from the report
                "A::newly_skipped": "passed", # -> passed, now skipped
            },
            {
                "A::passed_now": "passed",
                "A::broke": "failed",
                "A::flaky": "passed",
                "A::chronic": "failed",
                "A::newly_skipped": "skipped",
                "A::first_time": "skipped",   # skipped on arrival: not a "became"
            },
        )
        self.assertEqual(buckets["new_failures"], ["A::broke"])
        self.assertEqual(buckets["recovered"], ["A::flaky"])
        self.assertEqual(buckets["still_failing"], ["A::chronic"])
        self.assertEqual(buckets["became_skipped"], ["A::newly_skipped"])
        self.assertEqual([i for i, _ in buckets["disappeared"]], ["A::vanished"])

    def test_a_test_absent_before_and_failing_now_is_a_new_failure(self):
        buckets = cft.compare({}, {"A::brand_new": "failed"})
        self.assertEqual(buckets["new_failures"], ["A::brand_new"])


class TestBaseline(unittest.TestCase):
    def test_reason_after_hash_is_captured(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "b.txt"
            p.write_text("# header\nA::t  # real-time timeout\n", encoding="utf-8")
            self.assertEqual(cft.load_baseline(p), {"A::t": "real-time timeout"})

    def test_bare_class_name_acknowledges_every_test_in_it(self):
        """Nondeterminism is a property of the fixture, not of one assertion."""
        acknowledged = {"A": "shared Room DB handle"}
        self.assertTrue(cft.is_acknowledged("A::one", acknowledged))
        self.assertTrue(cft.is_acknowledged("A::two", acknowledged))
        self.assertFalse(cft.is_acknowledged("B::one", acknowledged))


class TestGate(unittest.TestCase):
    def setUp(self):
        self._saved_argv = sys.argv
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.baseline = self.tmp / "flaky.txt"
        self.baseline.write_text("", encoding="utf-8")
        self.addCleanup(self._restore)

    def _restore(self):
        sys.argv = self._saved_argv

    def _run(self, previous_dir, current_dir, *extra):
        sys.argv = [
            "check-flaky-tests.py",
            "--current", str(current_dir),
            "--previous", str(previous_dir),
            "--baseline", str(self.baseline),
        ] + list(extra)
        return cft.main()

    def test_unacknowledged_new_failure_fails(self):
        prev = write_run(self.tmp / "prev", [("A", "t", "pass")])
        cur = write_run(self.tmp / "cur", [("A", "t", "fail")])
        self.assertEqual(self._run(prev, cur), 1)

    def test_acknowledged_new_failure_passes(self):
        prev = write_run(self.tmp / "prev", [("A", "t", "pass")])
        cur = write_run(self.tmp / "cur", [("A", "t", "fail")])
        self.baseline.write_text("A::t  # known\n", encoding="utf-8")
        self.assertEqual(self._run(prev, cur), 0)

    def test_recovered_test_does_not_fail_the_gate(self):
        """A flake witness is a signal to report, not a reason to fail a green build."""
        prev = write_run(self.tmp / "prev", [("A", "t", "fail")])
        cur = write_run(self.tmp / "cur", [("A", "t", "pass")])
        self.assertEqual(self._run(prev, cur), 0)

    def test_identical_green_runs_pass(self):
        prev = write_run(self.tmp / "prev", [("A", "t", "pass")])
        cur = write_run(self.tmp / "cur", [("A", "t", "pass")])
        self.assertEqual(self._run(prev, cur), 0)

    def test_absent_previous_fails_unless_allowed(self):
        cur = write_run(self.tmp / "cur", [("A", "t", "pass")])
        self.assertEqual(self._run(self.tmp / "nope", cur), 1)
        self.assertEqual(self._run(self.tmp / "nope", cur, "--allow-missing-previous"), 0)

    def test_summary_receives_a_markdown_section(self):
        prev = write_run(self.tmp / "prev", [("A", "t", "fail")])
        cur = write_run(self.tmp / "cur", [("A", "t", "pass")])
        summary = self.tmp / "summary.md"
        self._run(prev, cur, "--summary", str(summary))
        text = summary.read_text(encoding="utf-8")
        self.assertIn("Flake analysis", text)
        self.assertIn("A::t", text)


if __name__ == "__main__":
    unittest.main()
