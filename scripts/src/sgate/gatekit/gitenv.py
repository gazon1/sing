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

#: The identity a commit made inside the sandbox carries. Git reads these
#: before any configuration file, so a commit in the sandbox is attributed to
#: the fixture and to nobody else.
SANDBOX_IDENTITY = {
    'GIT_AUTHOR_NAME': 'Sandbox Fixture',
    'GIT_AUTHOR_EMAIL': 'sandbox@example.com',
    'GIT_COMMITTER_NAME': 'Sandbox Fixture',
    'GIT_COMMITTER_EMAIL': 'sandbox@example.com',
}


#: The shell helper that runs a command without the environment a hook exports.
#:
#: The name is part of the answer: the hook calls the helper, the gate that
#: checks the scripts reads the name, and the test that reads the hook reads
#: the name — so a script that scrubs the environment in some other way is
#: visible as a script that does something else.
SHELL_HELPER = "without_hook_git_env"


def shell_unset_args() -> str:
    """The `env` arguments that drop the variables a hook exports.

    The shell cannot import this module — the hook runs where the package is
    out of reach — so the answer is printed and read. This is the one place
    the names are written down, and the shell side of it carries no copy.
    """
    return " ".join(f"-u {name}" for name in INHERITED_FROM_HOOK)


def tree_env() -> dict[str, str]:
    """An environment in which git means the repository it discovers."""
    env = dict(os.environ)
    for name in INHERITED_FROM_HOOK:
        env.pop(name, None)
    return env


def sandbox_env(root: pathlib.Path) -> dict[str, str]:
    """`tree_env`, the upward walk stops at `root`, and commits can be made.

    The ceiling is the second half of the contract: a command that does not
    find a repository of its own fails instead of finding one further up.

    The identity is the third. Git refuses a commit it cannot attribute
    ("Author identity unknown", exit 128), and where that identity comes from
    is the environment: the developer's global `~/.gitconfig` on a workstation,
    and nothing at all on a CI runner. A fixture that commits under the sandbox
    therefore worked on the machine that wrote it and failed in CI — a test
    that only passes where the developer has a global configuration. The
    sandbox carries the identity itself, and because git reads these names
    before any configuration file, the commit is attributed to the fixture
    rather than to the developer, and no configuration file is read or written.
    """
    env = tree_env()
    env['GIT_CEILING_DIRECTORIES'] = str(root)
    env.update(SANDBOX_IDENTITY)
    return env


if __name__ == '__main__':
    import sys

    if sys.argv[1:] == ['--shell-unset-args']:
        print(shell_unset_args())
    else:
        print('usage: gitenv.py --shell-unset-args', file=sys.stderr)
        raise SystemExit(2)
