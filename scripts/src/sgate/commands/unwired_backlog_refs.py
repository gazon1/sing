"""sgate.commands.unwired_backlog_refs — shim for: unwired-backlog-refs."""
from __future__ import annotations

import json
import re
import sys
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False

# ── output parser ──────────────────────────────────────────────────────────────


def _parse_unwired_backlog_refs(stdout: str | bytes) -> dict:
    """Parse human-readable output from check-unwired-backlog-refs.py.

    Returns a dict with:
      - passed: bool
      - errors: list of {file, lineno, symbol, message}
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    lines = stdout.splitlines()
    passed = True
    errors: list[dict] = []

    for line in lines:
        if "  ok  " in line or line.startswith("check-unwired-backlog-refs: OK"):
            passed = True
            continue
        if line.startswith("ERROR:"):
            passed = False
            # "ERROR: find-unwired-surfaces-baseline.txt:42: symbol has malformed BacklogRef ..."
            rest = line[6:].strip()
            # Try: "filename:lineno: symbol  message"
            m = re.match(r"([^:]+):(\d+):\s*(.+?)\s+[-–]\s+(.+)", rest)
            if m:
                errors.append({
                    "file": m.group(1),
                    "lineno": int(m.group(2)),
                    "symbol": m.group(3),
                    "message": m.group(4),
                })
            else:
                errors.append({"file": None, "lineno": None, "symbol": "", "message": rest})

    return {
        "gate": "unwired-backlog-refs",
        "passed": passed,
        "errors": errors,
    }


@click.command("unwired_backlog_refs")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def unwired_backlog_refs(check: bool, emit_json: bool) -> None:
    """Run the "unwired-backlog-refs" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = "unwired_backlog_refs"

    script = repo_root / "scripts/check-unwired-backlog-refs.py"
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]

    try:
        if emit_json:
            result = run_capture(py_cmd, gate_name)
            parsed = _parse_unwired_backlog_refs(result.stdout.decode())
            parsed["exit_code"] = result.returncode
            if result.returncode != 0:
                gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            print(json.dumps(parsed, indent=2))
            raise SystemExit(result.returncode)
        else:
            run(py_cmd, gate_name)
    except SystemExit:
        raise
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


register(unwired_backlog_refs)
