#!/usr/bin/env python3
# no-sgate: this script must run when the gate tooling is foreign or missing —
# importing it would bind the very package whose state it is asked about.
"""staged-tree.py — hold the working tree at the index while the commit is checked.

## What is checked, and what is not

The pre-commit hook compiles the working tree. That is the wrong tree: the
commit carries the **index**, and the working tree carries the index plus
whatever else happens to be lying around uncommitted.

On a branch with one commit per idea, the working tree is usually a mix: the
part that is about to be committed and the part that is not yet ready. The
compile then sees both, and the commit fails on files it does not contain —
the commit is refused for a reason that belongs to another change, and the
person committing has to find which half broke it.

The pre-push hook has the right shape already: it checks the tree that is
being pushed, in a throwaway worktree, not the working tree. This script is
the same idea for the commit — but it does **not** use a throwaway worktree,
because a worktree has its own build directory and its own Gradle home, and a
compile there starts cold. The price of every commit would be a full build.

So the working tree is *held* instead: the unstaged changes are put aside,
the index's content is written over the working tree, the check runs, and then
the working tree is put back exactly as it was. Same directory, same warm
build cache, same speed — and the check sees the tree the commit will carry.

## What is held

- **Tracked files whose working-tree content differs from the index.** Their
  working-tree bytes are copied aside, and the index's version is written
  over them. A file deleted in the working tree but present in the index is
  restored for the check and deleted again afterwards.
- **Untracked files** (the ones git does not ignore). They are not in the
  commit, so they must not be compiled: a Kotlin file that was never added is
  exactly the kind of thing that breaks a commit it does not belong to. They
  are moved aside and moved back.

Gitignored files are left alone. They are build output, not source, and
moving a build directory out of the tree to check a commit would be a way of
making the check slow.

## The restore is not optional

The held state is written to a plan file, and `restore` puts everything back.
The hook installs a trap that restores on every exit path, including a
failure and an interrupt — a developer's uncommitted work is not something a
hook may keep.

If the restore itself fails, the plan file says where the held files are, and
the message says so. Nothing is lost: the held bytes are on disk until the
plan is removed.

Usage:
    python3 scripts/staged-tree.py hold --plan <path>
    python3 scripts/staged-tree.py restore --plan <path>

Exit codes:
    0 — the action succeeded
    1 — the action failed (the reason is on stderr)
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

CLEAN = 0
FAILURE = 1

#: The variables git exports to the hooks it runs. They name the tree the hook
#: runs in, so a command that reads another tree must not inherit them.
INHERITED_FROM_HOOK = ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR")


class GitUnavailable(RuntimeError):
    """Raised when git is not available or a git command fails."""


def tree_env(root: pathlib.Path) -> dict[str, str]:
    """The environment in which git means the repository it discovers.

    Git exports GIT_DIR, GIT_WORK_TREE, GIT_INDEX_FILE and GIT_COMMON_DIR to
    the hooks it runs, and they name the tree the hook runs in. A git command
    that reads another tree must not inherit them — it would read the hook's
    tree instead of the one it was told to read, and the answer would be about
    a different checkout than the one the caller asked about.

    This is the same contract ``sgate.gatekit.gitenv.tree_env`` implements,
    written out here because this script must run when the gate tooling is
    foreign or missing: it is the copy that holds the tree, and a copy that
    cannot import its own helper cannot hold anything.

    GIT_INDEX_FILE is kept when the tree being read is this checkout's root:
    a partial commit (``git commit <path>``) runs its hook against a temporary
    index, and the index the hold has to read is that one. The path is relative
    to the tree's root, and the command runs there.
    """
    env = dict(os.environ)
    names = INHERITED_FROM_HOOK
    if root.resolve() != ROOT.resolve():
        names = ("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR")
    for name in names:
        env.pop(name, None)
    return env


def git(*args: str, stdin: str | None = None, root: pathlib.Path | None = None) -> str:
    """Run a git command in the repository root and return its stdout."""
    tree = (root or ROOT).resolve()
    try:
        out = subprocess.run(
            ["git", *args],
            capture_output=True,
            text=True,
            check=False,
            cwd=str(tree),
            input=stdin,
            env=tree_env(tree),
        )
    except FileNotFoundError:
        raise GitUnavailable("git not found — cannot hold the tree at the index") from None
    if out.returncode != 0:
        raise GitUnavailable(f"git {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def git_paths(*args: str, root: pathlib.Path | None = None) -> list[str]:
    """The NUL-separated paths a git command reports."""
    raw = git(*args, root=root)
    return [p for p in raw.split("\0") if p]


def _copy_aside(source: pathlib.Path, target: pathlib.Path) -> None:
    """Copy a file aside, keeping what a file *is* and not only its bytes.

    A symlink is copied as a symlink: restoring it as the file it points at
    would turn a link into a copy, and the tree after the restore would not be
    the tree before it.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_symlink():
        if target.exists() or target.is_symlink():
            target.unlink()
        os.symlink(os.readlink(source), target)
        return
    shutil.copy2(source, target, follow_symlinks=False)


def _restore_aside(held: pathlib.Path, target: pathlib.Path) -> None:
    """Put a held file back where it came from."""
    target.parent.mkdir(parents=True, exist_ok=True)
    if held.is_symlink():
        if target.exists() or target.is_symlink():
            target.unlink()
        os.symlink(os.readlink(held), target)
        return
    shutil.copy2(held, target, follow_symlinks=False)


def hold(plan_path: pathlib.Path, root: pathlib.Path | None = None) -> int:
    """Put the unstaged work aside and write the index's content over it."""
    root = (root or ROOT).resolve()
    plan_path = plan_path.resolve()
    holder = pathlib.Path(str(plan_path) + ".d")
    if holder.exists():
        print(f"staged-tree: the holder already exists: {holder}", file=sys.stderr)
        return FAILURE
    holder.mkdir(parents=True)

    try:
        modified = git_paths("diff", "--name-only", "-z", root=root)
        untracked = git_paths("ls-files", "--others", "--exclude-standard", "-z", root=root)
    except GitUnavailable as exc:
        shutil.rmtree(holder, ignore_errors=True)
        print(f"staged-tree: {exc}", file=sys.stderr)
        return FAILURE

    plan: dict[str, object] = {
        "root": str(root),
        "holder": str(holder),
        "modified": [],
        "untracked": untracked,
    }

    try:
        for rel in modified:
            absolute = root / rel
            state = "present" if absolute.exists() or absolute.is_symlink() else "absent"
            if state == "present":
                _copy_aside(absolute, holder / "modified" / rel)
            plan["modified"].append({"path": rel, "state": state})  # type: ignore[union-attr]

        for rel in untracked:
            absolute = root / rel
            if not absolute.exists() and not absolute.is_symlink():
                continue
            _copy_aside(absolute, holder / "untracked" / rel)
            absolute.unlink()

        # The index's version, written over the working tree's. `-z` makes the
        # path list NUL-separated, which is the only form that survives a path
        # with a newline in it.
        if modified:
            git(
                "checkout-index",
                "--force",
                "-z",
                "--stdin",
                stdin="\0".join(modified) + "\0",
                root=root,
            )

        plan_path.parent.mkdir(parents=True, exist_ok=True)
        plan_path.write_text(json.dumps(plan, indent=2), encoding="utf-8")
    except GitUnavailable as exc:
        print(f"staged-tree: {exc}", file=sys.stderr)
        print(f"staged-tree: the held state is at {holder}", file=sys.stderr)
        return FAILURE
    except OSError as exc:
        print(f"staged-tree: {exc}", file=sys.stderr)
        print(f"staged-tree: the held state is at {holder}", file=sys.stderr)
        return FAILURE

    if modified or untracked:
        print(
            f"staged-tree: holding {len(modified)} modified and "
            f"{len(untracked)} untracked file(s) aside; the working tree now "
            f"carries the index."
        )
    return CLEAN


def restore(plan_path: pathlib.Path, root: pathlib.Path | None = None) -> int:
    """Put the held work back, exactly as it was."""
    plan_path = plan_path.resolve()
    if not plan_path.is_file():
        print(f"staged-tree: no plan at {plan_path}", file=sys.stderr)
        return FAILURE
    try:
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"staged-tree: the plan at {plan_path} cannot be read: {exc}", file=sys.stderr)
        return FAILURE

    holder = pathlib.Path(plan["holder"])
    root = (root or pathlib.Path(plan["root"])).resolve()

    failures: list[str] = []
    for entry in plan["modified"]:
        rel = entry["path"]
        target = root / rel
        if entry["state"] == "present":
            try:
                _restore_aside(holder / "modified" / rel, target)
            except OSError as exc:
                failures.append(f"{rel}: {exc}")
        else:
            try:
                if target.exists() or target.is_symlink():
                    target.unlink()
            except OSError as exc:
                failures.append(f"{rel}: {exc}")

    for rel in plan["untracked"]:
        target = root / rel
        try:
            if target.exists() or target.is_symlink():
                target.unlink()
            _restore_aside(holder / "untracked" / rel, target)
        except OSError as exc:
            failures.append(f"{rel}: {exc}")

    if failures:
        print("staged-tree: these files could not be put back:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        print(f"staged-tree: the held state is at {holder}", file=sys.stderr)
        print("staged-tree: it was not deleted — nothing is lost.", file=sys.stderr)
        return FAILURE

    shutil.rmtree(holder, ignore_errors=True)
    plan_path.unlink(missing_ok=True)
    return CLEAN


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="action", required=True)

    hold_parser = sub.add_parser("hold", help="Hold the unstaged work aside.")
    hold_parser.add_argument("--plan", required=True, type=pathlib.Path)
    hold_parser.add_argument(
        "--root",
        type=pathlib.Path,
        default=None,
        help=(
            "the repository to hold. Default: the repository this script lives "
            "in. The option exists so that a copy of this script, kept outside "
            "the tree, can still hold and restore it — a copy inside the tree "
            "is itself held, and a restore that cannot run is a restore that "
            "does not happen."
        ),
    )

    restore_parser = sub.add_parser("restore", help="Put the held work back.")
    restore_parser.add_argument("--plan", required=True, type=pathlib.Path)
    restore_parser.add_argument(
        "--root",
        type=pathlib.Path,
        default=None,
        help="the repository to restore into. Default: the one the plan names.",
    )

    args = parser.parse_args(argv)
    try:
        if args.action == "hold":
            return hold(args.plan, args.root)
        return restore(args.plan, args.root)
    except GitUnavailable as exc:
        print(f"staged-tree: {exc}", file=sys.stderr)
        return FAILURE


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
