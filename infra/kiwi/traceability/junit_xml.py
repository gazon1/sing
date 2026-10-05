"""The one JUnit XML reader, shared by the new pipeline and the legacy path.

``sync.py`` already parses Gradle JUnit XML. Rather than write a second parser
for the traceability path — two parsers of one format is how the join between
"what ran" and "what was claimed" quietly breaks — the parsing primitive lives
here and ``sync.py`` imports it. ``scripts/tests/test_kiwi_sync.py`` (318 lines)
is the safety net for that refactor: it pins ``classname``-not-``testsuite@name``,
the failure/error/skipped precedence, and malformed-XML tolerance.

Deliberate behaviours, both pinned by existing tests:

* the class comes from ``<testcase classname>``, never ``<testsuite name>`` —
  Gradle writes the latter with the target suffix (``AgendaDslTest[jvm]``) and
  it matches no FQN in the repository;
* a malformed file is reported and skipped, never raised, so one bad artefact
  does not abort a sync.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

__all__ = ["TestCaseResult", "parse_junit_file", "iter_result_files", "MESSAGE_LIMIT"]

#: Failure messages are truncated before they reach Kiwi (a TEXT column, not a
#: log sink). Kept identical to the legacy reader so behaviour does not shift.
MESSAGE_LIMIT = 2000


@dataclass(frozen=True, slots=True)
class TestCaseResult:
    """One ``<testcase>``, with the three attributes that carry meaning.

    ``status`` is the raw JUnit outcome: ``passed`` / ``failed`` / ``error`` /
    ``skipped``. Mapping an outcome onto a Kiwi execution status is a *publish*
    concern and lives in exactly one table, so the ``skipped`` divergence
    between the official plugin (``WAIVED``) and this codebase (``IDLE``) is
    visible in one place instead of encoded twice.
    """

    classname: str
    name: str
    status: str
    time: float = 0.0
    message: str = field(default="")
    #: ``<testcase file=…>``. Gradle omits it; Maestro sets it to the flow path,
    #: which is the only stable identifier a flow's result has — its
    #: ``classname`` is reporter-derived and is not a path we can join on.
    file: str = field(default="")

    @property
    def failed_like(self) -> bool:
        """``error`` is a distinct status but a failing outcome."""
        return self.status in ("failed", "error")


def iter_result_files(directory: Path) -> list[Path]:
    """Every JUnit XML file in ``directory``, in a stable order.

    Sorted so a rebuild that changes nothing also produces a byte-identical
    ``results.json``; unsorted globbing makes CI diffs depend on the filesystem.
    A missing directory yields an empty list rather than raising — the caller
    decides whether "no results" is fatal, because for a target that produced
    zero testcases it is, and for a target that was never claimed it is not.
    """
    if not directory.is_dir():
        return []
    return sorted(directory.glob("TEST-*.xml"))


def _classify(case: ET.Element) -> tuple[str, ET.Element | None]:
    """Outcome from child-element presence, in strict precedence.

    ``failure`` > ``error`` > ``skipped`` > passed. The precedence is not
    arbitrary: a test that both logs a failure and is skipped is a failure, and
    an ``error`` is an infrastructure problem that must not be laundered into a
    pass.
    """
    node = case.find("failure")
    if node is not None:
        return "failed", node
    node = case.find("error")
    if node is not None:
        return "error", node
    node = case.find("skipped")
    if node is not None:
        return "skipped", node
    return "passed", None


def parse_junit_file(path: Path) -> list[TestCaseResult]:
    """Parse one JUnit XML file into flat results.

    Handles both root shapes: a bare ``<testsuite>`` (Gradle) and a
    ``<testsuites>`` container (Maestro emits one suite per flow). ``testcase``
    elements are read as direct children of a suite — not via a recursive walk
    — so a nested ``<testcase>`` inside an unexpected wrapper is skipped rather
    than silently double-counted somewhere deeper.
    """
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"  ! нечитаемый {path.name}: {exc}", file=sys.stderr)
        return []

    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    results: list[TestCaseResult] = []
    for suite in suites:
        for case in suite.findall("testcase"):
            status, node = _classify(case)
            message = ""
            if node is not None and node.get("message"):
                message = node.get("message", "")[:MESSAGE_LIMIT]
            try:
                elapsed = float(case.get("time") or 0.0)
            except ValueError:
                elapsed = 0.0
            results.append(
                TestCaseResult(
                    classname=case.get("classname") or "",
                    name=case.get("name") or "",
                    status=status,
                    time=elapsed,
                    message=message,
                    file=case.get("file") or "",
                )
            )
    return results


def parse_junit(directories: list[Path]) -> list[TestCaseResult]:
    """Parse every JUnit XML file across ``directories``.

    Flat, and deliberately not grouped by class: the traceability pipeline joins
    per *test*, so grouping by class would only invite the legacy "one case per
    class" mistake. The legacy caller still groups on its own side.
    """
    results: list[TestCaseResult] = []
    for directory in directories:
        for xml_file in iter_result_files(directory):
            results.extend(parse_junit_file(xml_file))
    return results
