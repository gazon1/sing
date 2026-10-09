#!/usr/bin/env python3
"""refresh-issues-snapshot.py — fetch all issues and write a local snapshot.

Why this exists (2026-10-07): the backlog↔issues gate (K4) must run without
network access. A local snapshot of the issue tracker is committed to the repo
and refreshed by `just issues-refresh`. The gate reads the snapshot; the
snapshot is regenerated on demand.

The snapshot is **not** generated during the gate run — that would make the
gate non-deterministic (a network failure or API rate-limit turns it red) and
ties the CI run to a network dependency it is supposed to replace. The snapshot
is a committed artifact; the gate is a pure read of that artifact.

Usage:
    python3 scripts/refresh-issues-snapshot.py [--output <path>]

The `--output` path is always written relative to the repository root, even
when the script is run from a different directory.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = ROOT / "config" / "docs" / "issues-snapshot.json"


def run_gh(args: list[str]) -> list[dict]:
    """Call `gh issue list` and return parsed JSON, or exit with an error."""
    cmd = ["gh", "issue", "list", "--state", "all", "--json", "number,state,title,body", "--limit", "300"]
    try:
        result = subprocess.run(
            cmd + args,
            capture_output=True,
            text=True,
            check=True,
        )
    except FileNotFoundError:
        print("ERROR: `gh` is not installed. Install it from https://cli.github.com/", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as exc:
        print(f"ERROR: `gh issue list` exited {exc.returncode}", file=sys.stderr)
        if exc.stderr:
            print(exc.stderr, file=sys.stderr)
        sys.exit(1)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        print(f"ERROR: `gh issue list` returned non-JSON: {exc}", file=sys.stderr)
        print(f"stdout: {result.stdout[:500]}", file=sys.stderr)
        sys.exit(1)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"output path (default: {DEFAULT_OUTPUT.relative_to(ROOT)})",
    )
    ap.add_argument(
        "--dry-run",
        action="store_true",
        help="print what would be written without writing it",
    )
    args = ap.parse_args()

    output: Path = args.output
    # Resolve relative paths against ROOT even when cwd is elsewhere
    if not output.is_absolute():
        output = ROOT / output

    issues = run_gh(["--limit", "300"])

    # Normalise: keep only the fields the gate reads; drop large body content
    # beyond what the gate actually inspects (first 500 chars are enough for
    # the Backlog: slug check).
    snapshot = [
        {
            "number": issue["number"],
            "state": issue["state"],
            "title": issue["title"],
            "backlog_slug": _extract_backlog_slug(issue.get("body", "")),
        }
        for issue in sorted(issues, key=lambda i: i["number"])
    ]

    n_open = sum(1 for i in snapshot if i["state"] == "OPEN")
    n_closed = len(snapshot) - n_open
    print(f"fetched {len(snapshot)} issues from GitHub ({n_open} open, {n_closed} closed)")
    print(f"snapshot: {output.relative_to(ROOT)}")

    if args.dry_run:
        print(f"[dry-run] would write {len(snapshot)} entries to {output}")
        for issue in snapshot[:5]:
            print(f"  #{issue['number']} {issue['state']:6s}  {issue['title'][:60]}")
        if len(snapshot) > 5:
            print(f"  ... and {len(snapshot) - 5} more")
        return 0

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(snapshot, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"written: {output.relative_to(ROOT)}")
    return 0


_BACKLOG_RE = __import__("re").compile(r"(?i)^\s*Backlog:\s*([^\n]+)", __import__("re").M)


def _extract_backlog_slug(body: str) -> str:
    """Extract the backlog slug from an issue body.

    Convention: an issue that tracks a backlog entry carries a line:
        Backlog: <slug>

    The slug is the lowercased kebab-case heading of the backlog entry, e.g.
    `log-export-has-no-surface` for the entry `## log-export-has-no-surface`.
    Whitespace is stripped; empty if no field is present.
    """
    m = _BACKLOG_RE.search(body or "")
    return m.group(1).strip() if m else ""


if __name__ == "__main__":
    sys.exit(main())
