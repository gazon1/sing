"""The environment a fixture's git commands run in, and the check that they stayed there.

Git exports `GIT_DIR` (and `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`)
for the hooks it runs. A fixture that creates a repository *inside a hook*
inherits them, and every git command it runs then means something else:

  - `git init` reinitialises the repository the hook is about, not the
    fixture's own directory;
  - `git add -A` stages the fixture's directory against *that* repository's
    index, so every tracked file of the developer's project looks deleted;
  - `git commit` writes the fixture's tree onto the developer's branch.

That is not a hypothetical. On 2026-10-23 a fixture committed a 12124-file
deletion into the repository it ran in, the same tree reached the remote
`main`, and the shared configuration ended up carrying an `sshCommand` that
pointed at a port and an identity file that do not exist here — after which
every fetch and every push in every worktree of this repository failed.

The fixtures therefore run their git commands with those names removed — the
same scrubbing `scripts/pre-push-verdict.py` does for the tree it judges — and
with a ceiling that stops git from walking up past the fixture's own
directory. The assertion below is the second half of the contract: the
fixture's repository is its own repository, and the test says so when it is
not.
"""

from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / 'src'))

from sgate.gatekit.gitenv import INHERITED_FROM_HOOK, sandbox_env, tree_env  # noqa: E402,F401


def git(*args: str, cwd: pathlib.Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
    """A git command that must succeed."""
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=env,
    )


def git_output(*args: str, cwd: pathlib.Path, env: dict[str, str] | None = None) -> str:
    """The output of a git command that must succeed."""
    result = git(*args, cwd=cwd, env=env)
    return result.stdout.strip()


def assert_own_repository(case: unittest.TestCase, repo: pathlib.Path, env: dict[str, str]) -> None:
    """The repository is the fixture's own, not the one the hook is about."""
    toplevel = git_output('rev-parse', '--show-toplevel', cwd=repo, env=env)
    case.assertEqual(
        repo.resolve(),
        pathlib.Path(toplevel).resolve(),
        'the fixture repository is not its own repository',
    )
