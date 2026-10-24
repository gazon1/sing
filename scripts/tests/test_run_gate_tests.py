"""Unit tests for `scripts/run-gate-tests.py` — the report the gate tests leave.

Run with: python3 -m unittest discover -s scripts/tests

The gate scripts' own tests run in CI as a step of a job, and a step's console
is a bad place for a failure: the repository's own rule is that the cause of a
failed test is in an artifact and not in the step log. These tests pin that the
runner writes a report a reader can open, and that the report says what the
console says — including the cases the console reports in ways the report has
to carry too: a sub-test that failed, a class that could not be set up, a
skipped test, and a module that does not import.
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

SUB_TEST = """
import unittest


class SubTestTest(unittest.TestCase):
    def test_the_cases(self):
        for case in range(3):
            with self.subTest(case=case):
                self.assertNotEqual(case, 2, "the third case is the one that fails")
"""

SUB_TEST_WITH_MESSAGE = """
import unittest


class SubTestWithMessageTest(unittest.TestCase):
    def test_the_cases(self):
        for case in range(3):
            with self.subTest("case %s" % case, case=case):
                self.assertNotEqual(case, 2, "the third case is the one that fails")
"""

SAME_CLASS_NAME = """
import unittest


class SameTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raise RuntimeError("the class could not be set up")

    def test_never_runs(self):
        self.assertTrue(True)
"""

SAME_CLASS_NAME_ELSEWHERE = """
import unittest


class SameTest(unittest.TestCase):
    def test_it_passes(self):
        self.assertTrue(True)
"""

MODULE_ERROR = """
import unittest


def setUpModule():
    raise RuntimeError("the module could not be set up")


class InsideTest(unittest.TestCase):
    def test_never_runs(self):
        self.assertTrue(True)
"""

UNEXPECTED_SUCCESS = """
import unittest


class ExpectedFailureTest(unittest.TestCase):
    @unittest.expectedFailure
    def test_it_was_expected_to_fail(self):
        self.assertTrue(True)
"""

TWO_CLASSES_ONE_BROKEN = """
import unittest


class BrokenClassTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raise RuntimeError("the class could not be set up")

    def test_never_runs(self):
        self.assertTrue(True)


class OtherTest(unittest.TestCase):
    def test_it_passes(self):
        self.assertTrue(True)
"""

CLASS_ERROR = """
import unittest


class BrokenClassTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raise RuntimeError("the class could not be set up")

    def test_never_runs(self):
        self.assertTrue(True)
"""

SKIPPED = """
import unittest


class SkippedTest(unittest.TestCase):
    @unittest.skip("not on this platform")
    def test_it_is_skipped(self):
        self.assertTrue(True)
"""

NOT_IMPORTABLE = "this is not python(\n"


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
        self.assertEqual("0", document.get("errors"))

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
            (tests / "test_broken.py").write_text(NOT_IMPORTABLE, encoding="utf-8")
            self.assertEqual(1, run(tests, root / "junit.xml"))

    def test_a_module_that_does_not_import_is_counted_once(self):
        # One broken module is one broken module: `unittest` already turns it
        # into a failed test, and the runner must not add a second one.
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "tests"
            tests.mkdir()
            (tests / "test_broken.py").write_text(NOT_IMPORTABLE, encoding="utf-8")
            report = root / "junit.xml"
            self.assertEqual(1, run(tests, report))
            document = ET.parse(report).getroot()
            self.assertEqual("1", document.get("errors"))
            self.assertEqual("0", document.get("failures"))

    def test_a_module_that_does_not_import_still_leaves_a_report(self):
        # The report is the reason the job publishes an artifact, and the case
        # where the report is missing is exactly the case where somebody needs
        # it.
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "tests"
            tests.mkdir()
            (tests / "test_broken.py").write_text(NOT_IMPORTABLE, encoding="utf-8")
            report = root / "junit.xml"
            self.assertEqual(1, run(tests, report))
            self.assertTrue(report.is_file())
            document = ET.parse(report).getroot()
            self.assertEqual("1", document.get("errors"))

    def test_a_tests_directory_that_is_not_there_leaves_a_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            report = root / "junit.xml"
            self.assertEqual(1, run(root / "no-such-directory", report))
            self.assertTrue(report.is_file())
            document = ET.parse(report).getroot()
            self.assertEqual("1", document.get("errors"))

    def test_a_suite_with_no_tests_says_so(self):
        # `unittest` exits with 5 when no test ran, and a green exit for a
        # suite that ran nothing is a green that nobody wrote.
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "tests"
            tests.mkdir()
            self.assertEqual(5, run(tests, root / "junit.xml"))


class OutcomeTest(unittest.TestCase):
    """The outcomes the console reports and the report has to carry too."""

    def _run(self, body: str) -> ET.Element:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = _tests_dir(root, body)
            report = root / "junit.xml"
            run(tests, report)
            return ET.parse(report).getroot()

    def test_a_sub_test_that_failed_is_a_failure_of_its_test(self):
        document = self._run(SUB_TEST)
        cases = document.findall(".//testcase")
        self.assertEqual(1, len(cases))
        self.assertEqual("test_the_cases", cases[0].get("name"))
        failures = cases[0].findall("failure")
        self.assertEqual(1, len(failures))
        self.assertIn("the third case is the one that fails", failures[0].get("message"))
        self.assertEqual("1", document.get("failures"))

    def test_a_class_that_could_not_be_set_up_is_an_error_of_its_own(self):
        document = self._run(CLASS_ERROR)
        errors = document.findall(".//error")
        self.assertEqual(1, len(errors))
        self.assertIn("the class could not be set up", errors[0].get("message"))
        self.assertEqual("1", document.get("errors"))
        self.assertEqual("0", document.get("failures"))

    def test_a_class_error_is_written_once_and_into_its_own_suite(self):
        # One broken class among several: the error belongs to the suite of
        # that class, and it is counted once — an error written into every
        # suite would be counted as many times as there are suites.
        document = self._run(TWO_CLASSES_ONE_BROKEN)
        self.assertEqual("1", document.get("errors"))
        self.assertEqual("0", document.get("failures"))
        self.assertEqual("2", document.get("tests"))
        errors = document.findall(".//error")
        self.assertEqual(1, len(errors))
        cases = document.findall(".//testcase")
        self.assertEqual(2, len(cases))
        broken = [case for case in cases if case.get("name") == "setUpClass"]
        self.assertEqual(1, len(broken))
        self.assertTrue(broken[0].get("classname").endswith("BrokenClassTest"))

    def test_a_sub_test_with_a_message_is_a_failure_of_its_test(self):
        # The identifier of a sub-test carries the test's id, then the
        # message, then the parameters.
        document = self._run(SUB_TEST_WITH_MESSAGE)
        self.assertEqual("1", document.get("failures"))
        cases = document.findall(".//testcase")
        self.assertEqual(1, len(cases))
        failures = cases[0].findall("failure")
        self.assertEqual(1, len(failures))
        self.assertIn("the third case is the one that fails", failures[0].get("message"))

    def test_a_class_error_is_written_once_when_two_modules_share_a_class_name(self):
        # The repository has classes with the same name in different modules
        # (`CliTest` is in nine of them), and an error written into every
        # suite of every name would be counted as many times as there are
        # suites.
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "tests"
            tests.mkdir()
            (tests / "test_a.py").write_text(SAME_CLASS_NAME, encoding="utf-8")
            (tests / "test_b.py").write_text(SAME_CLASS_NAME_ELSEWHERE, encoding="utf-8")
            report = root / "junit.xml"
            self.assertEqual(1, run(tests, report))
            document = ET.parse(report).getroot()
            self.assertEqual("1", document.get("errors"))
            self.assertEqual(1, len(document.findall(".//error")))

    def test_a_module_error_is_in_the_report(self):
        # `setUpModule` belongs to no class, and the report gives the module
        # a suite of its own — a red run with a green report is the worst of
        # the two answers.
        document = self._run(MODULE_ERROR)
        self.assertEqual("1", document.get("errors"))
        errors = document.findall(".//error")
        self.assertEqual(1, len(errors))
        self.assertIn("the module could not be set up", errors[0].get("message"))

    def test_an_unexpected_success_is_a_failure_in_the_report(self):
        document = self._run(UNEXPECTED_SUCCESS)
        self.assertEqual("1", document.get("failures"))
        failures = document.findall(".//failure")
        self.assertEqual(1, len(failures))
        self.assertIn("expected to fail", failures[0].get("message"))

    def test_a_skipped_test_is_skipped_in_the_report(self):
        document = self._run(SKIPPED)
        skipped = document.findall(".//skipped")
        self.assertEqual(1, len(skipped))
        self.assertIn("not on this platform", skipped[0].get("message"))
        self.assertEqual("1", document.get("skipped"))
        self.assertEqual("1", document.get("tests"))


if __name__ == "__main__":
    unittest.main()
