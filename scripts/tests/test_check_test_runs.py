"""Unit tests for check-test-runs.py.

Run with: python3 -m unittest discover -s scripts/tests

The script is the only mechanism that catches a *partial* skip: a green test
task that executed fewer classes than it should. That makes the script itself
load-bearing infrastructure — a regression inside it silently disables the
gate, which is the same failure shape the gate exists to detect. These tests
are the answer to that.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_scripts_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "scripts"
_spec = importlib.util.spec_from_file_location(
    "check_test_runs", _scripts_dir / "check-test-runs.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "check_test_runs"
_module.__file__ = str(_scripts_dir / "check-test-runs.py")
sys.modules["check_test_runs"] = _module  # must precede exec_module
_spec.loader.exec_module(_module)
import check_test_runs as ctr

SUITE = (
    '<testsuite name="{name}" tests="{tests}" skipped="{skipped}" '
    'failures="0" errors="0" time="0.5"></testsuite>'
)


def write_suite(directory: pathlib.Path, name: str, tests: int, skipped: int = 0) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"TEST-{name}.xml").write_text(
        SUITE.format(name=name, tests=tests, skipped=skipped), encoding="utf-8"
    )


class TestCount(unittest.TestCase):
    def test_absent_directory_is_none_not_zero(self):
        """A source set that never ran must be 'missing', not 'ran nothing'.

        Reporting zero would satisfy a floor of zero and hide a whole job.
        """
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(ctr.count(pathlib.Path(tmp) / "nope"))

    def test_sums_classes_tests_and_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=10, skipped=0)
            write_suite(d, "B", tests=5, skipped=3)
            self.assertEqual(ctr.count(d), (2, 15, 3))

    def test_non_testsuite_root_is_ignored(self):
        """A stray XML in the results dir must not count as a class."""
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=10)
            (d / "TEST-other.xml").write_text(
                '<?xml version="1.0"?><testsuites><testsuite tests="99">'
                "</testsuite></testsuites>",
                encoding="utf-8",
            )
            self.assertEqual(ctr.count(d), (1, 10, 0))

    def test_skipped_testcase_is_counted_in_tests(self):
        """Documents the blind spot the ceiling exists for.

        JUnit reports a skipped testcase inside tests=, which is why a test-count
        floor alone cannot see a @Disabled class lose 15 tests of coverage.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            write_suite(d, "A", tests=15, skipped=15)
            classes, tests, skipped = ctr.count(d)
            self.assertEqual((classes, tests, skipped), (1, 15, 15))


class TestBaselineParsing(unittest.TestCase):
    def test_three_column_line_defaults_skipped_ceiling_to_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "b.txt"
            p.write_text("# comment\nshared:jvmTest 190 1519\n", encoding="utf-8")
            self.assertEqual(ctr.load_baseline(p), {"shared:jvmTest": (190, 1519, 0)})

    def test_four_column_line_keeps_its_ceiling(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "b.txt"
            p.write_text("shared:jvmTest 190 1519 0\n", encoding="utf-8")
            self.assertEqual(ctr.load_baseline(p), {"shared:jvmTest": (190, 1519, 0)})

    def test_missing_file_is_empty_not_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(ctr.load_baseline(pathlib.Path(tmp) / "absent"), {})


class TestGate(unittest.TestCase):
    """End-to-end over a temp ROOT, exercising main()'s exit code."""

    def setUp(self):
        self._saved = (ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, sys.argv)
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        ctr.ROOT = self.tmp
        ctr.BASELINE = self.tmp / "baseline.txt"
        ctr.SOURCE_SETS = {"shared:jvmTest": "results/jvmTest"}
        # main() parses sys.argv; under unittest that is the runner's own argv.
        sys.argv = ["check-test-runs.py"]
        self.addCleanup(self._restore)

    def _restore(self):
        ctr.ROOT, ctr.BASELINE, ctr.SOURCE_SETS, sys.argv = self._saved

    def _baseline(self, text: str) -> None:
        ctr.BASELINE.write_text(text, encoding="utf-8")

    def _results(self, classes: int, tests: int, skipped: int = 0) -> pathlib.Path:
        d = self.tmp / "results" / "jvmTest"
        for i in range(classes):
            write_suite(d, f"C{i}", tests=max(tests // classes, 0), skipped=0)
        if skipped:
            # attribute belongs to one suite; rewrite the last one
            last = d / f"TEST-C{classes - 1}.xml"
            last.write_text(
                SUITE.format(name=f"C{classes - 1}", tests=tests // classes, skipped=skipped),
                encoding="utf-8",
            )
        return d

    def test_drop_below_floor_fails(self):
        self._results(2, 20)
        self._baseline("shared:jvmTest 5 50 0\n")
        self.assertEqual(ctr.main(), 1)

    def test_rise_above_floor_passes(self):
        self._results(5, 50)
        self._baseline("shared:jvmTest 2 20 0\n")
        self.assertEqual(ctr.main(), 0)

    def test_equal_to_floor_passes(self):
        self._results(5, 50)
        self._baseline("shared:jvmTest 5 50 0\n")
        self.assertEqual(ctr.main(), 0)

    def test_skips_above_ceiling_fail_even_at_full_count(self):
        """The whole point: counts are intact, coverage is gone."""
        self._results(5, 50, skipped=7)
        self._baseline("shared:jvmTest 5 50 0\n")
        self.assertEqual(ctr.main(), 1)

    def test_missing_results_fail_only_when_required(self):
        self._baseline("shared:jvmTest 1 1 0\n")
        self.assertEqual(ctr.main(), 0)
        saved_argv = sys.argv
        sys.argv = ["check-test-runs.py", "--require", "shared:jvmTest"]
        try:
            self.assertEqual(ctr.main(), 1)
        finally:
            sys.argv = saved_argv

    def test_absent_baseline_fails_loudly(self):
        self._results(5, 50)
        self.assertEqual(ctr.main(), 1)


if __name__ == "__main__":
    unittest.main()
