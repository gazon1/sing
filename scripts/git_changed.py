#!/usr/bin/env python3
"""Shared helpers for "files this change touches" (lint-changed, compile-changed).

Branch-wide (default): compare merge-base(<base>, HEAD) to the working tree
(tracked staged+unstaged), plus untracked files. Staged-only: the index against
HEAD. The git work is GitPython's; the paths are deduped and filtered to
existing files.

The paths are relative to the repository root, and the repository is the one
this directory belongs to — not the one a hook exports through `GIT_DIR`.
"""

from __future__ import annotations

import pathlib
import sys
from typing import Iterable, Sequence

from git import (
    INDEX,
    Commit,
    GitCommandError,
    InvalidGitRepositoryError,
    NoSuchPathError,
    Repo,
)
from git.diff import Diff

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The answer to "which tree does this git command read" lives in one place.
_SRC = ROOT / "scripts" / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))
from sgate.gatekit.gitenv import own_tree_env  # noqa: E402

#: The change types `git diff --diff-filter=ACMR` keeps: added, copied,
#: modified and renamed. A deletion is not a file to lint.
KEPT_CHANGE_TYPES = frozenset({"A", "C", "M", "R"})


def _open_repo(cwd: pathlib.Path) -> Repo:
    """The repository this directory belongs to."""
    try:
        return Repo(cwd, search_parent_directories=True)
    except (InvalidGitRepositoryError, NoSuchPathError) as exc:
        raise RuntimeError(f"changed-files: {cwd} is not inside a git repository: {exc}") from exc


def _head_commit(repo: Repo) -> Commit:
    """The commit HEAD points at."""
    try:
        return repo.head.commit
    except ValueError as exc:  # an empty repository has no commit
        raise RuntimeError(f"changed-files: the repository has no commits yet: {exc}") from exc


def _detail(exc: GitCommandError) -> str:
    """What git said, without GitPython's wrapper around it.

    GitPython puts the reason into `stderr`, and the raw diff machinery is
    no longer the exception it used to be. An empty reason is no reason:
    the exit code is what is left, and it goes into the message instead of
    a blank.
    """
    text = exc.stderr or exc.stdout or ""
    if isinstance(text, bytes):
        text = text.decode("utf-8", errors="replace")
    _, _, tail = text.strip().rpartition("stderr:")
    detail = (tail or text).strip().strip("'")
    return detail or f"git exited with code {exc.status}"


def _merge_base(repo: Repo, base: str) -> str:
    """The commit the base branch and HEAD share."""
    try:
        bases = repo.merge_base(base, "HEAD")
    except GitCommandError as exc:
        detail = _detail(exc)
        msg = f"changed-files: cannot resolve merge-base for '{base}'"
        if detail:
            msg += f": {detail}"
        msg += " (fetch the base, or pass --base)"
        raise RuntimeError(msg) from exc
    if not bases:
        msg = f"changed-files: cannot resolve merge-base for '{base}'"
        msg += " (fetch the base, or pass --base)"
        raise RuntimeError(msg)
    return bases[0].hexsha


def _resolve_base(repo: Repo, base: str, require_base: bool) -> str | None:
    try:
        return _merge_base(repo, base)
    except RuntimeError as exc:
        if require_base:
            raise
        print(
            f"{exc} — checking only the working tree (tracked vs HEAD + untracked); "
            "this is NOT a full branch-wide check",
            file=sys.stderr,
        )
        return None


def _diff(commit: Commit, other, pathspec: Sequence[str], what: str) -> Iterable[Diff]:
    """The diff between a commit and *other* (the index, or the working tree)."""
    try:
        return commit.diff(other, paths=list(pathspec) or None)
    except GitCommandError as exc:
        raise RuntimeError(f"changed-files: git diff failed for {what}: {_detail(exc)}") from exc


def _paths_of(diff: Iterable[Diff]) -> list[str]:
    """The paths a diff touches, keeping the change types ACMR keeps."""
    return [
        entry.b_path
        for entry in diff
        if entry.change_type in KEPT_CHANGE_TYPES and entry.b_path
    ]


def _untracked_paths(repo: Repo, pathspec: Sequence[str]) -> list[str]:
    """The files git does not track yet."""
    if not pathspec:
        return list(repo.untracked_files)
    raw = repo.git.ls_files("--others", "--exclude-standard", "-z", "--", *pathspec)
    return [part for part in raw.split("\0") if part]


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

    *staged_only* — staged (index against HEAD) files only.
    *pathspec* — git pathspecs, e.g. ``("*.md",)``.
    *require_base* — when True (default), an unresolvable merge-base is an error;
      when False, explicitly falls back to working-tree-only scope.

    Branch-wide uses ``git merge-base`` + the diff between that commit and the
    working tree (covering staged+unstaged tracked changes) plus untracked files.
    """
    root = pathlib.Path(root) if root is not None else (pathlib.Path(cwd) if cwd is not None else ROOT)
    cwd = pathlib.Path(cwd) if cwd is not None else root
    paths = list(pathspec)

    files: list[str] = []
    with own_tree_env():
        repo = _open_repo(cwd)
        if staged_only:
            commit = _head_commit(repo)
            files.extend(_paths_of(_diff(commit, INDEX, paths, "the staged changes")))
        else:
            merge_base = _resolve_base(repo, base, require_base)
            if merge_base is None:
                commit = _head_commit(repo)
                what = "HEAD"
            else:
                commit = repo.commit(merge_base)
                what = f"merge-base {merge_base}"
            files.extend(_paths_of(_diff(commit, None, paths, what)))
            files.extend(_untracked_paths(repo, paths))

    seen: set[str] = set()
    out_files: list[str] = []
    for rel in files:
        if not rel or rel in seen:
            continue
        seen.add(rel)
        if (root / rel).is_file():
            out_files.append(rel)
    return out_files


__all__ = ["KEPT_CHANGE_TYPES", "changed_paths"]
