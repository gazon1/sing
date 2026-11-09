#!/usr/bin/env python3
"""test-evidence.py — what failed, and where the evidence for it is.

## Why this exists

A failing CI run leaves evidence scattered across three places, and nothing
ties them together:

* the **JUnit XML** says *what* failed and *why*;
* the **diagnostics bundle** (`build/diagnostics/<Class>/attempt-N/`) says *what
  the screen looked like* — but only for the tests that produced one;
* the **artifacts** (`ci-evidence-<run_id>`, `maestro-junit-…`) say *where the
  bytes are* — but not *which failure they belong to*.

The correlation was manual: a reader opened the artifact list, found the
bundle directory by eye, and matched it to a testcase by hand. This script does
that correlation once, mechanically, and writes the answer down.

Three issues asked for the same missing thing, which is why there is one
implementation and not three:

* **#2664** — a failing Desktop test must have a direct path to its bundle.
* **#2666** — a failing Maestro flow must carry its JUnit result, its log and
  its screenshots together.
* **#2669** — a run must publish a navigable summary of its own evidence.

## Two outcome vocabularies, kept apart

The point of this script is that a *task* failing is not a *testcase* failing,
and the report says which one happened:

* **Task / process outcomes** — `passed`, `failed`, `skipped`, `not_run`,
  `cancelled`, `timed_out`, `unknown`. A compilation error, a Gradle
  configuration error, a worker crash and a job timeout are all task outcomes.
  None of them is a testcase failure.
* **Testcase outcomes** — `passed`, `failed`, `error`, `skipped`, `flaky`,
  `unknown`.

The task outcome is an **input**, never an inference: the caller passes what
the build actually did (`--task-outcome`). The script does not read the XML and
decide that a missing testcase was a failure — the issue that asked for this
explicitly says not to infer `not_run` for an individual testcase from JUnit
XML. Missing evidence is reported at the *task* level, where it is knowable.

A cancelled or timed-out task is therefore never rendered as a failed test:
the two vocabularies sit in different sections of the same report.

## What "incomplete evidence" means

Three states are distinguishable, and they used to blur into each other:

| State | Meaning |
|---|---|
| a report with testcases | the task ran |
| a report with **no** testcases | the task ran and selected nothing — a successful empty report |
| **no** report | the task did not execute, or its output was never uploaded |

The third is the one that lies: a missing report read as an empty one says
"the suite passed and found nothing to run". `--expect` names the tasks that
were supposed to run, and every one of them without a report is listed as
`not_run` with the reason.

A report that exists but cannot be parsed is a fourth state: *unreadable*.
It is never counted as an empty successful report.

## Usage

    python3 scripts/test-evidence.py \
        --glob "**/build/test-results/**/*.xml" \
        --expect shared:jvmTest --expect desktopApp:test \
        --task-outcome failed \
        --population fast \
        --out-dir build/evidence

    # and, in CI, append the same index to the job summary:
    python3 scripts/test-evidence.py --glob "..." --summary

## Outputs

* `<out-dir>/index.md` — the human-readable index.
* `<out-dir>/manifest.json` — the machine-readable manifest: what ran, what
  failed, where each failure's diagnostics are, and what is missing.

## Exit codes

0 in every case. This is a reporter: it describes a run, and a reporter that
fails the job it describes is a reporter nobody trusts. The verdict about the
run belongs to the build, the floors and the gates.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import sys
from dataclasses import dataclass, field

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this script must read the reader from *this* checkout, not from
# whatever happens to be installed. When this file is loaded by a test harness
# via importlib, sys.path[0] is `scripts/tests` and the package is invisible;
# this file's own location is authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

from traceability.junit_xml import TestCaseResult, parse_junit_file_strict  # noqa: E402
import workflow_gradle as wg  # noqa: E402

if _added:
    sys.path.remove(_scripts_src)

#: The outcome of a *task* or a *process* — never of a testcase.
#:
#: `not_run` is a task-level state on purpose: the issue that asked for this
#: model says explicitly that a missing testcase must not be inferred to be
#: `not_run` from the XML, because a testcase that is absent from a report
#: never ran for reasons the report does not record.
TASK_OUTCOMES = (
    "passed",
    "failed",
    "skipped",
    "not_run",
    "cancelled",
    "timed_out",
    "unknown",
)

#: The outcome of a single testcase, as the XML states it.
TESTCASE_OUTCOMES = (
    "passed",
    "failed",
    "error",
    "skipped",
    "flaky",
    "unknown",
)

#: The task outcomes that mean the run did not finish. A run in one of these
#: states is not a run whose tests failed — it is a run that stopped.
RUN_DID_NOT_FINISH = ("not_run", "cancelled", "timed_out")


@dataclass
class Report:
    """One JUnit XML file and what could be read out of it."""

    path: pathlib.Path
    module: str
    task: str
    readable: bool
    reason: str = ""
    counts: dict[str, int] = field(default_factory=dict)
    failures: list["Failure"] = field(default_factory=list)


@dataclass
class Failure:
    """One failing (or erroring) testcase, and where its evidence is."""

    classname: str
    name: str
    outcome: str
    message: str
    report: str
    diagnostics: list[str] = field(default_factory=list)


@dataclass
class Evidence:
    """Everything the index says about one run."""

    task_outcome: str = "unknown"
    population: str = ""
    commit: str = ""
    run_id: str = ""
    profile: str = ""
    #: The repository the paths in this index are relative to. A manifest
    #: that spells out `/home/someone/build/...` is a manifest of one
    #: machine, and the evidence is meant to travel between them.
    root: pathlib.Path | None = None
    metadata: dict[str, str] = field(default_factory=dict)
    reports: list[Report] = field(default_factory=list)
    missing_tasks: list[str] = field(default_factory=list)
    unreadable: list[tuple[str, str]] = field(default_factory=list)

    @property
    def failures(self) -> list[Failure]:
        return [f for report in self.reports for f in report.failures]

    @property
    def incomplete(self) -> bool:
        """Is any piece of the expected evidence missing or unreadable?"""
        return bool(self.missing_tasks or self.unreadable)


# ── the report's own identity ─────────────────────────────────────────────────


#: AGP names the unit-test report directory after the VARIANT, not after the
#: task: the `test` task of the debug variant writes `testDebugUnitTest`. The
#: profile vocabulary names tasks, so the identity is the task.
_VARIANT_UNIT_TEST = re.compile(r"^test(?:Debug|Release)?UnitTest$")

#: A diagnostics attempt directory: `attempt-1`, `attempt-10`. The number is
#: the attempt's ordinal, and it is compared as one.
_ATTEMPT = re.compile(r"^attempt-(\d+)$")


def _task_name(task_dir: str) -> str:
    """The Gradle task a report directory belongs to."""
    return "test" if _VARIANT_UNIT_TEST.match(task_dir) else task_dir


def report_identity(root: pathlib.Path, path: pathlib.Path) -> tuple[str, str]:
    """The module and the task a report belongs to, read off its path.

    The path is the only thing that says which task wrote the file, and it is
    the *only* honest source: a report's contents do not name the task that
    produced it, and a report that travels between runners (an artifact
    downloaded into another job) still names the task in its path.

    Three shapes exist in this repository:

    * `<module>/build/test-results/<task-dir>/TEST-*.xml` — Gradle.
    * `<module>/build/outputs/androidTest-results/connected/<variant>/…` — the
      instrumented suite.
    * `build/maestro-results/<flow>.xml` — Maestro, whose tasks are flows and
      not Gradle tasks at all.

    The module is everything BEFORE the `build` segment, not the first segment
    of the path: a module of this repository is a Gradle project path, and one
    of them is nested (`tools/unwritten-properties`). Taking the first segment
    would call that module `tools` — a name no profile declares and no task
    answers to, so its reports would look like a task nobody ran.

    The module is written the way the profile vocabulary writes it: with
    COLONS (`tools:unwritten-properties`), not with slashes. The vocabulary
    says `module:task`, and a report whose module is spelled `tools` while
    every profile spells it `tools:unwritten-properties` is a report of a task
    nobody declared — the reporter would call it missing and list it in the
    same breath as the report it was reading. The slash form is a FILE path
    and `module_path()` turns the identity back into one.

    A path that matches none of them yields the module and an unknown task
    rather than a guess: a wrong task name is worse than an absent one.

    The task name is the GRADLE task name, with one exception: AGP writes the
    unit-test report of the `test` task into a directory named after the
    variant (`testDebugUnitTest`), and the profile vocabulary names the task
    (`androidApp:test`). The identity is the task, so the variant suffix is
    dropped — otherwise one task would have two identities and the reporter
    would call a report missing that was there.
    """
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        parts = path.parts
    if not parts:
        return "?", "?"
    # Maestro first: its results live at the ROOT (`build/maestro-results/`),
    # so the `build` segment is the first one and would name an empty module.
    if "maestro-results" in parts:
        return "maestro", "maestro:flows"
    if "build" not in parts:
        module = ":".join(parts[:-1]) or "?"
        return module, f"{module}:?"
    index = parts.index("build")
    module = ":".join(parts[:index]) or "?"
    rest = parts[index:]
    if "androidTest-results" in rest:
        # AGP writes `androidTest-results/connected/<variant>/<device>/…`, so
        # the variant is the segment *after* `connected`.
        offset = rest.index("connected") + 1 if "connected" in rest else 1
        variant = rest[offset] if len(rest) > offset else "debug"
        return module, f"{module}:connected{variant.capitalize()}AndroidTest"
    if "test-results" in rest:
        task_index = rest.index("test-results")
        task_dir = rest[task_index + 1] if len(rest) > task_index + 1 else "?"
        return module, f"{module}:{_task_name(task_dir)}"
    return module, f"{module}:?"


def module_path(module: str) -> str:
    """The FILE path of a module, from its colon-form identity.

    The identity is `tools:unwritten-properties` and the directory is
    `tools/unwritten-properties` — the two directions are not mirrors, and a
    reporter that mixed them up would look for the diagnostics bundle of a
    directory that does not exist.
    """
    return module.replace(":", "/")


def simple_class_names(classname: str) -> list[str]:
    """The names a diagnostics directory could be named after.

    Gradle writes the fully-qualified name, sometimes with the target suffix
    (`MenuBarTest[jvm]`), and a nested test keeps its `$` separator
    (`OuterTest$NestedTest`). The bundles name their directory after the
    simple class name, so the candidates are tried in order of specificity:
    the full simple name first, then the outer class of a nested test.
    """
    name = classname.split("[")[0]
    name = name.rsplit(".", 1)[-1]
    candidates = [name]
    if "$" in name:
        outer = name.split("$", 1)[0]
        if outer and outer not in candidates:
            candidates.append(outer)
    return candidates


def diagnostics_dirs(
    root: pathlib.Path,
    module: str,
    classname: str,
    report_path: pathlib.Path,
) -> list[pathlib.Path]:
    """The diagnostics directories that belong to one testcase.

    Two contracts, one per tier, and both are *directories named after the
    class* — which is what makes the association deterministic and free of
    cross-linking between concurrent tests:

    * a Gradle tier writes `<module>/build/diagnostics/<Class>/`, and the
      desktop suite adds an `attempt-N/` directory per attempt;
    * Maestro writes `build/maestro-results/<flow>/` next to the flow's own
      JUnit file.

    The latest `attempt-N` wins: a retried test's last attempt is the one the
    report talks about. The number is compared as a NUMBER — `attempt-10` is
    later than `attempt-2`, and a string comparison says the opposite, which
    would link a failed test to the bundle of a run nine attempts older. A
    directory that is not a numbered attempt is not an attempt at all, and is
    ignored rather than guessed at.
    """
    found: list[pathlib.Path] = []
    if module == "maestro":
        candidate = report_path.parent / report_path.stem
        if candidate.is_dir():
            found.append(candidate)
        return found
    for name in simple_class_names(classname):
        base = root / module_path(module) / "build" / "diagnostics" / name
        if not base.is_dir():
            continue
        attempts = sorted(
            (
                child
                for child in base.iterdir()
                if child.is_dir() and _ATTEMPT.match(child.name)
            ),
            key=lambda child: int(_ATTEMPT.match(child.name).group(1)),  # type: ignore[union-attr]
        )
        found.append(attempts[-1] if attempts else base)
        break
    return found


# ── reading ───────────────────────────────────────────────────────────────────


def _outcome(case: TestCaseResult) -> str:
    """The testcase outcome, in this script's vocabulary.

    A test that failed an earlier attempt and passed on the retry is `flaky`,
    not `passed`: it is the outcome the run actually had, and a summary that
    counts it as a clean pass claims more than the run did.
    """
    if case.failed_like:
        return case.status
    if case.status == "skipped":
        return "skipped"
    if case.retried:
        return "flaky"
    return case.status


def _message(case: TestCaseResult) -> str:
    """The one-line reason a testcase failed.

    Gradle puts only the exception's `toString()` in the `message` attribute
    and the trace in the element body, so a reporter that reads only the
    attribute prints a summary where the evidence is. The first line of the
    trace is the fallback.
    """
    if case.message:
        return case.message
    for line in case.stacktrace.splitlines():
        if line.strip():
            return line.strip()
    return ""


def read_report(root: pathlib.Path, path: pathlib.Path) -> Report:
    """Read one JUnit XML file, keeping the failure of a parse explicit."""
    module, task = report_identity(root, path)
    try:
        cases = parse_junit_file_strict(path)
    except Exception as exc:  # noqa: BLE001 — a malformed report must not crash the reader
        return Report(path=path, module=module, task=task, readable=False, reason=str(exc))
    counts: dict[str, int] = {}
    failures: list[Failure] = []
    for case in cases:
        outcome = _outcome(case)
        counts[outcome] = counts.get(outcome, 0) + 1
        if outcome in ("failed", "error"):
            failures.append(
                Failure(
                    classname=case.classname,
                    name=case.name,
                    outcome=outcome,
                    message=_message(case),
                    report=str(path.relative_to(root)) if path.is_relative_to(root) else str(path),
                    diagnostics=[
                        str(d.relative_to(root)) if d.is_relative_to(root) else str(d)
                        for d in diagnostics_dirs(root, module, case.classname, path)
                    ],
                )
            )
    return Report(path=path, module=module, task=task, readable=True, counts=counts, failures=failures)


def collect(
    root: pathlib.Path,
    globs: list[str],
    expect: list[str],
    task_outcome: str = "unknown",
) -> Evidence:
    """Read every report the globs name and work out what is missing."""
    evidence = Evidence(task_outcome=task_outcome, root=root)
    seen: set[pathlib.Path] = set()
    for pattern in globs:
        for path in sorted(root.glob(pattern)):
            if not path.is_file() or path in seen:
                continue
            seen.add(path)
            report = read_report(root, path)
            evidence.reports.append(report)
            if not report.readable:
                evidence.unreadable.append((str(path.relative_to(root)), report.reason))
    # A task that was supposed to run and left no report is `not_run`. A task
    # that left a report with no testcase in it is a successful empty report —
    # a different thing, and the reason this distinction is worth a table.
    #
    # The existence of a report and its READABILITY are two questions. A report
    # that exists and cannot be parsed is a report the task wrote and the
    # reader could not make sense of: it is in the `unreadable` list, and it
    # is NOT a task that did not run. Saying both would say the same thing
    # twice, and the second thing would be false.
    produced = {report.task for report in evidence.reports}
    for task in expect:
        if task not in produced:
            evidence.missing_tasks.append(task)
    return evidence


# ── rendering ─────────────────────────────────────────────────────────────────


def _code(text: str) -> str:
    """An inline code span that survives the text inside it.

    A test name is untrusted text. A backtick in it closes the span, and the
    rest of the name stops being a name and becomes markdown. A double fence
    survives a single backtick, and the span is still a span.

    A pipe is the other one that matters: this index puts untrusted text in
    MARKDOWN TABLES, and a pipe ends a cell even inside a code span — the
    table would grow a column that nobody wrote. The pipe is escaped, and so
    is the backslash that would otherwise escape the escape.
    """
    fence = "``" if "`" in text else "`"
    body = text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")
    return f"{fence}{body}{fence}"


def _cell(text: str) -> str:
    """A table cell out of untrusted text.

    A pipe ends the cell, a newline ends the row, and a backtick opens a code
    span. All three are things a failure message can contain — a stack trace
    is not written by this repository and does not agree to keep its pipes
    to itself.
    """
    return text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("`", "'")


def profile_expectations(profile: str) -> list[str]:
    """The tasks a named profile says a run of it should have produced.

    The profile registry is the DECLARED set, and this is what makes it one:
    a run of the `all` profile expects the tasks `all` declares — not every
    XML file that happens to be under `build/`. A wildcard sweep counts the
    reports of a task nobody ran in this run, and a report of an unrelated
    task is evidence about a different execution.

    The expected tasks are the profile's REPORTED ones, not all of its tasks:
    a task whose report arrives after the index is built, and a task that ran
    and selected no test, are both tasks that ran — and the reporter that
    called them missing would report every run as incomplete.
    """
    return list(wg.profile_reported(profile))


def render_index(evidence: Evidence) -> str:
    """The human-readable index."""
    lines: list[str] = ["# Test evidence", ""]
    lines.append(f"- task outcome: **{evidence.task_outcome}**")
    if evidence.population:
        lines.append(f"- population: {_code(evidence.population)}")
    if evidence.profile:
        lines.append(f"- profile: {_code(evidence.profile)}")
    if evidence.commit:
        lines.append(f"- commit: {_code(evidence.commit)}")
    if evidence.run_id:
        lines.append(f"- run id: {_code(evidence.run_id)}")
    for key in sorted(evidence.metadata):
        lines.append(f"- {key}: {_code(evidence.metadata[key])}")
    lines.append("")

    failures = evidence.failures
    if evidence.task_outcome in RUN_DID_NOT_FINISH:
        lines.append(
            f"## The run did not finish (`{evidence.task_outcome}`)"
        )
        lines.append("")
        lines.append(
            "The task outcome and the testcase outcomes are different things, and a "
            "cancelled or timed-out run is not a run whose tests failed. The testcases "
            "below are what the partial report holds; they are not the verdict."
        )
        lines.append("")
    elif failures:
        lines.append(f"## Failing testcases ({len(failures)})")
        lines.append("")
    else:
        lines.append("## Failing testcases (0)")
        lines.append("")
        lines.append("No testcase in the reports read a failure.")
        lines.append("")

    if failures:
        lines.append("| test | outcome | message | diagnostics | report |")
        lines.append("|---|---|---|---|---|")
        for failure in failures:
            diagnostics = "<br>".join(_code(d) for d in failure.diagnostics) or "—"
            message = _cell(failure.message)
            lines.append(
                f"| {_code(f'{failure.classname}.{failure.name}')} | {failure.outcome} | "
                f"{message} | {diagnostics} | {_code(failure.report)} |"
            )
        lines.append("")

    lines.append("## Reports")
    lines.append("")
    lines.append("| report | module | task | outcome | counts |")
    lines.append("|---|---|---|---|---|")
    for report in evidence.reports:
        if report.readable:
            counts = ", ".join(f"{name} {count}" for name, count in sorted(report.counts.items()))
            # A report with no testcase in it is a report the task ran and wrote
            # and that selected nothing. It is not the same thing as no report
            # at all, and the table must not let the two look alike.
            outcome = "read" if report.counts else "read, **0 testcases**"
        else:
            counts = "—"
            outcome = "**unreadable**"
        lines.append(
            f"| {_code(report.path.name)} | {_code(report.module)} | {_code(report.task)} | "
            f"{outcome} | {counts or '—'} |"
        )
    lines.append("")

    if evidence.incomplete:
        lines.append("## Incomplete evidence")
        lines.append("")
        lines.append(
            "The evidence below is missing or unreadable. It is *not* a passing result: "
            "a run that produced no report proves nothing about what it would have found."
        )
        lines.append("")
        for path, reason in evidence.unreadable:
            lines.append(f"- {_code(path)} — unreadable: {_cell(reason)}")
        for task in evidence.missing_tasks:
            lines.append(
                f"- {_code(task)} — no report: the task did not execute, "
                f"or its output never arrived."
            )
        lines.append("")
    else:
        lines.append("## Incomplete evidence")
        lines.append("")
        lines.append("None: every expected task produced a readable report.")
        lines.append("")

    return "\n".join(lines)


def render_manifest(evidence: Evidence) -> dict:
    """The machine-readable manifest."""
    root = evidence.root
    return {
        "task_outcome": evidence.task_outcome,
        "population": evidence.population,
        "profile": evidence.profile,
        "commit": evidence.commit,
        "run_id": evidence.run_id,
        "metadata": dict(evidence.metadata),
        "reports": [
            {
                "path": (
                    str(report.path.relative_to(root))
                    if root is not None and report.path.is_relative_to(root)
                    else str(report.path)
                ),
                "module": report.module,
                "task": report.task,
                "readable": report.readable,
                "reason": report.reason,
                "counts": report.counts,
            }
            for report in evidence.reports
        ],
        "failures": [
            {
                "classname": failure.classname,
                "name": failure.name,
                "outcome": failure.outcome,
                "message": failure.message,
                "report": failure.report,
                "diagnostics": failure.diagnostics,
            }
            for failure in evidence.failures
        ],
        "missing_tasks": evidence.missing_tasks,
        "unreadable": [{"path": path, "reason": reason} for path, reason in evidence.unreadable],
        "incomplete": evidence.incomplete,
    }


# ── entry point ───────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Index the evidence a test run produced")
    parser.add_argument("--root", default=".", help="The repository root (default: .)")
    parser.add_argument(
        "--glob",
        dest="globs",
        action="append",
        default=None,
        help=(
            "A glob, relative to the root, of the JUnit XML to read (repeatable). "
            "Without it the profile says where its reports live"
        ),
    )
    parser.add_argument(
        "--expect",
        dest="expect",
        action="append",
        default=None,
        help=(
            "A task that was supposed to run, as module:task (repeatable). "
            "Overrides the profile's own list of expected tasks"
        ),
    )
    parser.add_argument(
        "--profile",
        default="",
        help=(
            "The named profile the run executed (fast, full, device, e2e, all). "
            "The profile names the run, supplies its population, says where its "
            "reports live, and says which tasks are expected to have written one "
            "by the time the index is built — a task whose report arrives later, "
            "or whose population selected no test of it, is a task that ran and "
            "is not missing evidence"
        ),
    )
    parser.add_argument(
        "--metadata",
        dest="metadata",
        action="append",
        default=None,
        help="A run fact as key=value — os, jdk, gradle, device, shard, apk (repeatable)",
    )
    parser.add_argument(
        "--metadata-file",
        default="",
        help=(
            "A JSON file with an object of run facts (device, api, apk, commit, "
            "shard, …). The platform runners write one; the workflow passes it "
            "on rather than retyping what the runner already knows"
        ),
    )
    parser.add_argument(
        "--task-outcome",
        default="unknown",
        choices=list(TASK_OUTCOMES),
        help=(
            "What the task or process did, as the caller saw it. The caller is "
            "the only one who knows: a step that hit its deadline and a step "
            "that failed look the same to GitHub Actions, so CI passes `failed` "
            "and a caller that knows better passes `timed_out`"
        ),
    )
    parser.add_argument("--population", default="", help="The population the run selected")
    parser.add_argument("--commit", default="", help="The commit the run measured")
    parser.add_argument("--run-id", default="", help="The run id the evidence belongs to")
    parser.add_argument("--out-dir", default="build/evidence", help="Where the index and the manifest go")
    parser.add_argument(
        "--summary",
        action="store_true",
        help="Append the index to $GITHUB_STEP_SUMMARY when that variable is set",
    )
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    # Where the reports are comes from the profile: the three tiers write
    # them in three different places, and a reporter asked for `device` that
    # looked in the JVM place would report a run that has no reports in it.
    globs = list(args.globs or [])
    if not globs and args.profile:
        globs = list(wg.profile_globs(args.profile))
    if not globs:
        globs = ["**/build/test-results/**/*.xml"]
    # The explicit list wins over the profile's declared set: a run that knows
    # exactly which tasks it ran says so, and a run that names only a profile
    # gets the profile's tasks. The two are never merged — a task the caller
    # did not expect but the profile declares would be reported as missing
    # evidence of a run that never promised it.
    if args.expect:
        expected = list(args.expect)
    elif args.profile:
        expected = profile_expectations(args.profile)
    else:
        expected = []
    evidence = collect(root, globs, expected, args.task_outcome)
    evidence.population = args.population or (
        wg.profile_population(args.profile) if args.profile else ""
    )
    evidence.profile = args.profile
    evidence.commit = args.commit
    evidence.run_id = args.run_id
    for item in args.metadata or []:
        key, _, value = item.partition("=")
        if key.strip():
            evidence.metadata[key.strip()] = value.strip()
    if args.metadata_file:
        path = pathlib.Path(args.metadata_file)
        if not path.is_absolute():
            path = root / path
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print(f"could not read the run's metadata file {path}: {exc}", file=sys.stderr)
            payload = {}
        if isinstance(payload, dict):
            for key, value in payload.items():
                evidence.metadata[str(key)] = "" if value is None else str(value)
        evidence.metadata.setdefault("metadata_file", str(path))

    out_dir = root / args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    index = render_index(evidence)
    (out_dir / "index.md").write_text(index, encoding="utf-8")
    (out_dir / "manifest.json").write_text(
        json.dumps(render_manifest(evidence), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(index)
    print(f"the index and the manifest are in {out_dir}", file=sys.stderr)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if args.summary and summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(index)
            handle.write("\n")
    if evidence.incomplete and os.environ.get("GITHUB_ACTIONS"):
        # The summary says it, but a summary is a thing a reader has to open.
        # An annotation shows up on the run page and on the PR, next to the
        # job that produced it — and "the evidence is incomplete" is exactly
        # the kind of thing that must not need a second click to notice.
        detail = ", ".join(evidence.missing_tasks) or ", ".join(
            path for path, _ in evidence.unreadable
        )
        print(f"::warning::the evidence of this run is incomplete: {detail}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
