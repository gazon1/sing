#!/usr/bin/env python3
"""check-review-verdicts.py — every change carries the verdict of its review.

What went wrong
---------------
`AGENTS.md` says a change gets a review by a separate agent before the PR is
opened, and it says it in one line. The line is a promise, and a promise
that leaves no trace is indistinguishable from a rule nobody follows: the
next session cannot tell whether the change was reviewed, by whom, what
was found, or whether the findings were fixed.

The review that came with the issue-registry gate found four real defects
in a diff that had already been written, reviewed and pushed. Nothing in
the tree said that. The finding existed only in the reviewer's answer, in
a session that was about to end.

The rule
--------
Every active change — a directory under `openspec/changes/`, not the
archive — carries a verdict in `.specify/reviews/<slug>.md`:

    ---
    change: <the slug>
    verdict: pass | fail
    date: YYYY-MM-DD
    pr: <the pull request>
    ---

    ## What was reviewed
    ...

A `fail` is allowed, and is the interesting case: it says the review
found something. What is not allowed is a verdict nobody wrote.

The changes that predate this rule are in
`config/docs/review-verdicts-baseline.txt` — a list of accepted
exceptions, the same shape as every other baseline in this repository.
It shrinks and never grows: `--update-baseline` prunes the entries whose
change is gone and refuses to add new ones, so accepting a new exception
is an edit in the diff that a reviewer can see, not a side effect of a
maintenance command.

Usage:
    python3 scripts/check-review-verdicts.py            # report
    python3 scripts/check-review-verdicts.py --quiet
    python3 scripts/check-review-verdicts.py --update-baseline
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
REVIEWS_DIR = ROOT / '.specify/reviews'
BASELINE = ROOT / 'config/docs/review-verdicts-baseline.txt'

#: The verdicts a review may carry.
VERDICTS = frozenset({'pass', 'fail'})

#: A date, as the verdict file states it.
DATE = re.compile(r'^\d{4}-\d{2}-\d{2}$')

#: A date prefix on a change directory — the convention for a new change.
DATE_PREFIX = re.compile(r'^\d{4}-\d{2}-\d{2}-')

#: The line the spec-create workflow leaves in a proposal it has just written.
#: A change whose proposal still says this is scaffolding, not a decision:
#: nobody has written it up, so nobody has reviewed it, and there is nothing
#: to have reviewed yet. The exemption ends when the author writes the
#: proposal — the placeholder is gone with it.
#:
#: The text comes from `.specify/workflows/openspec-change/workflow.yml`.
#: If that template changes, the scaffold stops being exempt and the gate
#: asks for a verdict — the failure mode is stricter, not looser.
SCAFFOLD_PLACEHOLDER = '_Describe the problem or opportunity here'

#: The marker the spec-create workflow leaves next to the proposal.
SCAFFOLD_MARKER = '.openspec.yaml'


def active_changes(changes_dir: pathlib.Path) -> list[str]:
    """The slugs of the changes that are still open."""
    if not changes_dir.is_dir():
        return []
    slugs: list[str] = []
    for entry in sorted(changes_dir.iterdir()):
        if not entry.is_dir() or entry.name == 'archive':
            continue
        slugs.append(DATE_PREFIX.sub('', entry.name))
    return slugs


def change_directories(changes_dir: pathlib.Path) -> dict[str, pathlib.Path]:
    """The open change directories, keyed by the slug they carry."""
    if not changes_dir.is_dir():
        return {}
    directories: dict[str, pathlib.Path] = {}
    for entry in sorted(changes_dir.iterdir()):
        if entry.is_dir() and entry.name != 'archive':
            directories[DATE_PREFIX.sub('', entry.name)] = entry
    return directories


def archived_changes(changes_dir: pathlib.Path) -> list[str]:
    """The slugs of the changes that were filed away.

    A verdict for an archived change is a record of a review that happened,
    so it is not an error — the same reasoning the registry gate uses when it
    compares by slug rather than by path.
    """
    archive = changes_dir / 'archive'
    if not archive.is_dir():
        return []
    return sorted(
        DATE_PREFIX.sub('', entry.name)
        for entry in archive.iterdir()
        if entry.is_dir()
    )


def is_scaffold(change_dir: pathlib.Path) -> bool:
    """Whether a change is still the scaffold the spec-create workflow wrote."""
    if not (change_dir / SCAFFOLD_MARKER).is_file():
        return False
    proposal = change_dir / 'proposal.md'
    if not proposal.is_file():
        return False
    return SCAFFOLD_PLACEHOLDER in proposal.read_text(encoding='utf-8', errors='replace')


def read_baseline(path: pathlib.Path) -> set[str]:
    """The slugs the baseline accepts."""
    if not path.is_file():
        return set()
    return {
        line.strip()
        for line in path.read_text(encoding='utf-8', errors='replace').splitlines()
        if line.strip() and not line.startswith('#')
    }


def parse_frontmatter(text: str) -> dict[str, str]:
    """The frontmatter of a verdict file, as a mapping."""
    if not text.startswith('---'):
        return {}
    end = text.find('\n---', 3)
    if end == -1:
        return {}
    fields: dict[str, str] = {}
    for line in text[3:end].splitlines():
        pair = re.match(r'^([A-Za-z_][\w-]*):\s*(.*)$', line)
        if pair:
            fields[pair.group(1)] = pair.group(2).strip().strip('"\'')
    return fields


def body_of(text: str) -> str:
    """The text after the frontmatter."""
    end = text.find('\n---', 3)
    if end == -1:
        return ''
    return text[end + 4:]


def review_problems(text: str, slug: str) -> list[str]:
    """What is wrong with a verdict file, if anything."""
    problems: list[str] = []
    fields = parse_frontmatter(text)
    if not fields:
        return [f'{slug}.md has no frontmatter — a verdict states its change, verdict, date and PR']

    if fields.get('change') != slug:
        problems.append(
            f'{slug}.md reviews {fields.get("change")!r} — the file is named after the change'
        )
    if fields.get('verdict') not in VERDICTS:
        problems.append(
            f'{slug}.md states verdict {fields.get("verdict")!r} — it is one of '
            f'{", ".join(sorted(VERDICTS))}'
        )
    if not DATE.match(fields.get('date', '')):
        problems.append(f'{slug}.md states date {fields.get("date")!r} — it is YYYY-MM-DD')
    if not fields.get('pr'):
        problems.append(f'{slug}.md names no pull request')

    said_something = [
        line for line in body_of(text).splitlines()
        if line.strip() and not line.lstrip().startswith('#')
    ]
    if not said_something:
        problems.append(f'{slug}.md says nothing but headings — a review says what it reviewed')

    return problems


def find_problems(
    changes_dir: pathlib.Path,
    reviews_dir: pathlib.Path,
    baseline: set[str],
) -> tuple[list[str], list[str], list[str], dict[str, int]]:
    """The changes without a verdict, the baseline entries that went stale,
    the scaffolds that are exempt, and what was checked."""
    problems: list[str] = []
    directories = change_directories(changes_dir)
    changes = sorted(directories)
    archived = set(archived_changes(changes_dir))
    scaffolds: list[str] = []
    counted = {'changes': len(changes), 'verdicts': 0, 'baselined': 0, 'scaffolds': 0}

    for slug in changes:
        review = reviews_dir / f'{slug}.md'
        if not review.is_file():
            change_dir = directories.get(slug)
            if change_dir is not None and is_scaffold(change_dir):
                scaffolds.append(slug)
                counted['scaffolds'] += 1
                continue
            if slug in baseline:
                counted['baselined'] += 1
                continue
            problems.append(
                f'{slug} has no review verdict — write {reviews_dir.name}/{slug}.md, '
                f'or accept the change in the baseline'
            )
            continue
        counted['verdicts'] += 1
        text = review.read_text(encoding='utf-8', errors='replace')
        problems.extend(review_problems(text, slug))

    # A verdict for a change that is neither open nor archived is a verdict
    # about nothing. A verdict for an archived change is a record of a review
    # that happened, so it stays.
    if reviews_dir.is_dir():
        for review in sorted(reviews_dir.glob('*.md')):
            slug = review.stem
            if slug not in changes and slug not in archived:
                problems.append(
                    f'{reviews_dir.name}/{review.name} reviews {slug!r}, which is neither an '
                    f'open change nor an archived one'
                )

    stale = sorted(baseline - set(changes))
    return problems, stale, scaffolds, counted


@click.command()
@click.option('--quiet', is_flag=True)
@click.option('--update-baseline', is_flag=True)
@click.option(
    '--root',
    default=None,
    help='the tree to check (default: the repository this script lives in)',
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, update_baseline: bool, root: str | None) -> None:
    tree = pathlib.Path(root).resolve() if root else ROOT
    changes_dir = tree / 'openspec' / 'changes'
    reviews_dir = tree / '.specify' / 'reviews'
    baseline_path = tree / 'config/docs/review-verdicts-baseline.txt'
    accepted = read_baseline(baseline_path)

    if update_baseline:
        changes = set(active_changes(changes_dir))
        stale = sorted(accepted - changes)
        kept = sorted(accepted & changes)
        header = (
            '# Changes that predate the review-verdict rule.\n'
            '# This list only shrinks: a new entry is an edit a reviewer can see.\n'
            '# Prune it with: python3 scripts/check-review-verdicts.py --update-baseline\n'
        )
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(header + '\n'.join(kept) + ('\n' if kept else ''), encoding='utf-8')
        click.echo(
            f'check-review-verdicts: pruned {len(stale)} stale entr(ies) from '
            f'{baseline_path.name}'
        )
        if stale:
            for slug in stale:
                click.echo(f'  - {slug}')
        raise ctx.exit(CLEAN)

    problems, stale, scaffolds, counted = find_problems(changes_dir, reviews_dir, accepted)

    for problem in problems:
        click.echo(f'ERROR: {problem}')

    if stale:
        click.echo('')
        click.echo(
            f'check-review-verdicts: note — {len(stale)} baseline entr(ies) name a change that '
            f'is not open any more; remove them with --update-baseline'
        )

    if scaffolds and not quiet:
        click.echo('')
        click.echo(
            f'check-review-verdicts: note — {len(scaffolds)} change(s) are still the scaffold '
            f'the spec-create workflow wrote, and are exempt until they are written up: '
            f'{", ".join(scaffolds)}'
        )

    if problems:
        click.echo('')
        click.echo(
            'check-review-verdicts: the review of a change is what the next session cannot '
            'read anywhere else — a verdict that leaves no trace is a promise with nothing '
            'behind it.'
        )
        click.echo(
            '  fix with — write .specify/reviews/<slug>.md (change, verdict, date, PR), '
            'or add the change to '
            f'{baseline_path.name} by hand (the list only shrinks by command; adding one is '
            'an edit in the diff a reviewer can see)'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-review-verdicts: OK — {counted["changes"]} open changes: '
            f'{counted["verdicts"]} carry a verdict, {counted["baselined"]} predate the rule, '
            f'{counted["scaffolds"]} are still a scaffold'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
