"""Tests for `.githooks/pre-commit` — the wrapper hands the commit to hk.

The checks themselves live in `hk.pkl` and are held by
`scripts/tests/test_hk_config.py`; the compile set has its own tests in
`test_compile_changed.py`. What is left for this file is the seam between git
and hk:

  - the hook runs `hk run pre-commit` (not some other hook, not nothing);
  - hk's exit code is the commit's: a verdict of "failed" must keep the commit
    from happening;
  - a commit in a linked worktree runs the same checks — a worktree is this
    repository's main working mode, and the defect this file was written for
    was a hook that exited 0 in one without checking anything;
  - a missing hk names how to install it, instead of committing unchecked.

`hk` is a stand-in here. The real binary reads a Pkl package over the network
and needs the pinned toolchain, which would make a sub-second hook test slow
and flaky; the stand-in records how it was called and takes its verdict from a
marker file, so these tests are about the wrapper and not about hk.
"""

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'scripts' / 'src'))

from sgate.gatekit.gitenv import sandbox_env  # noqa: E402

HOOK = REPO_ROOT / '.githooks' / 'pre-commit'

#: The stand-in for hk. It writes down its arguments and decides the verdict
#: from a marker file, so a test can make the checks fail without hk.
FAKE_HK = """#!/usr/bin/env bash
here="$(dirname "$0")"
echo "$@" >> "$here/hk-calls.txt"
if [ -f "$here/hk-fails" ]; then
    echo "fake hk: the checks failed"
    exit 1
fi
exit 0
"""

MODULE_FILE = 'shared/src/main/kotlin/Example.kt'


def _git(*args: str, cwd: pathlib.Path, path: str | None = None) -> subprocess.CompletedProcess:
    env = sandbox_env(cwd)
    if path is not None:
        env['PATH'] = path
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=env,
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
        # The stand-in hk lives outside the repository: a file in the tree
        # would be committed by the fixture and would show up in `git status`
        # the way an untracked tool does in a developer's checkout.
        self.bin = self.tmp / 'bin'
        self.bin.mkdir()
        fake_hk = self.bin / 'hk'
        fake_hk.write_text(FAKE_HK, encoding='utf-8')
        fake_hk.chmod(0o755)
        self._write('README.md', 'seed\n')
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', 'seed', '--no-verify', cwd=self.repo)

    def _write(
        self,
        relative: str,
        content: str,
        *,
        executable: bool = False,
    ) -> pathlib.Path:
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        if executable:
            path.chmod(0o755)
        return path

    def _linked_worktree(self) -> pathlib.Path:
        worktree = self.tmp / 'worktree'
        _git('worktree', 'add', '--detach', str(worktree), 'HEAD', cwd=self.repo)
        return worktree

    def _commit(
        self,
        where: pathlib.Path,
        relative: str = MODULE_FILE,
        *,
        path: str | None = None,
    ) -> subprocess.CompletedProcess:
        (self.bin / 'hk-calls.txt').unlink(missing_ok=True)
        target = where / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('change\n', encoding='utf-8')
        _git('add', '-A', cwd=where)
        env = sandbox_env(where)
        env['PATH'] = f'{self.bin}{os.pathsep}{os.environ["PATH"]}' if path is None else path
        return subprocess.run(
            ['git', 'commit', '-m', 'change'],
            cwd=where,
            capture_output=True,
            text=True,
            env=env,
        )

    def _calls(self) -> str:
        marker = self.bin / 'hk-calls.txt'
        return marker.read_text(encoding='utf-8') if marker.is_file() else ''

    def test_the_hook_runs_the_pre_commit_hook_through_hk(self):
        result = self._commit(self.repo)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('run pre-commit', self._calls())
        self.assertTrue(_committed(self.repo))

    def test_a_commit_in_a_linked_worktree_runs_the_checks(self):
        """A worktree is the main working mode, so it is the case that matters."""
        worktree = self._linked_worktree()

        result = self._commit(worktree)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('run pre-commit', self._calls())

    def test_a_failing_hk_stops_the_commit(self):
        (self.bin / 'hk-fails').write_text('', encoding='utf-8')

        result = self._commit(self.repo)

        self.assertIn('run pre-commit', self._calls(), 'hk never ran, so this is not about its verdict')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(_committed(self.repo), 'the commit happened even though hk failed')

    def test_a_missing_hk_stops_the_commit_and_says_how_to_install_it(self):
        no_hk = self.tmp / 'no-hk'
        no_hk.mkdir()
        for program in ('bash', 'git'):
            (no_hk / program).symlink_to(shutil.which(program))

        result = self._commit(self.repo, path=str(no_hk))

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(_committed(self.repo), 'the commit happened without any check')
        self.assertIn('mise install', result.stdout + result.stderr)


def _committed(where: pathlib.Path) -> bool:
    """Whether the branch carries the commit that was attempted."""
    completed = _git('log', '--oneline', '-1', cwd=where)
    return 'change' in completed.stdout


if __name__ == '__main__':
    unittest.main()
