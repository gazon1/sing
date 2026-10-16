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

*Test-capable* means the invocation names a test task or an aggregate that depends
on one: `test`, `jvmTest`, `connectedDebugAndroidTest`, `check`, `build`, or
anything under Kover (`koverVerify`, `koverReport`, `koverHtml`,
`koverMarkdownTable`, `koverLog`) — Kover reads the binary reports those test
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

It does not run Gradle, so it cannot tell whether a task would actually re-run. It
compares *declared intent* across invocations. That is the part a diff can change
silently and a reviewer cannot see; whether the task really re-ran is the
up-to-date check's job.

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
import re
import sys

import click
import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
LOCAL_SURFACE = ROOT / "check.sh"

#: A Gradle invocation. `./gw` is the repo's pass-through wrapper (check.sh:15).
_GRADLE = re.compile(r"(?:^|[\s;&|(])(?:\./gradlew|\./gw)\b")

#: `-Ptest.tags=fast,slow` and the space-separated `-Ptest.tags all` both bind.
_SELECTOR = re.compile(r"-Ptest\.tags[= ]([^\s;&|)]+)")

#: Fragments are split on shell separators so a pipeline cannot hide an invocation.
_SPLIT = re.compile(r"[|\n;&]+")

#: A whole-line shell comment. Trailing `# ...` is deliberately NOT stripped:
#: `./gradlew foo # runs the tests` is still an invocation.
_FULL_LINE_COMMENT = re.compile(r"^\s*#")

#: Exact task names that pull in a test task, module-qualified or bare.
_EXACT_TEST_TASKS = frozenset(
    {
        "test",
        "check",
        "build",
        "jvmTest",
        "testAndroidHostTest",
        "androidHostTest",
        "connectedDebugAndroidTest",
    }
)

#: Task-name suffixes that mark a test task: `jvmTest`, `testAndroidHostTest`.
#: Anchored on a segment boundary so `latest` and `koverMarkdownTable` are not
#: mistaken for one — `koverMarkdownTable` is matched as a Kover task instead.
_TEST_SUFFIX = re.compile(r"^(?:[A-Za-z0-9_.-]+:)*(?:[A-Za-z0-9_.-]*[Tt]est)$")

#: Anything under Kover. Kover's aggregate tasks read the binary reports the test
#: tasks produce, so an invocation that runs one can run those tests.
_KOVER = re.compile(r"^:?(?:[A-Za-z0-9_.-]+:)*kover[A-Za-z0-9]*$")


def _is_named_task(token: str) -> bool:
    """A command-line word that reads as a task name.

    Excludes the launcher itself, flags, property assignments, and the two things a
    run block splices in rather than names: a GitHub expression and a shell redirect.
    """
    if token.startswith("-") or "=" in token or "${{" in token or ">" in token:
        return False
    if re.match(r"^\d", token):
        return False
    return not _GRADLE.search(token)


def _is_test_task(token: str) -> bool:
    """True when this command-line word names a task that runs tests.

    Flags and property assignments are not tasks, which is why ``-Ptest.tags=…``
    cannot make an invocation test-capable on its own.
    """
    if token.startswith("-") or "=" in token:
        return False
    bare = token.rsplit(":", 1)[-1]
    if bare in _EXACT_TEST_TASKS:
        return True
    if _KOVER.match(token):
        return True
    return bool(_TEST_SUFFIX.match(bare)) and "test" in bare.lower()


def _logical_lines(script: str) -> list[tuple[int, str]]:
    """`(first physical line, command text)` per logical command.

    A backslash-continued block is one command, and reading it as several is what made
    the producer invisible. `ci.yml`'s test job opens with

        ./gradlew --continue \
          --no-build-cache \
          :shared:jvmTest :desktopApp:test …

    and per-line parsing saw `./gradlew --continue` followed by a lone continuation
    marker — a launcher with no tasks — then
    discarded every continuation line, because none of them repeats `./gradlew`. The
    invocation that *produces* the test results was therefore reported as `test_capable=False`
    and did not exist as far as the caller was concerned. `check-build-output-readers.py`
    then treated the *next* real invocation as the producer and exempted it, which is
    precisely the violation its docstring says this parser exists to catch: `Flake analysis
    vs previous run` reads `shared/build/test-results/jvmTest` and sits after
    `:koverVerify`.

    A trailing backslash is what the shell itself treats as continuation, so matching it
    is not a heuristic about this repository's formatting — it is the grammar.
    """
    lines = script.splitlines()
    joined: list[tuple[int, str]] = []
    buffer: list[str] = []
    start = 0
    for line_no, line in enumerate(lines, start=1):
        if not buffer:
            start = line_no
        if line.rstrip().endswith("\\"):
            buffer.append(line.rstrip()[:-1].strip())
            continue
        buffer.append(line)
        joined.append((start, " ".join(part for part in buffer if part)))
        buffer = []
    if buffer:
        joined.append((start, " ".join(part for part in buffer if part)))
    return joined


def _invocations(script: str, origin: str) -> list[Invocation]:
    """Return `(location, selector, test_capable, tasks)` per invocation, in order."""
    found: list[Invocation] = []
    for line_no, line in _logical_lines(script):
        if _FULL_LINE_COMMENT.match(line):
            continue
        for fragment in _SPLIT.split(line):
            fragment = fragment.strip()
            if not _GRADLE.search(fragment):
                continue
            match = _SELECTOR.search(fragment)
            selector = match.group(1) if match else None
            tasks = tuple(word for word in fragment.split() if _is_named_task(word))
            found.append((f"{origin}:{line_no}", selector, bool(tasks) and any(
                _is_test_task(task) for task in tasks
            ), tasks))
    return found


def _workflow_jobs(path: pathlib.Path) -> dict[str, str]:
    """`{job_name: script_text}` for every job step that runs something inline."""
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    bodies: dict[str, list[str]] = {}
    for job_name, job in (data.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in job.get("steps") or []:
            if isinstance(step, dict) and isinstance(step.get("run"), str):
                bodies.setdefault(str(job_name), []).append(step["run"])
    return {name: "\n".join(texts) for name, texts in bodies.items()}


#: One Gradle invocation: `(location, selector, test_capable, tasks)`.
#: `selector` is `None` for an absent `-Ptest.tags`, which is a population of its own.
Invocation = tuple[str, str | None, bool, tuple[str, ...]]


def _surfaces() -> list[tuple[str, list[Invocation]]]:
    """Every job (and `check.sh`) with at least one Gradle invocation."""
    found: list[tuple[str, list[Invocation]]] = []
    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        try:
            jobs = _workflow_jobs(workflow)
        except yaml.YAMLError as exc:
            raise RuntimeError(f"{workflow}: {exc}") from exc
        relative = str(workflow.relative_to(ROOT))
        for job_name, body in sorted(jobs.items()):
            invocations = _invocations(body, f"{relative}::{job_name}")
            if invocations:
                found.append((f"{relative}::{job_name}", invocations))
    if LOCAL_SURFACE.exists():
        invocations = _invocations(LOCAL_SURFACE.read_text(encoding="utf-8"), LOCAL_SURFACE.name)
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
            for location, selector, test_capable, tasks in invocations:
                kind = "test-capable" if test_capable else "compile-only"
                chosen = f"-Ptest.tags={selector}" if selector else "Gradle default"
                named = " ".join(tasks) if tasks else "(no task named)"
                listing.append(f"  {location}: {kind}, {chosen}, tasks: {named}")

        tests = [inv for inv in invocations if inv[2]]
        if len(tests) < 2:
            continue

        selectors = {selector for _, selector, _, _ in tests}
        if len(selectors) > 1:
            rendered = ", ".join(
                (f"{loc} → -Ptest.tags={sel}" if sel else f"{loc} → Gradle default")
                for loc, sel, _, _ in tests
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
def main(verbose: bool, check: bool, emit_json: bool) -> None:
    """One test population per job, or the gate is noise."""
    try:
        violations, notes, listing = evaluate(verbose=verbose)
    except (OSError, RuntimeError) as exc:
        if emit_json:
            click.echo(json.dumps({"passed": False, "blocking_errors": [str(exc)],
                                   "warnings": []}, ensure_ascii=False))
        else:
            click.echo(f"check_gradle_test_population: ERROR — {exc}", err=True)
        sys.exit(2)

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not violations,
                "blocking_errors": violations,
                "warnings": notes,
            },
            ensure_ascii=False,
        ))
        sys.exit(0 if (not violations or check) else 1)

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
        sys.exit(0 if check else 1)

    click.echo(
        "check_gradle_test_population: OK — every test-capable Gradle invocation in a job "
        "selects the same population"
    )


if __name__ == "__main__":
    main()  # pylint: disable=no-value-for-parameter
