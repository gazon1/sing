"""sgate.commands.test_task_external_inputs_declared — native Click for: test-task external inputs declared."""
from __future__ import annotations

import json
import re
import sys
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False

# Pattern: "check_test_task_inputs.py: OK — N external root(s) checked, all declared as task inputs"
_OK_RE = re.compile(
    r"check_test_task_inputs\.py:\s+OK\s+—\s+"
    r"(?P<checked>\d+)\s+external root\(s\) checked,\s+"
    r"all declared as task inputs"
)


def _parse_test_task_inputs(stdout: str | bytes, stderr: str | bytes) -> dict:
    """Parse check-test-task-inputs.py output.

    Returns:
        {
          "passed": bool,
          "checked": int | None,
          "blocking_errors": list[str],
          "warnings": list[str],
        }
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")

    blocking_errors: list[str] = []
    warnings: list[str] = []

    combined = stdout + "\n" + stderr

    m = _OK_RE.search(combined)
    if m:
        checked = int(m.group("checked"))
        return {
            "passed": True,
            "checked": checked,
            "blocking_errors": [],
            "warnings": [],
        }

    # Parse failure output
    for line in combined.splitlines():
        stripped = line.strip()
        if stripped.startswith("ERROR:") or stripped.startswith("check_test_task_inputs.py:"):
            blocking_errors.append(stripped)

    return {
        "passed": False,
        "checked": None,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


@click.command("test_task_external_inputs_declared")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def test_task_external_inputs_declared(check: bool, emit_json: bool) -> None:
    """Run the "test-task external inputs declared" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = 'test_task_external_inputs_declared'

    script = repo_root / 'scripts/check-test-task-inputs.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]
    try:
        if emit_json:
            result: Result = run_capture(py_cmd, gate_name)
            parsed = _parse_test_task_inputs(result.stdout, result.stderr)
            if result.returncode != 0 and not parsed["blocking_errors"]:
                gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            print(json.dumps(parsed, ensure_ascii=False))
            raise SystemExit(0 if parsed["passed"] else 1)
        else:
            run(py_cmd, gate_name)
    except SystemExit:
        raise
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


register(test_task_external_inputs_declared)
