"""Tests for setup-worktree.sh — the keepalive it puts on the SSH connection.

The pre-push hook takes a detekt verdict, and git opens its SSH connection to
the remote *before* the hook runs. A verdict that takes minutes leaves the
connection idle, GitHub closes it, and the push fails with 141 — after the
hook printed its own verdict and said the tree was clean. Measured on
2026-10-28, on the first push of the pushed-tree verdict.

The remedy is a client-side keepalive, and the script that prepares a
worktree is where it belongs: the rule is then in the code rather than in the
memory of whoever pushes next.

Two properties are held here:

  - a worktree that has no core.sshCommand gets one with a keepalive;
  - a worktree that already has a core.sshCommand keeps it — the value may
    carry a different binary, a port or an identity, and overwriting it would
    break the push in a way nobody would read as this.
"""

import pathlib
import subprocess
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'setup-worktree.sh'

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
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True)
        scripts = self.repo / 'scripts'
        scripts.mkdir()
        stub = scripts / 'setup-gradle-home.sh'
        stub.write_text(STUB_GRADLE_HOME, encoding='utf-8')
        stub.chmod(0o755)
        target = scripts / 'setup-worktree.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        self.script = target

    def _run(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(self.script)],
            cwd=self.repo,
            capture_output=True,
            text=True,
        )

    def _ssh_command(self) -> str:
        result = subprocess.run(
            ['git', 'config', '--get', 'core.sshCommand'],
            cwd=self.repo,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()

    def test_a_worktree_without_a_ssh_command_gets_one_with_a_keepalive(self):
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        command = self._ssh_command()
        self.assertIn('ServerAliveInterval', command)
        self.assertIn('ssh', command)

    def test_a_configured_ssh_command_is_left_alone(self):
        subprocess.run(
            ['git', 'config', 'core.sshCommand', 'ssh -p 2222 -i /home/me/key'],
            cwd=self.repo,
            check=True,
        )

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('ssh -p 2222 -i /home/me/key', self._ssh_command())


if __name__ == '__main__':
    unittest.main()
