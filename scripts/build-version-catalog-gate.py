#!/usr/bin/env python3
"""
Build version catalog gate.

Hardcoded `group:artifact:version` literals in *.gradle.kts are a code smell.
Every dependency must come from gradle/libs.versions.toml (the single source of
truth). A literal that bypasses the catalog drifts silently: the version falls out
of step with the catalog's alignment rules, and the next dependency audit misses it.

This gate detects that drift class — a literal that SHOULD be in the catalog
but isn't — before it lands. It does NOT flag:
  - Plugin version literals in plugins {} blocks (version refs are resolved from
    the catalog already; the plugins {} block is the canonical place for them)
  - Entries in gradle/libs.versions.toml itself (they ARE the catalog)
  - Files under /detekt-rules/ (builds its own plugin-classpath without the catalog)
  - Files under /buildSrc/ (convention plugins, same)
  - Gradle's own .gradle/ and build/ output directories

Pattern: mirror scripts/find-unwired-surfaces.py — --quiet for CI, positive
control that the scan covered ≥MIN_SCAN files (vacuous green is worse than red).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

EXCLUDE_PREFIXES = (
    "detekt-rules/",
    "buildSrc/",
    ".gradle/",
    "build/",
)
# group:artifact:version where version starts with a digit (filters out plugin refs)
LITERAL_RE = re.compile(r"\b[\w.-]+\:[\w-]+\:\d[\w.-]*")
MIN_SCAN = 5


def main() -> int:
    # Parse: flags anywhere, path is the first non-flag argument.
    args = sys.argv[1:]
    quiet = "--quiet" in args
    positional = [a for a in args if not a.startswith("--")]
    workspace = Path(positional[0] if positional else ".")

    gradle_files = [
        f
        for f in workspace.rglob("*.gradle.kts")
        if not any(p in str(f) for p in EXCLUDE_PREFIXES)
    ]

    if len(gradle_files) < MIN_SCAN:
        print(
            f"ERROR: only {len(gradle_files)} *.gradle.kts files scanned — "
            f"path resolution is broken (expected ≥{MIN_SCAN}). "
            "The gate would pass on zero files.",
            file=sys.stderr,
        )
        return 2

    violations: list[tuple[Path, str]] = []
    for f in gradle_files:
        for m in LITERAL_RE.finditer(f.read_text()):
            violations.append((f, m.group(0)))

    if not violations:
        if not quiet:
            print(f"OK: scanned {len(gradle_files)} files, 0 hardcoded version literals.")
        return 0

    print(f"Found {len(violations)} hardcoded version literal(s) in {len(gradle_files)} files:")
    for path, lit in violations:
        try:
            rel = path.relative_to(workspace)
        except ValueError:
            rel = path
        print(f"  {rel}: {lit}")
    print("\nFix: add the library to gradle/libs.versions.toml and reference via libs.*")
    return 1


if __name__ == "__main__":
    sys.exit(main())
