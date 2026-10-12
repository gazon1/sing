"""sgate.commands.no_dead_doc_refs — shim for: no dead doc refs."""
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


@click.command("no_dead_doc_refs")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--skill-symbols", is_flag=True, help="Check Kotlin symbol references in skills")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def no_dead_doc_refs(check: bool, skill_symbols: bool, emit_json: bool) -> None:
    """Run the "no dead doc refs" gate.

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
    gate_name = 'no_dead_doc_refs'

    script = repo_root / 'scripts/check-doc-dead-refs.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    import sys
    py_cmd = [sys.executable, str(script)]
    if skill_symbols:
        py_cmd.append("--skill-symbols")
    try:
        if emit_json:
            result = run_capture(py_cmd, gate_name)
            _handle_result(result, gate_name, emit_json=True)
        run(py_cmd, gate_name)
    except SystemExit:
        raise  # propagate deliberate exits from run()/run_capture()
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(no_dead_doc_refs)

