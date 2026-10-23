#!/usr/bin/env python3
"""check-build-output-readers.py — a step that reads build output must read it first.

## Why this exists

Two defects, same shape, found on 2026-10-16 while validating PR #684.

Both come from Gradle invocations that sit *after* a test run in the same job. Any
of them may find its test task out of date, re-run it, and overwrite the JUnit XML
that a later step reads. The counting gate and the traceability step had already
taken their numbers; the reader then looked at a directory produced by a *second*
measurement and reported on that.

The first instance failed loudly and was fixed: the advisory `:koverMarkdownTable`
step re-ran `:desktopApp:test` without `-Ptest.tags`, so the `@Tag("slow")` scenario
classes were skipped rather than executed, and traceability exited 2 with "declared
target produced zero testcases" for a target that had just been measured
(run 38933517902).

The second was silent and was found by reading the workflow rather than by a
failure: `Flake analysis vs previous run` reads `shared/build/test-results/jvmTest`
and ran after `Coverage floors`, `Coverage table` and `Kover verify (detekt-rules)`
— any of which pulls the shared test task. A flake rate computed from a
re-measured set compares this run against a previous run's set and calls the
difference flaky, which is the one thing a flake measurement must not do. It is
`continue-on-error`, so even a visible failure would have been a summary line.

## The rule

Within one job, a step whose command reads a build-output path SHALL precede every
Gradle invocation that can run tests.

This is about *position*, not about correctness of any one step, and it is not the
same rule as `check-gradle-test-population.py`:

* that one compares invocations against each other — do they select the same tests?
* this one compares a **reader** against the invocations that can rewrite what it
  reads — has it read yet?

Both were needed. Selector parity alone would not have caught the flake step, which
was already fully compliant with it.

## Why a heuristic rather than a declaration

The rule needs to know which steps read build output. Two ways: every such gate
declares its inputs, or the gate recognises the read in the command text.

Declarations are more precise, and they drift: a declaration nobody is forced to
update is a claim, and this repository's experience with declared-but-unread roots
(`2026-10-05-test-task-external-inputs`) is that the missing declaration is the
defect that bites. Text recognition is cruder and cannot be forgotten, because it
reads the command that is actually going to run. The cost is stated rather than
hidden: a step that reads results through a variable the gate cannot resolve is
invisible to it, and that is the limitation to revisit if it ever matters.

The read is matched on the build-output paths themselves
(`build/test-results`, `build/reports`, `build/traceability`), which is the set of
directories a test or coverage task rewrites. A reader of anything else is out of
scope by construction.

## What it does not do

It does not run anything and it does not model Gradle's up-to-date check. It cannot
say a task *would* re-run; it says that if one does, the reader has already taken
its numbers. That is the whole claim, and it is the one that can be made from the
text of a workflow.

Usage:
  python3 scripts/check-build-output-readers.py [--verbose]

Exit codes:
  0 — every reader precedes every test-capable Gradle invocation
  1 — a reader is ordered after an invocation that can rewrite what it reads
  2 — I/O or parse error
"""

from __future__ import annotations

import json
import pathlib
import sys

import click

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this gate must resolve `sgate` against *this* checkout, not
# against whatever `sgate` happens to be installed — an editable install of a
# sibling worktree is a different tree under the same package name, and an
# import that happens first is already bound to it. When this file is loaded by
# a test harness via importlib, sys.path[0] is `scripts/tests` and the package
# is invisible; this file's own location is authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

from sgate.commands.base import run  # noqa: E402
import workflow_gradle as wg  # noqa: E402

if _added:
    sys.path.remove(_scripts_src)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

#: Build-output directories a test or coverage task rewrites. A reader of anything
#: else is out of scope by construction.
READ_PATHS = (
    "build/test-results",
    "build/reports",
    "build/traceability",
)


def _reads_build_output(text: str) -> str | None:
    """The build-output path this command reads, or None."""
    for build_path in READ_PATHS:
        if build_path in text:
            return build_path
    return None


def evaluate(verbose: bool = False) -> tuple[list[str], list[str]]:
    """Return `(violations, listing)`.

    Per job: the steps that run a test-capable Gradle invocation, in order, and the
    steps that read build output. The *first* test-capable invocation is the
    producer — it is what creates the results a reader later consumes, so it is
    never a violation ahead of one. Every later one is a re-run risk, because
    nothing stops it from being out of date.
    """
    violations: list[str] = []
    listing: list[str] = []

    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        relative = str(workflow.relative_to(ROOT))
        jobs: dict[str, list[wg.Step]] = {}
        for step in wg.iter_steps(workflow, ROOT):
            jobs.setdefault(step.job, []).append(step)

        for job, steps in sorted(jobs.items()):
            writers: list[tuple[int, str]] = []
            for step in steps:
                if any(inv.test_capable
                       for inv in wg.iter_invocations(step.text, step.name)):
                    writers.append((step.position, step.name))
            if len(writers) < 2:
                # A job with one test-capable invocation has nothing that can rewrite
                # what its readers read.
                continue

            for step in steps:
                reads = _reads_build_output(step.text)
                if not reads:
                    continue
                if verbose:
                    listing.append(f"  {relative}::{job}::{step.name} reads {reads}")
                for writer_position, writer_name in writers[1:]:
                    if writer_position >= step.position:
                        break
                    violations.append(
                        f"{relative}::{job}::{step.name} reads {reads}, but "
                        f"{writer_name} is a test-capable Gradle invocation that runs "
                        f"before it — a later invocation may re-run the task and "
                        f"overwrite the directory this step reads"
                    )
    return violations, listing


@click.command()
@click.option("--verbose", is_flag=True, help="List every build-output reader found.")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, verbose: bool, check: bool, emit_json: bool) -> None:
    """A step that reads build output must read it first."""
    try:
        violations, listing = evaluate(verbose=verbose)
    except (OSError, RuntimeError) as exc:
        if emit_json:
            click.echo(json.dumps({"passed": False, "blocking_errors": [str(exc)]},
                                  ensure_ascii=False))
        else:
            click.echo(f"check_build_output_readers: ERROR — {exc}", err=True)
        raise ctx.exit(2)

    if emit_json:
        click.echo(json.dumps({"passed": not violations, "blocking_errors": violations},
                              ensure_ascii=False))
        raise ctx.exit(0 if (not violations or check) else 1)

    for line in listing:
        click.echo(line)

    if violations:
        for violation in violations:
            click.echo(f"check_build_output_readers: {violation}", err=True)
        click.echo(
            "check_build_output_readers: FAIL — a reader of build output is ordered "
            "after an invocation that can rewrite it",
            err=True,
        )
        raise ctx.exit(0 if check else 1)

    click.echo(
        "check_build_output_readers: OK — every step that reads build output precedes "
        "the Gradle invocations that can rewrite it"
    )


if __name__ == "__main__":
    raise SystemExit(run(cli))