"""sgate.commands.whereami — print the checkout this sgate belongs to.

Why this exists
---------------

On a machine with several checkouts of this repository, `sgate` on PATH is an
editable install pointing at *one* of them — not necessarily the checkout the
caller is standing in. The gate suite then certifies somebody else's code
while reporting it as this checkout's result (ADR
`2026-10-21-one-owner-of-stdout.md`).

The answer is one line long and it is not guessable from the outside: the
package does not know where it was installed from, it only knows where its own
files are. So it says it, and the preflight asks it.

Usage:
    sgate whereami            # prints the absolute path of the checkout
    sgate whereami --json     # {"root": "/path/to/checkout"}

Exit codes:
  0 = the root was printed
  2 = the root could not be resolved (the package is not inside a checkout)
"""
from __future__ import annotations

import json as _json

import click

from sgate.commands.base import CLEAN, CONFIG_ERROR, register


@click.command("whereami")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def whereami(emit_json: bool) -> None:
    """Print the checkout this sgate belongs to.

    The path is derived from the package's own location, so it answers the
    question "whose code is this?" rather than "where was it invoked from?" —
    the two differ exactly when the answer matters.
    """
    from sgate.repo import ROOT

    if emit_json:
        click.echo(_json.dumps({"root": str(ROOT)}))
        return
    click.echo(str(ROOT))


register(whereami)
