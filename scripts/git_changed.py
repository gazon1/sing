#!/usr/bin/env python3
"""Shared helpers for "files this change touches" (lint-changed, compile-changed).

Branch-wide (default): compare merge-base(<base>, HEAD) to the working tree
(tracked staged+unstaged) via ``git diff <merge-base> --``, plus untracked files.
Staged-only: ``git diff --cached``. Paths are NUL-delimited; output is deduped
and filtered to existing files.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
from typing import Sequence

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The answer to "which tree does this git command read" lives in one place.
_SRC = ROOT / "scripts" / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))
from sgate.gatekit.gitenv import tree_env  # noqa: E402


def _git(*args: str, cwd: pathlib.Path, check: bool = False) -> tuple[int, str, str]:
    p = subprocess.run(
        ["git", *args],
        cwd=cwd,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    if check and p.returncode != 0:
        raise subprocess.CalledProcessError(p.returncode, ["git", *args], p.stdout, p.stderr)
    return p.returncode, p.stdout, p.stderr


def _zsplit(stdout: str) -> list[str]:
    return [x for x in stdout.split("\0") if x]


def _merge_base(base: str, cwd: pathlib.Path) -> str:
    code, out, err = _git("merge-base", base, "HEAD", cwd=cwd)
    if code != 0:
        detail = (err or out).strip()
        msg = f"changed-files: cannot resolve merge-base for '{base}'"
        if detail:
            msg += f": {detail}"
        msg += " (fetch the base, or pass --base)"
        raise RuntimeError(msg)
    return out.strip()


def _resolve_base(base: str, cwd: pathlib.Path, require_base: bool) -> str | None:
    try:
        return _merge_base(base, cwd)
    except RuntimeError as exc:
        if require_base:
            raise
        print(
            f"{exc} — checking only the working tree (tracked vs HEAD + untracked); "
            "this is NOT a full branch-wide check",
            file=sys.stderr,
        )
        return None


def changed_paths(
    base: str = "origin/main",
    pathspec: Sequence[str] = (),
    staged_only: bool = False,
    *,
    cwd: pathlib.Path | None = None,
    root: pathlib.Path | None = None,
    require_base: bool = True,
) -> list[str]:
    """Paths this change touches, deduped, existing.

    *staged_only* — staged (cached) files only.
    *pathspec* — git pathspecs appended after ``--`` (e.g. ``("*.md",)``).
    *require_base* — when True (default), an unresolvable merge-base is an error;
      when False, explicitly falls back to working-tree-only scope.

    Branch-wide uses ``git merge-base`` + ``git diff <merge-base>`` (working tree vs
    merge-base, covering staged+unstaged tracked changes) plus untracked files.
    """
    root = pathlib.Path(root) if root is not None else (pathlib.Path(cwd) if cwd is not None else ROOT)
    cwd = pathlib.Path(cwd) if cwd is not None else root
    ps = ("--", *pathspec) if pathspec else ()

    files: list[str] = []
    if staged_only:
        code, out, _ = _git("diff", "--name-only", "--diff-filter=ACMR", "-z", "--cached", *ps, cwd=cwd)
        if code == 0:
            files.extend(_zsplit(out))
    else:
        merge_base = _resolve_base(base, cwd, require_base)
        if merge_base is None:
            code, out, _ = _git("diff", "--name-only", "--diff-filter=ACMR", "-z", "HEAD", *ps, cwd=cwd)
            if code == 0:
                files.extend(_zsplit(out))
        else:
            code, out, err = _git(
                "diff", "--name-only", "--diff-filter=ACMR", "-z", merge_base, *ps, cwd=cwd
            )
            if code != 0:
                detail = (err or out).strip()
                raise RuntimeError(f"changed-files: git diff failed for merge-base {merge_base}: {detail}")
            files.extend(_zsplit(out))
        code, out, _ = _git("ls-files", "--others", "--exclude-standard", "-z", *ps, cwd=cwd)
        if code == 0:
            files.extend(_zsplit(out))

    seen: set[str] = set()
    out_files: list[str] = []
    for rel in files:
        if not rel or rel in seen:
            continue
        seen.add(rel)
        if (root / rel).is_file():
            out_files.append(rel)
    return out_files
