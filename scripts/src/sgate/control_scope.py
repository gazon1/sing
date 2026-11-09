"""sgate.control_scope — which controls this change can have broken.

A control proves that one gate can fail. That proof belongs to the gate's own
script, to the file the control edits, and to the modules those import — not to
the rest of the repository. So the control phase can be scoped: a change that
touches none of them cannot have broken the control, and the verdict that
proved the gate on main still proves it here.

The scope is deliberately coarse where it cannot be made precise, and precise
where it can:

* a change to a **gate script** affects that gate and no other — the gates are
  separate processes over separate files;
* a change to a **control target** affects the gate whose control edits it;
* a change to any **other file under `scripts/`** affects every gate, because
  a gate script may import it and this module does not resolve the import
  graph;
* a change under `scripts/tests/` affects no gate — those are the tests of the
  gate scripts, not the gate scripts;
* a change anywhere else affects a gate only when it is that gate's target.

`changed_files()` returns None when the scope cannot be read, and None means
**prove everything**: a scope nobody can compute is not a scope, and a control
phase that silently proves nothing is the defect this module exists to avoid.
"""

from __future__ import annotations

import os
import subprocess
import sys
from collections.abc import Iterable, Mapping
from pathlib import Path

#: The tree this package lives in. A module of the package is imported as
#: `sgate.control_scope`, but the gate script loads it by path — and a module
#: loaded by path has no package to import from. Putting the tree on the path
#: here gives both callers the same answer.
_SRC = Path(__file__).resolve().parents[1]
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from sgate.gatekit.gitenv import tree_env  # noqa: E402

#: The refs the change is measured against, in order. The first one that
#: resolves wins; `origin/main` is the branch a pull request is measured
#: against, and the others are what a local checkout of that branch looks like.
BASE_CANDIDATES = (
    "origin/main",
    "main",
    "refs/remotes/origin/main",
    "refs/heads/main",
)

#: The tree whose files are the gate scripts' own tests, not gate scripts.
TEST_PREFIX = "scripts/tests/"

#: The tree the gate scripts live in.
SCRIPT_PREFIX = "scripts/"


#: This checkout's own root. The scope is about THIS repository, so the git
#: commands below read this tree and no other: a caller-chosen tree is a tree
#: whose git variables the command would inherit.
ROOT = Path(__file__).resolve().parents[3]


def _git(*args: str) -> tuple[int, str]:
    # `tree_env()` drops the variables git exports to a hook: this command
    # must mean the repository it discovers, not the one the hook runs in.
    proc = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    return proc.returncode, proc.stdout


def _merge_base(base: str) -> str | None:
    code, out = _git("merge-base", base, "HEAD")
    found = out.strip()
    if code != 0 or not found:
        return None
    return found


def _base_ref() -> str | None:
    """The ref this change is measured against, or None when there is none."""
    explicit = os.environ.get("SGATE_BASE_REF", "").strip()
    candidates = (explicit,) if explicit else BASE_CANDIDATES
    for candidate in candidates:
        code, _ = _git("rev-parse", "--verify", "--quiet", f"{candidate}^{{commit}}")
        if code == 0:
            return candidate
    return None


def changed_files() -> set[str] | None:
    """The files this change touches, or None when the scope cannot be read.

    None comes back when there is no base ref to measure against, when the
    merge-base cannot be found, and when the change is empty — an empty diff
    means "we cannot tell what changed", not "nothing changed".
    """
    base = _base_ref()
    if base is None:
        return None
    merge_base = _merge_base(base)
    if merge_base is None:
        return None
    code, out = _git("diff", "--name-only", "--diff-filter=ACMR", "-z", merge_base)
    if code != 0:
        return None
    files = {path for path in out.split("\0") if path}
    if not files:
        return None
    return files


def affected_gates(
    changed: Iterable[str],
    gate_files: Mapping[str, Iterable[str]],
) -> dict[str, str]:
    """The gates this change can have broken, and the change that put each in scope.

    *gate_files* maps a gate name to the files that belong to it: its script
    and the target its control edits.
    """
    changed = set(changed)
    files_by_gate = {name: set(paths) for name, paths in gate_files.items()}
    script_owners: dict[str, set[str]] = {}
    for name, paths in files_by_gate.items():
        for path in paths:
            if path.startswith(SCRIPT_PREFIX) and not path.startswith(TEST_PREFIX):
                script_owners.setdefault(path, set()).add(name)

    scope: dict[str, str] = {}
    for path in sorted(changed):
        if path.startswith(TEST_PREFIX):
            # The gate scripts' own tests. A test that changes does not change
            # what the gate does.
            continue
        if path.startswith(SCRIPT_PREFIX):
            owners = script_owners.get(path)
            if owners:
                for name in owners:
                    scope.setdefault(name, f"the gate script {path} changed")
            else:
                # A script that is not a gate script: the gate scripts may
                # import it, and this module does not resolve the import graph.
                for name in files_by_gate:
                    scope.setdefault(name, f"{path} changed, and a gate script may import it")
            continue
        for name, paths in files_by_gate.items():
            for owned in paths:
                if owned and (path == owned or path.startswith(f"{owned.rstrip('/')}/")):
                    scope.setdefault(name, f"the control target {owned} changed")
    return scope


__all__ = ["BASE_CANDIDATES", "affected_gates", "changed_files"]
