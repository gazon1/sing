#!/usr/bin/env python3
"""check-slow-tests-ran.py — every `@Tag("slow")` class ran in the full suite.

What went wrong
---------------
`McpServerEndToEndTest.create_task_then_read_task_returns_same_title()`
failed in `:mcp-server:test` on `main` — and had been failing since the
read surface changed the shape of its answer (4e6e4d62). The class is
tagged `@Tag("slow")`, and the default test run excludes `slow`, so the
breakage sat in the tree through several merges and was discovered by CI.

That is the shape of the failure: a test that the local loop never runs
is a test whose breakage nobody local ever sees. The suite is green
locally, the suite is red in CI, and the two disagree about what the
suite is.

The rule
--------
A class tagged `@Tag("slow")` is a class the full run (`-Ptest.tags=fast,slow`)
owes the reader. It must appear in the JUnit report of the run, and none
of its tests may be skipped.

This is the second half of the run-floor gate. `check-test-runs.py`
requires the `fast` classes to have run, because `fast` is what a local
run executes; requiring `slow` there would fail every local run, since
the absent-`-Ptest.tags` default is `excludeTags("slow")`. So the two
tags are checked in the two places that can see them: `fast` in the run
floor, `slow` here, against the report the full run produced.

No report is not a pass
-----------------------
If a results directory is missing, the gate fails. "I could not find the
report, so there is nothing to complain about" is the vacuous green this
repository keeps paying for; a missing report is reported as a missing
report.

Usage:
    python3 scripts/check-slow-tests-ran.py            # report
    python3 scripts/check-slow-tests-ran.py --quiet
"""

from __future__ import annotations

import os
import pathlib
import sys
import xml.etree.ElementTree as ET

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: Where a source root's tests report, and where their source lives.
#: The report of a `slow` class that never ran is a missing file, and a
#: missing file is what this gate is about.
SOURCES = (
    ('shared/src/commonTest/kotlin', 'shared/build/test-results/jvmTest'),
    ('shared/src/jvmTest/kotlin', 'shared/build/test-results/jvmTest'),
    ('shared/src/androidHostTest/kotlin', 'shared/build/test-results/testAndroidHostTest'),
    ('desktopApp/src/jvmTest/kotlin', 'desktopApp/build/test-results/test'),
    ('mcp-server/src/test/kotlin', 'mcp-server/build/test-results/test'),
    ('detekt-rules/src/test/kotlin', 'detekt-rules/build/test-results/test'),
    ('adr-cli/src/test/kotlin', 'adr-cli/build/test-results/test'),
    ('adr-core/src/jvmTest/kotlin', 'adr-core/build/test-results/jvmTest'),
)

#: The fewest slow classes the check must look at. It is a backstop, not the
#: completeness guard: a module dropping out of the mapping is caught by
#: `unmapped_slow_classes`, which compares the mapping with the repository.
#: This floor catches the case where the gate is looking at a tree it does
#: not understand at all.
MIN_SLOW_CLASSES = 10


def _scanner():
    """The repository's test scanner — one implementation of "is this a test?".

    The scanner lives next to this script (`scripts/scanning.py`), which is
    `sys.path[0]` when the script is run directly and is not when a test
    loads it by path. The directory is put on the path here, the same way
    `check-test-runs.py` does it, so the scanner is one implementation in
    every caller.
    """
    scripts_dir = str(pathlib.Path(__file__).resolve().parent)
    added = scripts_dir not in sys.path
    if added:
        sys.path.insert(0, scripts_dir)
    try:
        import scanning  # noqa: PLC0415 — imported late so the error names the cause
    finally:
        if added:
            sys.path.remove(scripts_dir)
    return scanning


#: The tag that makes a class slow. A slow class carries the primary tag
#: `slow` — that is the one `-Ptest.tags=fast,slow` selects it by — and, since
#: 2026-10-22, a second tag from a dictionary: `slow:konsist`, `slow:migration`,
#: `slow:data`, `slow:compose`, `slow:platform`. The sub-tag says what is slow;
#: the primary tag says that the class is.
SLOW_TAG = "slow"


def is_slow(tags: list[str]) -> bool:
    """Whether these class-level tags make the class slow.

    The check is on the *presence* of the primary tag, not on the nearest
    annotation: a class whose last annotation is `@Tag("slow:konsist")` is
    still a class that `-Ptest.tags=fast,slow` runs, and reading only the
    nearest tag made it invisible to this rule. That is not a theoretical gap
    — it is what the gate reported on the day the sub-tags landed: 4 slow
    classes out of 100, and a CI step that failed on every run.

    The primary tag alone decides, and that is the repository's rule: CI selects
    on `fast`/`slow`, so a sub-tag never replaces the primary one — a class
    carrying only `slow:konsist` is in no CI run at all. `TestTagCoverageTest`
    reports that for the source sets it scans; requiring such a class in the
    report of a run that selects on the primary tag would be this rule
    inventing a failure that another rule already owns.
    """
    return SLOW_TAG in tags


def slow_classes(source_root: pathlib.Path) -> set[str]:
    """The names of the slow classes a source root declares."""
    if not source_root.is_dir():
        return set()
    scanner = _scanner()
    names: set[str] = set()
    for source_file in sorted(source_root.rglob('*Test.kt')):
        source = source_file.read_text(encoding='utf-8', errors='replace')
        for name in scanner.test_classes_in(source):
            if is_slow(scanner.read_tags(source, name)):
                names.add(name)
    return names


def reported_suites(results_dir: pathlib.Path) -> dict[str, int]:
    """Class name -> number of skipped tests, from the JUnit report."""
    suites: dict[str, int] = {}
    if not results_dir.is_dir():
        return suites
    for xml in sorted(results_dir.glob('**/*.xml')):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        if root.tag != 'testsuite':
            continue
        name = root.get('name')
        if not name:
            continue
        # A Kotlin Multiplatform suite is named `<ClassName>[<target>]` —
        # `ArchitectureTest[jvm]` — while the scanner yields the bare class
        # name. Without stripping the target suffix, every slow class in a
        # KMP module reads as "did not run" although it ran, and the gate
        # fails on every run of the very tree it is meant to protect.
        key = name.rsplit('.', 1)[-1].split('[')[0]
        suites[key] = suites.get(key, 0) + int(root.get('skipped', 0) or 0)
    return suites


def test_roots(tree: pathlib.Path) -> list[pathlib.Path]:
    """Every test source root the tree has, nested projects included.

    A nested Gradle project is a module like any other: `tools/*` are
    included builds, and a slow class in one of them runs nowhere in CI —
    the same hole the mapping once had in two top-level modules.
    """
    roots: list[pathlib.Path] = []
    for dirpath, dirnames, filenames in os.walk(tree):
        dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle', '.git')]
        if 'build.gradle.kts' not in filenames:
            continue
        sources = pathlib.Path(dirpath) / 'src'
        if not sources.is_dir():
            continue
        for candidate in sorted(sources.iterdir()):
            if not candidate.is_dir():
                continue
            if not (candidate.name.endswith('Test') or candidate.name == 'test'):
                continue
            kotlin = candidate / 'kotlin'
            if kotlin.is_dir():
                roots.append(kotlin)
    return roots


def unmapped_slow_classes(root: pathlib.Path, mapped: set[str]) -> list[str]:
    """The slow classes that live in a root the mapping does not cover.

    The rule says *every* slow class. A root that is not in the mapping is a
    root whose slow classes nobody checks, so the gate reports them by name
    rather than counting them somewhere and hoping.
    """
    scanner = _scanner()
    unmapped: list[str] = []
    for source_root in test_roots(root):
        try:
            relative = str(source_root.relative_to(root))
        except ValueError:
            continue
        if relative in mapped:
            continue
        for source_file in sorted(source_root.rglob('*Test.kt')):
            source = source_file.read_text(encoding='utf-8', errors='replace')
            for name in scanner.test_classes_in(source):
                if is_slow(scanner.read_tags(source, name)):
                    unmapped.append(f'{relative}:{name}')
    return sorted(unmapped)


def find_problems(root: pathlib.Path) -> tuple[list[str], list[str]]:
    """The slow classes that did not run, and the ones that were skipped."""
    problems: list[str] = []
    checked: list[str] = []

    for source_rel, results_rel in SOURCES:
        source_root = root / source_rel
        results_dir = root / results_rel
        expected = slow_classes(source_root)
        if not expected:
            continue

        if not results_dir.is_dir():
            problems.append(
                f'no report at {results_rel} — the slow classes of {source_rel} '
                f'({", ".join(sorted(expected))}) cannot be seen'
            )
            continue

        suites = reported_suites(results_dir)
        for name in sorted(expected):
            checked.append(name)
            if name not in suites:
                problems.append(
                    f'{name} is tagged slow and did not run: no suite in {results_rel}'
                )
            elif suites[name]:
                problems.append(
                    f'{name} is tagged slow and {suites[name]} of its tests were skipped'
                )

    return problems, checked


@click.command()
@click.option('--quiet', is_flag=True)
@click.option(
    '--root',
    default=None,
    help='the tree to read (default: the repository this script lives in)',
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None) -> None:
    tree = pathlib.Path(root).resolve() if root else ROOT
    problems, checked = find_problems(tree)

    for problem in problems:
        click.echo(f'ERROR: {problem}')

    unmapped = unmapped_slow_classes(tree, {source for source, _ in SOURCES})
    for entry in unmapped:
        click.echo(
            f'ERROR: {entry} is tagged slow and lives in a root the gate does not map — '
            f'it would run nowhere and be checked by nothing'
        )
    if unmapped:
        click.echo('')
        click.echo(
            'check-slow-tests-ran: a slow class in a root the gate does not map is a class '
            'nobody checks. Map the root (and make its test task run) or the rule is not the '
            'rule it says it is.'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if len(checked) < MIN_SLOW_CLASSES:
        click.echo(
            f'ERROR: the check found {len(checked)} slow class(es); fewer than '
            f'{MIN_SLOW_CLASSES} means it is not looking at the tree it thinks it is'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if problems:
        click.echo('')
        click.echo(
            'check-slow-tests-ran: a slow test that does not run is a test whose breakage '
            'nobody local ever sees — the suite is green locally and red in CI, and the two '
            'disagree about what the suite is.'
        )
        click.echo('  fix with — run the full suite (-Ptest.tags=fast,slow) and make it pass')
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-slow-tests-ran: OK — {len(checked)} slow classes ran, none skipped'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
