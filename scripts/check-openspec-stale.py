#!/usr/bin/env python3
"""
Warn when an active OpenSpec change is older than 14 days.

An active change is one that has not been archived (i.e., lives under
openspec/changes/<name>/, not under openspec/changes/archive/).
"""
import sys
import time
from pathlib import Path

STALE_DAYS = 14

def main() -> int:
    changes_dir = Path(__file__).parent.parent / "openspec" / "changes"
    if not changes_dir.exists():
        print("No openspec/changes/ directory found — skipping stale check.")
        return 0

    now = time.time()
    stale = []

    for change_dir in changes_dir.iterdir():
        if not change_dir.is_dir():
            continue
        if change_dir.name == "archive":
            continue
        if change_dir.name.startswith("."):
            continue

        mtime = change_dir.stat().st_mtime
        age_days = (now - mtime) / 86400
        if age_days > STALE_DAYS:
            stale.append((change_dir.name, int(age_days)))

    if stale:
        print(f"WARNING: {len(stale)} active change(s) older than {STALE_DAYS} days:")
        for name, age in sorted(stale):
            print(f"  openspec/changes/{name}/  ({age} days old)")
        print("Consider archiving, completing, or abandoning these changes.")
        return 1

    print(f"All active OpenSpec changes are younger than {STALE_DAYS} days.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
