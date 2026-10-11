"""sgate.commands.adr_frontmatter_drift — shim for: ADR frontmatter drift."""
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
KIWI_REQUIRED: ClassVar[bool] = False


@click.command("adr_frontmatter_drift")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def adr_frontmatter_drift(check: bool, json: bool) -> None:
    """Run the "ADR frontmatter drift" gate.

    Mode: advisory

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      3 = config error
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    scripts_dir = repo_root / 'scripts'

    static_sh = scripts_dir / 'ci' / 'static-gates.sh'
    if not static_sh.exists():
        click.echo(f"{static_sh} not found", err=True)
        raise SystemExit(IO_ERROR)

    bash_cmd = ['bash', '-c', f'. {static_sh} && adr_dry_run']

    try:
        result = subprocess.run(bash_cmd, capture_output=False)
        raise SystemExit(result.returncode)
    except FileNotFoundError:
        click.echo("bash not found", err=True)
        raise SystemExit(IO_ERROR)

    except Exception as exc:
        click.echo(f"Unexpected error: {exc}", err=True)
        raise SystemExit(INTERNAL_ERROR)

register(adr_frontmatter_drift)

