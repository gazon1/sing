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
from sgate.commands._parsers import parse_header, skip_blanks, parse_error_block
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

KIWI_REQUIRED: ClassVar[bool] = False

# ── output parser ──────────────────────────────────────────────────────────────


def _parse_backlog_issue_refs(stdout: str | bytes) -> dict:
    """Parse human-readable output from check-backlog-issue-refs.py.

    Returns a dict with:
      - entry_count, open_count, snapshot_issue_count, snapshot_open_count
      - blocking_errors: list of {file, line, slug, code, message}
      - warnings: list of {issue_num, message}
      - passed: bool
    """
    if isinstance(stdout, bytes):
        stdout = stdout.decode("utf-8", errors="replace")
    lines = stdout.splitlines()
    entry_count = open_count = snapshot_issue_count = snapshot_open_count = 0
    blocking_errors: list[dict] = []
    warnings: list[dict] = []
    passed = True

    i = 0

    # First line: "backlog-issue-refs: N entries, M open"
    hdr, i = parse_header(lines, i, re.compile(r"backlog-issue-refs:\s*(?P<count>\d+)\s+entries,\s*(?P<open>\d+)\s+open"))
    if hdr:
        entry_count = int(hdr["count"])
        open_count = int(hdr["open"])

    # Second line: "  snapshot: N issues (M open)"
    hdr2, i = parse_header(lines, i, re.compile(r"\s*snapshot:\s*(?P<count>\d+)\s+issues\s+\((?P<open>\d+)\s+open\)"))
    if hdr2:
        snapshot_issue_count = int(hdr2["count"])
        snapshot_open_count = int(hdr2["open"])

    i = skip_blanks(lines, i)

    # ERROR: lines — use shared helper
    for file, line_num, message in parse_error_block(lines, i):
        passed = False
        blocking_errors.append({
            "file": file,
            "line": line_num,
            "slug": "",
            "code": "",
            "message": message,
        })

    # Skip to end of parsed error block
    while i < len(lines) and lines[i].rstrip().startswith("ERROR:"):
        i += 1

    # Remaining warning lines
    while i < len(lines):
        line = lines[i].rstrip()
        if line.startswith("WARNING:"):
            m = re.match(r"WARNING:\s*(\d+)\s+open issue\(s\)\s+missing Backlog: field", line)
            if m:
                i += 1
                while i < len(lines) and lines[i].strip().startswith("issue #"):
                    issue_line = lines[i].strip()
                    m_issue = re.match(r"issue #(\d+)\s+\[I4\]\s+(.+)", issue_line)
                    if m_issue:
                        warnings.append({
                            "issue_num": int(m_issue.group(1)),
                            "message": m_issue.group(2).rstrip(),
                        })
                    i += 1
                continue
        if not line or "  ok  " in line or "⚠" in line or line.startswith("backlog-issue-refs:"):
            i += 1
        else:
            i += 1

    return {
        "gate": "backlog-issue-refs",
        "entry_count": entry_count,
        "open_entry_count": open_count,
        "snapshot_issue_count": snapshot_issue_count,
        "snapshot_open_count": snapshot_open_count,
        "passed": passed,
        "blocking_errors": blocking_errors,
        "warnings": warnings,
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
            # Capture output and emit structured JSON
            result = run_capture(py_cmd, gate_name)
            parsed = _parse_backlog_issue_refs(result.stdout)
            parsed["exit_code"] = result.returncode

            # Emit JSON to stdout; gate_fail still fires for CI annotations
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
