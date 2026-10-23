#!/usr/bin/env python3
"""check-gate-tooling-origin.py — the gate CLI must serve THIS checkout.

What went wrong
---------------
A local gate run in one worktree reported "41 passed, 0 failed" — and measured
a different tree. The `sgate` package is installed *editable*, so the venv on
`PATH` decides which checkout's `scripts/src/sgate` the gate CLI actually is:

    $ python3 -c 'import pathlib, sgate; print(pathlib.Path(sgate.__file__).parent)'
    /home/max/orca/workspaces/singularity_cllone_kmp/gulper/scripts/src/sgate

`sgate/repo.py` derives the repository root from *its own* file location, so a
foreign `sgate` reads a foreign `scripts/ci/static-gates.sh`, runs a foreign
registry, and reports about a tree the person is not working in. The run is
green, quiet, and says nothing about the code in front of you.

The two registries differ exactly where it hurts: the checkout that was
measured had 45 gate specs against this checkout's 58, and the three that were
missing were the three that failed in CI afterwards — the tag catalogue, the
issue registry, and the selectable-text coverage.

The rule
--------
The importable `sgate` package must live in this checkout's `scripts/src/sgate`.
A gate run that measures another tree is not a gate run that found nothing to
report, and the difference has to be visible before the gates are read as a
verdict.

What this does NOT check
------------------------
Whether the tools are *installed*. That is `scripts/ci/preflight-gate-tools.sh`,
which names the fix for a missing tool. This check is about a tool that is
installed and points somewhere else — a case a presence check cannot see.

Why this script does not import `sgate`
---------------------------------------
Every other gate script ends with `from sgate.commands.base import run`. This
one must not: the question it answers is *where the importable `sgate` comes
from*, and importing the package to find that out would make the script
unrunnable in the one state it exists to report — the state where the package
is missing, or is missing from the checkout that is being checked. It uses
Click, like the other gate scripts, and nothing else from the project.

Usage:
    python3 scripts/check-gate-tooling-origin.py            # report
    python3 scripts/check-gate-tooling-origin.py --quiet    # findings only

Exit code: 0 when the tooling serves this checkout (or is not importable at
all — the preflight's business), 1 when it serves another checkout.
"""

from __future__ import annotations

import importlib.util
import pathlib

import click

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCAL_SGATE = (ROOT / "scripts" / "src" / "sgate").resolve()


def sgate_origin() -> pathlib.Path | None:
    """Where the importable `sgate` package comes from, or None."""
    try:
        spec = importlib.util.find_spec("sgate")
    except (ImportError, ValueError):
        return None
    if spec is None or not spec.origin:
        return None
    return pathlib.Path(spec.origin).resolve().parent


@click.command()
@click.option("--quiet", is_flag=True, help="Findings only.")
def cli(quiet: bool) -> None:
    """Check that the gate CLI serves this checkout."""
    origin = sgate_origin()
    if origin is None:
        # Not installed at all. The preflight names the fix; this check does
        # not turn a missing tool into a claim about a foreign one.
        if not quiet:
            click.echo("check-gate-tooling-origin: sgate is not importable — "
                       "the preflight says how to install it.")
        raise SystemExit(CLEAN)

    if origin == LOCAL_SGATE:
        if not quiet:
            click.echo(f"check-gate-tooling-origin: OK — the gate CLI serves this checkout "
                       f"({origin}).")
        raise SystemExit(CLEAN)

    venv = pathlib.Path.home() / ".venvs" / f"singularity-todo-{ROOT.name}"
    click.echo("check-gate-tooling-origin: FOREIGN — the gate CLI serves another checkout:")
    click.echo(f"    {origin}")
    click.echo(f"  not this one: {LOCAL_SGATE}")
    click.echo("")
    click.echo("A gate run in this state measures that other tree: it reads that checkout's")
    click.echo("registry and reports about code this one does not contain. The run looks")
    click.echo("green and says nothing about the tree in front of you.")
    click.echo("")
    click.echo("Give this checkout its own venv (the gate CLI is an editable install of")
    click.echo("scripts/src/, so a venv made elsewhere measures that other checkout):")
    click.echo("")
    click.echo(f'    SINGULARITY_TOOLS_VENV="{venv}" bash scripts/ci/setup-local-tools.sh')
    click.echo(f'    export PATH="{venv}/bin:$PATH"')
    click.echo("")
    click.echo("Then `bash scripts/ci/preflight-gate-tools.sh` reports 'sgate ok'.")
    raise SystemExit(VALIDATION_FAILURE)


if __name__ == "__main__":
    cli()
