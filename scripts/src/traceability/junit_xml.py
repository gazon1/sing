"""The one JUnit XML reader for the traceability pipeline.

``sync.py`` already parsed Gradle JUnit XML. Rather than write a second parser
for the traceability path, the parsing primitive lives here.

Behaviour, and where each behaviour is pinned:

* the class comes from ``<testcase classname>``, never ``<testsuite name>`` —
  Gradle writes the latter with the target suffix (``AgendaDslTest[jvm]``) and
  it matches no FQN in the repository;
* a malformed file is reported and skipped, never raised, so one bad artefact
  does not abort a sync;
* ``<testcase file=…>`` is carried through untouched.

Covered by ``scripts/tests/test_junit_xml.py`` (this module's own contracts) and
``scripts/tests/test_traceability.py`` (the projection and join). An earlier
version of this docstring named ``scripts/tests/test_kiwi_sync.py`` as the safety
net; that file was deleted with the Kiwi stand, so the docstring advertised
coverage that no longer existed.

Why this is ``xml.etree`` and not ``junitparser``
-----------------------------------------------
``junitparser`` was evaluated as the backend here and rejected on evidence, not
on taste:

    >>> from junitparser import JUnitXml
    >>> case = next(iter(next(iter(JUnitXml.fromfile(p)))))
    >>> hasattr(case, "file")
    False

``junitparser``'s ``TestCase`` exposes only ``name``, ``classname``, ``time`` and
the outcome predicates; it does **not** carry the ``file`` attribute. That is
fatal here: ``file`` is the only stable identifier a Maestro flow's result has
(its ``classname`` is reporter-derived), and both ``normalize.py`` (the
``_FLOW_ROOTS`` match) and the provenance chain join on it. Adopting the library
would have traded one silent failure for a louder one.

For a long time this module was one of **two** readers: ``junitparser`` in
``scripts/junit-annotations.py`` (which needed no ``file`` — it now passes the
``file`` attribute to the GitHub annotation, so it does) and ``ElementTree``
here. The second reader did not merely duplicate the first — it disagreed with
it, and the disagreement was invisible in the place that mattered: a
``<error>`` testcase was reported to CI as a clean pass, and a retried test's
terminal failure was dropped. Both reporters now read this module, so there is
one parse and two renderings. If a future ``junitparser`` preserves unknown
attributes, this decision is worth revisiting — and this paragraph is where the
evidence lives.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

__all__ = [
    "TestCaseResult",
    "parse_junit_file",
    "parse_junit_file_strict",
    "parse_junit_text",
    "iter_result_files",
    "MESSAGE_LIMIT",
]

#: Failure messages are truncated before they reach a report. Kept as a bound
#: on the *summary* line only — the stack trace is carried separately and in
#: full, so the evidence a reader needs is never the thing that got cut.
MESSAGE_LIMIT = 2000


@dataclass(frozen=True, slots=True)
class TestCaseResult:
    """One ``<testcase>``, with the attributes that carry meaning.

    ``status`` is the raw JUnit outcome: ``passed`` / ``failed`` / ``error`` /
    ``skipped``. Mapping an outcome onto a publish status is a *publish*
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
    #: How many ``<flakyFailure>`` children the case carries — the failed
    #: attempts a retry recovered from (Gradle's ``mergeReruns``). ``0`` means
    #: the test passed on its first attempt.
    flaky_attempts: int = field(default=0)
    #: The failure/error element's text — the stack trace. Gradle puts only the
    #: exception's ``toString()`` in the ``message`` attribute; the trace lives
    #: in the element body, and a reporter that reads only the attribute
    #: prints a summary where the evidence is.
    stacktrace: str = field(default="")

    @property
    def failed_like(self) -> bool:
        """``error`` is a distinct status but a failing outcome."""
        return self.status in ("failed", "error")

    @property
    def retried(self) -> bool:
        """The test failed an earlier attempt and was retried."""
        return self.flaky_attempts > 0


def iter_result_files(directory: Path) -> list[Path]:
    """Every JUnit XML file in ``directory``, in a stable order.

    Sorted so a rebuild that changes nothing also produces a byte-identical
    ``results.json``; unsorted globbing makes CI diffs depend on the filesystem.
    A missing directory yields an empty list rather than raising — the caller
    decides whether "no results" is fatal, because for a target that produced
    zero testcases it is, and for a target that was never claimed it is not.

    The glob is ``*.xml``, not ``TEST-*.xml``. Gradle names its reports after
    the test class, but Maestro names its reports after the FLOW — the file is
    ``build/maestro-results/<flow>.xml`` — and a glob that only knows Gradle's
    naming sees none of them. The Maestro results therefore reached the
    traceability matrix as ZERO testcases: the matrix was empty and looked
    like a suite that ran nothing, and no error said otherwise.
    """
    if not directory.is_dir():
        return []
    return sorted(directory.glob("*.xml"))


def _classify(case: ET.Element) -> tuple[str, ET.Element | None]:
    """Outcome from child-element presence, in strict precedence.

    ``failure`` > ``error`` > ``skipped`` > passed. The precedence is not
    arbitrary: a test that both logs a failure and is skipped is a failure, and
    an ``error`` is an infrastructure problem that must not be laundered into a
    pass.

    ``<flakyFailure>`` is deliberately *not* part of the precedence: it records
    an earlier failed attempt of a retried test, not the test's outcome. Gradle
    writes one such child per failed attempt when ``mergeReruns`` is on, so a
    case can carry both a ``<flakyFailure>`` and a terminal ``<failure>`` — and
    in that case the terminal failure is the outcome. Reading ``flakyFailure``
    as the outcome (and stopping there) is what used to make the CI reporter
    drop a real failure.
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


def parse_junit_text(text: str) -> list[TestCaseResult]:
    """Parse one JUnit XML document held in memory.

    The text twin of :func:`parse_junit_file_strict`: a reporter that already
    has the document parses it through the same code, so the repository keeps
    one reader instead of one reader per entry point. Raises
    ``ET.ParseError`` on a malformed document, like its file twin.
    """
    return _results_from_root(ET.fromstring(text))


def parse_junit_file_strict(path: Path) -> list[TestCaseResult]:
    """Parse one JUnit XML file, raising ``ET.ParseError`` on malformed input.

    The strict twin of :func:`parse_junit_file`. A reporter must be able to say
    "this report is unreadable" out loud — a malformed report is incomplete
    evidence, not an empty result — while a sync pipeline must be able to skip
    one bad artefact without losing the rest. The tolerant wrapper is the
    default; the strict one is what reporters use.
    """
    root = ET.parse(path).getroot()
    return _results_from_root(root)


def _results_from_root(root: ET.Element) -> list[TestCaseResult]:
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    results: list[TestCaseResult] = []
    for suite in suites:
        for case in suite.findall("testcase"):
            status, node = _classify(case)
            message = ""
            stacktrace = ""
            if node is not None:
                message = (node.get("message") or "")[:MESSAGE_LIMIT]
                stacktrace = (node.text or "").strip()
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
                    flaky_attempts=len(case.findall("flakyFailure")),
                    stacktrace=stacktrace,
                )
            )
    return results


def parse_junit_file(path: Path) -> list[TestCaseResult]:
    """Parse one JUnit XML file into flat results.

    Handles both root shapes: a bare ``<testsuite>`` (Gradle) and a
    ``<testsuites>`` container (Maestro emits one suite per flow). ``testcase``
    elements are read as direct children of a suite — not via a recursive walk
    — so a nested ``<testcase>`` inside an unexpected wrapper is skipped rather
    than silently double-counted somewhere deeper.

    A malformed file is reported on stderr and skipped, never raised: one bad
    artefact must not abort a whole sync. Reporters that need to *say* that the
    evidence is incomplete use :func:`parse_junit_file_strict` instead.
    """
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"  ! нечитаемый {path.name}: {exc}", file=sys.stderr)
        return []
    except OSError as exc:
        if isinstance(exc, FileNotFoundError):
            print(f"  ! отсутствует {path.name}: {exc}", file=sys.stderr)
        else:
            print(f"  ! нечитаемый {path.name}: {exc}", file=sys.stderr)
        return []
    return _results_from_root(root)


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
