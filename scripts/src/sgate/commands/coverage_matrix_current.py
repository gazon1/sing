"""sgate.commands.coverage_matrix_current — shim for: coverage matrix current."""
from __future__ import annotations

import json
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = True


@click.command("coverage_matrix_current")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def coverage_matrix_current(check: bool, emit_json: bool) -> None:
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
    gate_name = 'coverage_matrix_current'

    try:
        if emit_json:
            result = run_capture(['traceability', 'coverage', '--check'], gate_name)
            out = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
            data = {"gate": gate_name, "passed": result.returncode == 0, "exit_code": result.returncode, "output": out}
            if result.returncode != 0:
                gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            print(json.dumps(data))
            raise SystemExit(result.returncode)
        run(['traceability', 'coverage', '--check'], gate_name)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(coverage_matrix_current)

