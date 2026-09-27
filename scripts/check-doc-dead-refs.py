#!/usr/bin/env python3
"""check-doc-dead-refs.py — find references to files that no longer exist.

The batch cleanup in this sprint found ~80 stale references in docs, skills, ADRs and
KDoc, almost all of them the residue of a refactor that removed a file. They are
invisible to a plain grep (the path looks fine) and only surface when an agent tries to
open the file.

This script extracts every backticked `*.kt` / `*.py` / `*.sh` / `*.md` path from
Markdown and KDoc and reports the ones that do not resolve.

Deliberately conservative: it only reports a reference when it can locate the source
file but not the target, and it skips anything that looks like an example, a glob, an
ADR that is itself being referenced by slug, or a path in a historical ADR section.
Historical ADRs are checked but reported separately, because their whole purpose is to
record what was true at the time.

Usage: python3 scripts/check-doc-dead-refs.py [--include-adr] [--max N]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILLS_DIR = ROOT / ".agents" / "skills"
DECISIONS_DIR = ROOT / "docs" / "decisions"
SRC_DIRS = ["shared/src", "shared", "androidApp", "desktopApp", "mcp-server",
            "detekt-rules", "scripts", "docs", "config", "evals", ".agents", "gradle"]

# Top-level files that exist but are not under SRC_DIRS.
TOP_LEVEL_FILES = [
    "AGENTS.md", "ARCHITECTURE.md", "README.md", "PROGRESS.md", "check.sh",
    "justfile", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "skills-lock.json", "SKILL-MECHANICS.md", "CLAUDE.md", "LICENSE",
    "gradlew", "gradlew.bat", "package.json",
]

# Per-module build files live beside their module's source, not in an indexed dir.
GLOB_BUILD = ["shared/build.gradle.kts", "androidApp/build.gradle.kts",
              "desktopApp/build.gradle.kts", "mcp-server/build.gradle.kts",
              "detekt-rules/build.gradle.kts"]

# Files that are runtime artifacts or external, not repo sources.
RUNTIME_ARTIFACTS = {
    "manifest.json", "payload.json", "backup.json", "data.json", "index.json",
    "skill-mechanics.md",
}

# A backticked token that looks like a file path.
PATH_RE = re.compile(
    r"`([A-Za-z0-9_][A-Za-z0-9_./@-]*\.(?:kt|py|sh|md|yml|yaml|kts|json|toml|sql))`"
)
# Historical ADRs are allowed to reference files that no longer exist.
HISTORY_MARKERS = (
    "superseded in part",
    "as written:",
    "was removed",
    "was never added",
    "paths below are historical",
    "no longer",
    "retired",
    "deleted in",
    "removed 2026",
)


def is_historical(text: str, pos: int) -> bool:
    """True when the reference sits inside a passage marked as historical."""
    start = max(0, pos - 600)
    window = text[start:pos].lower()
    return any(m in window for m in HISTORY_MARKERS)


def build_index() -> tuple[set[str], dict[str, list[pathlib.Path]]]:
    """Index every tracked file by repo-relative path and by basename."""
    rel_paths: set[str] = set()
    by_name: dict[str, list[pathlib.Path]] = {}
    for d in SRC_DIRS:
        base = ROOT / d
        if not base.exists():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            if any(part in {"build", ".gradle", ".git"} for part in p.parts):
                continue
            try:
                rel = p.relative_to(ROOT).as_posix()
            except ValueError:
                continue
            rel_paths.add(rel)
            by_name.setdefault(p.name, []).append(p)
    for name in TOP_LEVEL_FILES:
        p = ROOT / name
        if p.exists():
            rel_paths.add(name)
            by_name.setdefault(name, []).append(p)
    for name in GLOB_BUILD:
        if name in rel_paths:
            continue
        rel_paths.add(name)
        by_name.setdefault(name.split("/")[-1], []).append(ROOT / name)
    return rel_paths, by_name


# Placeholder paths in templates and examples — not claims about existing files.
PLACEHOLDER_PATTERNS = (
    re.compile(r"^path/to/"),
    re.compile(r"YYYY"),
    re.compile(r"^(domain|data|presentation|ui|feature|core|commonMain)/[a-z]+$"),
    re.compile(r"^<.+>$"),
    re.compile(r"^[a-z-]+/$"),
    re.compile(r"^(your|my|some)-"),
    re.compile(r"\.android/jvm\.kt$"),   # shorthand for a .android.kt/.jvm.kt pair
    re.compile(r"^iosMain/"),            # platform not targeted yet
)


def is_placeholder(ref: str) -> bool:
    return any(p.search(ref) for p in PLACEHOLDER_PATTERNS)


def resolve(ref: str, rel_paths: set[str], by_name: dict[str, list[pathlib.Path]]) -> str:
    """Classify a reference: 'ok', 'drift' (file exists, path stale) or 'dead'."""
    if ref.lower() in RUNTIME_ARTIFACTS or is_placeholder(ref):
        return "ok"
    if ref in rel_paths:
        return "ok"
    name = ref.split("/")[-1]
    if "..." in ref or "*" in ref or "<" in ref or ">" in ref:
        # Ellipsis/glob form: match on the trailing segments after the last ...
        tail = ref.split("...")[-1].lstrip("/")
        if not tail:
            return "ok"  # too vague to judge
        hits = by_name.get(tail.split("/")[-1], [])
        return "ok" if any(h.as_posix().endswith(tail) for h in hits) else "dead"
    hits = by_name.get(name, [])
    if not hits:
        return "dead"
    if any(h.as_posix().endswith(ref) for h in hits):
        return "ok"
    # The file exists under a different path — a moved file, not a phantom.
    return "drift"


def scan(path: pathlib.Path, rel_paths, by_name) -> list[tuple[int, str, str]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    out = []
    for m in PATH_RE.finditer(text):
        ref = m.group(1)
        verdict = resolve(ref, rel_paths, by_name)
        if verdict == "ok":
            continue
        if verdict == "drift" and is_historical(text, m.start()):
            continue
        line = text.count("\n", 0, m.start()) + 1
        historical = is_historical(text, m.start())
        out.append((line, ref, "historical" if historical else verdict))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--include-adr", action="store_true",
                    help="also scan docs/decisions (reports historical refs separately)")
    ap.add_argument("--max", type=int, default=40, help="max lines to print per file")
    ap.add_argument("--strict", action="store_true",
                    help="treat path drift as a failure too")
    args = ap.parse_args()

    rel_paths, by_name = build_index()
    targets: list[pathlib.Path] = [
        ROOT / "AGENTS.md",
        ROOT / "ARCHITECTURE.md",
        ROOT / "README.md",
        ROOT / "PROGRESS.md",
    ]
    targets += sorted(SKILLS_DIR.glob("*/SKILL.md"))
    targets += sorted((ROOT / "shared/src").rglob("*.kt"))
    if args.include_adr:
        targets += [p for p in sorted(DECISIONS_DIR.glob("*.md")) if p.name != "DIGEST.md"]

    dead_total = 0
    drift_total = 0
    hist_total = 0
    for path in targets:
        if not path.exists():
            continue
        findings = scan(path, rel_paths, by_name)
        if not findings:
            continue
        rel = path.relative_to(ROOT).as_posix()
        dead = [f for f in findings if f[2] == "dead"]
        drift = [f for f in findings if f[2] == "drift"]
        hist = [f for f in findings if f[2] == "historical"]
        if not dead and not drift and hist:
            print(f"\n{rel}: {len(hist)} historical reference(s) (expected for retired ADRs)")
        for label, group in (("DEAD", dead), ("DRIFT", drift)):
            if not group:
                continue
            print(f"\n{rel}: {len(group)} {label} reference(s)")
            for line, ref, _ in group[: args.max]:
                print(f"  L{line}: {ref}")
            if len(group) > args.max:
                print(f"  ... and {len(group) - args.max} more")
        dead_total += len(dead)
        drift_total += len(drift)
        hist_total += len(hist)

    print(f"\nDead refs: {dead_total} dead, {drift_total} path-drift, {hist_total} historical")
    if dead_total:
        print("\nDEAD: the file does not exist anywhere. Point it at the current file,")
        print("or mark the passage as historical (a 'Superseded in part' banner).")
    if drift_total:
        print("\nDRIFT: the file exists but moved. Update the path (or accept the")
        print("basename, which is why this is a warning rather than a hard failure).")
    return 1 if (dead_total or (drift_total and args.strict)) else 0


if __name__ == "__main__":
    sys.exit(main())
