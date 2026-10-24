#!/usr/bin/env python3
"""check-checkout-invariants.py — the checkout has to be able to work.

What went wrong
---------------
The canonical checkout's `.git/config` was corrupted three times in one
session, and each corruption had a different symptom:

  - `core.bare = true` — every command in the checkout refuses ("this
    operation must be run in a work tree"), so nothing could be committed;
  - `core.sshCommand` replaced by another value — the keepalive was gone, and
    a push died with 141 after the hook had already said the tree was clean;
  - `core.hooksPath` lost — git silently ran NO hooks, so the pre-push
    verdict never happened and nothing said so.

Each time the cause had to be found from scratch, because the symptom names
the failure, never the cause. The cause was found on 2026-10-23: the test
suite, run with the `GIT_DIR` a hook exports, wrote those values into the
shared config (ADR
`2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md`).

The rule
--------
The checkout this gate runs in must be able to do its job:

  1. it is not a bare repository;
  2. `core.hooksPath` is relative (so it is right in every worktree) and, when
     the hooks are expected to be installed, points at a directory that HOLDS
     an executable pre-push — git runs no hooks at all when the path does not
     exist, and that silence reads as a green push;
  3. `core.sshCommand`, when set, carries the keepalive — the pre-push hook
     takes minutes, and the connection is opened before the hook runs.

The hooks and the remote are checked only outside CI: a CI checkout has no
hooks installed and no local remote, and a gate that failed there would be a
gate everybody learned to ignore.

Usage:
    python3 scripts/check-checkout-invariants.py
    python3 scripts/check-checkout-invariants.py --quiet
"""

from __future__ import annotations

import json
import os
import pathlib
import subprocess
import sys

import click

# The sgate package lives in this checkout's own `scripts/src`, and the answer
# this script takes from it — the environment a gate must read the repository
# in — is about this checkout. The bootstrap puts that root on the path before
# the import, the way `scripts/check-hook-gates.py` does: an import that
# happens first is already bound to another tree's package under the same name.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / 'src')
if _scripts_src not in sys.path:
    sys.path.insert(0, _scripts_src)

from sgate.commands.base import run  # noqa: E402
from sgate.gatekit.gitenv import tree_env  # noqa: E402

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: What a checkout of this repository must be able to do. The declaration is
#: a file, so the gate compares the live checkout against a written promise
#: instead of against constants buried in the code — and so the doctor and the
#: gate read the same promise.
DECLARATION = ROOT / 'config' / 'checkout-invariants.json'

#: The environment git exports to a hook. A gate that reads the config must
#: not read the one the hook points at — that is the whole failure this gate
#: exists to catch, and reading it would hide the answer. The answer belongs
#: to `sgate.gatekit.gitenv`; this gate takes it from there.


def clean_env() -> dict[str, str]:
    """The environment without the variables git exports to a hook."""
    return tree_env()


def config(key: str) -> str | None:
    """The effective value of a config key, or None when it is not set."""
    proc = subprocess.run(
        ['git', 'config', '--get', key], cwd=ROOT, capture_output=True, text=True, env=clean_env(),
    )
    value = proc.stdout.strip()
    return value or None


def is_bare() -> bool:
    """Whether this checkout is a bare repository."""
    proc = subprocess.run(
        ['git', 'rev-parse', '--is-bare-repository'], cwd=ROOT, capture_output=True, text=True, env=clean_env(),
    )
    return proc.stdout.strip() == 'true'


def in_ci() -> bool:
    """Whether this run happens in CI, where the local hooks are not installed."""
    return os.environ.get('CI', '').lower() in {'true', '1'}


def declaration() -> dict:
    """The declared invariants, or an empty mapping when the file is unreadable."""
    try:
        return json.loads(DECLARATION.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {}


def problems() -> list[str]:
    """Every broken invariant, in the order they are decided."""
    found: list[str] = []
    declared = declaration()
    expected_bare = bool(declared.get('bare', False))
    expected_hooks = str(declared.get('hooks_path', '.githooks'))
    keepalive_marker = str(declared.get('ssh_keepalive', 'ServerAliveInterval'))

    if is_bare() != expected_bare:
        found.append(
            f"this checkout is {'bare' if is_bare() else 'not bare'}, and the declaration "
            f'says it must not be — every command in a bare checkout refuses to run. '
            "Repair it with 'scripts/doctor.sh --fix', which writes this worktree's own "
            "config and leaves the shared one alone.",
        )

    # The shape of the value is check-hooks-path.py's business: that the path
    # is relative and names the tracked directory. What this gate asks is the
    # other question — whether the path leads to hooks that actually exist.
    # A path that leads nowhere is a gate that reports success because
    # nothing ever asked it a question.
    hooks_path = config('core.hooksPath')
    if hooks_path is None:
        if not in_ci():
            found.append(
                'core.hooksPath is not set — git runs NO hooks, so the pre-push verdict '
                "never happens. Install them with 'just setup-hooks'.",
            )
    elif not (ROOT / hooks_path / 'pre-push').is_file():
        found.append(
            f'core.hooksPath points at {hooks_path}, which holds no pre-push — git runs '
            'no hooks at all, and that silence reads as a green push.',
        )
    elif hooks_path != expected_hooks:
        found.append(
            f'core.hooksPath is {hooks_path}, and the declaration says it must be '
            f'{expected_hooks}.',
        )

    ssh_command = config('core.sshCommand')
    if ssh_command is not None and keepalive_marker not in ssh_command:
        found.append(
            f'core.sshCommand has no keepalive ({ssh_command}) — the pre-push hook takes '
            'minutes, and the connection to the remote is opened before it runs.',
        )

    return found


@click.command()
@click.option('--quiet', is_flag=True, help='Print nothing; the exit code carries the verdict.')
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    """Check the invariants the checkout needs to be able to work."""
    found = problems()
    if found:
        if not quiet:
            for problem in found:
                click.echo(f'checkout invariant broken: {problem}', err=True)
        raise ctx.exit(VALIDATION_FAILURE)
    if not quiet:
        click.echo('checkout invariants: OK')
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
