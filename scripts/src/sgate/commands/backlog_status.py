"""sgate.commands.backlog_status — shim for: backlog-status."""
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


def _parse_backlog_status(stdout: str | bytes) -> dict:
    """Parse human-readable output from check-backlog-status.py.

    Returns a dict with:
      - entry_count, budget, counts: {open, partial, closed, unclassifiable}
      - unclassifiable_entries: list of {file, line, slug, raw_status}
      - untracked_open_entries: list of {file, line, slug}
      - over_budget: bool
      - passed: bool
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    lines = stdout.splitlines()
    entry_count = 0
    budget = 0
    counts: dict[str, int] = {}
    unclassifiable_entries: list[dict] = []
    untracked_open_entries: list[dict] = []
    over_budget = False
    passed = True
    errors_collected: list[str] = []

    i = 0
    # First line: "docs/decisions/deferred: N entries (budget M)"
    if lines:
        m = re.match(r"([^:]+):\s*(\d+)\s+entries\s+\(budget\s+(\d+)\)", lines[i])
        if m:
            entry_count = int(m.group(2))
            budget = int(m.group(3))
            over_budget = entry_count > budget
            i += 1

    # Count lines: "  open            36"
    while i < len(lines) and lines[i].strip():
        m = re.match(r"\s*(\w+)\s+(\d+)", lines[i])
        if m:
            counts[m.group(1)] = int(m.group(2))
        i += 1

    # Skip blank lines
    while i < len(lines) and not lines[i].strip():
        i += 1

    # Parse ERROR blocks
    while i < len(lines):
        line = lines[i].rstrip()
        if line.startswith("ERROR: check-backlog-status:"):
            passed = False
            # Multi-line error block
            i += 1
            while i < len(lines) and lines[i].startswith("  "):
                detail = lines[i].strip()
                # Format: "docs/decisions/deferred:42  slug   (status reads: 'OPEN')"
                # or: "docs/decisions/deferred:42  slug   (no Status line)"
                m = re.match(r"([^:]+):(\d+)\s+(.+?)\s+\(([^)]+)\)", detail)
                if m:
                    entry = {
                        "file": m.group(1),
                        "line": int(m.group(2)),
                        "slug": m.group(3),
                        "raw_status": m.group(4),
                    }
                    if "unclassifiable" in detail or "no Status line" in detail:
                        unclassifiable_entries.append(entry)
                    else:
                        untracked_open_entries.append(entry)
                i += 1
            i += 1  # skip blank after error block
        elif "  ok  " in line:
            i += 1
        elif not line.strip():
            i += 1
        else:
            i += 1

    return {
        "gate": "backlog-status",
        "entry_count": entry_count,
        "budget": budget,
        "counts": counts,
        "passed": passed,
        "over_budget": over_budget,
        "unclassifiable_entries": unclassifiable_entries,
        "untracked_open_entries": untracked_open_entries,
    }


@click.command("backlog_status")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def backlog_status(check: bool, emit_json: bool) -> None:
    """Run the "backlog-status" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = "backlog_status"

    script = repo_root / "scripts/check-backlog-status.py"
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]

    try:
        if emit_json:
            result = run_capture(py_cmd, gate_name)
            parsed = _parse_backlog_status(result.stdout)
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


register(backlog_status)
