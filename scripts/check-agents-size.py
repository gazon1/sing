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

Two limits, because there are two ceilings
------------------------------------------
The **lines** are the repository's own ceiling: a cheat sheet is read in one
go, and every line is paid for by every session.

The **bytes** are the harness's ceiling: the project instructions are read
into the agent's context and truncated to fit the injection budget
(`sgate.gatekit.budget.PROJECT_INSTRUCTIONS_BUDGET`). The truncation is silent
about the part that did not fit, and
the agent cannot tell that the rules it did not read were there. On 2026-10-24
a checkout carried an `AGENTS.md` of 45165 bytes — over the budget by 12400
bytes — and the session in it was given instructions that ended in the middle
of a sentence.

A file can be short in lines and long in bytes, so the two ceilings are not
the same number and the gate holds both. The byte budget is one record —
`sgate.gatekit.budget.PROJECT_INSTRUCTIONS_BUDGET` — and the command that
reports a checkout's state (`sgate checkout-state`) reads it from there too.

Usage:
    python3 scripts/check-agents-size.py
    python3 scripts/check-agents-size.py --quiet
"""

from __future__ import annotations

import pathlib
import sys

import click

ROOT = pathlib.Path(__file__).resolve().parent.parent

# This checkout's package root, before the import that reads this checkout's
# facts. `sgate.gatekit.budget` carries the budget the harness gives the
# project instructions, and a bootstrap that comes after the import it serves
# is a no-op for that import: the name is already bound, and the gate runs
# against another checkout's number (R2.5, R2.6).
sys.path.insert(0, str(ROOT / 'scripts' / 'src'))

from sgate.commands.base import run  # noqa: E402
from sgate.gatekit.budget import (  # noqa: E402
    PROJECT_INSTRUCTIONS_BUDGET,
    PROJECT_INSTRUCTIONS_FILE,
)

CLEAN = 0
VALIDATION_FAILURE = 1

#: The file the limit is about.
AGENTS = ROOT / PROJECT_INSTRUCTIONS_FILE

#: The most lines the cheat sheet may have.
#
# 300, not 273: the limit is a ceiling, not a description of the current
# file, so that an ordinary edit that adds two lines of real content does
# not have to touch the gate. The reference that holds the prose lives in
# `docs/architecture/`.
MAX_LINES = 300


def line_count(path: pathlib.Path) -> int:
    """How many lines the file has."""
    return len(path.read_text(encoding='utf-8').splitlines())


def byte_count(path: pathlib.Path) -> int:
    """How many bytes the file has, as the harness counts them."""
    return len(path.read_bytes())


def findings(path: pathlib.Path) -> list[str]:
    """The findings about one file; empty when it fits both ceilings.

    @param path the file to measure.
    @return one finding per ceiling the file does not fit.
    """
    lines = line_count(path)
    size = byte_count(path)
    found: list[str] = []
    if lines > MAX_LINES:
        found.append(
            f'AGENTS.md has {lines} lines, and the limit is {MAX_LINES}. A cheat sheet is '
            f'read in one go, and every line is paid for by every session. The rules that '
            f'need more room belong in a document under docs/architecture/, with a pointer '
            f'here.'
        )
    if size > PROJECT_INSTRUCTIONS_BUDGET:
        over = size - PROJECT_INSTRUCTIONS_BUDGET
        share = round(100 * size / PROJECT_INSTRUCTIONS_BUDGET)
        found.append(
            f'AGENTS.md is {size} bytes — {share} % of the {PROJECT_INSTRUCTIONS_BUDGET}-byte '
            f'project-instruction budget, {over} bytes over. The harness truncates the file '
            f'to fit the budget, and it truncates it silently: the rules past the cut are '
            f'rules the agent does not read, and the agent cannot tell that they are there. '
            f'The tail belongs behind a pointer.'
        )
    return found


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

    found = findings(path)
    if found:
        if not quiet:
            for finding in found:
                click.echo(finding, err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'AGENTS.md: {line_count(path)} lines (limit {MAX_LINES}), '
            f'{byte_count(path)} bytes (budget {PROJECT_INSTRUCTIONS_BUDGET})'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
