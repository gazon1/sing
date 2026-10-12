"""sgate.commands.speckit_stale — native Click for: speckit stale changes."""
from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from typing import ClassVar

import click

import sgate
from sgate.output import gate_fail, gate_warn
from sgate.commands._runner import run_capture, Result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-independent gate
KIWI_REQUIRED: ClassVar[bool] = False

STALE_DAYS = 14


def _parse_stale_check(stdout: str | bytes, exit_code: int) -> dict:
    """Parse check-openspec-stale.py output.

    Returns:
        {
          "passed": bool,
          "stale_changes": list[dict],
          "blocking_errors": list[str],
        }
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    stdout = stdout.strip()

    # "WARNING: N active change(s) older than N days:" → stale
    # "All active OpenSpec changes are younger than N days." → pass
    # "No openspec/changes/ directory found — skipping stale check." → skip (exit 0)
    stale_changes: list[dict] = []
    blocking_errors: list[str] = []

    warning_match = re.search(
        r"WARNING:\s*(\d+)\s*active change\(s\)\s*older than\s*\d+\s*days:",
        stdout,
    )
    if warning_match:
        count = int(warning_match.group(1))
        for line in stdout.splitlines():
            m = re.match(r"\s+(\S+)/\s+\((\d+)\s*days old\)", line)
            if m:
                stale_changes.append({"name": m.group(1), "age_days": int(m.group(2))})
        blocking_errors.append(f"{count} active speckit change(s) older than {STALE_DAYS} days")

    passed = exit_code == 0 and not stale_changes

    return {
        "passed": passed,
        "stale_changes": stale_changes,
        "blocking_errors": blocking_errors,
    }


@click.command("speckit_stale")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def speckit_stale(check: bool, emit_json: bool) -> None:
    """Run the "speckit stale" gate.

    Mode: advisory

    Warns when an active speckit/OpenSpec change is older than 14 days.
    An active change lives under openspec/changes/<name>/, not under archive/.

    Exit codes:
      0 = clean (no stale changes) or skipped (no openspec dir)
      1 = stale changes detected
      2 = I/O error
    """
    repo_root = sgate.repo.ROOT
    gate_name = "speckit_stale"

    script = repo_root / "scripts" / "check-openspec-stale.py"
    if not script.exists():
        # Script may have been renamed — try the new path
        alt = repo_root / "scripts" / "check-speckit-stale.py"
        if alt.exists():
            script = alt
        else:
            gate_fail(gate_name, None, None, None, None, f"neither {script} nor {alt} found")
            raise SystemExit(IO_ERROR)

    result: Result = run_capture(
        [sys.executable, str(script)],
        gate_name + " [check-openspec-stale]",
    )

    if emit_json:
        parsed = _parse_stale_check(result.stdout, result.returncode)
        print(json.dumps({"gate": gate_name, **parsed}, indent=2))
        raise SystemExit(0 if parsed["passed"] else 1)

    if check:
        parsed = _parse_stale_check(result.stdout, result.returncode)
        if parsed["passed"]:
            gate_warn(gate_name, None, None, None, None, "no stale speckit changes")
        else:
            gate_warn(
                gate_name, None, None, None, None,
                f"{len(parsed['stale_changes'])} stale change(s)",
            )
        raise SystemExit(0)

    # Human-readable: run_capture already printed stdout
    raise SystemExit(result.returncode)


register(speckit_stale)
