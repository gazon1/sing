"""sgate.commands.build_output_readers — native Click for: build-output readers precede the invocations that rewrite them."""
from __future__ import annotations

import sys
from typing import ClassVar

import click

import sgate
from sgate.commands._runner import run_gate_command, run_json_contract
from sgate.commands.base import INTERNAL_ERROR, IO_ERROR, register
from sgate.log import logger
from sgate.output import gate_fail

KIWI_REQUIRED: ClassVar[bool] = False


@click.command("build_output_readers")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def build_output_readers(check: bool, emit_json: bool) -> None:
    """Run the "build-output readers precede the invocations that rewrite them" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = 'build_output_readers'

    script = repo_root / 'scripts/check-build-output-readers.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]
    try:
        if emit_json:
            # The producer speaks JSON; the verdict is read, not re-derived from
            # the wording of its output.
            payload, returncode = run_json_contract(py_cmd + ["--json"], gate_name)
            if check:
                # Dry run: report the verdict, do not fail the run. The verdict
                # is the honest one, so a producer that exited non-zero cannot
                # be read as "passed" out of a check-mode report.
                print(payload.honest_payload(returncode).model_dump_json(indent=2))
                raise SystemExit(0)
            payload.emit_and_exit(returncode, emit_json=True)
        else:
            run_gate_command(py_cmd, gate_name, check=check)
    except SystemExit:
        raise
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


register(build_output_readers)
