"""sgate.commands.adr_status_policy — shim for: ADR status policy."""
from __future__ import annotations

from typing import ClassVar

import click

import sgate
from sgate.commands._runner import handle_result, run_capture, run_gate_command
from sgate.commands.base import INTERNAL_ERROR, IO_ERROR, KIWI_UNAVAILABLE, register
from sgate.log import logger
from sgate.output import gate_fail

KIWI_REQUIRED: ClassVar[bool] = False

@click.command("adr_status_policy")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def adr_status_policy(check: bool, emit_json: bool) -> None:
    """Run the "ADR status policy" gate.

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
    gate_name = 'adr_status_policy'

    script = repo_root / 'scripts/check_adr_status.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    import sys
    py_cmd = [sys.executable, str(script)]
    try:
        if emit_json:
            result = run_capture(py_cmd, gate_name)
            handle_result(result, gate_name, check=check)
        run_gate_command(py_cmd, gate_name, check=check)
    except SystemExit:
        raise  # propagate deliberate exits from run()/run_capture()
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(adr_status_policy)

