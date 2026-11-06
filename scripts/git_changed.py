#!/usr/bin/env python3
"""Shared helpers for "files this change touches" (lint-changed, rumdl-changed).

Scope (default): merge-base with base..HEAD, plus the working tree (staged +
unstaged tracked), plus untracked files — the same definition both scoped
checkers use. ``pathspec`` filters by git pathspec (e.g. ``*.md``).
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
from typing import Sequence

ROOT = pathlib.Path(__file__).resolve().parent.parent


def git_paths(*args: str, cwd: pathlib.Path | None = None) -> tuple[int, list[str]]:
    """Run git for a list of paths; returns (exit code, paths).

    Uses ``-z`` so paths with spaces/quotes are not mangled.
    """
    p = subprocess.run(
        ["git", *args],
        cwd=cwd or ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return p.returncode, [x for x in p.stdout.split("\0") if x]


def changed_paths(
    base: str,
    pathspec: Sequence[str] = (),
    staged_only: bool = False,
    cwd: pathlib.Path | None = None,
    root: pathlib.Path | None = None,
) -> list[str]:
    """Paths this change touches (committed + working tree), deduped, existing.

    *staged_only* — only staged (cached) files; used by pre-commit hooks.
    *pathspec* — git pathspecs appended after ``--`` (e.g. ``("*.md",)``).
    """
    root = root or cwd or ROOT
    cwd = cwd or root
    ps = ("--", *pathspec) if pathspec else ()
    if staged_only:
        code, out = git_paths(
            "diff", "--name-only", "--diff-filter=ACMR", "-z", "--cached", *ps, cwd=cwd
        )
        files = out if code == 0 else []
    else:
        files: list[str] = []
        code, committed = git_paths(
            "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{base}...HEAD", *ps, cwd=cwd
        )
        if code == 0:
            files.extend(committed)
        else:
            print(
                f"changed-files: cannot compare against '{base}' — "
                "only the working tree is considered (fetch it, or pass --base)",
                file=sys.stderr,
            )
        code, tracked = git_paths(
            "diff", "--name-only", "--diff-filter=ACMR", "-z", "HEAD", *ps, cwd=cwd
        )
        if code == 0:
            files.extend(tracked)
        code, untracked = git_paths(
            "ls-files", "--others", "--exclude-standard", "-z", *ps, cwd=cwd
        )
        if code == 0:
            files.extend(untracked)

    seen: set[str] = set()
    out_files: list[str] = []
    for rel in files:
        if rel in seen:
            continue
        seen.add(rel)
        if (root / rel).is_file():
            out_files.append(rel)
    return out_files
