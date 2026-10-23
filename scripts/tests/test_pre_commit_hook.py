"""Tests for `.githooks/pre-commit` — the compile happens in a worktree too.

The defect this exists for: the hook exited 0 in any linked worktree, without
compiling anything. A linked worktree is this repository's main working mode,
so the common case was the unchecked one: a commit in a worktree could not
compile, and the breakage was found in CI — or, when the merge went through
without CI, not at all.

ADR 2026-09-25-git-hooks-worktree-isolation says worktrees get a "compile-only"
check. The implementation gave them nothing, and the gap was invisible: the
only thing that tested the hook was the hook, and the hook skipped.

These tests drive the real hook through a real `git commit` in a temporary
repository, in a temporary linked worktree, with a stand-in for the Gradle
wrapper that records what it was asked to compile:

  - a commit in a worktree COMPILES (the wrapper is asked for the compile);
  - a commit in a worktree whose compile FAILS does not happen (this is the
    regression: before, the commit succeeded because nothing was compiled);
  - a commit in the main checkout still compiles.

Nothing here touches the developer's repository: the repository, the worktree
and the hook copy are all temporary, and the Gradle wrapper is a stand-in, so
the tests take well under a second and need no build.
"""

import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'scripts' / 'src'))

from sgate.gatekit.gitenv import tree_env as _clean_git_env  # noqa: E402

HOOK = REPO_ROOT / '.githooks' / 'pre-commit'

#: The stand-in for the Gradle wrapper. It writes down what it was asked to
#: do and decides the verdict from a marker file, so a test can make the
#: compile fail without a build.
FAKE_GW = """#!/usr/bin/env bash
echo "$@" >> "$(dirname "$0")/gw-calls.txt"
if [ -f "$(dirname "$0")/gw-fails" ]; then
    echo "fake gw: the compile failed"
    exit 1
fi
exit 0
"""

#: The lint step the hook runs before the compile.
PASSING_LINT = """#!/usr/bin/env python3
import sys
sys.exit(0)
"""


def _git(*args: str, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=_clean_git_env(),
    )


class PreCommitHookTest(unittest.TestCase):
    """The hook, driven through a real commit."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-commit-hook-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.repo)
        _git('config', 'user.email', 'hook@example.com', cwd=self.repo)
        _git('config', 'user.name', 'Hook Test', cwd=self.repo)
        _git('config', 'commit.gpgsign', 'false', cwd=self.repo)
        _git('config', 'core.hooksPath', '.githooks', cwd=self.repo)
        self._write('.githooks/pre-commit', HOOK.read_text(encoding='utf-8'), executable=True)
        self._write('gw', FAKE_GW, executable=True)
        self._write('scripts/lint-changed.py', PASSING_LINT)
        (self.repo / 'README.md').write_text('seed\n', encoding='utf-8')
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', 'seed', cwd=self.repo)

    def _write(self, relative: str, content: str, *, executable: bool = False) -> pathlib.Path:
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        if executable:
            path.chmod(0o755)
        return path

    def _linked_worktree(self) -> pathlib.Path:
        """A linked worktree of the fixture repository — where the defect lived."""
        worktree = self.tmp / 'worktree'
        _git('worktree', 'add', '--detach', str(worktree), 'HEAD', cwd=self.repo)
        # The worktree has its own checkout, so the hook and the stubs have to
        # be there too — the hook resolves its root from Git, not from its own
        # path.
        shutil.copy2(self.repo / '.githooks' / 'pre-commit', worktree / '.githooks' / 'pre-commit')
        (worktree / '.githooks' / 'pre-commit').chmod(0o755)
        shutil.copy2(self.repo / 'gw', worktree / 'gw')
        (worktree / 'gw').chmod(0o755)
        (worktree / 'scripts').mkdir(exist_ok=True)
        shutil.copy2(self.repo / 'scripts' / 'lint-changed.py', worktree / 'scripts' / 'lint-changed.py')
        return worktree

    def _commit(self, where: pathlib.Path) -> subprocess.CompletedProcess:
        (where / 'change.txt').write_text('change\n', encoding='utf-8')
        _git('add', '-A', cwd=where)
        return subprocess.run(
            ['git', 'commit', '-m', 'change'],
            cwd=where,
            capture_output=True,
            text=True,
            env=_clean_git_env(),
        )

    def _calls(self, where: pathlib.Path) -> str:
        marker = where / 'gw-calls.txt'
        return marker.read_text(encoding='utf-8') if marker.is_file() else ''

    def test_a_commit_in_a_worktree_compiles(self):
        worktree = self._linked_worktree()

        result = self._commit(worktree)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(':shared:compileKotlinJvm', self._calls(worktree))

    def test_a_commit_in_a_worktree_that_does_not_compile_does_not_happen(self):
        """The regression: before, the worktree commit succeeded anyway.

        The compile is the whole point of the hook in a worktree, so a compile
        that fails must stop the commit — otherwise the check costs minutes and
        buys nothing.
        """
        worktree = self._linked_worktree()
        (worktree / 'gw-fails').write_text('', encoding='utf-8')

        result = self._commit(worktree)

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(
            (worktree / '.git').exists() and _committed(worktree),
            'the commit happened even though the compile failed',
        )

    def test_a_commit_in_the_main_checkout_still_compiles(self):
        result = self._commit(self.repo)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(':shared:compileKotlinJvm', self._calls(self.repo))


def _committed(worktree: pathlib.Path) -> bool:
    """Whether the worktree's branch carries the commit that was attempted."""
    completed = subprocess.run(
        ['git', 'log', '--oneline', '-1'],
        cwd=worktree,
        capture_output=True,
        text=True,
        env=_clean_git_env(),
    )
    return 'change' in completed.stdout
