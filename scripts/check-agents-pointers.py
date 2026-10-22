#!/usr/bin/env python3
"""check-agents-pointers.py — every file path `AGENTS.md` names exists.

What went wrong
---------------
`AGENTS.md` was given four notes in one change, and one of them pointed at
the wrong file: the note said the ViewModel test exemptions live in the
harness (`test/helpers/RunVmTest.kt`), and they live in
`arch/SharedHarnessConventionsTest.kt` — the file the gate that enforces
them actually reads.

Nobody noticed for a session, because a wrong pointer does not fail. It
costs the reader one `ls`, and it costs the writer the stop the note was
written to remove: "where is this thing called?" is exactly the question
the note answers, and a note that answers it with a wrong path answers it
with a second question.

This gate is the mechanical half of that: it reads the paths `AGENTS.md`
names and checks that they resolve. A pointer that no longer resolves is a
pointer that will send the next session looking for a file that is not
there.

What counts as a pointer
------------------------
A backticked token that

  - names a path inside the repository (it starts with a known top-level
    directory: `docs/`, `scripts/`, `shared/`, …), and
  - has no whitespace in it (a command is not a path), and
  - has no `<`, `>` or `*` in it (those are placeholders, not paths), and
  - is not inside a generated directory (`build/`, `.gradle/`, …), whose
    contents depend on the last build rather than on the tree.

Two shapes are accepted and resolved:

  - `{a,b}` — a choice, e.g. `core/di/{Core,Calendar}DiModule.kt`; every
    alternative has to resolve, because the note claims both exist;
  - `...` — any number of directories, e.g.
    `shared/src/commonTest/.../test/helpers/RunVmTest.kt`.

A path that is relative to something the note mentions in prose
(`core/di/Modules.kt`, said next to
`shared/src/commonMain/kotlin/com/singularity/todo/`) is **not** checked:
where it is relative to is not written down, so the gate would be guessing.
The rule covers the pointers that are unambiguous, and says so.

Not vacuous
-----------
If the file names no pointer at all, the gate fails. A rule that finds
nothing to check has proved nothing, and a passing gate that checked
nothing is the failure this repository keeps paying for.

Usage:
    python3 scripts/check-agents-pointers.py            # report
    python3 scripts/check-agents-pointers.py --quiet
"""

from __future__ import annotations

import pathlib
import re

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENTS = ROOT / 'AGENTS.md'

#: The top-level directories a repository-relative path can start with.
#: A token that starts with anything else is either relative to prose
#: around it or is not a path at all.
KNOWN_ROOTS = frozenset({
    '.githooks',
    '.specify',
    'adr-cli',
    'adr-core',
    'androidApp',
    'build-logic',
    'config',
    'core-base',
    'detekt-rules',
    'desktopApp',
    'docs',
    'gradle',
    'mcp-server',
    'openspec',
    'scripts',
    'shared',
})

#: Directories whose contents are a build product, not the tree.
GENERATED = frozenset({'build', 'out', '.gradle', 'node_modules'})

#: The fewest pointers the file must name for the check to mean anything.
MIN_POINTERS = 10

#: A backticked token.
TICKED = re.compile(r'`([^`\n]+)`')

#: `{a,b}` — a choice between names.
BRACES = re.compile(r'\{([^{}]*)\}')


def expand_braces(token: str) -> list[str]:
    """Every path a `{a,b}` token stands for."""
    match = BRACES.search(token)
    if not match:
        return [token]
    expanded: list[str] = []
    for choice in match.group(1).split(','):
        expanded.extend(
            expand_braces(token[:match.start()] + choice.strip() + token[match.end():])
        )
    return expanded


def is_pointer(token: str) -> bool:
    """Whether a backticked token claims to name a file in the repository."""
    if not token or any(character.isspace() for character in token):
        return False
    if any(character in token for character in '<>*'):
        return False
    if '://' in token or token.startswith('-'):
        return False
    relative = token[2:] if token.startswith('./') else token
    parts = relative.split('/')
    if parts[0] not in KNOWN_ROOTS:
        return False
    return not any(part in GENERATED for part in parts)


def pointers_in(text: str) -> list[str]:
    """The repository paths a document names, expanded and deduplicated."""
    found: list[str] = []
    for token in TICKED.findall(text):
        if not is_pointer(token):
            continue
        relative = token[2:] if token.startswith('./') else token
        for candidate in expand_braces(relative):
            if candidate not in found:
                found.append(candidate)
    return found


def resolves(pointer: str, root: pathlib.Path) -> bool:
    """Whether a pointer names something that exists.

    `...` stands for any number of directories, so it is resolved as a
    glob; everything else is a plain path.
    """
    if '...' in pointer:
        pattern = pointer.replace('...', '**')
        return any(True for _ in root.glob(pattern))
    return (root / pointer).exists()


def find_problems(document: pathlib.Path, root: pathlib.Path) -> tuple[list[str], list[str]]:
    """The pointers that do not resolve, and the pointers that were found."""
    text = document.read_text(encoding='utf-8', errors='replace')
    pointers = pointers_in(text)
    broken = [pointer for pointer in pointers if not resolves(pointer, root)]
    return broken, pointers


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
    document = tree / 'AGENTS.md'
    if not document.is_file():
        click.echo(f'ERROR: {document} does not exist — there is nothing to check')
        raise ctx.exit(VALIDATION_FAILURE)

    broken, pointers = find_problems(document, tree)

    for pointer in broken:
        click.echo(f'ERROR: {document.name} names {pointer!r}, which does not exist')

    if len(pointers) < MIN_POINTERS:
        click.echo(
            f'ERROR: {document.name} names {len(pointers)} pointer(s); '
            f'a check that finds fewer than {MIN_POINTERS} proves nothing'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if broken:
        click.echo('')
        click.echo(
            'check-agents-pointers: a pointer that does not resolve sends the next session '
            'looking for a file that is not there — which is the stop the note was written '
            'to remove.'
        )
        click.echo('  fix with — point the note at the file that exists, or drop the mention')
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-agents-pointers: OK — {len(pointers)} pointers in '
            f'{document.name}, every one resolves'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
