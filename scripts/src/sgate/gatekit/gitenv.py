"""The environment a git command runs in when its caller is a hook.

Git exports `GIT_DIR` (and `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`)
for the hooks it runs, and they point at the repository the hook is about. A
command that means *the repository in this directory* must not inherit them:

  - `git config` answers about somebody else's repository;
  - `git init` reinitialises that repository instead of creating a new one;
  - `git add -A` stages this directory against that repository's index, so
    every tracked file of the developer's project looks deleted;
  - `git commit` writes this directory's tree onto the developer's branch.

That last chain is not a theory: on 2026-10-23 a test fixture ran inside the
pre-push hook, inherited the names, and committed a 12124-file deletion into
the working repository — the same tree reached the remote `main`. See
`docs/decisions/2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md`.

The scrubbing is what `scripts/pre-push-verdict.py` already did for the tree it
judges; it lives here so that the gates and the tests have one answer.
"""

from __future__ import annotations

import os
import pathlib

#: The variables git exports for a hook. A command that means "this
#: repository" must not inherit them.
INHERITED_FROM_HOOK = ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR')


def tree_env() -> dict[str, str]:
    """An environment in which git means the repository it discovers."""
    env = dict(os.environ)
    for name in INHERITED_FROM_HOOK:
        env.pop(name, None)
    return env


def sandbox_env(root: pathlib.Path) -> dict[str, str]:
    """`tree_env`, and the upward walk stops at `root`.

    The ceiling is the second half of the contract: a command that does not
    find a repository of its own fails instead of finding one further up.
    """
    env = tree_env()
    env['GIT_CEILING_DIRECTORIES'] = str(root)
    return env
