"""sgate.commands.coverage_holes_did_not_grow — native Click for: coverage holes did not grow."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False

# Pattern: "check-traceability-ratchet: OK — N holes, M dark_scenarios, K unreachable_cells, L carrier(s) across J spec(s)"
_RATCHET_OK_RE = re.compile(
    r"check-traceability-ratchet:\s+OK\s+—\s+"
    r"(?P<holes>\d+)\s+holes,\s+"
    r"(?P<dark_scenarios>\d+)\s+dark_scenarios,\s+"
    r"(?P<unreachable_cells>\d+)\s+unreachable_cells,\s+"
    r"(?P<carriers>\d+)\s+carrier\(s\)\s+across\s+"
    r"(?P<specs>\d+)\s+spec\(s\)"
)


def _parse_coverage_ratchet(stdout: str | bytes, stderr: str | bytes) -> dict:
    """Parse check-traceability-ratchet.py output.

    Returns:
        {
          "passed": bool,
          "holes": int | None,
          "dark_scenarios": int | None,
          "unreachable_cells": int | None,
          "carriers": int | None,
          "specs": int | None,
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

    m = _RATCHET_OK_RE.search(combined)
    if m:
        return {
            "passed": True,
            "holes": int(m.group("holes")),
            "dark_scenarios": int(m.group("dark_scenarios")),
            "unreachable_cells": int(m.group("unreachable_cells")),
            "carriers": int(m.group("carriers")),
            "specs": int(m.group("specs")),
            "blocking_errors": [],
            "warnings": [],
        }

    # Parse failure output
    for line in combined.splitlines():
        stripped = line.strip()
        if stripped.startswith("ERROR") or stripped.startswith("FAIL"):
            blocking_errors.append(stripped)

    return {
        "passed": False,
        "holes": None,
        "dark_scenarios": None,
        "unreachable_cells": None,
        "carriers": None,
        "specs": None,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
    }


@click.command("coverage_holes_did_not_grow")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def coverage_holes_did_not_grow(check: bool, emit_json: bool) -> None:
    """Run the "coverage holes did not grow" gate.

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
    gate_name = 'coverage_holes_did_not_grow'

    script = repo_root / 'scripts/check-traceability-ratchet.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    # Use system python (has yaml); uv-managed python lacks pyyaml dependency.
    py_cmd = ["/usr/bin/python3", str(script)]
    env = dict(os.environ, PYTHONPATH=str(repo_root / "infra" / "kiwi"))
    try:
        if emit_json:
            result = subprocess.run(py_cmd, capture_output=True, env=env)
            parsed = _parse_coverage_ratchet(result.stdout, result.stderr)
            if result.returncode != 0 and not parsed["blocking_errors"]:
                gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            print(json.dumps(parsed, ensure_ascii=False))
            raise SystemExit(0 if parsed["passed"] else 1)
        else:
            result = subprocess.run(py_cmd, capture_output=True, env=env)
            if result.stdout:
                sys.stdout.write(result.stdout.decode())
                sys.stdout.flush()
            if result.returncode != 0:
                gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
                raise SystemExit(result.returncode)
    except SystemExit:
        raise
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


register(coverage_holes_did_not_grow)
