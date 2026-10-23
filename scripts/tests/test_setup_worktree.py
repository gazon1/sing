"""Tests for setup-worktree.sh — the keepalive it must NOT put on the SSH connection.

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


if __name__ == '__main__':
    unittest.main()
