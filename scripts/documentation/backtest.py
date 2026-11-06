#!/usr/bin/env python3
"""One-off backtest harness: for each commit in a range, list affected concepts.

Usage: python3 scripts/documentation/backtest.py <rev-range>  (e.g. HEAD~30..HEAD)
Output: CSV commit,changed_files,affected_concepts
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402


def commits(rng):
    out = subprocess.run(
        ["git", "-C", str(ROOT), "rev-list", "--reverse", rng],
        capture_output=True, text=True, check=True,
    )
    return [c for c in out.stdout.split() if c]


def changed_files(commit):
    out = subprocess.run(
        ["git", "-C", str(ROOT), "diff-tree", "--no-commit-id", "--name-only", "-r", commit],
        capture_output=True, text=True, check=True,
    )
    return [f for f in out.stdout.split() if f]


def main():
    if len(sys.argv) != 2:
        print("usage: backtest.py <rev-range>", file=sys.stderr)
        sys.exit(2)
    rng = sys.argv[1]
    print("commit,changed,affected")
    for c in commits(rng):
        files = changed_files(c)
        affected = set()
        for f in files:
            # reuse affected logic by matching source paths
            for p in (ROOT / "knowledge").rglob("*.md"):
                try:
                    fm, body, _ = check.parse_concept(p)
                except Exception:
                    continue
                for s in (fm.get("sources") or []):
                    sp = check.resolve_source(s["resource"])
                    if sp is not None and sp.relative_to(ROOT).as_posix() == f:
                        affected.add(p.stem)
        print(f"{c[:8]},{len(files)},{'|'.join(sorted(affected))}")


if __name__ == "__main__":
    main()
