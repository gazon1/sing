"""sgate.commands.conventions — shim for check-script-conventions.py."""
from __future__ import annotations

import subprocess
import sys

import click

import sgate
from sgate.commands.base import CLEAN, INTERNAL_ERROR, IO_ERROR, VALIDATION_FAILURE, register
from sgate.log import logger
from sgate.output import gate_fail


@click.command("conventions")
@click.option("--check", is_flag=True, help="Print violations without exiting non-zero (dry-run)")
@click.option("--json", is_flag=True, help="Emit JSON output to stdout")
def conventions(check: bool, json: bool) -> None:
    """Run check-script-conventions.py — lint the gate scripts.

    Enforces: naming (R5.1), no pass-through shell wrapper (R5.2), Click over
    argparse (R2.1).

    The rules the script declares but does not enforce (R5.3, R5.4, R2.2,
    R2.3, R3.x) are listed in its own docstring. They were removed from the
    enforced set on 2026-10-20: R5.4 could never fire, and the `--json` check
    was a no-op that claimed to enforce R2.2 and R3.7.

    Exit codes:
      0 = clean
      1 = validation failure (convention violations found)
      2 = I/O error
      5 = internal error
    """
    repo_root = sgate.repo.ROOT
    try:
        gate_name = click.get_current_context().command.name or "conventions"
    except RuntimeError:
        gate_name = "conventions"
    script = repo_root / "scripts" / "check-script-conventions.py"

    if not script.exists():
        logger.error(f"check-script-conventions.py not found at {script}")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    parts = [sys.executable, str(script)]
    if check:
        parts.append("--check")
    if json:
        parts.append("--json")

    try:
        result = subprocess.run(parts, capture_output=True)
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
        logger.exception("unexpected error running conventions check")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


# Register with the CLI group
register(conventions)
