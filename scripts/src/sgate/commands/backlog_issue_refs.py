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
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
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
    if lines:
        m = re.match(r"backlog-issue-refs:\s*(\d+)\s+entries,\s*(\d+)\s+open", lines[i])
        if m:
            entry_count = int(m.group(1))
            open_count = int(m.group(2))
            i += 1

    # Second line: "  snapshot: N issues (M open)"
    if i < len(lines) and lines[i].strip().startswith("snapshot:"):
        m = re.match(r"\s*snapshot:\s*(\d+)\s+issues\s+\((\d+)\s+open\)", lines[i])
        if m:
            snapshot_issue_count = int(m.group(1))
            snapshot_open_count = int(m.group(2))
            i += 1

    # Skip blank lines
    while i < len(lines) and not lines[i].strip():
        i += 1

    # ERROR: lines — format: "ERROR: {file}:{line}  {slug}  [{code}] {message}"
    # or: "ERROR: {file}:{line}  {slug}  [{code}] {rest}"
    while i < len(lines):
        line = lines[i].rstrip()
        if line.startswith("ERROR:"):
            passed = False
            # "ERROR: deferred/test.md:42  test-slug  [I1] message"
            rest = line[6:].strip()
            # Split on "  " (double-space) to separate location from message
            parts = rest.split("  ", 1)
            if len(parts) == 2:
                location = parts[0].strip()
                message = parts[1].strip()
                # location: "deferred/test.md:42"
                loc_match = re.match(r"(.+):(\d+)", location)
                if loc_match:
                    blocking_errors.append({
                        "file": loc_match.group(1),
                        "line": int(loc_match.group(2)),
                        "slug": "",
                        "code": "",
                        "message": message,
                    })
                else:
                    blocking_errors.append({
                        "file": location,
                        "line": None,
                        "slug": "",
                        "code": "",
                        "message": message,
                    })
            else:
                blocking_errors.append({"file": None, "line": None, "slug": "", "code": "", "message": rest})
            i += 1
        elif line.startswith("WARNING:"):
            # "WARNING: N open issue(s) missing Backlog: field"
            m = re.match(r"WARNING:\s*(\d+)\s+open issue\(s\)\s+missing Backlog: field", line)
            if m:
                # Warning count extracted; individual issues follow
                i += 1
                # Read individual issue lines (indented, start with "  issue #")
                while i < len(lines) and lines[i].strip().startswith("issue #"):
                    issue_line = lines[i].strip()
                    # "  issue #25  [I4] open issue has no Backlog: field — ..."
                    m_issue = re.match(r"issue #(\d+)\s+\[I4\]\s+(.+)", issue_line)
                    if m_issue:
                        warnings.append({
                            "issue_num": int(m_issue.group(1)),
                            "message": m_issue.group(2).rstrip(),
                        })
                    i += 1
                continue
            i += 1
        elif "  ok  " in line or "  ok  all blocking invariants pass" in line:
            i += 1
        elif line.startswith("check-backlog-issue-refs:") and "FAIL" in line:
            passed = False
            i += 1
        elif "⚠" in line:
            # Advisory warning summary: "  ⚠   N open issue(s) still need Backlog: field"
            i += 1
        elif re.match(r"^\s*\.\.\.\s+and\s+\d+\s+more", line):
            i += 1
        elif line.startswith("backlog-issue-refs:") or line.startswith("  snapshot:"):
            i += 1
        elif not line.strip():
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
