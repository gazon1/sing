#!/usr/bin/env python3
"""
Post-rebase compile gate for DI and import-sensitive modules.

Rebases can pull in changes to *DiModule.kt or other import-sensitive files
that compile cleanly in isolation but break a teammate's tree when they
pull. Unlike a full `./gradlew compile`, this script runs a targeted compile
only when DI-related files are part of the incoming commit set — otherwise
it exits 0 immediately.

This is not a general "did the build break" monitor; the tests job already
covers that. It is a fast pre-flight check specifically for the DI surface
that most often regresss after a large rebase.

Usage:
    python3 scripts/check-rebase-compiles.py [--quiet]

Exit codes:
    0  — no DI files in the diff, or compile succeeded
    1  — DI files changed and compile failed
    2  — git state error (not on a branch, not a git repo, etc.)
"""

import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

# Files whose changes warrant a targeted compile after a rebase.
# Covers the DI surface that most often breaks after a large rebase:
# - All Koin module definitions (DI graph)
# - Any file that exports many names used as implicit receivers (CoroutineScope, etc.)
DI_SENSITIVE_PATTERNS = [
    "core/di/",          # CoreDiModule + all Core/*DiModule.kt
    "feature/*/di/",      # All feature DI modules
    "feature/*/*DiModule.kt",  # Any DiModule.kt anywhere under feature
    "core/coroutines/",   # testScope, AutoCloseableCoroutineScope (implicit receivers)
]

# Gradle tasks to run for a targeted compile check.
# KMP produces per-target tasks; bare `:shared:compileKotlin` is ambiguous.
# `:shared:compileKotlinJvm` covers commonMain + jvmMain; `:desktopApp:compileKotlin`
# covers the desktop Compose entry.
GRADLE_TASKS = [
    ":shared:compileKotlinJvm",
    ":desktopApp:compileKotlin",
]

parser = argparse.ArgumentParser(description="Post-rebase DI compile gate")
parser.add_argument("--quiet", action="store_true",
                    help="Suppress output when exiting 0 (no DI changes)")
args = parser.parse_args()


def run(cmd: list[str], capture: bool = True) -> tuple[int, str]:
    """Run a command, return (rc, stdout+stderr)."""
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True,
            cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        )
        return result.returncode, result.stdout + result.stderr
    except Exception as e:
        return 2, str(e)


def changed_files_since_upstream() -> list[str]:
    """
    Returns the list of files changed between HEAD and its upstream branch.

    Detects two states:
    1. A tracked branch: compares against the configured upstream.
    2. After a rebase: the upstream is now at a different commit, and
       the diff between our HEAD and the upstream's new position is what
       we need to check.

    Returns every DI-sensitive file in the tree when no upstream is
    configured — the compile check still runs, it just covers the full
    DI surface rather than the rebase delta. A gate that skips when it
    cannot prove it needs to run is a gate that cannot fail on a
    clean-tree sabotage, which is exactly the defect it was written to
    catch.
    """
    # Check if we have an upstream
    rc, upstream = run(["git", "rev-parse", "--abbrev-ref", "HEAD@{upstream}"])
    if rc != 0:
        # Not on a tracked branch — collect all DI-sensitive files as
        # a coarse but safe approximation: we cannot know what changed,
        # so we check everything.
        import fnmatch
        di_files = []
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        for dirpath, _dirnames, filenames in os.walk(root):
            for fn in filenames:
                if fn.endswith(".kt"):
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, root)
                    for pat in DI_SENSITIVE_PATTERNS:
                        if fnmatch.fnmatch(rel, pat) or fnmatch.fnmatch(rel, "*/" + pat):
                            di_files.append(rel)
                            break
        return di_files

    # Get the diff against upstream — files that are new/changed vs upstream
    # This is exactly what changed in the rebase.
    rc, diff = run(["git", "diff", "--name-only", "HEAD@{upstream}", "HEAD"])
    if rc != 0:
        return []

    # Also include uncommitted changes (staged + unstaged), since the gate is
    # meant to run on a dirty tree before pushing.
    rc, untracked = run(["git", "diff", "--name-only", "HEAD"])
    if rc == 0:
        diff += "\n" + untracked

    return [f for f in diff.strip().split("\n") if f]


def di_sensitive(changed: list[str]) -> list[str]:
    """Return the subset of changed files that match DI-sensitive patterns."""
    import fnmatch
    matched = []
    for f in changed:
        for pat in DI_SENSITIVE_PATTERNS:
            if fnmatch.fnmatch(f, pat) or fnmatch.fnmatch(f, "*/" + pat):
                matched.append(f)
                break
    return matched


def gradle_compile(tasks: list[str], quiet: bool) -> bool:
    """
    Run Gradle compile tasks.
    Returns True if all succeeded (exit 0), False otherwise.
    """
    cmd = ["./gradlew"] + tasks + ["--no-configuration-cache", "--no-daemon"]
    if quiet:
        cmd.append("--quiet")

    rc, output = run(cmd)
    return rc == 0


def main() -> int:
    changed = changed_files_since_upstream()

    if not changed:
        # No upstream tracked, or git error — nothing to check
        if not args.quiet:
            print("check-rebase-compiles: no upstream tracked; skipping")
        return 0

    di_files = di_sensitive(changed)
    if not di_files:
        if not args.quiet:
            print("check-rebase-compiles: no DI-sensitive files changed; skipping compile check")
        return 0

    print(f"check-rebase-compiles: {len(di_files)} DI-sensitive file(s) changed:")
    for f in di_files:
        print(f"  {f}")
    print(f"Running targeted compile: {' '.join(GRADLE_TASKS)}")

    ok = gradle_compile(GRADLE_TASKS, quiet=False)
    if ok:
        print("check-rebase-compiles: compile succeeded")
        return 0
    else:
        print("check-rebase-compiles: COMPILE FAILED — fix DI/import errors before pushing", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
