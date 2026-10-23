#!/usr/bin/env python3
"""check-gradle-test-population.py — one test population per job, or the gate is noise.

## Why this exists

Measured 2026-10-16, run <https://github.com/gazon1/sing/actions/runs/38933517902>.
The traceability step exited 2 with "declared target produced zero testcases" for
`desktop`, against a run that had measured it: `check-test-runs`, three steps
earlier, had counted `desktopApp:test: 44 classes, 135 tests, 0 skipped`.

Both were right. Between them sat four Gradle invocations, and the first one that
found its test task out of date — the *advisory* `:koverMarkdownTable`, the one
carrying `|| true` — re-ran `:desktopApp:test`. It re-ran it **without**
`-Ptest.tags`, and every module's `useJUnitPlatform` block maps an absent
`-Ptest.tags` to `excludeTags("slow")`:

    shared/build.gradle.kts, desktopApp/build.gradle.kts,
    mcp-server/build.gradle.kts, adr-cli/build.gradle.kts

So the `@Tag("slow")` scenario classes were *skipped, not executed*. The suite
stayed green, the count stayed at 135 because it had already been taken, and the
directory the normaliser was handed contained no scenario testcase at all. The run
had measured them; the step said it had not, and it was right to say so — the
normaliser's exit 2 is not the bug. The bug is that two Gradle invocations in one
job were allowed to disagree about which tests "the tests" are.

An absent selector is therefore not a neutral default. It is a *different*
population, and it is a narrower one, and it is the one a developer gets locally
because `flake.yml` already had to spell the reason out in a comment.

## The rule

Within one job (or `check.sh`, which is the local equivalent), every Gradle
invocation that can run tests must select the **same** population.

*Test-capable* means the invocation names a test task or an aggregate that
depends on one: `test`, `jvmTest`, `connectedDebugAndroidTest`, `check`,
`build`, or anything under Kover — Kover reads the binary reports those test
tasks produce, so a Kover task that runs them is a test run.

A selector is `-Ptest.tags=<value>`. "Absent" is a selector too: it is Gradle's
default, and it is not the same selector.

Deliberately **not** a rule: that every test invocation must carry a selector.
`check.sh` runs every test task with none, on purpose — the local default is
`excludeTags("slow")` and the local loop is a fast cycle. Demanding a selector
there would fail a tree that is correct. Those runs are reported as `note:`
lines, so the fact stays visible without being dressed as a violation.

Also deliberately not a rule: ordering. Which invocation ends up executing a test
task is Gradle's up-to-date check to decide, and a workflow cannot be reordered
into determinism. Parity is the property that survives either outcome.

## What it does not do

It does not run Gradle, so it cannot tell whether a task *would* re-run. It
compares *declared intent* across invocations. That is the part a diff can change
silently and a reviewer cannot see; whether the task really re-ran is the
up-to-date check's job.

What "an invocation" means is defined once, in `scripts/src/workflow_gradle.py`.
This file used to define it privately, and the sibling readers gate defined it
differently; the disagreement was silent until the readers gate's own sabotage
control passed a sabotaged workflow. See that module for the incident.

Usage:
  python3 scripts/check-gradle-test-population.py [--verbose]

Exit codes:
  0 — every job's test invocations agree (absent selectors reported as notes)
  1 — a job's invocations disagree
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

import yaml  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
LOCAL_SURFACE = ROOT / "check.sh"


def _workflow_bodies(path: pathlib.Path) -> dict[str, str]:
    """`{job_name: every inline run text in that job, in order}`."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    bodies: dict[str, list[str]] = {}
    for job_name, job in (data.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in job.get("steps") or []:
            if isinstance(step, dict) and isinstance(step.get("run"), str):
                bodies.setdefault(str(job_name), []).append(step["run"])
    return {name: "\n".join(texts) for name, texts in bodies.items()}


def _surfaces() -> list[tuple[str, list[wg.Invocation]]]:
    """Every job (and `check.sh`) with at least one Gradle invocation."""
    found: list[tuple[str, list[wg.Invocation]]] = []
    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        try:
            jobs = _workflow_bodies(workflow)
        except yaml.YAMLError as exc:
            raise RuntimeError(f"{workflow}: {exc}") from exc
        relative = str(workflow.relative_to(ROOT))
        for job_name, body in sorted(jobs.items()):
            invocations = list(wg.iter_invocations(body, f"{relative}::{job_name}"))
            if invocations:
                found.append((f"{relative}::{job_name}", invocations))
    if LOCAL_SURFACE.exists():
        invocations = list(wg.iter_invocations(
            LOCAL_SURFACE.read_text(encoding="utf-8"), LOCAL_SURFACE.name
        ))
        if invocations:
            found.append((LOCAL_SURFACE.name, invocations))
    return found


def evaluate(verbose: bool = False) -> tuple[list[str], list[str], list[str]]:
    """Return `(violations, notes, listing)` for every surface in the repository."""
    violations: list[str] = []
    notes: list[str] = []
    listing: list[str] = []

    for surface, invocations in _surfaces():
        if verbose:
            for invocation in invocations:
                kind = "test-capable" if invocation.test_capable else "compile-only"
                chosen = (
                    f"-Ptest.tags={invocation.selector}"
                    if invocation.selector
                    else "Gradle default"
                )
                named = " ".join(invocation.tasks) if invocation.tasks else "(no task named)"
                listing.append(f"  {invocation.location}: {kind}, {chosen}, tasks: {named}")

        tests = [inv for inv in invocations if inv.test_capable]
        if len(tests) < 2:
            continue

        selectors = {inv.selector for inv in tests}
        if len(selectors) > 1:
            rendered = ", ".join(
                (f"{inv.location} → -Ptest.tags={inv.selector}" if inv.selector
                 else f"{inv.location} → Gradle default")
                for inv in tests
            )
            violations.append(
                f"{surface}: {len(tests)} test-capable Gradle invocations disagree about "
                f"the population — {rendered}"
            )
        elif selectors == {None}:
            notes.append(
                f"{surface}: {len(tests)} test invocations, none declaring a selector — "
                "they agree on Gradle's default, which excludes @Tag(\"slow\")"
            )

    return violations, notes, listing


@click.command()
@click.option("--verbose", is_flag=True, help="List every Gradle invocation it found.")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, verbose: bool, check: bool, emit_json: bool) -> None:
    """One test population per job, or the gate is noise."""
    try:
        violations, notes, listing = evaluate(verbose=verbose)
    except (OSError, RuntimeError) as exc:
        if emit_json:
            click.echo(json.dumps({"passed": False, "blocking_errors": [str(exc)],
                                   "warnings": []}, ensure_ascii=False))
        else:
            click.echo(f"check_gradle_test_population: ERROR — {exc}", err=True)
        raise ctx.exit(2)

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not violations,
                "blocking_errors": violations,
                "warnings": notes,
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(0 if (not violations or check) else 1)

    for line in listing:
        click.echo(line)
    for note in notes:
        click.echo(f"check_gradle_test_population: note: {note}")

    if violations:
        for violation in violations:
            click.echo(f"check_gradle_test_population: {violation}", err=True)
        click.echo(
            "check_gradle_test_population: FAIL — a later Gradle invocation can re-run a "
            "test task against a different population than the gate that counted it",
            err=True,
        )
        raise ctx.exit(0 if check else 1)

    click.echo(
        "check_gradle_test_population: OK — every test-capable Gradle invocation in a job "
        "selects the same population"
    )


if __name__ == "__main__":
    raise SystemExit(run(cli))