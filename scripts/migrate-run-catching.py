#!/usr/bin/env python3
"""migrate-run-catching.py — rewrite `runCatching` to `runCatchingCancellable` at the
exact locations NoRunCatchingInSuspend reported.

Why: `runCatching` catches `Throwable`, so inside a suspend function it converts a
`CancellationException` into `Result.failure`. The coroutine then looks like it
finished normally to everything upstream, and the job is never cancelled — the
"coroutine died silently before first emission" failure documented in
`2026-10-03-kotlinx-coroutines-debug`.

`runCatchingCancellable` (core/error/RunCatching.kt) re-throws CancellationException
and returns Result.failure for everything else, so it is a drop-in at these sites.

Only the lines detekt reported are touched. Editing by line rather than by regex
over the whole file matters: `runCatching` in a non-suspend context is perfectly
correct, and the rule — not a search — is what knows which is which.

Usage:
    python3 scripts/migrate-run-catching.py --report <detekt.xml> [--dry-run]
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IMPORT = "import com.singularity.todo.core.error.runCatchingCancellable"

# `runCatching` not already followed by the helper's own name.
RUN_CATCHING = re.compile(r"(?<![\w.])runCatching(?![\wC])")


def collect(report: Path) -> dict[str, set[int]]:
    hits: dict[str, set[int]] = defaultdict(set)
    root = ET.parse(report).getroot()
    for f in root.iter("file"):
        name = f.get("name", "")
        for err in f.findall("error"):
            if "NoRunCatchingInSuspend" not in err.get("source", ""):
                continue
            line = err.get("line")
            if line:
                hits[name].add(int(line))
    return hits


LABEL = re.compile(r"return@runCatching\b")


def rewrite_labels_in_migrated_scopes(src: list[str], lines: set[int]) -> int:
    """Rename `return@runCatching` labels that belong to a migrated lambda.

    Scans upward from each label for the nearest `runCatching` /
    `runCatchingCancellable` occurrence within the same indentation block. If the
    nearest one is the helper, the label must be renamed; if it is a plain
    `runCatching` the label is left alone, because that call was not migrated and
    renaming its label would break a file that is currently correct.
    """
    renamed = 0
    for i, line in enumerate(src):
        if not LABEL.search(line):
            continue
        indent = len(line) - len(line.lstrip())
        nearest_migrated = False
        for j in range(i - 1, max(-1, i - 200), -1):
            up = src[j]
            if "runCatchingCancellable" in up and not LABEL.search(up):
                nearest_migrated = True
                break
            if RUN_CATCHING.search(up):
                nearest_migrated = False
                break
        if nearest_migrated:
            src[i] = LABEL.sub("return@runCatchingCancellable", line)
            renamed += 1
    return renamed


def migrate_file(path: Path, lines: set[int]) -> int:
    text = path.read_text(encoding="utf-8")
    src = text.splitlines(keepends=True)
    changed = 0
    for ln in lines:
        idx = ln - 1
        if idx < 0 or idx >= len(src):
            print(f"  WARN {path.name}:{ln} out of range", file=sys.stderr)
            continue
        new = RUN_CATCHING.sub("runCatchingCancellable", src[idx])
        if new != src[idx]:
            src[idx] = new
            changed += 1
    if changed == 0:
        return 0

    # A `runCatching { … return@runCatching … }` lambda carries an implicit label
    # that lives on a *different line* from the call. Rewriting only the reported
    # line renames the call and leaves the label, and the file then fails to
    # compile with "Unresolved label" — which is exactly what the first run of this
    # script produced. The label belongs to the innermost enclosing call, so it is
    # only renamed when that call is one we just migrated.
    if changed:
        rewrite_labels_in_migrated_scopes(src, lines)

    body = "".join(src)
    if IMPORT not in body and "package " in body:
        # Insert after the last existing import; if there are none, after package.
        last_import = None
        for i, l in enumerate(src):
            if l.startswith("import "):
                last_import = i
        insert_at = (last_import + 1) if last_import is not None else None
        if insert_at is None:
            for i, l in enumerate(src):
                if l.startswith("package "):
                    insert_at = i + 1
                    break
        if insert_at is not None:
            src.insert(insert_at, IMPORT + "\n")
    path.write_text("".join(src), encoding="utf-8")
    return changed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--report", required=True, help="detekt XML report")
    ap.add_argument("--dry-run", action="store_true", help="report only, change nothing")
    args = ap.parse_args()

    report = Path(args.report)
    if not report.is_absolute():
        report = ROOT / report
    if not report.is_file():
        print(f"report not found: {report}")
        return 1

    hits = collect(report)
    if not hits:
        print("no NoRunCatchingInSuspend findings in report — nothing to migrate")
        return 0

    total_sites = sum(len(v) for v in hits.values())
    print(f"{len(hits)} file(s), {total_sites} site(s)")

    if args.dry_run:
        for name in sorted(hits):
            print(f"  {name}: {len(hits[name])}")
        return 0

    total_changed = 0
    for name in sorted(hits):
        path = Path(name)
        if not path.is_absolute():
            path = ROOT / name
        if not path.is_file():
            print(f"  WARN missing {name}")
            continue
        changed = migrate_file(path, hits[name])
        total_changed += changed
        print(f"  {changed:3d}  {path.relative_to(ROOT)}")

    print(f"migrated {total_changed} site(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
