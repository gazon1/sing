#!/usr/bin/env python3
"""instrumented-run-summary.py — what the instrumented tier actually did, in words.

## The reader this is for

A pull request shows one step for the instrumented smoke: red, green, or
"failed with an annotation". The step's own log is a Gradle transcript, and the
answer to the only question a reviewer has — *did the app work on a device?* —
is in it somewhere, between the emulator's boot log and the JUnit XML path.

The smoke tier is advisory, so its failure does not block the merge, and an
advisory failure that nobody can read is the same as no signal at all. This
script writes the verdict into the job summary, where the reviewer is already
looking: what ran, how many tests, which failed, and — the case that matters
most — whether the tier ran at all.

## The four verdicts

- **passed** — the report exists and every test in it passed.
- **failed** — the report exists and at least one test failed. The failing
  test names are listed, because "the smoke failed" is a question and
  "NavigationFlowInstrumentedTest.nav_reaches_the_today_tab failed" is an
  answer.
- **did not run** — there is no report, or the report holds no testcase. This
  is the verdict that is worth distinguishing from a failure: a tier that did
  not run proves nothing about the app, and a red X that means "nothing
  happened" reads like "the app is broken".
- **could not read** — the report exists but could not be parsed. A reading
  failure is not a test failure, and saying so keeps the two apart.

The script never fails the job it summarises. Its exit code is 0 in every
case, including the case where it cannot read the report at all: a reporter
that crashes the pipeline is a reporter nobody trusts to report.

Usage:

    python3 scripts/instrumented-run-summary.py --tier smoke
    python3 scripts/instrumented-run-summary.py --tier full --json

Exit codes:
  0 — a verdict was written (whatever the verdict was)
"""
from __future__ import annotations

import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

import click

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPORT_DIR = ROOT / "androidApp" / "build" / "outputs" / "androidTest-results" / "connected"

#: The instrumented runner names its classes `com.singularity.todo.…`; the
#: summary prints the simple name, because the package is the same for every
#: class in the tier and the reader is looking for a test, not for a package.
_FQN = re.compile(r"^.*\.")


def report_files(report_dir: pathlib.Path) -> list[pathlib.Path]:
    """The JUnit XML files of the run."""
    if not report_dir.is_dir():
        return []
    return sorted(p for p in report_dir.rglob("*.xml") if p.is_file())


def measure(report_dir: pathlib.Path) -> dict:
    """What the run did: classes, tests, failures, skips, and the failures.

    A report that cannot be parsed is counted as unreadable rather than as an
    empty report: "no tests" and "could not read the tests" are different
    facts, and the summary says which one it is.
    """
    classes: set[str] = set()
    tests = 0
    failures: list[str] = []
    skipped: list[str] = []
    unreadable = 0

    for path in report_files(report_dir):
        try:
            tree = ET.parse(path)  # noqa: S314 — the report is written by AGP, not by a user
        except ET.ParseError:
            unreadable += 1
            continue
        for case in tree.getroot().iter("testcase"):
            name = case.get("classname") or case.get("class") or "unknown"
            classes.add(name)
            tests += 1
            label = f"{_FQN.sub('', name)}.{case.get('name')}"
            if case.find("failure") is not None or case.find("error") is not None:
                failures.append(label)
            elif case.find("skipped") is not None:
                skipped.append(label)

    return {
        "classes": len(classes),
        "tests": tests,
        "failures": failures,
        "skipped": skipped,
        "unreadable_files": unreadable,
    }


def verdict(measured: dict, has_report: bool) -> str:
    """The one word the reviewer needs first."""
    if not has_report:
        return "did not run"
    if measured["unreadable_files"] and not measured["tests"]:
        return "could not read"
    if not measured["tests"]:
        return "did not run"
    if measured["failures"]:
        return "failed"
    return "passed"


def render(measured: dict, tier: str, has_report: bool) -> str:
    """The Markdown block that goes into the job summary."""
    state = verdict(measured, has_report)
    lines = [f"### Instrumented tier: {state}", ""]

    if tier:
        lines.append(f"- Tier: `{tier}`")
    if not has_report:
        lines.append(
            "- No JUnit report was produced. The tier proves nothing about the app "
            "on a device — this is not a failure of the app."
        )
        return "\n".join(lines) + "\n"

    lines.append(
        f"- Classes: {measured['classes']}, tests: {measured['tests']}, "
        f"failed: {len(measured['failures'])}, skipped: {len(measured['skipped'])}"
    )
    if measured["unreadable_files"]:
        lines.append(
            f"- {measured['unreadable_files']} report file(s) could not be parsed"
        )
    if measured["failures"]:
        lines.append("")
        lines.append("Failed tests:")
        for name in measured["failures"]:
            lines.append(f"- `{name}`")
    if measured["skipped"]:
        lines.append("")
        lines.append("Skipped tests:")
        for name in measured["skipped"]:
            lines.append(f"- `{name}`")
    if state == "passed":
        lines.append("")
        lines.append(
            "The device run passed. This tier is advisory: it never blocks a merge, "
            "because an emulator is the most flaky surface in CI and a flaky gate is "
            "worse than no gate."
        )
    return "\n".join(lines) + "\n"


@click.command()
@click.option("--report-dir", default=str(REPORT_DIR), help="Where the instrumented report is")
@click.option("--tier", default="", help="The tier that ran (smoke or full)")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON instead of Markdown")
@click.pass_context
def cli(ctx: click.Context, report_dir: str, tier: str, emit_json: bool) -> None:
    path = pathlib.Path(report_dir)
    try:
        measured = measure(path)
        has_report = bool(report_files(path))
    except OSError as exc:
        # A report directory that cannot be listed is a reading failure, and it
        # is reported as one: the summary must not pretend the tier ran.
        measured = {
            "classes": 0,
            "tests": 0,
            "failures": [],
            "skipped": [],
            "unreadable_files": 0,
        }
        has_report = False
        click.echo(f"instrumented run summary: cannot read {path}: {exc}", err=True)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "verdict": verdict(measured, has_report),
                    "tier": tier,
                    "has_report": has_report,
                    **measured,
                },
                indent=2,
            )
        )
    else:
        click.echo(render(measured, tier, has_report))

    raise ctx.exit(0)


def main(argv: list[str] | None = None) -> int:
    # Deliberately not `sgate.commands.base.run`: importing sgate configures its
    # DEBUG logger, and twenty lines of loader chatter in a job summary is a
    # summary nobody reads. The summary is the output here — it has to be the
    # only output.
    return cli.main(standalone_mode=False, args=argv)


if __name__ == "__main__":
    sys.exit(main())
