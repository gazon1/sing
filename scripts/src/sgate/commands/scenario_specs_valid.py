"""sgate.commands.scenario_specs_valid — shim for: scenario specs valid."""
from __future__ import annotations

import json
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, KIWI_UNAVAILABLE, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = True

def _handle_result(result: Result, gate_name: str, *, emit_json: bool) -> None:
    """Handle run_capture result, applying KIWI_UNAVAILABLE logic for Kiwi gates."""
    # For KIWI_REQUIRED gates: if the command was not found (FileNotFoundError
    # caught in run_capture), treat it as KIWI_UNAVAILABLE (soft skip, exit 4)
    # rather than a hard failure (exit 127).
    kiwi_unavailable = (
        KIWI_REQUIRED and
        getattr(result, '_cmd_not_found', False)
    )
    if kiwi_unavailable:
        out = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
        data = {"gate": gate_name, "passed": True, "exit_code": 4, "output": out,
                "skipped": True, "reason": "kiwi not installed"}
        gate_fail(gate_name, None, None, None, None, "kiwi unavailable", to_stderr=True)
        print(json.dumps(data))
        raise SystemExit(KIWI_UNAVAILABLE)
    out = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
    data = {"gate": gate_name, "passed": result.returncode == 0, "exit_code": result.returncode, "output": out}
    if result.returncode != 0:
        gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}", to_stderr=True)
    print(json.dumps(data))
    raise SystemExit(result.returncode)


@click.command("scenario_specs_valid")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def scenario_specs_valid(check: bool, emit_json: bool) -> None:
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
        if emit_json:
            result = run_capture(['traceability', 'validate'], gate_name)
            _handle_result(result, gate_name, emit_json=True)
        run(['traceability', 'validate'], gate_name)
    except SystemExit:
        raise  # propagate deliberate exits from run()/run_capture()
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(scenario_specs_valid)

