"""Tests for setup-worktree.sh — the SSH keepalive it must NOT set, and the bare flag it MUST.

The pre-push hook takes a detekt verdict, and git opens its SSH connection to
the remote *before* the hook runs. A verdict that takes minutes leaves the
connection idle, GitHub closes it, and the push fails with 141 — after the
hook printed its own verdict and said the tree was clean. Measured on
2026-10-28, on the first push of the pushed-tree verdict.

The remedy is a client-side keepalive. It now lives in the SHARED config,
written by the `just setup-hooks` recipe: the keepalive is harmless in every
worktree, and a per-worktree copy had to be applied by hand in each one — so
a fresh worktree, or the canonical checkout, could still lose the connection.

Two properties are held here:

  - the worktree script sets no core.sshCommand of its own — the shared
    config owns that value now;
  - a core.sshCommand that already exists is left alone — the value may carry
    a different binary, a port or an identity, and overwriting it would break
    the push in a way nobody would read as this.

The second half of the file covers the other half of the script: the
`extensions.worktreeConfig` switch and the `core.bare = false` that has to
live in every worktree's own config once that switch is on.
"""

import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'setup-worktree.sh'
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from git_sandbox import assert_own_repository, git as _git, sandbox_env  # noqa: E402

# The script asks for a Gradle home before it does anything else; a stub keeps
# the test from touching the developer's real one.
STUB_GRADLE_HOME = """#!/usr/bin/env bash
echo "$(mktemp -d)"
"""


class SetupWorktreeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-worktree-'))
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.repo, env=self._env())
        scripts = self.repo / 'scripts'
        scripts.mkdir()
        stub = scripts / 'setup-gradle-home.sh'
        stub.write_text(STUB_GRADLE_HOME, encoding='utf-8')
        stub.chmod(0o755)
        target = scripts / 'setup-worktree.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        self.script = target
        assert_own_repository(self, self.repo, self._env())

    def _env(self) -> dict[str, str]:
        """An environment in which git cannot reach past the fixture's own tree.

        The script under test writes `core.sshCommand` into the repository it is
        run in. Git exports `GIT_DIR` for the hooks it runs, and a fixture that
        runs inside a hook inherits it — so the write lands in the developer's
        repository instead of the fixture's. That is how this repository ended
        up with an ssh command that pointed at a port and an identity file that
        do not exist here, and why every fetch and push failed. The scrubbing
        and the ceiling make the write fail instead, and the test says so.
        """
        return sandbox_env(self.tmp)

    def _run(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(self.script)],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=self._env(),
        )

    def _ssh_command(self) -> str:
        """The effective core.sshCommand (worktree value, else shared value)."""
        result = subprocess.run(
            ['git', 'config', '--get', 'core.sshCommand'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=self._env(),
        )
        return result.stdout.strip()

    def test_the_script_sets_no_ssh_command(self):
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('', self._ssh_command())
        self.assertNotIn('ServerAliveInterval', result.stdout)

    def test_a_configured_ssh_command_is_left_alone(self):
        _git(
            'config', 'core.sshCommand', 'ssh -p 2222 -i /home/me/key',
            cwd=self.repo, env=self._env(),
        )

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('ssh -p 2222 -i /home/me/key', self._ssh_command())


class SetupWorktreeBareRepoTest(unittest.TestCase):
    """The bare flag — a repo-wide switch that breaks every worktree at once.

    `extensions.worktreeConfig` is repo-wide, and turning it on makes git read
    `core.bare` from the PER-WORKTREE config. A worktree created before the
    switch has no `core.bare = false` there, so it is taken for a bare
    repository and every command that needs a work tree dies with "fatal: this
    operation must be run in a work tree". Measured on 2026-10-23: one run of
    the setup script in one worktree made all 39 worktrees of this repository
    unusable, and the shared `core.bare` was corrupted on the way.

    Four properties are held here, each on a throwaway repository — the real
    worktrees of this machine are never touched:

      - a worktree without the flag is repaired, and works afterwards;
      - the OTHER worktrees of the same repository keep working;
      - the shared config keeps its own `core.bare`, and gains no hooks path;
      - a second run changes nothing.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-worktree-bare-'))
        self.bare = self.tmp / 'bare.git'
        subprocess.run(
            ['git', 'init', '-q', '--bare', str(self.bare)],
            check=True, capture_output=True, text=True,
        )
        self.wt1 = self.tmp / 'wt1'
        self.wt2 = self.tmp / 'wt2'
        for name, path in (('b1', self.wt1), ('b2', self.wt2)):
            subprocess.run(
                ['git', '-C', str(self.bare), 'worktree', 'add', str(path), '-b', name],
                check=True, capture_output=True, text=True,
            )
        self.script = self._install(self.wt1)

    def _install(self, worktree: pathlib.Path) -> pathlib.Path:
        """Copy the script and a Gradle-home stub into the worktree."""
        scripts = worktree / 'scripts'
        scripts.mkdir(exist_ok=True)
        stub = scripts / 'setup-gradle-home.sh'
        stub.write_text(STUB_GRADLE_HOME, encoding='utf-8')
        stub.chmod(0o755)
        target = scripts / 'setup-worktree.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        return target

    def _run(self, worktree: pathlib.Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(worktree / 'scripts' / 'setup-worktree.sh')],
            cwd=worktree,
            capture_output=True,
            text=True,
        )

    def _status(self, worktree: pathlib.Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['git', 'status'], cwd=worktree, capture_output=True, text=True,
        )

    def _config_get(self, config_file: pathlib.Path, key: str) -> str:
        result = subprocess.run(
            ['git', 'config', '--file', str(config_file), '--get', key],
            capture_output=True, text=True,
        )
        return result.stdout.strip()

    def _shared_config(self) -> pathlib.Path:
        return self.bare / 'config'

    def _worktree_config(self, name: str) -> pathlib.Path:
        return self.bare / 'worktrees' / name / 'config.worktree'

    def test_a_worktree_without_the_bare_flag_is_repaired(self):
        """The defect itself: the extension is on, the worktree predates it."""
        subprocess.run(
            ['git', '-C', str(self.bare), 'config', 'extensions.worktreeConfig', 'true'],
            check=True,
        )

        broken = self._status(self.wt1)
        self.assertNotEqual(0, broken.returncode, broken.stdout + broken.stderr)
        self.assertIn('must be run in a work tree', broken.stderr)

        result = self._run(self.wt1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        repaired = self._status(self.wt1)
        self.assertEqual(0, repaired.returncode, repaired.stdout + repaired.stderr)
        config = self._worktree_config('wt1')
        self.assertEqual('false', self._config_get(config, 'core.bare'))
        self.assertEqual('.githooks', self._config_get(config, 'core.hooksPath'))

    def test_the_other_worktrees_keep_working(self):
        """Turning the extension on must not break the worktrees it did not touch."""
        result = self._run(self.wt1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        self.assertEqual(0, self._status(self.wt1).returncode)
        other = self._status(self.wt2)
        self.assertEqual(0, other.returncode, other.stdout + other.stderr)
        self.assertEqual('false', self._config_get(self._worktree_config('wt2'), 'core.bare'))

    def test_the_shared_config_keeps_its_bare_flag(self):
        """A `core.bare = false` in the shared config would un-bare the repository."""
        self.assertEqual('true', self._config_get(self._shared_config(), 'core.bare'))

        result = self._run(self.wt1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        self.assertEqual('true', self._config_get(self._shared_config(), 'core.bare'))
        self.assertEqual('', self._config_get(self._shared_config(), 'core.hooksPath'))

    def test_the_bare_repository_itself_stays_bare(self):
        """The main entry of a bare repository is not a worktree, and must not become one."""
        result = self._run(self.wt1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

        self.assertFalse((self.bare / 'config.worktree').exists())
        is_bare = subprocess.run(
            ['git', '-C', str(self.bare), 'rev-parse', '--is-bare-repository'],
            capture_output=True, text=True,
        )
        self.assertEqual('true', is_bare.stdout.strip())

    def test_running_the_script_twice_changes_nothing(self):
        """Idempotent: the second run leaves both configs byte for byte as they were."""
        first = self._run(self.wt1)
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        worktree_config = self._worktree_config('wt1').read_text(encoding='utf-8')
        shared_config = self._shared_config().read_text(encoding='utf-8')

        second = self._run(self.wt1)
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)

        self.assertEqual(worktree_config, self._worktree_config('wt1').read_text(encoding='utf-8'))
        self.assertEqual(shared_config, self._shared_config().read_text(encoding='utf-8'))
        self.assertEqual(0, self._status(self.wt1).returncode)


if __name__ == '__main__':
    unittest.main()
