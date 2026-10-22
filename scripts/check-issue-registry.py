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

The record's own links
----------------------
A record is a link to the change, not a claim about it, so the documents it
names have to be there. The importer used to write all four names — proposal,
design, adr, tasks — whether the change had them or not, so a record pointed
at a `design.md` that was never written. The importer now writes only the
documents that exist, and this gate holds it to that: a record naming a
document that is not there is a link to nothing.

The fix
-------
A missing record is created by:

    python3 scripts/openspec-import-all.py

A record that names another change, or a document that is not there, is
rewritten by the same script with `--replace` — without it the importer skips
a record that already exists, which is exactly the case that needs rewriting.

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


def archived_by_slug(changes_dir: pathlib.Path) -> dict[str, list[pathlib.Path]]:
    """The archived change directories, keyed by the slug they carry.

    Archiving moves a change and renames it: `openspec/changes/<slug>`
    becomes `openspec/changes/archive/<date>-<slug>`. A record keeps the path
    the change had before the move, so the record's documents are looked up
    here by slug — the same way the record itself is matched to the change.
    """
    archive = changes_dir / ARCHIVE_DIR
    if not archive.is_dir():
        return {}
    archived: dict[str, list[pathlib.Path]] = {}
    for entry in sorted(archive.iterdir()):
        if entry.is_dir():
            archived.setdefault(slug_for(entry.name), []).append(entry)
    return archived


def recorded_path(entry: pathlib.Path) -> str | None:
    """The `openspec_path` a registry entry records, if it has one."""
    try:
        text = entry.read_text(encoding='utf-8')
    except OSError:
        return None
    match = re.search(r'^openspec_path:\s*(\S+)', text, re.M)
    return match.group(1) if match else None


def recorded_documents(entry: pathlib.Path) -> dict[str, str]:
    """The documents a registry entry names, as written in its `documents:` block.

    The block is read with a pattern rather than a YAML parser on purpose: the
    gate has to keep working on a tree where the parser is not installed, and
    the block is a flat mapping of names to paths.
    """
    try:
        text = entry.read_text(encoding='utf-8')
    except OSError:
        return {}
    block = re.search(r'^documents:\s*$\n((?:[ \t]+.*\n?)*)', text, re.M)
    if not block:
        return {}
    documents: dict[str, str] = {}
    for line in block.group(1).splitlines():
        pair = re.match(r'\s+([A-Za-z_][\w-]*):\s*(\S+)', line)
        if pair:
            documents[pair.group(1)] = pair.group(2)
    return documents


def recorded_specs_dir(entry: pathlib.Path) -> str | None:
    """The `specs_dir` a registry entry records, if it records one that is set."""
    try:
        text = entry.read_text(encoding='utf-8')
    except OSError:
        return None
    match = re.search(r'^specs_dir:\s*(\S+)', text, re.M)
    if not match or match.group(1) in ('null', '~', "''", '""'):
        return None
    return match.group(1)


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


def _document_survived_in_archive(archived: list[pathlib.Path], path: str) -> bool:
    """Whether a recorded path names a document the archived change still has.

    The record keeps the path the change had before the move, so the file it
    names is looked up by its name inside the archived directory.
    """
    name = pathlib.PurePosixPath(path).name
    return any((directory / name).exists() for directory in archived)


def find_problems(
    changes_dir: pathlib.Path,
    issues_dir: pathlib.Path,
) -> tuple[list[str], list[str], list[str]]:
    """The changes without a registry entry, the entries pointing elsewhere,
    and the entries naming a document that is not there.

    The three lists are separate because they are different failures with
    different fixes: a missing entry is created by the import script, a
    mispointed one is rewritten by it with `--replace`, and an entry that
    names a document the change never had is rewritten by the same command —
    the importer writes only the documents that exist.
    """
    missing: list[str] = []
    mispointed: list[str] = []
    dangling: list[str] = []
    seen_slugs: dict[str, list[str]] = {}
    root = changes_dir.parent.parent

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

        # A record is a link to the change, not a claim about it. Every
        # document it names has to be there: a record that points at a
        # design.md the change never had sends the next session to a file
        # that was never written.
        #
        # A document that is not where the record says is not always a lie:
        # archiving moves the change and renames it, and the record keeps the
        # path the change had before the move. That is resolved by slug, the
        # same way the record itself is matched to its change.
        archived = archived_by_slug(changes_dir).get(slug, [])
        for name, path in sorted(recorded_documents(entry).items()):
            if (root / path).exists():
                continue
            if _document_survived_in_archive(archived, path):
                continue
            dangling.append(
                f'{issues_dir.name}/{slug}.yaml names the {name} {path!r}, which does not exist'
            )
        specs_dir = recorded_specs_dir(entry)
        if specs_dir and not (root / specs_dir).exists():
            if not _document_survived_in_archive(archived, specs_dir):
                dangling.append(
                    f'{issues_dir.name}/{slug}.yaml names the specs directory {specs_dir!r}, '
                    f'which does not exist'
                )

    # Two directories claiming one slug are two changes wearing one record's
    # name: the second one would pass this gate on the first one's record.
    for slug, names in sorted(seen_slugs.items()):
        if len(names) > 1:
            missing.append(
                f'two changes claim the slug {slug!r}: {", ".join(sorted(names))} — '
                f'one record cannot be the record of both'
            )

    return missing, mispointed, dangling


@click.command()
@click.option('--quiet', is_flag=True)
@click.option(
    '--root',
    default=None,
    help='the tree to check (default: the repository this script lives in)',
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None) -> None:
    tree = pathlib.Path(root).resolve() if root else ROOT
    changes_dir = tree / 'openspec' / 'changes'
    issues_dir = tree / '.specify' / 'issues'
    changes = change_directories(changes_dir)
    if not changes:
        if not quiet:
            click.echo(f'check-issue-registry: no changes under {changes_dir} — nothing to record')
        raise ctx.exit(CLEAN)

    missing, mispointed, dangling = find_problems(changes_dir, issues_dir)

    for problem in missing:
        click.echo(f'ERROR: no registry entry: {problem}')
    for problem in mispointed:
        click.echo(f'ERROR: registry entry points elsewhere: {problem}')
    for problem in dangling:
        click.echo(f'ERROR: registry entry names a document that is not there: {problem}')

    if missing or mispointed or dangling:
        click.echo('')
        click.echo(
            'check-issue-registry: the registry is the source of truth for a change, and a '
            'change without an entry is a change the next session cannot find.'
        )
        click.echo('  fix with — python3 scripts/openspec-import-all.py')
        if mispointed or dangling:
            click.echo(
                '  an entry that names another change, or a document that is not there, is '
                'rewritten by — python3 scripts/openspec-import-all.py --replace'
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
