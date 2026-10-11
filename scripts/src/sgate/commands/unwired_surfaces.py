"""sgate.commands.unwired_surfaces — shim for: unwired surfaces."""
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


@click.command("unwired_surfaces")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def unwired_surfaces(check: bool, json: bool) -> None:
    """Run the "unwired surfaces" gate.

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
    gate_name = 'unwired_surfaces'

    script = repo_root / 'scripts/find-unwired-surfaces.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]
    py_cmd.extend(['--quiet'])

    try:
        result = subprocess.run(py_cmd, capture_output=True)
        if result.stdout:
            sys.stdout.write(result.stdout.decode())
            sys.stdout.flush()
        if result.returncode != 0:
            gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            raise SystemExit(result.returncode)
    except FileNotFoundError:
        logger.error(f"python3 not found at {sys.executable}")
        gate_fail(gate_name, None, None, None, None, "python3 not found")
        raise SystemExit(IO_ERROR)

    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(unwired_surfaces)

