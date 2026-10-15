"""sgate.commands.backlog_issue_refs — shim for: backlog-issue-refs."""
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
from sgate.commands._parsers import parse_error_block
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

KIWI_REQUIRED: ClassVar[bool] = False

# ── output parser ──────────────────────────────────────────────────────────────


def _parse_backlog_issue_refs(stdout: str | bytes) -> dict:
    """Parse human-readable output from check-backlog-issue-refs.py.

    Returns a dict with:
      - open_count: number of open backlog issues in the snapshot
      - blocking_errors: list of {file, line, slug, code, message}
      - warnings: list of {issue_num, message}
      - passed: bool
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    lines = stdout.splitlines()
    open_count = 0
    blocking_errors: list[dict] = []
    passed = True

    i = 0

    # First line: "backlog-issue-refs: N open backlog issues"
    if lines and re.match(r"backlog-issue-refs:\s*\d+\s+open backlog issues", lines[i].strip()):
        m = re.search(r"\d+", lines[i])
        if m:
            open_count = int(m.group())
        i += 1

    i_skip = i
    # ERROR: lines
    for file, line_num, message in parse_error_block(lines, i):
        passed = False
        blocking_errors.append({
            "file": file,
            "line": line_num,
            "slug": "",
            "code": "",
            "message": message,
        })
    if not passed:
        # Count ERROR lines consumed
        while i_skip < len(lines) and lines[i_skip].rstrip().startswith("ERROR:"):
            i_skip += 1

    return {
        "gate": "backlog-issue-refs",
        "open_count": open_count,
        "passed": passed,
        "blocking_errors": blocking_errors,
        "warnings": [],
    }


@click.command("backlog_issue_refs")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def backlog_issue_refs(check: bool, emit_json: bool) -> None:
    """Run the "backlog-issue-refs" gate.

    Mode: advisory

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = "backlog_issue_refs"

    script = repo_root / "scripts/check-backlog-issue-refs.py"
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]

    try:
        if emit_json:
            result = run_capture(py_cmd, gate_name)
            parsed = _parse_backlog_issue_refs(result.stdout)
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


register(backlog_issue_refs)
