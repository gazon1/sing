"""sgate.commands.skills_catalog_current — shim for: skills catalog current."""
from __future__ import annotations

import json
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, KIWI_UNAVAILABLE, register

KIWI_REQUIRED: ClassVar[bool] = False

def _handle_result(result: Result, gate_name: str, *, emit_json: bool) -> None:
    """Handle run_capture result for non-Kiwi gates."""
    out = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
    data = {"gate": gate_name, "passed": result.returncode == 0, "exit_code": result.returncode, "output": out}
    if result.returncode != 0:
        gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}", to_stderr=True)
    print(json.dumps(data))
    raise SystemExit(result.returncode)


@click.command("skills_catalog_current")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def skills_catalog_current(check: bool, emit_json: bool) -> None:
    """Run the "skills catalog current" gate.

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
    gate_name = 'skills_catalog_current'

    try:
        if emit_json:
            result = run_capture(['./scripts/regen-skills-catalog.sh', '--check'], gate_name)
            _handle_result(result, gate_name, emit_json=True)
        run(['./scripts/regen-skills-catalog.sh', '--check'], gate_name)
    except SystemExit:
        raise  # propagate deliberate exits from run()/run_capture()
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(skills_catalog_current)

