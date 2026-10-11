"""sgate.commands.conventions — shim for check-script-conventions.py."""
from __future__ import annotations

import subprocess
import sys

import click

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, IO_ERROR, VALIDATION_FAILURE, register


@click.command("conventions")
@click.option("--check", is_flag=True, help="Print violations without exiting non-zero (dry-run)")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def conventions(check: bool, json: bool) -> None:
    """Run check-script-conventions.py — lint all gate scripts for R1.1–R8.2 compliance.

    Checks: docstrings (R1.1), Click over argparse (R2.1), no manual PYTHONPATH (R2.3),
    distinct exit codes (R3.3), naming (R5.1), absolute imports (R5.3), ADR reference (R5.4).

    Exit codes:
      0 = clean
      1 = validation failure (convention violations found)
      2 = I/O error
      5 = internal error
    """
    repo_root = sgate.repo.ROOT
    script = repo_root / "scripts" / "check-script-conventions.py"

    if not script.exists():
        click.echo(f"check-script-conventions.py not found at {script}", err=True)
        raise SystemExit(IO_ERROR)

    parts = [sys.executable, str(script)]
    if check:
        parts.append("--check")

    try:
        result = subprocess.run(parts, capture_output=False)
        raise SystemExit(result.returncode)
    except FileNotFoundError:
        click.echo(f"python3 not found at {sys.executable}", err=True)
        raise SystemExit(IO_ERROR)
    except Exception as exc:
        click.echo(f"Unexpected error running conventions check: {exc}", err=True)
        raise SystemExit(INTERNAL_ERROR)


# Register with the CLI group
register(conventions)
