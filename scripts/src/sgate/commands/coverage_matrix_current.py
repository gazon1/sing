"""sgate.commands.coverage_matrix_current — shim for: coverage matrix current."""
from __future__ import annotations

from typing import ClassVar

import click
import subprocess
import sys

import sgate
from sgate.commands.base import (
    CLEAN,
    INTERNAL_ERROR,
    IO_ERROR,
    VALIDATION_FAILURE,
    register,
)

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = True


@click.command("coverage_matrix_current")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def coverage_matrix_current(check: bool, json: bool) -> None:
    """Run the "coverage matrix current" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      3 = config error
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: True
    """
    repo_root = sgate.repo.ROOT
    scripts_dir = repo_root / 'scripts'

    try:
        result = subprocess.run(['traceability', 'coverage', '--check'], capture_output=False)
        raise SystemExit(result.returncode)
    except FileNotFoundError:
        click.echo("traceability not found on PATH", err=True)
        raise SystemExit(IO_ERROR)

    except Exception as exc:
        click.echo(f"Unexpected error: {exc}", err=True)
        raise SystemExit(INTERNAL_ERROR)

register(coverage_matrix_current)

