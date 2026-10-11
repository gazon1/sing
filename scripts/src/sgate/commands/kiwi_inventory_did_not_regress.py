"""sgate.commands.kiwi_inventory_did_not_regress — native Click for: kiwi inventory did not regress."""
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
KIWI_REQUIRED: ClassVar[bool] = True

# Pattern: "check-kiwi-inventory-ratchet: OK — inventory=N, skipped=M"
_OK_RE = re.compile(
    r"check-kiwi-inventory-ratchet:\s+OK\s+—\s+"
    r"inventory=(?P<inventory>\d+),\s+skipped=(?P<skipped>\d+)"
)
# Pattern: "  - metric (N) grew past floor M"
_FLOOR_RE = re.compile(
    r"^\s*-\s+(?P<metric>.+?)\s+\((?P<current>\d+)\)\s+grew past floor\s+(?P<floor>\d+)$"
)


def _parse_kiwi_inventory(stdout: str | bytes, stderr: str | bytes) -> dict:
    """Parse check-kiwi-inventory-ratchet.py output.

    Returns:
        {
          "passed": bool,
          "inventory": int | None,
          "skipped": int | None,
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
    inventory: int | None = None
    skipped: int | None = None

    combined = stdout + "\n" + stderr

    m = _OK_RE.search(combined)
    if m:
        inventory = int(m.group("inventory"))
        skipped = int(m.group("skipped"))

    for line in combined.splitlines():
        if line.startswith("ERROR:") or line.startswith("FAIL"):
            blocking_errors.append(line.strip())

    passed = inventory is not None and len(blocking_errors) == 0

    return {
        "passed": passed,
        "inventory": inventory,
        "skipped": skipped,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


@click.command("kiwi_inventory_did_not_regress")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def kiwi_inventory_did_not_regress(check: bool, emit_json: bool) -> None:
    """Run the "kiwi inventory did not regress" gate.

    Mode: advisory

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: True
    """
    repo_root = sgate.repo.ROOT
    gate_name = 'kiwi_inventory_did_not_regress'

    script = repo_root / 'scripts/check-kiwi-inventory-ratchet.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]
    try:
        if emit_json:
            result: Result = run_capture(py_cmd, gate_name)
            parsed = _parse_kiwi_inventory(result.stdout, result.stderr)
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


register(kiwi_inventory_did_not_regress)
