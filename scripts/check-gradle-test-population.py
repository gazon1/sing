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

A selector is `-Ptest.population=<name>` (the named selection) or
`-Ptest.tags=<value>` (the raw tag list). "Absent" is a selector too: it is
Gradle's default, and it is not the same selector.

The two spellings are compared by their EFFECT — the tag list each one
resolves to. `-Ptest.population=full` and `-Ptest.tags=fast,slow` select the
same tests, so they are the same population, and a report that called them a
disagreement would send the author hunting for a difference that is not
there. The name-to-tags mapping is `config/test-populations.properties`, the
one file the build and every gate read.

Deliberately **not** a rule: that every test invocation must carry a selector.
`check.sh` runs every test task with none, on purpose — the local default is
`excludeTags("slow")` and the local loop is a fast cycle. Demanding a selector
there would fail a tree that is correct. Those runs are reported as `note:`
lines, so the fact stays visible without being dressed as a violation.

Also deliberately not a rule: ordering. Which invocation ends up executing a test
task is Gradle's up-to-date check to decide, and a workflow cannot be reordered
into determinism. Parity is the property that survives either outcome.

## The second rule: the file answers the questions about a population

A population is not only a tag list. It is also the answer to questions like
"does this population include the slow half?" — and those answers used to live
in the shell, hardcoded to one name:

    if [ "$TEST_POPULATION" != "full" ]; then

Two places in `.github/workflows/ci.yml` asked it that way. A third population
would have needed an edit in the workflow, and the file that is supposed to
describe the populations would still not describe them.

So the properties live in the populations file, next to the tags:

    fast=fast
    full=fast,slow

    fast.includes-slow: false
    full.includes-slow: true

and the workflow asks the file. The rules this gate enforces about that:

1. **Every population the workflow can select is declared.** A name the file
   does not declare is a job that dies in the "Test population is one of the
   known ones" step, which is a runtime failure for a defect a diff can see.
2. **Every property the workflow asks about is answered for every population it
   can select.** A step that asks "does this population include the slow half?"
   of a population the file does not describe is a finding: the shell would
   have to guess, and guessing means the fast branch.
3. **The answer agrees with the tags.** The build asks the same question of
   the tag list (`settings.gradle.kts`: `selectedTags.any { it == "slow" }`), so
   a population that says `includes-slow: true` and selects no `slow` class —
   or says `false` and selects one — is a finding.
4. **The workflow does not ask by name.** A comparison of the population
   variable against a literal is the defect this rule exists to remove: the
   file describes the population, so the file is asked.
5. **A property is written with the properties-file colon separator.**
   `population.property: value`. The Python reader of the populations splits on
   `=`, so a property written with `=` would be read as a population of its
   own — a second answer to "which populations exist".

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
  1 — a job's invocations disagree, or the populations file does not answer
      what the workflow asks of it
  2 — I/O or parse error
"""

from __future__ import annotations

import json
import pathlib
import re
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

#: The file that declares the populations and their properties — the same file
#: the build reads. `settings.gradle.kts` and `build.gradle.kts` load it with
#: Java's Properties parser, which reads both separators; this gate reads the
#: populations through `workflow_gradle` (one reader for the mapping) and the
#: properties itself, because no reader of them existed.
POPULATIONS_FILE = wg.POPULATIONS_FILE

#: `population.property: value` — a property of a population.
#:
#: The COLON separator is what keeps a property out of the population mapping:
#: the Python reader of the populations splits on `=`, so a property written
#: with `=` would be read as a population named after the property.
PROPERTY = re.compile(
    r"^(?P<name>[A-Za-z0-9][A-Za-z0-9_-]*)\."
    r"(?P<property>[A-Za-z0-9][A-Za-z0-9_-]*)\s*:\s*(?P<value>.*)$"
)

#: `TEST_POPULATION=full` — a population a workflow can select. A literal
#: `-Ptest.population=full` selects one too, and is read the same way.
SELECTED = re.compile(
    r"(?:TEST_POPULATION[=:]|test\.population[=:])\s*\"?([A-Za-z0-9][A-Za-z0-9_.-]*)"
)

#: `${TEST_POPULATION}.includes-slow` — the property a step asks the file about.
ASKED = re.compile(
    r"\$\{?TEST_POPULATION\}?[\"']?(?:\.|\\\.|\[\.\])\s*([A-Za-z0-9][A-Za-z0-9_-]*)"
)

#: `[ "$TEST_POPULATION" != "full" ]` — a question asked by NAME.
COMPARED = re.compile(
    r"\$\{?TEST_POPULATION\}?[\"']?\s*(?:==|!=)\s*[\"']?([A-Za-z0-9][A-Za-z0-9_.-]*)"
)

#: The property that says whether a population selects the slow half, and the
#: tag the build asks the same question about (`settings.gradle.kts`).
SLOW_PROPERTY = "includes-slow"
SLOW_TAG = "slow"


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


def _without_comments(text: str) -> str:
    """The text with its full-line comments removed.

    A commented-out step is not a step: a workflow that mentions a population
    in a comment selects nothing. A trailing comment stays — a comparison on a
    live line is a comparison, whatever follows it on that line.
    """
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )


def read_properties(
    path: pathlib.Path,
) -> tuple[dict[tuple[str, str], str], list[tuple[int, str]]]:
    """The properties the file declares, and the lines that spell them wrong.

    Returns `({(population, property): value}, [(line number, line)])`. The
    second list holds the lines that look like a property but are written with
    `=`: the Python reader of the populations would read them as populations of
    their own, so they are a finding rather than a property.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise RuntimeError(f"the named test populations are unreadable: {path} ({exc})") from exc
    properties: dict[tuple[str, str], str] = {}
    miswritten: list[tuple[int, str]] = []
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = PROPERTY.match(line)
        if match:
            properties[(match["name"], match["property"])] = match["value"].strip()
            continue
        if "=" in line:
            name = line.partition("=")[0].strip()
            if "." in name:
                miswritten.append((lineno, line))
    return properties, miswritten


def _questions() -> tuple[set[str], set[str], list[tuple[str, str]]]:
    """`(selectable populations, asked properties, name comparisons)`.

    Read from every workflow in the repository: "which populations can this
    repository select?" is a property of the workflows, not of one job.
    """
    selectable: set[str] = set()
    asked: set[str] = set()
    compared: list[tuple[str, str]] = []
    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        try:
            jobs = _workflow_bodies(workflow)
        except yaml.YAMLError as exc:
            raise RuntimeError(f"{workflow}: {exc}") from exc
        relative = str(workflow.relative_to(ROOT))
        for job_name, body in sorted(jobs.items()):
            text = _without_comments(body)
            selectable.update(SELECTED.findall(text))
            asked.update(ASKED.findall(text))
            for literal in COMPARED.findall(text):
                compared.append((f"{relative}::{job_name}", literal))
    return selectable, asked, compared


def _property_violations(verbose: bool = False) -> tuple[list[str], list[str], list[str]]:
    """The findings about the populations and the properties of them.

    Returns `(violations, notes, listing)` — the same shape the caller gets
    from the population-parity pass, so both are reported in one verdict.
    """
    violations: list[str] = []
    notes: list[str] = []
    listing: list[str] = []

    selectable, asked, compared = _questions()
    properties, miswritten = read_properties(POPULATIONS_FILE)

    for lineno, line in miswritten:
        violations.append(
            f"{POPULATIONS_FILE.name}:{lineno}: {line!r} is written with '=' — a property is "
            f"written 'population.property: value', or the reader of the populations reads "
            f"it as a population of its own"
        )

    if not (selectable or asked or compared):
        return violations, notes, listing

    populations = wg.load_populations()

    for name in sorted(selectable):
        if name not in populations:
            violations.append(
                f"the workflow can select the population {name!r}, and the populations file "
                f"does not declare it"
            )

    for prop in sorted(asked):
        for name in sorted(selectable):
            value = properties.get((name, prop))
            if value is None:
                violations.append(
                    f"the workflow asks {prop!r} of the population {name!r}, and the "
                    f"populations file does not answer"
                )
                continue
            if value not in ("true", "false"):
                violations.append(
                    f"the population {name!r} answers {prop!r} with {value!r} — a boolean was "
                    f"expected"
                )
                continue
            if prop == SLOW_PROPERTY:
                selects_slow = SLOW_TAG in populations.get(name, ())
                if (value == "true") != selects_slow:
                    tags = ", ".join(populations.get(name, ())) or "none"
                    violations.append(
                        f"the population {name!r} says {prop}={value}, and its tags ({tags}) "
                        f"say otherwise"
                    )

    for location, literal in compared:
        violations.append(
            f"{location}: the workflow asks a question of the population by NAME ({literal!r}) "
            f"— the populations file describes the population, so the file is asked"
        )

    if verbose and selectable and asked:
        listing.append(
            f"  {POPULATIONS_FILE.name}: the workflow asks {', '.join(repr(p) for p in sorted(asked))} "
            f"of {', '.join(repr(p) for p in sorted(selectable))}"
        )

    return violations, notes, listing


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


def _rendered(invocation: wg.Invocation) -> str:
    """How this invocation's selection reads in a report.

    The name first: an invocation that asked for a population reads
    `-Ptest.population=full`, and one that spelled the tags reads
    `-Ptest.tags=fast,slow`. The comparison itself is made on the EFFECTIVE
    selection (the resolved tag list), because that is what decides which
    tests run — two spellings of the same population are not a disagreement.
    """
    if invocation.population:
        return f"-Ptest.population={invocation.population}"
    if invocation.selector:
        return f"-Ptest.tags={invocation.selector}"
    return "Gradle default"


def evaluate(verbose: bool = False) -> tuple[list[str], list[str], list[str]]:
    """Return `(violations, notes, listing)` for every surface in the repository."""
    violations: list[str] = []
    notes: list[str] = []
    listing: list[str] = []

    for surface, invocations in _surfaces():
        if verbose:
            for invocation in invocations:
                kind = "test-capable" if invocation.test_capable else "compile-only"
                named = " ".join(invocation.tasks) if invocation.tasks else "(no task named)"
                listing.append(f"  {invocation.location}: {kind}, {_rendered(invocation)}, tasks: {named}")

        tests = [inv for inv in invocations if inv.test_capable]
        if len(tests) < 2:
            continue

        selectors = {inv.selector for inv in tests}
        if len(selectors) > 1:
            rendered = ", ".join(f"{inv.location} → {_rendered(inv)}" for inv in tests)
            violations.append(
                f"{surface}: {len(tests)} test-capable Gradle invocations disagree about "
                f"the population — {rendered}"
            )
        elif selectors == {None}:
            notes.append(
                f"{surface}: {len(tests)} test invocations, none declaring a selector — "
                "they agree on Gradle's default, which excludes @Tag(\"slow\")"
            )

    property_violations, property_notes, property_listing = _property_violations(verbose=verbose)
    violations.extend(property_violations)
    notes.extend(property_notes)
    listing.extend(property_listing)

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
            "test task against a different population than the gate that counted it, "
            "or the populations file does not answer what the workflow asks of it",
            err=True,
        )
        raise ctx.exit(0 if check else 1)

    click.echo(
        "check_gradle_test_population: OK — every test-capable Gradle invocation in a job "
        "selects the same population, and the populations file answers every question the "
        "workflow asks of it"
    )


if __name__ == "__main__":
    raise SystemExit(run(cli))
