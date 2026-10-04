"""Unit tests for check-coverage.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_scripts_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "scripts"
_spec = importlib.util.spec_from_file_location(
    "check_coverage", _scripts_dir / "check-coverage.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "check_coverage"
_module.__file__ = str(_scripts_dir / "check-coverage.py")
sys.modules["check_coverage"] = _module  # must precede exec_module
_spec.loader.exec_module(_module)
import check_coverage as cc

PACKAGE = (
    '<package name="{name}">'
    '<counter type="INSTRUCTION" missed="{missed}" covered="{covered}"/>'
    '<counter type="BRANCH" missed="{bmissed}" covered="{bcovered}"/>'
    '<counter type="LINE" missed="{missed}" covered="{covered}"/>'
    "</package>"
)


def write_report(path: pathlib.Path, packages) -> pathlib.Path:
    body = "".join(
        PACKAGE.format(
            name=name,
            missed=missed,
            covered=covered,
            bmissed=missed // 2,
            bcovered=covered // 2,
        )
        for name, covered, missed in packages
    )
    path.write_text(f"<report>{body}</report>", encoding="utf-8")
    return path


class TestMeasure(unittest.TestCase):
    def test_missing_report_is_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(cc.measure(pathlib.Path(tmp) / "absent.xml"))

    def test_only_own_packages_are_counted(self):
        """A total over the whole report measures the dependency graph.

        Kover lists uninstrumented third-party classes too; counting them makes
        the floor drift on unrelated version bumps.
        """
        with tempfile.TemporaryDirectory() as tmp:
            report = write_report(
                pathlib.Path(tmp) / "r.xml",
                [
                    ("com/singularity/todo/feature/tasks", 50, 50),
                    ("ai/koog/agent", 9999, 1),
                ],
            )
            observed = cc.measure(report)
            self.assertEqual(observed["INSTRUCTION"], (50, 50))

    def test_percent_rounds_to_one_decimal(self):
        self.assertEqual(cc.percent(1, 2), 33.3)
        self.assertEqual(cc.percent(0, 0), 0.0)


class TestGate(unittest.TestCase):
    def setUp(self):
        self._saved_argv = sys.argv
        sys.argv = ["check-coverage.py"]
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.baseline = self.tmp / "coverage-baseline.txt"
        self.report = write_report(
            self.tmp / "r.xml", [("com/singularity/todo/core", 75, 25)]
        )
        self.addCleanup(self._restore)

    def _restore(self):
        sys.argv = self._saved_argv

    def _run(self, report=None, *extra):
        argv = ["check-coverage.py", "--report", str(report or self.report),
                "--baseline", str(self.baseline), "--quiet"]
        sys.argv = argv + list(extra)
        return cc.main()

    def test_drop_below_floor_fails(self):
        self.baseline.write_text("INSTRUCTION 80.0\nBRANCH 30.0\nLINE 80.0\n", encoding="utf-8")
        self.assertEqual(self._run(), 1)

    def test_at_or_above_floor_passes(self):
        self.baseline.write_text("INSTRUCTION 75.0\nBRANCH 35.0\nLINE 75.0\n", encoding="utf-8")
        self.assertEqual(self._run(), 0)

    def test_missing_report_fails_by_default(self):
        self.baseline.write_text("INSTRUCTION 1.0\n", encoding="utf-8")
        self.assertEqual(self._run(self.tmp / "absent.xml"), 1)

    def test_missing_report_passes_with_if_present(self):
        self.baseline.write_text("INSTRUCTION 1.0\n", encoding="utf-8")
        self.assertEqual(self._run(self.tmp / "absent.xml", "--if-present"), 0)

    def test_absent_baseline_fails_loudly(self):
        self.assertEqual(self._run(), 1)

    def test_update_baseline_records_all_three_metrics(self):
        self.assertEqual(self._run(None, "--update-baseline"), 0)
        text = self.baseline.read_text(encoding="utf-8")
        self.assertIn("INSTRUCTION 75.0", text)
        self.assertIn("BRANCH 75.5", text)
        self.assertIn("LINE 75.0", text)

    def test_report_without_our_packages_is_treated_as_absent(self):
        """An empty or foreign report must not read as 0% and fail the floor."""
        report = write_report(self.tmp / "foreign.xml", [("org/other", 10, 10)])
        self.baseline.write_text("INSTRUCTION 1.0\n", encoding="utf-8")
        self.assertEqual(self._run(report, "--if-present"), 0)


if __name__ == "__main__":
    unittest.main()
