"""sgate.commands.sync_allowlist_seed_matches_contract — shim for: sync allowlist seed matches contract."""
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


@click.command("sync_allowlist_seed_matches_contract")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def sync_allowlist_seed_matches_contract(check: bool, json: bool) -> None:
    """Run the "sync allowlist seed matches contract" gate.

    Mode: blocking

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

    script = repo_root / 'scripts/check-sync-allowlist-regenerated.py'
    if not script.exists():
        click.echo(f"{script} not found", err=True)
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]

    try:
        result = subprocess.run(py_cmd, capture_output=False)
        raise SystemExit(result.returncode)
    except FileNotFoundError:
        click.echo(f"python3 not found at {sys.executable}", err=True)
        raise SystemExit(IO_ERROR)

    except Exception as exc:
        click.echo(f"Unexpected error: {exc}", err=True)
        raise SystemExit(INTERNAL_ERROR)

register(sync_allowlist_seed_matches_contract)

