#!/usr/bin/env python3
# no-sgate: this script runs the gate scripts' own tests and writes a report.
# It is not a gate — nothing about the tree it sits in is judged by it, and
# the gate that judges the gate scripts' wiring lives in
# `scripts/check-gate-wiring.py`.
"""run-gate-tests.py — the gate scripts' own tests, and a report a reader can open.

What went wrong
---------------
The gate scripts have their own unit tests — the regression tests for the
wiring gate, the liveness probe, the controls and the verdict registry. They
run in the `Gate tooling` job of `ci.yml`, and until now they ran as a plain
`python3 -m unittest discover`: the console of the job step.

A console is a bad place for a failure. The Kotlin tests of this repository
publish their JUnit XML as an artifact, and the rule that the repository
already wrote down for them is that *the cause of a failed test is in the
artifact and not in the step log* — the step log prints the first lines of a
stacktrace, and the text that explains the failure is somewhere else. The
gate scripts' own tests had the opposite shape: their whole explanation was
in a step log that nobody opens, and a red `Gate tooling` job said only that
something among 2383 assertions was wrong.

So the gate scripts' tests leave a report too — the same JUnit XML shape the
Kotlin tests leave, in the same artifact convention, read by the same tools.

What this script does
---------------------
It runs the tests of `scripts/tests` and writes a JUnit XML document: one
`<testsuite>` per test class, one `<testcase>` per test, and a `<failure>` with
the traceback for every test that failed. The exit code is the one `unittest`
would have returned — zero when everything passed, one otherwise — so the
script can replace the `unittest` call without changing what the job means.

Usage:
    python3 scripts/run-gate-tests.py
    python3 scripts/run-gate-tests.py --junit-xml build/diagnostics/gate-tests/junit.xml

Exit codes:
    0 — every test passed
    1 — a test failed, or the report could not be written
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: Where the gate scripts' own tests live.
DEFAULT_TESTS_DIR = ROOT / "scripts" / "tests"


class RecordingResult(unittest.TextTestResult):
    """A result that remembers how long each test took.

    `unittest` does not time a test, and a report without a duration is a
    report that cannot say which half of a suite is slow.
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._started: dict[str, float] = {}
        self.durations: dict[str, float] = {}

    def startTest(self, test: unittest.TestCase) -> None:
        super().startTest(test)
        self._started[test.id()] = time.monotonic()

    def stopTest(self, test: unittest.TestCase) -> None:
        super().stopTest(test)
        started = self._started.pop(test.id(), None)
        self.durations[test.id()] = 0.0 if started is None else time.monotonic() - started


def _classname(test: unittest.TestCase) -> str:
    """The class a test belongs to, as a fully qualified name."""
    cls = type(test)
    module = getattr(cls, "__module__", "")
    return f"{module}.{cls.__qualname__}" if module else cls.__qualname__


def _failure_of(result: RecordingResult, test_id: str) -> tuple[str, str, str] | None:
    """The failure of one test — its kind, its message and its traceback.

    The message is the last line of the traceback: it is the line that names
    the exception and says what it was — `AssertionError: the answer is not
    the one this test wanted` — and a reader who opens the report sees it
    without opening the traceback.

    @param result the outcome of the run.
    @param test_id the id of the test.
    """
    for outcome, text in result.failures:
        if outcome.id() == test_id:
            return "AssertionError", _message_of(text), text
    for outcome, text in result.errors:
        if outcome.id() == test_id:
            return "Error", _message_of(text), text
    return None


def _message_of(traceback_text: str) -> str:
    """The line of a traceback that says what happened."""
    lines = [line for line in traceback_text.splitlines() if line.strip()]
    return lines[-1].strip() if lines else ""


def write_report(
    path: pathlib.Path,
    result: RecordingResult,
    suites: dict[str, list[unittest.TestCase]],
    elapsed: float,
) -> None:
    """The JUnit XML of a run.

    @param path where the document is written.
    @param result the outcome of the run.
    @param suites the tests of every class, by class name.
    @param elapsed how long the run took.
    """
    document = ET.Element(
        "testsuites",
        {
            "name": "gate scripts' own tests",
            "tests": str(result.testsRun),
            "failures": str(len(result.failures)),
            "errors": str(len(result.errors)),
            "skipped": str(len(result.skipped)),
            "time": f"{elapsed:.3f}",
        },
    )
    for classname, tests in suites.items():
        suite = ET.SubElement(
            document,
            "testsuite",
            {
                "name": classname,
                "tests": str(len(tests)),
                "failures": str(sum(1 for test in tests if _failure_of(result, test.id()))),
                "errors": "0",
                "skipped": str(sum(1 for test in tests if test.id() in result.skipped)),
                "time": f"{sum(result.durations.get(test.id(), 0.0) for test in tests):.3f}",
            },
        )
        for test in tests:
            case = ET.SubElement(
                suite,
                "testcase",
                {
                    "classname": classname,
                    "name": test._testMethodName,  # noqa: SLF001 — the name is the method
                    "time": f"{result.durations.get(test.id(), 0.0):.3f}",
                },
            )
            failure = _failure_of(result, test.id())
            if failure is not None:
                kind, message, text = failure
                element = ET.SubElement(case, "failure", {"message": message[:200], "type": kind})
                element.text = text
            elif test.id() in result.skipped:
                ET.SubElement(case, "skipped", {"message": result.skipped[test.id()]})

    path.parent.mkdir(parents=True, exist_ok=True)
    tree = ET.ElementTree(document)
    ET.indent(tree, space="  ")
    tree.write(path, encoding="utf-8", xml_declaration=True)


def run(tests_dir: pathlib.Path, junit_xml: pathlib.Path | None) -> int:
    """The gate scripts' own tests, and the report they leave.

    @param tests_dir where the tests live.
    @param junit_xml where the report is written; `None` writes no report.
    """
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=str(tests_dir), top_level_dir=str(tests_dir))
    if loader.errors:
        for error in loader.errors:
            print(error, file=sys.stderr)
        return 1

    # The tests are collected before the run: `unittest` reuses the suite for
    # the results, and a suite that was run holds the failures and `None` for
    # the tests that passed.
    tests = _flatten(suite)

    started = time.monotonic()
    runner = unittest.TextTestRunner(verbosity=1, stream=sys.stderr, resultclass=RecordingResult)
    result = runner.run(suite)
    elapsed = time.monotonic() - started

    if junit_xml is not None:
        suites: dict[str, list[unittest.TestCase]] = {}
        for test in tests:
            suites.setdefault(_classname(test), []).append(test)
        write_report(junit_xml, result, suites, elapsed)
        print(f"run-gate-tests: the report is at {junit_xml}", file=sys.stderr)

    return 0 if result.wasSuccessful() else 1


def _flatten(suite: unittest.TestSuite) -> list[unittest.TestCase]:
    """Every test of a suite, in the order the suite holds them."""
    tests: list[unittest.TestCase] = []
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            tests.extend(_flatten(item))
        else:
            tests.append(item)
    return tests


def main() -> int:
    """Run the gate scripts' own tests."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--tests-dir",
        type=pathlib.Path,
        default=DEFAULT_TESTS_DIR,
        help="Where the tests live (the repository's scripts/tests by default).",
    )
    parser.add_argument(
        "--junit-xml",
        type=pathlib.Path,
        default=None,
        help="Write the JUnit XML report to this path.",
    )
    args = parser.parse_args()
    return run(args.tests_dir, args.junit_xml)


if __name__ == "__main__":
    sys.exit(main())
