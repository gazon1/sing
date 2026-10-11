"""sgate.commands.scenario_specs_valid — shim for: scenario specs valid."""
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
KIWI_REQUIRED: ClassVar[bool] = True


@click.command("scenario_specs_valid")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def scenario_specs_valid(check: bool, json: bool) -> None:
    """Run the "scenario specs valid" gate.

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
    gate_name = 'scenario_specs_valid'

    try:
        result = subprocess.run(['traceability', 'validate'], capture_output=True)
        if result.stdout:
            sys.stdout.write(result.stdout.decode())
            sys.stdout.flush()
        if result.returncode != 0:
            gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            raise SystemExit(result.returncode)
    except FileNotFoundError:
        logger.error("traceability not found on PATH")
        gate_fail(gate_name, None, None, None, None, "traceability not found on PATH")
        raise SystemExit(IO_ERROR)

    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(scenario_specs_valid)

