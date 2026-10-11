"""sgate.commands.room_schema_integrity — native Click for: room schema integrity."""
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

# Pattern: "Room schema integrity: SCHEMA_VERSION N, M exported schemas, chain continuous"
_SCHEMA_OK_RE = re.compile(
    r"Room schema integrity:\s+SCHEMA_VERSION\s+(?P<version>\d+),\s+"
    r"(?P<exported>\d+)\s+exported schemas,\s+chain continuous"
)
# Pattern: "  - path/to/file.kt: missing entity TableName"
_ENTITY_RE = re.compile(r"^\s*-\s+(.+?):\s+missing entity\s+(.+)$")


def _parse_room_schema(stdout: str | bytes, stderr: str | bytes) -> dict:
    """Parse check-room-schema-integrity.py output.

    Returns:
        {
          "passed": bool,
          "schema_version": int | None,
          "exported_count": int | None,
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
    schema_version: int | None = None
    exported_count: int | None = None

    combined = stdout + "\n" + stderr

    # Check for clean pass
    m = _SCHEMA_OK_RE.search(combined)
    if m:
        schema_version = int(m.group("version"))
        exported_count = int(m.group("exported"))

    # Look for ERROR lines
    for line in combined.splitlines():
        if line.startswith("ERROR:"):
            blocking_errors.append(line)
        elif "ERROR" in line and not blocking_errors:
            # e.g. "some error message" without ERROR: prefix
            blocking_errors.append(line.strip())

    passed = schema_version is not None and len(blocking_errors) == 0

    return {
        "passed": passed,
        "schema_version": schema_version,
        "exported_count": exported_count,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


@click.command("room_schema_integrity")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def room_schema_integrity(check: bool, emit_json: bool) -> None:
    """Run the "room schema integrity" gate.

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
    gate_name = 'room_schema_integrity'

    script = repo_root / 'scripts/check-room-schema-integrity.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]
    try:
        if emit_json:
            result: Result = run_capture(py_cmd, gate_name)
            parsed = _parse_room_schema(result.stdout, result.stderr)
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


register(room_schema_integrity)
