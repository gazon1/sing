#!/usr/bin/env python3
"""check-issue-registry.py — every OpenSpec change has an entry in the issue registry.

What went wrong
---------------
Four changes landed in `main` without a registry entry:

    close-rule-consolidation              (the close rule consolidation)
    every-close-refuses-in-its-own-write
    a-closed-project-is-reachable
    the-backup-keeps-the-verb

`AGENTS.md` calls `.specify/issues/<slug>.yaml` the **source of truth** for a
change, and the registry is what a later session reads to learn what a change
was and whether it is done. An unregistered change is a change the next session
cannot find: the decision, the spec and the tasks are in the tree, but nothing
points at them and nothing says they are the work of this branch.

Nothing failed. The changes were valid, the specs validated, CI was green —
and the registry simply did not mention them. That is the failure this gate is
for: a rule that is written down and not checked is a hope, not a property.

The rule
--------
Every directory under `openspec/changes/` — including `openspec/changes/archive/`
— has a record in `.specify/issues/`, named after the change with its
`YYYY-MM-DD-` prefix stripped (`2026-10-09-detekt-class-signature-gotcha` →
`detekt-class-signature-gotcha.yaml`). The record must point back at that
change: a record whose `openspec_path` names another change is a record of
something else.

The path is compared by its last segment rather than in full, because moving a
change into `archive/` is a rename the registry does not follow: the archived
`the-search-asks-the-index` still records the path it had before the move, and
requiring that path to resolve would make every archive operation a second
unrelated edit.

The fix is one command, and it is the same one that creates the record:

    python3 scripts/openspec-import-all.py

Usage:
    python3 scripts/check-issue-registry.py            # report
    python3 scripts/check-issue-registry.py --quiet
"""

from __future__ import annotations

import pathlib
import re

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHANGES_DIR = ROOT / 'openspec/changes'
ISSUES_DIR = ROOT / '.specify/issues'

#: A change directory may carry a date prefix; the registry entry drops it.
DATE_PREFIX = re.compile(r'^\d{4}-\d{2}-\d{2}-')


def slug_for(change_dir: pathlib.Path) -> str:
    """The registry entry's name for a change directory."""
    return DATE_PREFIX.sub('', change_dir.name)


def change_directories(changes_dir: pathlib.Path) -> list[pathlib.Path]:
    """Every change directory, live and archived."""
    if not changes_dir.is_dir():
        return []
    found: list[pathlib.Path] = []
    for entry in sorted(changes_dir.iterdir()):
        if not entry.is_dir():
            continue
        if entry.name == 'archive':
            found.extend(child for child in sorted(entry.iterdir()) if child.is_dir())
        else:
            found.append(entry)
    return found


def recorded_path(entry: pathlib.Path) -> str | None:
    """The `openspec_path` a registry entry records, if it has one."""
    try:
        text = entry.read_text(encoding='utf-8')
    except OSError:
        return None
    match = re.search(r'^openspec_path:\s*(\S+)', text, re.M)
    return match.group(1) if match else None


def find_problems(
    changes_dir: pathlib.Path,
    issues_dir: pathlib.Path,
) -> tuple[list[str], list[str]]:
    """The changes without a registry entry, and the entries pointing elsewhere.

    The two lists are separate because they are different failures with
    different fixes: a missing entry is created by the import script, and a
    mispointed one is corrected by it too — but only after somebody says so.
    """
    missing: list[str] = []
    mispointed: list[str] = []

    for change in change_directories(changes_dir):
        slug = slug_for(change)
        entry = issues_dir / f'{slug}.yaml'
        if not entry.is_file():
            missing.append(f'{change.name} → {issues_dir.name}/{slug}.yaml')
            continue
        recorded = recorded_path(entry)
        if recorded is None:
            mispointed.append(
                f'{issues_dir.name}/{slug}.yaml records no openspec_path, so it points at nothing'
            )
        elif not recorded.endswith(change.name):
            mispointed.append(
                f'{issues_dir.name}/{slug}.yaml records {recorded!r}, which is not {change.name!r}'
            )

    return missing, mispointed


@click.command()
@click.option('--quiet', is_flag=True)
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    changes = change_directories(CHANGES_DIR)
    if not changes:
        if not quiet:
            click.echo(f'check-issue-registry: no changes under {CHANGES_DIR.relative_to(ROOT)} — nothing to record')
        raise ctx.exit(CLEAN)

    missing, mispointed = find_problems(CHANGES_DIR, ISSUES_DIR)

    for problem in missing:
        click.echo(f'ERROR: no registry entry: {problem}')
    for problem in mispointed:
        click.echo(f'ERROR: registry entry points elsewhere: {problem}')

    if missing or mispointed:
        click.echo('')
        click.echo(
            'check-issue-registry: the registry is the source of truth for a change, and a '
            'change without an entry is a change the next session cannot find.'
        )
        click.echo('  fix with — python3 scripts/openspec-import-all.py')
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-issue-registry: OK — {len(changes)} changes, each with a registry entry '
            f'pointing back at it'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
