#!/usr/bin/env python3
"""print-test-failures.py — the failures of a Gradle test run, as text.

Why
---
A red test is only useful if the message survives the trip from the test
report to the person reading it. The reports are XML, and reading XML by hand
is what an agent does when nothing prints the failures for it.

So this script prints them: the failing test, its message, and the first part
of its body. It is the same summary the CI helper used to produce from an
inline python heredoc — the difference is that this one can be tested, and
that a broken report is a line of output instead of a traceback in the middle
of a red build.

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

#: Where Gradle writes the test results of the shared module.
DEFAULT_ROOT = "shared/build/test-results"

#: How much of a failure's body is printed. Enough to see the assertion, short
#: enough that a stack trace does not bury the other failures.
BODY_LIMIT = 500


def summarize(xml_text: str) -> list[dict[str, str]]:
    """The failures in one JUnit XML, as plain dictionaries.

    A report that cannot be parsed has no failures to report: the caller says
    so in a line, rather than the reader getting a traceback in the middle of
    a red build.
    """
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    failures: list[dict[str, str]] = []
    for case in root.iter("testcase"):
        for node in list(case):
            if node.tag not in ("failure", "error"):
                continue
            failures.append({
                "classname": case.get("classname", "?"),
                "name": case.get("name", "?"),
                "message": node.get("message", ""),
                "body": (node.text or "").strip(),
            })
    return failures


def render(failure: dict[str, str]) -> str:
    """One failure as text: the test, its message, and the start of its body."""
    lines = [f"{failure['classname']}#{failure['name']}: {failure['message']}"]
    body = failure["body"]
    if body:
        lines.append("  " + body[:BODY_LIMIT].replace("\n", "\n  "))
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    root = pathlib.Path(argv[0] if argv else DEFAULT_ROOT)
    if not root.is_dir():
        print(f"no test results at {root}")
        return 0

    for path in sorted(root.rglob("*.xml")):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            print(f"  {path}: could not be read ({exc})")
            continue
        try:
            ET.fromstring(text)
        except ET.ParseError as exc:
            print(f"  {path}: parse error {exc}")
            continue
        for failure in summarize(text):
            print(render(failure))
    print("done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
