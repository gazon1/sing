#!/usr/bin/env python3
"""wrap-long-signatures.py — wrap parameter lists on lines that exceed the limit.

Context (2026-10-04): migrating `runCatching` to `runCatchingCancellable` adds ten
characters to the identifier. On a one-line expression-bodied signature that pushed
16 lines past detekt's 120-character limit, which is why this exists — the migration
itself is correct and the formatting has to follow it.

Only lines matching a single-expression `= runCatchingCancellable {` body are
touched, and any parameter list containing nested parentheses is skipped rather than
guessed at. Everything is reported so a skipped line is visible, not silent.

Usage:
    python3 scripts/wrap-long-signatures.py [--limit 120] [--dry-run]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIRS = ["shared/src", "desktopApp/src", "androidApp/src", "mcp-server/src"]

# `indent <prefix>fun name(params)<suffix>` where suffix ends the expression body.
SIG = re.compile(
    r"^(?P<indent>\s*)"
    r"(?P<head>(?:[\w@\[\]<>., ]*?\bfun)\s+[\w]+\()"
    r"(?P<params>[^()]*?)"
    r"(?P<tail>\)\s*:.*=\s*runCatchingCancellable\s*\{.*)$"
)


def wrap(line: str, limit: int) -> str | None:
    m = SIG.match(line.rstrip("\n"))
    if not m:
        return None
    indent = m.group("indent")
    params = m.group("params").strip()
    if not params:
        return None
    # Nested parens would mean a lambda or a function type in the parameter list;
    # splitting those on ", " is a guess, so leave the line for a human.
    if "(" in params or ")" in params:
        return None

    parts = [p.strip() for p in params.split(",") if p.strip()]
    if not parts:
        return None

    inner = indent + "    "
    wrapped = [f"{indent}{m.group('head')}"]
    wrapped += [f"{inner}{p}," for p in parts]
    wrapped.append(f"{indent}{m.group('tail')}")
    out = "\n".join(wrapped) + ("\n" if line.endswith("\n") else "")
    # Wrapping must actually help; if the longest produced line is still over the
    # limit, leave the original in place rather than churn it for nothing.
    if max(len(x) for x in out.splitlines()) >= limit:
        return None
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--limit", type=int, default=120)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    changed_files = 0
    changed_lines = 0
    skipped: list[str] = []

    for d in SOURCE_DIRS:
        base = ROOT / d
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.kt")):
            if "/build/" in str(path):
                continue
            src = path.read_text(encoding="utf-8").splitlines(keepends=True)
            out: list[str] = []
            touched = 0
            for line in src:
                if len(line.rstrip("\n")) > args.limit:
                    new = wrap(line, args.limit)
                    if new is not None:
                        out.extend(new.splitlines(keepends=True))
                        touched += 1
                        continue
                    if SIG.match(line.rstrip("\n")):
                        rel = path.relative_to(ROOT)
                        skipped.append(f"{rel}:{len(out)}  {line.strip()[:90]}")
                out.append(line)
            if touched and not args.dry_run:
                path.write_text("".join(out), encoding="utf-8")
            if touched:
                changed_files += 1
                changed_lines += touched

    if args.dry_run:
        print(f"would wrap {changed_lines} line(s) in {changed_files} file(s)")
    else:
        print(f"wrapped {changed_lines} line(s) in {changed_files} file(s)")
    if skipped:
        print(f"\n{len(skipped)} line(s) left for a human:")
        for s in skipped:
            print(f"  {s}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
