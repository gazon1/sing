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
     python3 scripts/check-doc-dead-refs.py --skill-symbols   # check Kotlin symbol references in skills
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
            "detekt-rules", "scripts", "docs", "config", "evals", ".agents", "gradle",
            "Maestro", "openspec"]

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
    ap.add_argument("--baseline", metavar="PATH", default=str(ROOT / "config" / "docs" / "dead-refs-baseline.txt"),
                    help="file of accepted dead refs, one 'path:ref' per line")
    ap.add_argument("--update-baseline", action="store_true",
                    help="rewrite the baseline from the current findings, then exit")
    ap.add_argument("--force", action="store_true",
                    help="with --update-baseline, allow dropping entries that are no "
                         "longer detected (destructive — see the refusal message)")
    ap.add_argument("--skill-symbols", action="store_true",
                    help="check Kotlin symbol references in skill files (detector 8)")
    args = ap.parse_args()

    if args.skill_symbols:
        return _skill_symbols_main(args)

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

    baseline_path = pathlib.Path(args.baseline)
    accepted: set[str] = set()
    if baseline_path.exists():
        accepted = {l.strip() for l in baseline_path.read_text().splitlines() if l.strip() and not l.startswith("#")}

    dead_total = 0
    drift_total = 0
    hist_total = 0
    new_dead: list[str] = []
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
        # Split dead into baselined (accepted debt) and new (must be fixed).
        baselined = [f for f in dead if f"{rel}:{f[1]}" in accepted]
        new = [f for f in dead if f"{rel}:{f[1]}" not in accepted]
        new_dead += [f"{rel}:{ref}" for _, ref, _ in new]
        if not new and not drift and (baselined or hist):
            print(f"\n{rel}: {len(baselined)} baselined dead, {len(hist)} historical — OK")
        for label, group in (("DEAD (new)", new), ("DRIFT", drift)):
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

    if args.update_baseline:
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        all_dead = sorted(
            {f"{path.relative_to(ROOT).as_posix()}:{ref}"
             for path in targets if path.exists()
             for _, ref, kind in scan(path, rel_paths, by_name) if kind == "dead"}
        )
        # A rewrite is destructive: the scan only sees refs in `targets`, so entries
        # baselined earlier (e.g. from --include-adr, or from a source tree that has since
        # been reworded) are silently dropped. On 2026-10-05 an unguarded
        # --update-baseline reduced this file from 328 entries to 39, erasing the record
        # of accepted debt. Require --force when entries would be removed.
        dropped = sorted(accepted - set(all_dead))
        if dropped and not args.force:
            print(f"REFUSING to rewrite {baseline_path}: it would drop "
                  f"{len(dropped)} existing accepted entr(ies).", file=sys.stderr)
            print("The scan only covers the current target set, so this is not a "
                  "statement that the refs are fixed.", file=sys.stderr)
            for entry in dropped[:20]:
                print(f"  would drop: {entry}", file=sys.stderr)
            if len(dropped) > 20:
                print(f"  ... and {len(dropped) - 20} more", file=sys.stderr)
            print("\nRe-run with --force to accept the loss, or add the still-valid "
                  "entries back by hand.", file=sys.stderr)
            return 1
        if dropped:
            print(f"WARNING: dropping {len(dropped)} accepted dead-ref entr(ies) "
                  f"(--force)", file=sys.stderr)
        header = (
            "# Accepted dead file references in docs, skills and KDoc.\n"
            "#\n"
            "# Each line is `<file>:<referenced-path>`. A dead reference means the\n"
            "# referenced file does not exist anywhere in the repo. These are accepted\n"
            "# because the surrounding prose describes a design that was written down\n"
            "# but never built; they are catalogued in\n"
            "# docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md.\n"
            "#\n"
            "# New dead references are NOT baselined and fail `just docs-audit`.\n"
            "# Regenerate with: python3 scripts/check-doc-dead-refs.py --update-baseline\n"
            "#   (add --force only if you intend to drop entries that are no longer\n"
            "#    detected; the scan does not see everything this file records).\n"
            "# Remove a line once the file is written or the example is reworded.\n"
        )
        baseline_path.write_text(header + "\n".join(all_dead) + "\n", encoding="utf-8")
        print(f"\nbaseline written: {len(all_dead)} accepted dead refs -> {baseline_path}")
        return 0

    print(f"\nDead refs: {dead_total} dead ({dead_total - len(new_dead)} baselined), "
          f"{drift_total} path-drift, {hist_total} historical")
    if new_dead:
        print("\nNEW dead references (not baselined — fix or reword the example):")
        for entry in new_dead[: args.max]:
            print(f"  {entry}")
        if len(new_dead) > args.max:
            print(f"  ... and {len(new_dead) - args.max} more")
    if dead_total - len(new_dead):
        print("\nBaselined dead refs are accepted debt — see the baseline file header.")
    if drift_total:
        print("\nDRIFT: the file exists but moved. Update the path; basename matches are")
        print("accepted, so drift is a warning unless --strict is passed.")
    return 1 if (new_dead or (drift_total and args.strict)) else 0


# ── Detector 8 — skill-dangling-symbol ───────────────────────────────────────


_SYMBOL_RE = re.compile(r"`([^`]+)`")
_TOP_LEVEL_KT = re.compile(
    r"^(?:object|class|interface|enum\s+class|value\s+class|"
    r"annotation\s+class|fun|val|var)\s+(\w+)",
    re.M,
)
# Types that are framework-allocated and never have production call sites.
_FRAMEWORK_ALLOCATED = frozenset({
    "App", "SingularityApp",  # Application/main entry
    "SingularityDatabase",    # Room database
    "PlatformDatabase",       # Koin graph entry
    "MainScreen",             # Desktop main screen
    "androidApp",             # package name
    "desktopApp",             # package name
})


def _build_kt_symbol_index() -> dict[str, str]:
    """Scan production .kt files; return {symbol_name → file_rel_path}."""
    index: dict[str, str] = {}
    prod_roots = [
        ROOT / "shared/src/commonMain",
        ROOT / "shared/src/androidMain",
        ROOT / "shared/src/jvmMain",
        ROOT / "androidApp/src/main",
        ROOT / "desktopApp/src",
        ROOT / "mcp-server/src/main",
    ]
    for base in prod_roots:
        if not base.exists():
            continue
        for path in base.rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            for m in _TOP_LEVEL_KT.finditer(text):
                name = m.group(1)
                if name in _FRAMEWORK_ALLOCATED:
                    continue
                # First-wins: commonMain is the canonical declaration
                if name not in index:
                    index[name] = path.relative_to(ROOT).as_posix()
    return index


def _scan_skill_symbol_refs(
    skill_md: pathlib.Path,
) -> list[tuple[int, str]]:
    """Return [(line, backtick-quoted-symbol)] from a SKILL.md file."""
    text = skill_md.read_text(encoding="utf-8", errors="replace")

    # Strip code-fence blocks so content inside ``` ``` doesn't pollute symbol scan.
    text = re.sub(r"```[\s\S]*?```", "", text)

    # Skip known non-symbol patterns:
    KOTLIN_BUILTINS = frozenset({
        "println", "print", "readLine", "error", "exit",
        "null", "true", "false", "this", "super",
        "try", "catch", "finally", "throw", "return",
        "when", "is", "as", "in", "out", "by",
        "get", "set", "field", "delegate",
        "init", "combine", "await", "async", "launch", "run", "delay",
        "suspend", "yield", "lock", "synchronized",
    })
    ANDROID_CONSTANTS = frozenset({
        "KEYCODE_MENU", "KEYCODE_WAKEUP", "KEYCODE_BACK", "KEYCODE_HOME",
        "MODE_PRIVATE", "PERMISSION_DENIED", "POST_NOTIFICATIONS",
        "REQUEST_CODE", "RESULT_OK", "RESULT_CANCELED",
    })
    PROSE_WORDS = frozenset({
        "accepted", "completed", "tasks", "sync", "di", "detekt",
        "skills", "build", "test", "feat", "fix", "refactor",
        "docs", "chore", "grilling", "section", "discard",
    })

    refs: list[tuple[int, str]] = []
    for m in _SYMBOL_RE.finditer(text):
        sym = m.group(1)
        # Skip multi-line spans (tables, etc.)
        if "\n" in sym:
            continue
        stripped = sym.strip()
        # Skip empty / code-fence-like / shell-var / special-prefix content
        if (not stripped or stripped.startswith("```") or stripped.startswith("#")
                or stripped.startswith("$") or stripped.startswith("-")):
            continue
        # Skip file-path-like backticks
        if "/" in sym or sym.endswith(".md") or sym.startswith("shared/"):
            continue
        # Skip non-identifier content (punctuation, spaces)
        if not re.match(r"[a-zA-Z_$][a-zA-Z0-9_$]*$", stripped):
            continue
        # Skip known non-symbol categories
        if stripped in KOTLIN_BUILTINS | ANDROID_CONSTANTS | PROSE_WORDS:
            continue
        # Only report PascalCase symbols (class/interface/type names) —
        # these are what skills reference for architecture documentation.
        # Skip mixed-case (camelCase) as these are often testing APIs or prose words.
        if not re.match(r"[A-Z][a-zA-Z0-9_$]*$", stripped):
            continue
        line = text.count("\n", 0, m.start()) + 1
        refs.append((line, sym))
    return refs


def _skill_symbols_main(_args) -> int:
    """Check that Kotlin symbols referenced in skill files exist in production code."""
    index = _build_kt_symbol_index()
    skill_dir = ROOT / ".agents" / "skills"
    baseline_path = ROOT / "config" / "docs" / "skill-symbol-baseline.txt"
    accepted: set[str] = set()
    if baseline_path.exists():
        accepted = {
            l.strip()
            for l in baseline_path.read_text().splitlines()
            if l.strip() and not l.startswith("#")
        }

    def _rel(path: pathlib.Path) -> str:
        try:
            return path.relative_to(ROOT).as_posix()
        except ValueError:
            return str(path)

    new_findings: list[str] = []
    for skill_md in sorted(skill_dir.glob("*/SKILL.md")):
        refs = _scan_skill_symbol_refs(skill_md)
        for line, sym in refs:
            key = f"{skill_md.relative_to(ROOT).as_posix()}:{sym}"
            if sym not in index and key not in accepted:
                new_findings.append(
                    f"  {_rel(skill_md)}:{line}: `{sym}` — not in production code"
                )

    if new_findings:
        print("NEW skill symbol references (not baselined):")
        for f in new_findings:
            print(f)
        print(f"\n{len(new_findings)} new dangling symbol(s).")
        return 1

    print("No new dangling symbols found in skill files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
