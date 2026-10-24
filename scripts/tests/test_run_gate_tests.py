"""Unit tests for `scripts/run-gate-tests.py` — the report the gate tests leave.

Run with: python3 -m unittest discover -s scripts/tests

The gate scripts' own tests run in CI as a step of a job, and a step's console
is a bad place for a failure: the repository's own rule is that the cause of a
failed test is in an artifact and not in the step log. These tests pin that the
runner writes a report a reader can open, and that the report says what the
console says.
"""

import importlib.util
import itertools
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
RUNNER = _ROOT / "scripts" / "run-gate-tests.py"

_spec = importlib.util.spec_from_file_location("run_gate_tests", RUNNER)
_runner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_runner)

run = _runner.run

#: A counter that gives every generated module its own name.
_COUNTER = itertools.count()


PASSING = """
import unittest


class PassingTest(unittest.TestCase):
    def test_it_passes(self):
        self.assertTrue(True)
"""

FAILING = """
import unittest


class FailingTest(unittest.TestCase):
    def test_it_fails(self):
        self.assertEqual(1, 2, "the answer is not the one this test wanted")
"""


def _tests_dir(directory: pathlib.Path, body: str) -> pathlib.Path:
    """A directory of tests, with one module in it.

    The module's name is unique per call: `unittest` discovers by module
    name, and a module that was imported from an earlier temporary directory
    is still in `sys.modules` — the second discovery would import the first
    one and fail on the path.
    """
    tests = directory / "tests"
    tests.mkdir()
    name = f"test_generated_{next(_COUNTER)}"
    (tests / f"{name}.py").write_text(body, encoding="utf-8")
    return tests


class ReportTest(unittest.TestCase):
    """The report the runner leaves."""

    def _run(self, body: str) -> tuple[int, ET.Element | None]:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = _tests_dir(root, body)
            report = root / "junit.xml"
            code = run(tests, report)
            if not report.is_file():
                return code, None
            return code, ET.parse(report).getroot()

    def test_a_passing_suite_exits_zero_and_leaves_a_report(self):
        code, document = self._run(PASSING)
        self.assertEqual(0, code)
        self.assertIsNotNone(document)
        self.assertEqual("1", document.get("tests"))
        self.assertEqual("0", document.get("failures"))

    def test_a_failing_suite_exits_non_zero(self):
        code, _ = self._run(FAILING)
        self.assertEqual(1, code)

    def test_the_report_names_the_class_and_the_test(self):
        _, document = self._run(PASSING)
        cases = document.findall(".//testcase")
        self.assertEqual(1, len(cases))
        self.assertTrue(cases[0].get("classname").endswith("PassingTest"))
        self.assertEqual("test_it_passes", cases[0].get("name"))

    def test_the_report_carries_the_failure_and_its_reason(self):
        _, document = self._run(FAILING)
        failures = document.findall(".//failure")
        self.assertEqual(1, len(failures))
        self.assertIn("the answer is not the one this test wanted", failures[0].get("message"))
        self.assertIn("AssertionError", failures[0].text)

    def test_the_report_counts_the_failures(self):
        _, document = self._run(FAILING)
        self.assertEqual("1", document.get("failures"))

    def test_a_suite_that_cannot_be_loaded_is_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "tests"
            tests.mkdir()
            (tests / "test_broken.py").write_text("this is not python(", encoding="utf-8")
            self.assertEqual(1, run(tests, root / "junit.xml"))


if __name__ == "__main__":
    unittest.main()
