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

#: The fewest slow classes the check must look at. Below this, the gate is
#: looking at a tree it does not understand — the two modules that were
#: missing from the list above held two slow classes each, and the gate
#: reported 96 of the repository's 98 without noticing.
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


def slow_classes(source_root: pathlib.Path) -> set[str]:
    """The names of the `@Tag("slow")` classes a source root declares."""
    if not source_root.is_dir():
        return set()
    scanner = _scanner()
    names: set[str] = set()
    for source_file in sorted(source_root.rglob('*Test.kt')):
        source = source_file.read_text(encoding='utf-8', errors='replace')
        for name in scanner.test_classes_in(source):
            if scanner.read_tag(source, name) == 'slow':
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
        key = name.rsplit('.', 1)[-1]
        suites[key] = suites.get(key, 0) + int(root.get('skipped', 0) or 0)
    return suites


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
