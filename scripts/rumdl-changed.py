#!/usr/bin/env python3
"""rumdl-changed.py — rumdl over the Markdown files this change touches.

Mirrors lint-changed.py's scope (merge-base + working tree + untracked) via
scripts.git_changed, filtered to *.md. Uses rumdl config (.rumdl.toml).

Usage:
    python3 scripts/rumdl-changed.py                  # check changed md
    python3 scripts/rumdl-changed.py --fix            # format changed md
    python3 scripts/rumdl-changed.py --staged          # check staged md only
    python3 scripts/rumdl-changed.py --staged --fix    # format staged md
    python3 scripts/rumdl-changed.py --base main
    python3 scripts/rumdl-changed.py --list
    python3 scripts/rumdl-changed.py --files-from f   # explicit file list

Exit: 0 ok/nothing (or advisory findings, or skip without --require),
      1 findings/format needed (or rumdl missing with --require).
"""

from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from git_changed import changed_paths  # noqa: E402


def files_from(path: str, root: pathlib.Path) -> list[str]:
    p = pathlib.Path(path)
    if not p.is_file():
        return []
    out: list[str] = []
    for line in p.read_text(encoding="utf-8", errors="ignore").splitlines():
        rel = line.strip()
        if not rel:
            continue
        if rel.endswith(".md") and (root / rel).is_file():
            out.append(rel)
    # dedupe preserving order
    seen: set[str] = set()
    res: list[str] = []
    for r in out:
        if r not in seen:
            seen.add(r)
            res.append(r)
    return res


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    ap.add_argument("--fix", action="store_true", help="Format files (rumdl fmt) instead of checking.")
    ap.add_argument("--staged", action="store_true", help="Only staged *.md files (for pre-commit).")
    ap.add_argument("--base", default=os.environ.get("LINT_BASE", "origin/main"))
    ap.add_argument("--list", action="store_true", help="Print files and exit.")
    ap.add_argument(
        "--advisory",
        action="store_true",
        help="Report findings but exit 0 (for non-blocking hooks/gates).",
    )
    ap.add_argument(
        "--require",
        action="store_true",
        help="Fail if rumdl is not installed (for CI/enforced mode). Default: skip.",
    )
    ap.add_argument("--files-from", help="Read newline-delimited file paths from this file.")
    ap.add_argument("files", nargs="*", help="Explicit files (bypass git discovery).")
    args = ap.parse_args(argv)

    rumdl = shutil.which("rumdl")
    if rumdl is None:
        msg = "rumdl-changed: rumdl is not installed (run: mise install)."
        if args.require:
            print(msg, file=sys.stderr)
            return 1
        print(msg + " — skipping markdown check.")
        return 0

    cwd = ROOT
    if args.files_from:
        # Explicit list (e.g. pre-push touched files): resolve relative to cwd.
        cwd = pathlib.Path.cwd()
        files = files_from(args.files_from, cwd)
    elif args.files:
        cwd = pathlib.Path.cwd()
        files = []
        for f in args.files:
            if not f.endswith(".md"):
                continue
            cand = pathlib.Path(f)
            if cand.is_file() or (cwd / f).is_file() or (ROOT / f).is_file():
                files.append(f)
        # dedupe
        seen: set[str] = set()
        files = [f for f in files if not (f in seen or seen.add(f))]
    else:
        files = changed_paths(args.base, pathspec=("*.md",), staged_only=args.staged, root=ROOT)

    if not files:
        print("rumdl-changed: no Markdown files to check.")
        return 0

    if args.list:
        print("\n".join(files))
        return 0

    cmd = [rumdl, "fmt" if args.fix else "check"] + files
    verb = "formatting" if args.fix else "checking"
    print(f"rumdl-changed: {verb} {len(files)} Markdown file(s)…")
    r = subprocess.run(cmd, cwd=cwd, check=False)
    if r.returncode == 0:
        print("rumdl-changed: nothing to report." if not args.fix else "rumdl-changed: formatted.")
        return 0
    if args.fix:
        print("rumdl-changed: rumdl fmt reported issues.", file=sys.stderr)
        return 1
    print(
        "rumdl-changed: findings — fix with: python3 scripts/rumdl-changed.py --fix "
        "(or: rumdl fmt <files>)",
        file=sys.stderr,
    )
    if args.advisory:
        print("rumdl-changed: advisory mode — not failing.", file=sys.stderr)
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
