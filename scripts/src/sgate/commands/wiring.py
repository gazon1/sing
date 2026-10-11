"""sgate.commands.wiring — shim for check-gate-wiring.py Part A."""
from __future__ import annotations

import subprocess
import sys

import click

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, IO_ERROR, register


@click.command("wiring")
@click.option("--wiring", is_flag=True, help="Run wiring check (Part A)")
@click.option("--can-fail", is_flag=True, help="Run can-fail check (Part C)")
def wiring(wiring: bool, can_fail: bool) -> None:
    """Run gate-wiring sanity checks (check-gate-wiring.py).

    Part A proves every gate in static-gates.sh exists and can be invoked.
    Part C proves every gate's sabotage test actually fails its sabotage input.

    Exit codes:
      0 = clean (all wiring correct)
      1 = validation failure (gate missing or sabotage doesn't fail)
      2 = I/O error (script not found)
      5 = internal error (unexpected exception)
    """
    repo_root = sgate.repo.ROOT
    script = repo_root / "scripts" / "check-gate-wiring.py"

    if not script.exists():
        click.echo(f"check-gate-wiring.py not found at {script}", err=True)
        raise SystemExit(IO_ERROR)

    parts = [sys.executable, str(script)]
    if wiring:
        parts.append("--wiring")
    if can_fail:
        parts.append("--can-fail")

    try:
        result = subprocess.run(parts, capture_output=False)
        raise SystemExit(result.returncode)
    except FileNotFoundError:
        click.echo(f"python3 not found at {sys.executable}", err=True)
        raise SystemExit(IO_ERROR)
    except Exception as exc:
        click.echo(f"Unexpected error running wiring check: {exc}", err=True)
        raise SystemExit(INTERNAL_ERROR)


# Register with the CLI group
register(wiring)
