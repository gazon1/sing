"""sgate.commands.build_output_readers — native Click for: build-output readers precede the invocations that rewrite them."""
from __future__ import annotations

import json
import re
import sys
from typing import ClassVar

import click

import sgate
from sgate.commands._runner import Result, run, run_capture
from sgate.commands.base import INTERNAL_ERROR, IO_ERROR, register
from sgate.log import logger
from sgate.output import gate_fail

KIWI_REQUIRED: ClassVar[bool] = False

# Pattern: "check_build_output_readers: OK — every test-capable Gradle invocation …"
_OK_RE = re.compile(r"check_build_output_readers:\s+OK\b")


def _parse_build_output_readers(stdout: str | bytes, stderr: str | bytes) -> dict:
    """Parse check-build-output-readers.py output.

    Returns:
        {
          "passed": bool,
          "blocking_errors": list[str],
          "warnings": list[str],
        }
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")

    warnings: list[str] = []
    blocking_errors: list[str] = []
    for line in (stdout + "\n" + stderr).splitlines():
        stripped = line.strip()
        if stripped.startswith("check_build_output_readers: note:"):
            warnings.append(stripped)
        elif stripped.startswith("check_build_output_readers:"):
            blocking_errors.append(stripped)

    return {
        "passed": bool(_OK_RE.search(stdout)),
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


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
            result: Result = run_capture(py_cmd, gate_name)
            parsed = _parse_build_output_readers(result.stdout, result.stderr)
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


register(build_output_readers)