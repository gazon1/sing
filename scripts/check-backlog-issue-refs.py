#!/usr/bin/env python3
"""check-backlog-issue-refs.py — pure GitHub-issue backlog consistency gate.

Single invariant (I4) is enforced:

  I4  Every OPEN issue in the snapshot cites a backlog entry via a `Backlog:`
      field in its body. This is the reverse direction: the issue tracker is
      the queue, the backlog is the reasoning — both sides must agree.

The four original invariants (I1–I3) checked consistency between local deferred/
files and GH issues. PR #645 deleted that directory; those invariants are dead.
Only I4 remains relevant.

Output format follows K2: "current / expected / delta / reason" per finding.
Exit code 0 = all invariants pass; 1 = at least one invariant fails.

Usage:
    python3 scripts/check-backlog-issue-refs.py \\
        --snapshot config/docs/issues-snapshot.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

_BACKLOG_FIELD_RE = re.compile(r"(?i)^\s*Backlog:\s*([^\n]+)", re.M)


def _load_snapshot(path: Path) -> dict[int, dict]:
    """Load the snapshot as {issue_number: {number, state, title, body}}."""
    if not path.is_file():
        print(f"ERROR: snapshot not found: {path}", file=sys.stderr)
        print(
            "Run `just issues-refresh` to fetch the current issue state from GitHub.",
            file=sys.stderr,
        )
        sys.exit(1)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"ERROR: snapshot is not valid JSON: {exc}", file=sys.stderr)
        sys.exit(1)
    result: dict[int, dict] = {}
    for item in data:
        if "number" not in item or "state" not in item:
            print(
                f"ERROR: snapshot entry missing required field: {item!r}",
                file=sys.stderr,
            )
            sys.exit(1)
        result[int(item["number"])] = item
    return result


def _has_backlog_field(issue: dict) -> bool:
    """Return True if the issue body contains a non-empty Backlog: field."""
    body = issue.get("body") or ""
    m = _BACKLOG_FIELD_RE.search(body)
    return bool(m and m.group(1).strip())


def check_invariants(snapshot_path: Path) -> list[str]:
    """Return a list of finding lines; empty means all invariants pass."""
    snapshot = _load_snapshot(snapshot_path)
    findings: list[str] = []

    open_issues = [issue for issue in snapshot.values() if issue["state"] == "OPEN"]

    for issue in open_issues:
        if not _has_backlog_field(issue):
            findings.append(
                f"issue #{issue['number']}  [I4] open issue has no Backlog: field"
                f" — add `Backlog: <slug>` to the issue body"
            )

    return findings


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--snapshot",
        type=Path,
        default=ROOT / "config" / "docs" / "issues-snapshot.json",
        help="issue snapshot file (default: issues-snapshot.json)",
    )
    args = ap.parse_args()

    snapshot_path: Path = args.snapshot
    if not snapshot_path.is_absolute():
        snapshot_path = ROOT / snapshot_path

    findings = check_invariants(snapshot_path)

    snapshot = _load_snapshot(snapshot_path)
    open_issues = [i for i in snapshot.values() if i["state"] == "OPEN"]

    print(f"backlog-issue-refs: {len(open_issues)} open backlog issues")

    if findings:
        print("")
        for err in findings:
            print(f"ERROR: {err}")

        print("")
        print(f"check-backlog-issue-refs: FAIL — {len(findings)} open issue(s) missing Backlog: field")
        return 1

    print("  ok  all open backlog issues have a Backlog: field")
    return 0


if __name__ == "__main__":
    sys.exit(main())
