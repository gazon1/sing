"""sgate.commands.adr_frontmatter_drift — shim for: ADR frontmatter drift."""
from __future__ import annotations

from typing import ClassVar

import subprocess
import sys

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands.base import (
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
    gate_name = 'adr_frontmatter_drift'

    # Call normalize-adr-frontmatter.sh directly — the bash function in static-gates.sh
    # sources all of static-gates.sh to get adr_dry_run, which is circular and re-runs
    # all gate definitions. The script itself is all we need.
    norm_sh = repo_root / 'scripts' / 'normalize-adr-frontmatter.sh'
    if not norm_sh.exists():
        logger.error(f"{norm_sh} not found")
        gate_fail(gate_name, None, None, None, None, "normalize-adr-frontmatter.sh not found")
        raise SystemExit(IO_ERROR)

    bash_cmd = ['bash', str(norm_sh)]

    try:
        result = subprocess.run(bash_cmd, capture_output=True)
        if result.stdout:
            sys.stdout.write(result.stdout.decode())
            sys.stdout.flush()
        if result.returncode != 0:
            gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            raise SystemExit(result.returncode)
    except FileNotFoundError:
        logger.error("bash not found")
        gate_fail(gate_name, None, None, None, None, "bash not found")
        raise SystemExit(IO_ERROR)

    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(adr_frontmatter_drift)

