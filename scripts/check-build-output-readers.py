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

import importlib.util
import json
import pathlib
import sys

import click
import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

#: Reused from the population gate rather than copied. A fourth copy of these
#: predicates would be a fourth thing to keep in step, and the two gates disagree
#: about what "a test-capable Gradle invocation" is only if one of them is wrong.
#: `importlib` rather than `import`: the sibling's filename carries a dash.
def _load_population_gate() -> object:
    path = ROOT / "scripts" / "check-gradle-test-population.py"
    spec = importlib.util.spec_from_file_location("_gradle_test_population", path)
    if spec is None or spec.loader is None:  # pragma: no cover - unreadable sibling
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


#: Build-output directories a test or coverage task rewrites. A reader of anything
#: else is out of scope by construction.
READ_PATHS = (
    "build/test-results",
    "build/reports",
    "build/traceability",
)


def _steps(path: pathlib.Path) -> list[tuple[str, int, str, str]]:
    """`(job, position, step_name, run_text)` for every step that runs something.

    Position is the step's ordinal *within the job*, not a line number. Line
    numbers are not usable here: a `run:` block can be one folded YAML scalar,
    a `|` block of continuation lines, or a single line, and a Gradle invocation
    written across backslash continuations is one command whose only line carrying
    `./gradlew` is its first. Feeding one line at a time to the parser is what
    made the main Gradle run invisible to it, and the sabotage control passed a
    sabotaged workflow for exactly that reason (caught on 2026-10-16, run
    38961950540 — the first run to catch it was the one that failed).
    """
    gate = _load_population_gate()
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    steps: list[tuple[str, int, str, str]] = []
    for job_name, job in (data.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        position = 0
        for step in job.get("steps") or []:
            if not isinstance(step, dict) or not isinstance(step.get("run"), str):
                continue
            steps.append((str(job_name), position, str(step.get("name", "run")), step["run"]))
            position += 1
    del gate  # loaded only to keep the dependency explicit for readers of this file
    return steps


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
    gate = _load_population_gate()
    violations: list[str] = []
    listing: list[str] = []

    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        relative = str(workflow.relative_to(ROOT))
        jobs: dict[str, list[tuple[int, str, str]]] = {}
        for job, position, name, text in _steps(workflow):
            jobs.setdefault(job, []).append((position, name, text))

        for job, entries in sorted(jobs.items()):
            writers: list[tuple[int, str]] = []
            for position, name, text in entries:
                for _location, _selector, test_capable, _tasks in gate._invocations(  # noqa: SLF001
                    text, f"{relative}::{job}::{name}"
                ):
                    if test_capable:
                        writers.append((position, name))
            if len(writers) < 2:
                # A job with one test-capable invocation has nothing that can rewrite
                # what its readers read.
                continue

            for position, name, text in entries:
                reads = _reads_build_output(text)
                if not reads:
                    continue
                if verbose:
                    listing.append(f"  {relative}::{job}::{name} reads {reads}")
                for writer_position, writer_name in writers[1:]:
                    if writer_position >= position:
                        break
                    violations.append(
                        f"{relative}::{job}::{name} reads {reads}, but "
                        f"{writer_name} is a test-capable Gradle invocation that runs "
                        f"before it — a later invocation may re-run the task and "
                        f"overwrite the directory this step reads"
                    )
    return violations, listing


@click.command()
@click.option("--verbose", is_flag=True, help="List every build-output reader found.")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
def main(verbose: bool, check: bool, emit_json: bool) -> None:
    """A step that reads build output must read it first."""
    try:
        violations, listing = evaluate(verbose=verbose)
    except (OSError, RuntimeError) as exc:
        if emit_json:
            click.echo(json.dumps({"passed": False, "blocking_errors": [str(exc)]},
                                  ensure_ascii=False))
        else:
            click.echo(f"check_build_output_readers: ERROR — {exc}", err=True)
        sys.exit(2)

    if emit_json:
        click.echo(json.dumps({"passed": not violations, "blocking_errors": violations},
                              ensure_ascii=False))
        sys.exit(0 if (not violations or check) else 1)

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
        sys.exit(0 if check else 1)

    click.echo(
        "check_build_output_readers: OK — every step that reads build output precedes "
        "the Gradle invocations that can rewrite it"
    )


if __name__ == "__main__":
    main()  # pylint: disable=no-value-for-parameter