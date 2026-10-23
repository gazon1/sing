#!/usr/bin/env python3
"""check-agents-size.py — AGENTS.md is a cheat sheet, and a cheat sheet has a size.

What went wrong
---------------
`AGENTS.md` was 517 lines. It had been 296 when the retro named it, and the
tables were moved out — but the prose stayed: a hundred and forty lines of
rules about how to build, written out in the file that every session reads
before it does anything.

Every line in that file is paid for by every session, whether the session
needs it or not, and a file that grows past the point where it can be held in
one reading stops being read at all. The rules that were worth keeping were
kept — in `docs/architecture/`, where they are read when they are needed.

The rule
--------
`AGENTS.md` stays at or below the declared limit. A change that needs more
room in the file needs a document under `docs/` and a pointer in the file:
the cheat sheet points at the reference, it does not become one.

The limit is a number in this gate, and it is a number that can only go
down without a deliberate change here — which is the point: the decision to
grow the file has to be a decision someone makes, not a drift nobody notices.

Usage:
    python3 scripts/check-agents-size.py
    python3 scripts/check-agents-size.py --quiet
"""

from __future__ import annotations

import pathlib

import click

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The file the limit is about.
AGENTS = ROOT / 'AGENTS.md'

#: The most lines the cheat sheet may have.
#
#: 300, not 273: the limit is a ceiling, not a description of the current
#: file, so that an ordinary edit that adds two lines of real content does
#: not have to touch the gate. The reference that holds the prose lives in
#: `docs/architecture/`.
MAX_LINES = 300


def line_count(path: pathlib.Path) -> int:
    """How many lines the file has."""
    return len(path.read_text(encoding='utf-8').splitlines())


@click.command()
@click.option('--quiet', is_flag=True, help='Print nothing; the exit code carries the verdict.')
@click.option('--file', 'target', default=None, help='the file to measure (default: AGENTS.md).')
@click.pass_context
def cli(ctx: click.Context, quiet: bool, target: str | None) -> None:
    """Fail when AGENTS.md is longer than the declared limit."""
    path = pathlib.Path(target).resolve() if target else AGENTS
    if not path.is_file():
        click.echo(f'{path} does not exist — there is no cheat sheet to keep short')
        raise ctx.exit(VALIDATION_FAILURE)

    lines = line_count(path)
    if lines > MAX_LINES:
        if not quiet:
            click.echo(
                f'AGENTS.md has {lines} lines, and the limit is {MAX_LINES}.',
                err=True,
            )
            click.echo(
                'A cheat sheet is read in one go, and every line is paid for by every '
                'session. The rules that need more room belong in a document under '
                'docs/architecture/, with a pointer here.',
                err=True,
            )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(f'AGENTS.md: {lines} lines (limit {MAX_LINES})')
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    cli()
