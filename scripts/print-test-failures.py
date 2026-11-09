#!/usr/bin/env python3
"""print-test-failures.py — the failures of a Gradle test run, as text.

Why
---
A red test is only useful if the message survives the trip from the test
report to the person reading it. The reports are XML, and reading XML by hand
is what an agent does when nothing prints the failures for it.

So this script prints them: the failing test, its message, the full stack
trace and — when the test was retried — the note that says so. It is the same
summary the CI helper used to produce from an inline python heredoc — the
difference is that this one can be tested, and that a broken report is a line
of output instead of a traceback in the middle of a red build.

The XML is parsed by ``scripts/src/traceability/junit_xml.py`` — the
repository's one JUnit reader. This script used to carry its own parser, and
that is exactly how it came to disagree with ``scripts/junit-annotations.py``:
a second reader is a second answer. One parse, two renderings.

Usage
-----
    python3 scripts/print-test-failures.py [path]

`path` is the directory of the test results (default: `shared/build/test-results`).
Every `*.xml` under it is read.

Exit code: always 0. This is a report about a failure, not a failure itself.
"""

from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ET

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this script must read the reader from *this* checkout, not from
# whatever happens to be installed. When this file is loaded by a test harness
# via importlib, sys.path[0] is `scripts/tests` and the package is invisible;
# this file's own location is authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
if _scripts_src not in sys.path:
    sys.path.insert(0, _scripts_src)

from traceability.junit_xml import (  # noqa: E402
    TestCaseResult,
    parse_junit_file_strict,
    parse_junit_text,
)

#: Where Gradle writes the test results of the shared module.
DEFAULT_ROOT = "shared/build/test-results"


def _failure_dict(case: TestCaseResult) -> dict[str, str]:
    """One failed case as a plain dictionary.

    The stack trace is carried in full: it is the evidence, and a truncated
    trace is a summary where the evidence should be. The reader's message
    bound applies to the message line only.
    """
    failure = {
        "classname": case.classname or "?",
        "name": case.name or "?",
        "message": case.message,
        "body": case.stacktrace,
        "status": case.status,
        "retry_note": "",
    }
    if case.retried:
        failure["retry_note"] = (
            f"(also failed {case.flaky_attempts} earlier attempt(s) "
            "before the terminal failure)"
        )
    return failure


def _failures(results: list[TestCaseResult]) -> list[dict[str, str]]:
    """The failed cases among the parsed results.

    ``<error>`` is a failing outcome too — an OOM, a timeout or a crashed
    fixture is the failure it is, and a reporter that calls it a pass claims
    more than the run did.
    """
    return [_failure_dict(case) for case in results if case.failed_like]


def summarize(xml_text: str) -> list[dict[str, str]]:
    """The failures in one JUnit XML, as plain dictionaries.

    A report that cannot be parsed has no failures to report: the caller says
    so in a line, rather than the reader getting a traceback in the middle
    of a red build.
    """
    try:
        results = parse_junit_text(xml_text)
    except ET.ParseError:
        return []
    return _failures(results)


def render(failure: dict[str, str]) -> str:
    """One failure as text: the test, its message, its stack trace, the retry note."""
    lines = [f"{failure['classname']}#{failure['name']}: {failure['message']}"]
    body = failure.get("body", "")
    if body:
        lines.append("  " + body.replace("\n", "\n  "))
    note = failure.get("retry_note", "")
    if note:
        lines.append("  " + note)
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    root = pathlib.Path(argv[0] if argv else DEFAULT_ROOT)
    if not root.is_dir():
        print(f"no test results at {root}")
        return 0

    for path in sorted(root.rglob("*.xml")):
        try:
            results = parse_junit_file_strict(path)
        except ET.ParseError as exc:
            print(f"  {path}: parse error {exc}")
            continue
        except OSError as exc:
            print(f"  {path}: could not be read ({exc})")
            continue
        for failure in _failures(results):
            print(render(failure))
    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
