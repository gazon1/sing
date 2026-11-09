#!/usr/bin/env python3
"""Unit tests for the JUnit reporters.

Run with: python3 -m unittest discover -s scripts/tests

Scope
-----
These tests pin the failure modes that made incomplete evidence look like a
successful test run. Both were live defects, not hypotheticals:

* **F1 — ``<error>`` was reported as a clean pass.** ``junitparser.Error`` is
  not a subclass of ``junitparser.Failure`` (they are siblings under
  ``FinalResult``), so the annotation script's ``isinstance(result,
  junitparser.Failure)`` check missed an ``<error>`` testcase entirely and
  counted it as a pass. An OOM, a timeout or a crashed fixture is invisible in
  the CI annotations.

* **F2 — a retried test's terminal failure was dropped.** The annotation
  script ``continue``d on the first ``<flakyFailure>`` child, so a testcase
  carrying both ``<flakyFailure>`` and a terminal ``<failure>`` was reported
  as "passed on retry" and its real failure never reached the annotations.

Both reporters now read the same reader
(``scripts/src/traceability/junit_xml.py``), so the tests below also pin that
they agree on the same file.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
ANNOTATIONS = ROOT / "scripts" / "junit-annotations.py"
PRINT_FAILURES = ROOT / "scripts" / "ci" / "print-test-failures.sh"
SRC = ROOT / "scripts" / "src"

SUITE = (
    '<testsuite name="s" tests="1">'
    '<testcase classname="com.example.{cls}" name="{case}"{file}>{body}</testcase>'
    "</testsuite>"
)


def _xml(body: str, cls: str = "Suite", case: str = "test", file: str = "") -> str:
    return SUITE.format(cls=cls, case=case, body=body, file=file)


#: The four outcomes that used to be misread, in one file.
MIXED = "\n".join(
    [
        '<testsuite name="mixed">',
        '<testcase classname="com.example.Passing" name="ok()"/>',
        '<testcase classname="com.example.Failing" name="bad()">'
        '<failure message="assertion failed">stack trace here</failure></testcase>',
        '<testcase classname="com.example.Erroring" name="boom()">'
        '<error message="java.lang.OutOfMemoryError">oom trace</error></testcase>',
        '<testcase classname="com.example.Retried" name="flaky()">'
        '<flakyFailure message="first attempt failed"/>'
        '<failure message="terminal failure">terminal trace</failure></testcase>',
        "</testsuite>",
    ]
)


def _write(directory: pathlib.Path, content: str, name: str = "TEST-mixed.xml") -> pathlib.Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_text(content, encoding="utf-8")
    return path


def run_annotations(directory: pathlib.Path) -> str:
    proc = subprocess.run(
        [sys.executable, str(ANNOTATIONS), "--glob", "*.xml", str(directory)],
        capture_output=True,
        text=True,
        cwd=ROOT,
        env={"PATH": "/usr/bin:/bin", "PYTHONPATH": str(SRC)},
    )
    return proc.stdout + proc.stderr


def run_print_failures(directory: pathlib.Path) -> str:
    proc = subprocess.run(
        ["bash", str(PRINT_FAILURES), str(directory)],
        capture_output=True,
        text=True,
        cwd=ROOT,
        env={"PATH": "/usr/bin:/bin", "PYTHONPATH": str(SRC)},
    )
    return proc.stdout + proc.stderr


class AnnotationOutcomeTest(unittest.TestCase):
    """The outcome semantics the annotation script must have."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_error_testcase_is_annotated_as_an_error(self):
        """F1: an ``<error>`` is a failure, not a pass."""
        _write(self.dir, _xml('<error message="boom"/>', cls="Erroring"))
        out = run_annotations(self.dir)
        self.assertIn("::error", out)
        self.assertIn("Erroring", out)

    def test_failure_annotation_names_the_file_the_test_ran_in(self):
        """The annotation points at the file, so CI can link to it."""
        _write(
            self.dir,
            _xml(
                '<failure message="assertion failed"/>',
                cls="Failing",
                file=' file="src/test/kotlin/FailingTest.kt"',
            ),
        )
        out = run_annotations(self.dir)
        self.assertIn("::error file=src/test/kotlin/FailingTest.kt", out)

    def test_error_testcase_is_not_counted_as_a_clean_pass(self):
        """F1: the summary line must not claim a pass for it."""
        _write(self.dir, _xml('<error message="boom"/>', cls="Erroring"))
        out = run_annotations(self.dir)
        self.assertIn("1 failure(s)", out)
        self.assertIn("0 clean pass(es)", out)

    def test_retried_test_with_terminal_failure_reports_the_failure(self):
        """F2: the terminal failure wins; the retry history is not the outcome."""
        _write(
            self.dir,
            _xml(
                '<flakyFailure message="first attempt failed"/>'
                '<failure message="terminal failure"/>',
                cls="Retried",
            ),
        )
        out = run_annotations(self.dir)
        self.assertIn("::error", out)
        self.assertIn("terminal failure", out)
        self.assertIn("1 failure(s)", out)

    def test_retried_test_that_passed_is_a_notice_not_a_clean_pass(self):
        """A retry-pass is neither a failure nor a clean pass."""
        _write(
            self.dir,
            _xml('<flakyFailure message="first attempt failed"/>', cls="Retried"),
        )
        out = run_annotations(self.dir)
        self.assertIn("::notice", out)
        self.assertNotIn("::error", out)
        self.assertIn("1 retry-pass(es)", out)
        self.assertIn("0 clean pass(es)", out)

    def test_skipped_test_is_neither_a_failure_nor_a_clean_pass(self):
        _write(self.dir, _xml("<skipped/>", cls="Skipped"))
        out = run_annotations(self.dir)
        self.assertNotIn("::error", out)
        self.assertNotIn("::notice", out)

    def test_skipped_test_is_counted_as_skipped_not_as_a_clean_pass(self):
        """A skipped test did not run — the summary must not claim it passed."""
        _write(self.dir, _xml("<skipped/>", cls="Skipped"))
        out = run_annotations(self.dir)
        self.assertIn("0 failure(s)", out)
        self.assertIn("0 clean pass(es)", out)
        self.assertIn("1 skipped", out)

    def test_malformed_report_is_reported_not_swallowed(self):
        """Incomplete evidence must be visible, not silently empty."""
        _write(self.dir, "<testsuite><testcase></testsuite>")
        out = run_annotations(self.dir)
        self.assertIn("::warning", out)


class ReporterAgreementTest(unittest.TestCase):
    """One parse, two renderings: the reporters must not disagree."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_both_reporters_report_the_same_failures(self):
        _write(self.dir, MIXED)
        annotations = run_annotations(self.dir)
        console = run_print_failures(self.dir)

        for marker in ("Failing", "Erroring", "Retried"):
            self.assertIn(marker, annotations, f"annotations missed {marker}")
            self.assertIn(marker, console, f"console reporter missed {marker}")

    def test_console_reporter_shows_the_terminal_failure_of_a_retried_test(self):
        _write(self.dir, MIXED)
        console = run_print_failures(self.dir)
        self.assertIn("terminal failure", console)

    def test_console_reporter_shows_the_stack_trace(self):
        """The element body is the evidence; the message attribute is a summary."""
        _write(self.dir, MIXED)
        console = run_print_failures(self.dir)
        self.assertIn("stack trace here", console)
        self.assertIn("oom trace", console)

    def test_neither_reporter_calls_an_errored_test_a_pass(self):
        """Three failures in the mixed file — the errored one included."""
        _write(self.dir, MIXED)
        annotations = run_annotations(self.dir)
        self.assertIn("3 failure(s)", annotations)
        self.assertIn("1 clean pass(es)", annotations)
        self.assertNotIn("4 clean pass(es)", annotations)


class ReaderContractTest(unittest.TestCase):
    """The shared reader is what makes the reporters agree."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        sys.path.insert(0, str(SRC))
        from traceability.junit_xml import parse_junit_file_strict

        self.parse = parse_junit_file_strict

    def tearDown(self):
        self.tmp.cleanup()
        sys.path.remove(str(SRC))

    def test_flaky_attempts_are_counted_without_becoming_the_outcome(self):
        path = _write(
            self.dir,
            _xml(
                '<flakyFailure message="a"/><flakyFailure message="b"/>'
                '<failure message="terminal"/>',
                cls="Retried",
            ),
        )
        case = self.parse(path)[0]
        self.assertEqual(case.status, "failed")
        self.assertEqual(case.flaky_attempts, 2)
        self.assertTrue(case.retried)

    def test_error_status_is_a_failing_outcome(self):
        path = _write(self.dir, _xml('<error message="boom"/>', cls="Erroring"))
        case = self.parse(path)[0]
        self.assertEqual(case.status, "error")
        self.assertTrue(case.failed_like)

    def test_stacktrace_is_carried_in_full(self):
        long_trace = "E" * 5000
        path = _write(self.dir, _xml(f'<failure message="short">{long_trace}</failure>'))
        case = self.parse(path)[0]
        self.assertEqual(case.stacktrace, long_trace)


if __name__ == "__main__":
    unittest.main()
