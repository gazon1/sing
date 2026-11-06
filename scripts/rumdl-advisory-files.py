#!/usr/bin/env python3
"""Advisory rumdl check over an explicit newline-delimited file list (pre-push).

No git change detection — the hook provides the touched file list. Skips when
rumdl is not installed or the list is empty. Always exits 0 (advisory).
"""
from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--files-from", required=True)
    ap.add_argument("--advisory", action="store_true")  # accepted for hook compat
    args = ap.parse_args(argv)

    rumdl = shutil.which("rumdl")
    if rumdl is None:
        print("rumdl-advisory: rumdl not installed — skipping.")
        return 0

    p = pathlib.Path(args.files_from)
    if not p.is_file():
        print("rumdl-advisory: no file list — skipping.")
        return 0

    files = []
    for line in p.read_text(encoding="utf-8", errors="ignore").splitlines():
        rel = line.strip()
        if rel.endswith(".md") and pathlib.Path(rel).is_file():
            files.append(rel)

    if not files:
        print("rumdl-advisory: no Markdown files to check.")
        return 0

    print(f"rumdl-advisory: checking {len(files)} Markdown file(s)…")
    r = subprocess.run([rumdl, "check"] + files, check=False)
    if r.returncode == 0:
        print("rumdl-advisory: nothing to report.")
    else:
        print(
            "rumdl-advisory: findings (advisory) — fix with: just mdfmt-changed or rumdl check --fix <files>",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
