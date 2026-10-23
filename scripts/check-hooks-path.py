#!/usr/bin/env python3
"""check-hooks-path.py — `core.hooksPath` must not point at another checkout (#519).

What went wrong
---------------
    $ git config core.hooksPath
    /home/max/AndroidStudioProjects/singularity_cllone_kmp/.githooks

An absolute path, pointing at the *main clone* — while the work was happening in a
worktree whose `.githooks/` was a different directory. Git keeps `config` in the
shared `.git/`, so every worktree inherited it and every worktree ran hooks out of a
checkout it was not working in. Editing a hook in the worktree did nothing until the
change landed on main, and until then every agent believed it had a `pre-push` gate.

That is the same shape as the merge-driver trap: **`.gitattributes` and hooks travel
with the repository, local `git config` does not.** A repository cannot ship its own
`core.hooksPath`, so the honest thing is to make the wrong value loud.

The rule
--------
`core.hooksPath` must be relative. Git resolves a relative value against each
working tree's own top level, so one setting is correct in every worktree; an
absolute value is correct in exactly one of them.

Measured, not assumed:

    absolute, wrong path    -> hook silently does not run, commit succeeds
    relative `.githooks`    -> hook runs
    relative, from a subdir -> hook still runs

Usage:
    python3 scripts/check-hooks-path.py            # report
    python3 scripts/check-hooks-path.py --quiet
"""

from __future__ import annotations

import pathlib
import subprocess
import sys

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

import click
from sgate.commands.base import run
from sgate.gatekit.gitenv import tree_env

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXPECTED = '.githooks'


def config(key: str) -> str | None:
    proc = subprocess.run(
        ['git', 'config', '--get', key], cwd=ROOT, capture_output=True, text=True,
        env=tree_env(),
    )
    value = proc.stdout.strip()
    return value or None


@click.command()
@click.option('--quiet', is_flag=True)
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    hooks_path = config('core.hooksPath')
    errors: list[str] = []

    if hooks_path is None:
        # Not an error: git defaults to .git/hooks, and the repository ships
        # .githooks/ deliberately (installed per-clone, documented in CONTRIBUTING).
        if not quiet:
            click.echo('check-hooks-path: core.hooksPath is unset — git will use .git/hooks; '
                       f'the tracked hooks in {EXPECTED}/ are inactive until you run')
            click.echo('  git config core.hooksPath ' + EXPECTED)
        raise ctx.exit(CLEAN)

    if pathlib.PurePath(hooks_path).is_absolute():
        errors.append(
            f'core.hooksPath is the absolute path {hooks_path!r}. It can only be correct '
            f'in the one checkout that lives there; every other worktree silently runs '
            f'hooks from a directory it is not working in. Set a relative value instead:'
        )
    elif hooks_path != EXPECTED:
        errors.append(
            f'core.hooksPath is {hooks_path!r}, but this repository tracks its hooks in '
            f'{EXPECTED}/. A relative value is what makes the setting correct in every '
            f'worktree; the name has to match the directory that is committed.'
        )

    hooks_dir = ROOT / (hooks_path if not pathlib.PurePath(hooks_path).is_absolute() else EXPECTED)
    if hooks_path and not hooks_dir.is_dir():
        errors.append(f'core.hooksPath points at {hooks_dir}, which does not exist in this checkout.')
    elif hooks_dir.is_dir():
        # A configured hooks path is only worth anything if the hooks are *tracked*.
        # This is #519's actual subject one level in: hooks that exist in the main
        # clone but were never committed are hooks that no worktree, and no CI run,
        # can rely on — and the configuration looks completely healthy.
        tracked = subprocess.run(
            ['git', 'ls-files', str(hooks_dir.relative_to(ROOT) / '')],
            cwd=ROOT, capture_output=True, text=True,
            env=tree_env(),
        ).stdout.split()
        if not tracked:
            errors.append(
                f'{hooks_dir.relative_to(ROOT)}/ is configured as the hooks path but '
                f'contains no tracked files. Git will run whatever is there, and this '
                f'clone may hold hooks nobody else has.'
            )

    for err in errors:
        click.echo(f'ERROR: {err}')
    if errors:
        click.echo('')
        click.echo(f'check-hooks-path: fix with — git config core.hooksPath {EXPECTED}')
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(f'check-hooks-path: OK — core.hooksPath is {hooks_path!r} (relative), '
                   f'so every worktree resolves it against its own tree')
    raise ctx.exit(CLEAN)




if __name__ == '__main__':
    raise SystemExit(run(cli))
