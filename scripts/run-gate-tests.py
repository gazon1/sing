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

What this script binds, and what it does not
--------------------------------------------
The gate CLI is an *editable* install of `scripts/src/`, so whichever checkout
was installed last decides what `import sgate` finds — on this machine, for a
whole afternoon, that was another clone, and every finding the gates produced
was about a repository nobody was working on.

This runner therefore binds the package to the checkout it sits in before the
suite starts: the editable finders are dropped and this checkout's `scripts/src`
goes first on the import path. A foreign `sgate` in the environment is then
reported, not obeyed — the run measures this checkout either way. The same move
`sg` makes, and the same one `sgate run-all` makes for its gate subprocesses
(ADR `docs/decisions/2026-10-21-one-owner-of-stdout.md`).

What stays a refusal: this checkout's own package cannot be imported. Then the
suite would measure *something* that is not this tree, and a run that measures
the wrong tree is not a red run — it is no run at all.

Usage:
    python3 scripts/run-gate-tests.py
    python3 scripts/run-gate-tests.py --junit-xml build/diagnostics/gate-tests/junit.xml

Exit codes:
    0 — every test passed
    1 — a test failed, or a test module could not be imported
    2 — the run was refused: this checkout's package could not be imported
    5 — no test ran
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import re
import subprocess
import sys
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: Where the gate scripts' own tests live.
DEFAULT_TESTS_DIR = ROOT / "scripts" / "tests"

#: The exit code `unittest` returns when no test ran.
NO_TESTS_RAN = 5

#: The exit code when the run was refused before it started.
#:
#: Distinct from 1 ("a test failed") on purpose: a reader — and a CI step —
#: has to be able to tell "the suite is red" from "the suite never measured
#: this tree". The second is not a failure of the code; it is the absence of a
#: measurement, and collapsing the two makes the first look like the second.
REFUSED = 2

#: An error that belongs to a class or a module, not to a test:
#: `setUpClass (tests.test_x.A)`.
CLASS_ERROR = re.compile(r'^(setUpClass|tearDownClass|setUpModule|tearDownModule) \((?P<target>.+)\)$')

#: The outcomes that belong to a class.
CLASS_OUTCOMES = frozenset({'setUpClass', 'tearDownClass'})

#: The outcomes that belong to a module.
MODULE_OUTCOMES = frozenset({'setUpModule', 'tearDownModule'})


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
    """The errors that belong to a class, not to a test.

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
        if match is not None and match.group(1) in CLASS_OUTCOMES:
            errors.setdefault(match.group("target"), []).append((match.group(1), text))
    return errors


def _module_errors(result: RecordingResult) -> dict[str, list[tuple[str, str]]]:
    """The errors that belong to a module, not to a class.

    `setUpModule` fails before any test of the module runs, and it belongs to
    no class at all — the report gives the module a suite of its own, so that
    the error is in the report and not only on the console.
    """
    errors: dict[str, list[tuple[str, str]]] = {}
    for outcome, text in result.errors:
        match = CLASS_ERROR.match(outcome.id())
        if match is not None and match.group(1) in MODULE_OUTCOMES:
            errors.setdefault(match.group("target"), []).append((match.group(1), text))
    return errors


def _same_class(target: str, classname: str) -> bool:
    """Whether a class error belongs to the suite of this class.

    `unittest` names the class of a `setUpClass` failure by its full id —
    `test_x.A` — and the suite is named the same way. The names are compared
    whole: the base name is not enough, because the repository has classes
    with the same name in different modules (`CliTest` is in nine of them),
    and an error written into every suite of every name would be counted as
    many times as there are suites.
    """
    return target == classname


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
    expected = {test.id(): reason for test, reason in result.expectedFailures}
    unexpected = {test.id() for test in result.unexpectedSuccesses}
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
            elif not outcomes and test.id() in expected:
                ET.SubElement(
                    case,
                    "skipped",
                    {"message": str(expected[test.id()])[:200]},
                )
                suite_counts["skipped"] += 1
            elif not outcomes and test.id() in unexpected:
                element = ET.SubElement(
                    case,
                    "failure",
                    {"message": "the test was expected to fail and passed"},
                )
                element.text = "the test was expected to fail and passed"
                suite_counts["failures"] += 1

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

    module_errors = _module_errors(result)
    for module, errors in module_errors.items():
        suite = ET.SubElement(document, "testsuite", {"name": module})
        suite_counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        for name, text in errors:
            case = ET.SubElement(
                suite,
                "testcase",
                {"classname": module, "name": name, "time": "0.000"},
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


def gate_tooling_origin() -> pathlib.Path | None:
    """Where the importable `sgate` package comes from, or None.

    The gate CLI is an *editable* install of `scripts/src/`, so whichever
    checkout was installed decides what `import sgate` finds. This is the same
    question `scripts/check-gate-tooling-origin.py` asks, asked the same way:
    without importing the package, because importing it is what has to be
    reported on.

    A package that has no `__init__.py` is a *namespace package*: it has no
    origin, but it has a place it was found in, and that place answers the
    question the same way. Reading "no origin" as "no package here" made the
    runner refuse a tree that carried its own — and the refusal said the suite
    would measure another checkout, which was not true.
    """
    try:
        spec = importlib.util.find_spec("sgate")
    except (ImportError, ValueError):
        return None
    if spec is None:
        return None
    if spec.origin:
        return pathlib.Path(spec.origin).resolve().parent
    locations = list(spec.submodule_search_locations or [])
    if not locations:
        return None
    return pathlib.Path(locations[0]).resolve()


def _is_editable_finder(finder: object) -> bool:
    """Whether a meta-path finder came from an editable install.

    The editable install of `scripts/src/` registers a finder that maps `sgate`
    to the checkout that was installed last. Setuptools *appends* it to
    `sys.meta_path`, so a `sys.path` entry wins while it is there — but the
    append is a property of the setuptools version that installed it, and the
    answer to "which `sgate` is this?" must not depend on that. The finder is
    dropped and the path is put first by hand. This is the move `sg` makes.
    """
    name = getattr(finder, "__module__", "") or ""
    if "editable" in name.lower():
        return True
    return "editable" in type(finder).__module__.lower()


def pin_local_package(root: pathlib.Path) -> pathlib.Path:
    """Bind `import sgate` to the checkout this runner sits in.

    Three moves, in this order:

    1. the editable finders go — they map the package to whichever checkout
       was installed last;
    2. this checkout's `scripts/src` goes first on `sys.path` — so the package
       that answers `import sgate` is the one on disk here;
    3. the same path goes first in `PYTHONPATH` — so the *children* this suite
       spawns inherit the binding. Without the third move a subprocess
       resolves the package by itself, and by "itself" means "by whatever is
       installed".

    @param root the repository root.
    @return the directory the package is bound to.
    """
    source = (root / "scripts" / "src").resolve()
    sys.meta_path = [finder for finder in sys.meta_path if not _is_editable_finder(finder)]
    if str(source) not in sys.path:
        sys.path.insert(0, str(source))
    existing = [part for part in os.environ.get("PYTHONPATH", "").split(os.pathsep) if part]
    if str(source) not in existing:
        os.environ["PYTHONPATH"] = os.pathsep.join([str(source), *existing])
    return source


def refuse_unmeasurable_tooling(original_env: dict[str, str]) -> bool:
    """Whether the run has to be refused, and the reason in the gate's words.

    ## What went wrong

    With a foreign editable install in reach, this suite failed in 72 places.
    Every one of those failures was about a file that is not in this checkout:
    the foreign `sgate` has a different registry, a different set of gate
    commands and a different set of helpers, so the tests that exercise them
    failed on assertions about code this tree does not contain. The number 72
    says nothing; the diagnostics point in every direction at once.

    The suite did not measure this tree and it did not fail on this tree. It
    measured another one, and the failures were the other tree's shape.

    ## What this does

    The package is bound to this checkout before the suite starts, so a foreign
    install in the environment no longer decides what is measured. What is left
    to refuse is the case where *this* checkout's own package cannot be
    imported: then the suite would measure whatever else answers `import sgate`,
    and a run that measures the wrong tree is not a red run — it is no run at
    all.

    The message is the one `scripts/check-gate-tooling-origin.py` prints, run
    as a subprocess **with the environment as it was**: two places that name the
    same problem have to say the same thing about it, and the origin of the
    *environment* is not the origin of the package this run measures.

    @param original_env the environment as it was before the binding.
    @return whether the run was refused.
    """
    origin = gate_tooling_origin()
    local = (ROOT / "scripts" / "src" / "sgate").resolve()
    if origin == local:
        return False

    report = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "check-gate-tooling-origin.py")],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=False,
        env=original_env,
    )
    sys.stdout.write(report.stdout)
    # The origin check writes to stdout and this runner's own lines go to
    # stderr. Without the flush, stdout's buffer is written at exit and the
    # two streams arrive in the wrong order — the explanation before the
    # reason it is explaining.
    sys.stdout.flush()
    sys.stderr.write(report.stderr)
    print("", file=sys.stderr)
    print("run-gate-tests: the suite was not run.", file=sys.stderr)
    print("run-gate-tests: it would have measured a package that is not this", file=sys.stderr)
    print("run-gate-tests: checkout's own — and a package that is not this", file=sys.stderr)
    print("run-gate-tests: checkout's own is another checkout's code.", file=sys.stderr)
    return True


def run(tests_dir: pathlib.Path, junit_xml: pathlib.Path | None) -> int:
    """The gate scripts' own tests, and the report they leave.

    @param tests_dir where the tests live.
    @param junit_xml where the report is written; `None` writes no report.
    """
    loader = unittest.TestLoader()
    try:
        suite = loader.discover(start_dir=str(tests_dir), top_level_dir=str(tests_dir))
    except (ImportError, OSError) as exc:
        # The tests are not there, or not readable. The report says so — a
        # report that is missing in the case where the run is broken is the
        # report nobody needs.
        print(f"run-gate-tests: the tests were not found: {exc}", file=sys.stderr)
        if junit_xml is not None:
            document = ET.Element(
                "testsuites",
                {
                    "name": "gate scripts' own tests",
                    "tests": "1",
                    "failures": "0",
                    "errors": "1",
                    "skipped": "0",
                    "time": "0.000",
                },
            )
            suite_element = ET.SubElement(document, "testsuite", {"name": "discovery"})
            case = ET.SubElement(
                suite_element,
                "testcase",
                {"classname": "discovery", "name": "the tests were found", "time": "0.000"},
            )
            element = ET.SubElement(case, "error", {"message": str(exc)[:200]})
            element.text = str(exc)
            suite_element.set("tests", "1")
            suite_element.set("failures", "0")
            suite_element.set("errors", "1")
            suite_element.set("skipped", "0")
            junit_xml.parent.mkdir(parents=True, exist_ok=True)
            tree = ET.ElementTree(document)
            ET.indent(tree, space="  ")
            tree.write(junit_xml, encoding="utf-8", xml_declaration=True)
        return 1

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

    # The environment as it was, before this runner binds anything: the origin
    # report asks about the environment, and a report run under the binding
    # would answer about the binding instead.
    original_env = dict(os.environ)
    served_by_environment = gate_tooling_origin()
    pin_local_package(ROOT)

    if refuse_unmeasurable_tooling(original_env):
        return REFUSED

    local = (ROOT / "scripts" / "src" / "sgate").resolve()
    if served_by_environment is not None and served_by_environment != local:
        print(
            f"run-gate-tests: the environment's sgate serves {served_by_environment}; "
            f"this run measured {local}.",
            file=sys.stderr,
        )
    print(f"run-gate-tests: measured {local}", file=sys.stderr)
    return run(args.tests_dir, args.junit_xml)


if __name__ == "__main__":
    sys.exit(main())
