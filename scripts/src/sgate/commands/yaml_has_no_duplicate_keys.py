"""sgate.commands.yaml_has_no_duplicate_keys — native Click for: YAML has no duplicate keys."""
from __future__ import annotations

import json
import re
import sys
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False

# Pattern: "  - path/to/file.yaml: duplicate key 'foo'"
_DUPLICATE_RE = re.compile(r"^\s*-\s+(.+?):\s+duplicate key\s+'([^']+)'$")


def _parse_yaml_duplicate_keys(stdout: str | bytes, stderr: str | bytes) -> dict:
    """Parse check-yaml-duplicate-keys.py --quiet output.

    Returns:
        {
          "passed": bool,
          "duplicate_count": int,
          "duplicates": list[{"file": str, "key": str}],
          "blocking_errors": list[str],
          "warnings": list[str],
        }
    """
    if isinstance(stderr, bytes):
        stderr = stderr.decode("utf-8", errors="replace")
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    blocking_errors: list[str] = []
    warnings: list[str] = []
    duplicates: list[dict] = []

    # stderr contains "check-yaml-duplicate-keys: N duplicate key(s)\n  - file: detail"
    # and then a explanatory message
    for line in stderr.splitlines():
        m = _DUPLICATE_RE.match(line)
        if m:
            file_path = m.group(1)
            key = m.group(2)
            duplicates.append({"file": file_path, "key": key})

    if duplicates:
        blocking_errors.append(f"{len(duplicates)} file(s) have duplicate YAML keys")

    return {
        "passed": len(duplicates) == 0,
        "duplicate_count": len(duplicates),
        "duplicates": duplicates,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


@click.command("yaml_has_no_duplicate_keys")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def yaml_duplicate_keys(check: bool, emit_json: bool) -> None:
    """Run the "YAML has no duplicate keys" gate.

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
    gate_name = 'yaml_duplicate_keys'

    script = repo_root / 'scripts/check-yaml-duplicate-keys.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script), '--quiet']
    try:
        result: Result = run_capture(py_cmd, gate_name)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

    if emit_json:
        parsed = _parse_yaml_duplicate_keys(result.stdout, result.stderr)
        # gate_fail still fires for blocking errors via ::error annotations
        print(json.dumps(parsed, ensure_ascii=False))
        raise SystemExit(0 if parsed["passed"] else 1)

    # Human-readable: run() already printed stdout, gate_fail already fired
    raise SystemExit(0 if result.returncode == 0 else 1)


register(yaml_duplicate_keys)
