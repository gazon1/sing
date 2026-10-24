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
something among thousands of assertions was wrong.

So the gate scripts' tests leave a report too — the same JUnit XML shape the
Kotlin tests leave, in the same artifact convention, read by the same tools.

What this script does
---------------------
It runs the tests of `scripts/tests` and writes a JUnit XML document: one
`<testsuite>` per test class, one `<testcase>` per test, a `<failure>` for
every test that failed and an `<error>` for every error — including the ones
that belong to a class rather than to a test (`setUpClass`) and the ones that
a sub-test produced. The exit code is the one `unittest` would have returned:
zero when everything passed, five when no test ran at all, one otherwise.

A test module that cannot be imported is a failure of that module and not the
end of the run: the report is written anyway, because the report is the reason
the job publishes an artifact, and the case where the report is missing is
exactly the case where somebody needs it.

Usage:
    python3 scripts/run-gate-tests.py
    python3 scripts/run-gate-tests.py --junit-xml build/diagnostics/gate-tests/junit.xml

Exit codes:
    0 — every test passed
    1 — a test failed, or a test module could not be imported
    5 — no test ran
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: Where the gate scripts' own tests live.
DEFAULT_TESTS_DIR = ROOT / "scripts" / "tests"

#: The exit code `unittest` returns when no test ran.
NO_TESTS_RAN = 5

#: An error that belongs to a class or a module, not to a test:
#: `setUpClass (tests.test_x.A)`.
CLASS_ERROR = re.compile(r'^(setUpClass|tearDownClass|setUpModule|tearDownModule) \((?P<target>.+)\)$')


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


def _belongs_to(outcome_id: str, test_id: str) -> bool:
    """Whether an outcome belongs to a test.

    A sub-test's outcome carries the test's id, then its message and then its
    parameters — `test_x.B.test_sub [the case] (i=1)` — and it belongs to the
    test it came from.
    """
    return (
        outcome_id == test_id
        or outcome_id.startswith(test_id + " (")
        or outcome_id.startswith(test_id + " [")
    )


def _message_of(traceback_text: str) -> str:
    """The line of a traceback that says what happened."""
    lines = [line for line in traceback_text.splitlines() if line.strip()]
    return lines[-1].strip() if lines else ""


def _outcomes(
    result: RecordingResult,
    test_id: str,
) -> list[tuple[str, str, str]]:
    """The outcomes of one test — `(kind, message, traceback)` for each."""
    outcomes: list[tuple[str, str, str]] = []
    for outcome, text in result.failures:
        if _belongs_to(outcome.id(), test_id):
            outcomes.append(("failure", _message_of(text), text))
    for outcome, text in result.errors:
        if _belongs_to(outcome.id(), test_id):
            outcomes.append(("error", _message_of(text), text))
    return outcomes


def _class_errors(result: RecordingResult) -> dict[str, list[tuple[str, str]]]:
    """The errors that belong to a class or a module, not to a test.

    `setUpClass` fails before any test of the class runs, so there is no
    `<testcase>` to hang the error on — the report carries it as a testcase
    of its own, named after the class it belongs to.

    The errors are grouped by the class they belong to, and each one is
    written once: an error written into every suite would be counted as many
    times as there are suites, and a counter that says "that many problems"
    has to say it once.
    """
    errors: dict[str, list[tuple[str, str]]] = {}
    for outcome, text in result.errors:
        match = CLASS_ERROR.match(outcome.id())
        if match is not None:
            errors.setdefault(match.group("target"), []).append((match.group(1), text))
    return errors


def _same_class(target: str, classname: str) -> bool:
    """Whether a class error belongs to the suite of this class.

    `unittest` names the class of a `setUpClass` failure by its id —
    `tests.test_x.A` — and the suite is named by the module and the class
    name. The two are compared by the tail, which is the class itself.
    """
    return target.rsplit(".", 1)[-1] == classname.rsplit(".", 1)[-1]


def _skipped(result: RecordingResult) -> dict[str, str]:
    """The tests that were skipped, and why.

    `unittest` keeps the skipped tests as a list of `(test, reason)` pairs —
    not as a mapping — and a reader of the report wants the reason.
    """
    return {test.id(): reason for test, reason in result.skipped}


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
    skipped = _skipped(result)
    class_errors = _class_errors(result)
    document = ET.Element("testsuites", {"name": "gate scripts' own tests", "time": f"{elapsed:.3f}"})
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

    for classname, tests in suites.items():
        suite = ET.SubElement(document, "testsuite", {"name": classname})
        suite_counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

        for test in tests:
            if test.id() not in result.durations:
                # The test never ran — its class failed to set up, and that
                # error is carried by the class-level testcase below.
                continue
            case = ET.SubElement(
                suite,
                "testcase",
                {
                    "classname": classname,
                    "name": test._testMethodName,  # noqa: SLF001 — the name is the method
                    "time": f"{result.durations.get(test.id(), 0.0):.3f}",
                },
            )
            suite_counts["tests"] += 1
            outcomes = _outcomes(result, test.id())
            for kind, message, text in outcomes:
                element = ET.SubElement(case, kind, {"message": message[:200]})
                element.text = text
                suite_counts["failures" if kind == "failure" else "errors"] += 1
            if not outcomes and test.id() in skipped:
                ET.SubElement(case, "skipped", {"message": str(skipped[test.id()])[:200]})
                suite_counts["skipped"] += 1

        for target, errors in class_errors.items():
            if not _same_class(target, classname):
                continue
            for name, text in errors:
                case = ET.SubElement(
                    suite,
                    "testcase",
                    {"classname": target, "name": name, "time": "0.000"},
                )
                element = ET.SubElement(case, "error", {"message": _message_of(text)[:200]})
                element.text = text
                suite_counts["tests"] += 1
                suite_counts["errors"] += 1

        for key, value in suite_counts.items():
            suite.set(key, str(value))
        for key in counts:
            counts[key] += suite_counts[key]

    for key, value in counts.items():
        document.set(key, str(value))

    path.parent.mkdir(parents=True, exist_ok=True)
    tree = ET.ElementTree(document)
    ET.indent(tree, space="  ")
    tree.write(path, encoding="utf-8", xml_declaration=True)


def _flatten(suite: unittest.TestSuite) -> list[unittest.TestCase]:
    """Every test of a suite, in the order the suite holds them."""
    tests: list[unittest.TestCase] = []
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            tests.extend(_flatten(item))
        else:
            tests.append(item)
    return tests


def run(tests_dir: pathlib.Path, junit_xml: pathlib.Path | None) -> int:
    """The gate scripts' own tests, and the report they leave.

    @param tests_dir where the tests live.
    @param junit_xml where the report is written; `None` writes no report.
    """
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=str(tests_dir), top_level_dir=str(tests_dir))

    # A module that does not import becomes a failed test, the way `unittest`
    # does it: the loader leaves the error in `loader.errors` and the suite
    # holds a failed test for it. The runner does not add its own — one
    # broken module is one broken module, and a defect counted twice is a
    # counter that lies.
    for error in loader.errors:
        print(f"run-gate-tests: a test module could not be imported:\n{error}", file=sys.stderr)

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

    if not result.wasSuccessful():
        return 1
    if not tests:
        print("run-gate-tests: NO TESTS RAN", file=sys.stderr)
        return NO_TESTS_RAN
    return 0


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
