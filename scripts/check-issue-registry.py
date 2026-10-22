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
something else, and it looks like coverage.

What "points back at that change" means
---------------------------------------
By **slug**, not by path.

Archiving a change renames it: this repository files a change as
`openspec/changes/android-test-hardening` and archives it as
`openspec/changes/archive/2026-10-20-android-test-hardening` — the move *adds*
a date prefix to the directory. The registry entry does not follow: it is
named after the slug, which the date prefix does not change, and its recorded
path still names the place the change lived before the move. So a record is
accepted when the path it names

  - sits under `openspec/changes/` or `openspec/changes/archive/`, and
  - ends in a directory whose slug — the name with a `YYYY-MM-DD-` prefix
    stripped — is the slug of the change being checked.

Comparing the raw path instead would fail on the next ordinary archive
operation, and would accept a record that points at a directory that does not
exist. Both are wrong; the slug comparison is the one that says what it means.

One direction, stated plainly
------------------------------
The gate walks the *changes* and looks up their records. It does not walk the
records: most of the registry is history — records whose change was archived
long ago, under a path the repository no longer has. A reverse check would
report hundreds of them, and a list of hundreds of known-old records is a
baseline, not a rule. What is checked is the direction that can still go wrong
for work that is happening now: a change with no record, a record that names
a different change, and two changes claiming one slug.

The fix
-------
A missing record is created by:

    python3 scripts/openspec-import-all.py

A record that names another change is rewritten by the same script with
`--replace` — without it the importer skips a record that already exists,
which is exactly the case that needs rewriting.

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

#: The parts of a recorded path that make it a path to a change.
CHANGES_ROOT = ('openspec', 'changes')
ARCHIVE_DIR = 'archive'


def slug_for(name: str) -> str:
    """The slug of a change directory's name — the registry entry's name for it."""
    return DATE_PREFIX.sub('', name)


def change_directories(changes_dir: pathlib.Path) -> list[pathlib.Path]:
    """Every change directory, live and archived."""
    if not changes_dir.is_dir():
        return []
    found: list[pathlib.Path] = []
    for entry in sorted(changes_dir.iterdir()):
        if not entry.is_dir():
            continue
        if entry.name == ARCHIVE_DIR:
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


def slug_of_recorded_path(recorded: str) -> str | None:
    """The slug a recorded path names, or None when it names no change at all.

    A path names a change when it sits under `openspec/changes/` — or under
    `openspec/changes/archive/` — and ends in a directory. Anything else
    (`openspec/changes/nowhere/<slug>`, a path outside the changes tree) names
    nothing that the registry is a record of.
    """
    parts = pathlib.PurePosixPath(recorded).parts
    if len(parts) < 3 or parts[:2] != CHANGES_ROOT:
        return None
    if len(parts) == 3:
        return slug_for(parts[2])
    if len(parts) == 4 and parts[2] == ARCHIVE_DIR:
        return slug_for(parts[3])
    return None


def find_problems(
    changes_dir: pathlib.Path,
    issues_dir: pathlib.Path,
) -> tuple[list[str], list[str]]:
    """The changes without a registry entry, and the entries pointing elsewhere.

    The two lists are separate because they are different failures with
    different fixes: a missing entry is created by the import script, and a
    mispointed one is rewritten by it with `--replace`.
    """
    missing: list[str] = []
    mispointed: list[str] = []
    seen_slugs: dict[str, list[str]] = {}

    for change in change_directories(changes_dir):
        slug = slug_for(change.name)
        seen_slugs.setdefault(slug, []).append(change.name)

        entry = issues_dir / f'{slug}.yaml'
        if not entry.is_file():
            missing.append(f'{change.name} → {issues_dir.name}/{slug}.yaml')
            continue
        recorded = recorded_path(entry)
        if recorded is None:
            mispointed.append(
                f'{issues_dir.name}/{slug}.yaml records no openspec_path, so it points at nothing'
            )
            continue
        recorded_slug = slug_of_recorded_path(recorded)
        if recorded_slug is None:
            mispointed.append(
                f'{issues_dir.name}/{slug}.yaml records {recorded!r}, which is not a path to a change'
            )
        elif recorded_slug != slug:
            mispointed.append(
                f'{issues_dir.name}/{slug}.yaml records {recorded!r}, which names the change '
                f'{recorded_slug!r} instead'
            )

    # Two directories claiming one slug are two changes wearing one record's
    # name: the second one would pass this gate on the first one's record.
    for slug, names in sorted(seen_slugs.items()):
        if len(names) > 1:
            missing.append(
                f'two changes claim the slug {slug!r}: {", ".join(sorted(names))} — '
                f'one record cannot be the record of both'
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
        if mispointed:
            click.echo(
                '  a record that names another change is rewritten by — '
                'python3 scripts/openspec-import-all.py --replace'
            )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-issue-registry: OK — {len(changes)} changes, each with a registry entry '
            f'pointing back at it'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
