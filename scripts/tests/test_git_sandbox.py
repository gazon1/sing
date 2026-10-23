"""A fixture's git commands must mean the fixture's repository, not the hook's.

This is the incident of 2026-10-23, written down as a test.

Git exports `GIT_DIR` (and `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`)
for the hook it runs. A test that creates a repository of its own, inside a
hook, inherits those, and every git command it runs then means something else:
`git init` reinitialises the repository the hook is about, `git add -A` stages
the test's directory against *that* repository's index — every tracked file of
the developer's project looks deleted — and `git commit` writes the test's tree
onto the developer's branch.

That is not a story: a fixture committed a 12124-file deletion into the
working repository, the same tree reached the remote `main`, and the shared
configuration ended up carrying an `sshCommand` that pointed at a port and an
identity file that do not exist here.

So the tests here do two things. The first shows the trap is real — with
`GIT_DIR` set, as a hook sets it, a command in the fixture's directory means
the *outer* repository. The second shows the fix holds: with the sandbox
environment, the same command means the fixture's own repository, and a commit
made under the hook's `GIT_DIR` lands in the fixture and nowhere else.
"""

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from git_sandbox import git as _git, sandbox_env  # noqa: E402


class GitSandboxTest(unittest.TestCase):
    """The sandbox, checked against the environment a hook provides."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='git-sandbox-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.outer = self.tmp / 'outer'
        self.outer.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.outer, env=sandbox_env(self.tmp))
        (self.outer / 'tracked.txt').write_text('tracked\n', encoding='utf-8')
        _git('add', '-A', cwd=self.outer, env=sandbox_env(self.tmp))
        _git('commit', '-q', '-m', 'the project', cwd=self.outer, env=sandbox_env(self.tmp))
        self.outer_head = _git('rev-parse', 'HEAD', cwd=self.outer, env=sandbox_env(self.tmp)).stdout.strip()

    def _as_a_hook(self):
        """The environment git hands to a hook: GIT_DIR points at this repository.

        It is set in the *ambient* environment, the way a hook receives it, so
        that the sandbox is checked against the real thing rather than against
        a hand-made copy of it.
        """
        return mock.patch.dict(os.environ, {
            'GIT_DIR': str(self.outer / '.git'),
            'GIT_WORK_TREE': str(self.outer),
        })

    def _fixture(self) -> pathlib.Path:
        repo = self.tmp / 'fixture'
        repo.mkdir()
        return repo

    # ── the trap ──────────────────────────────────────────────────────────

    def test_without_the_sandbox_a_command_in_the_fixture_means_the_outer_repository(self):
        """The trap is real: this is what the fixture inherited on 2026-10-23."""
        repo = self._fixture()

        with self._as_a_hook():
            toplevel = _git('rev-parse', '--show-toplevel', cwd=repo, env=dict(os.environ)).stdout.strip()

        self.assertEqual(
            self.outer.resolve(),
            pathlib.Path(toplevel).resolve(),
            'the trap this test guards against no longer holds — check the incident',
        )

    # ── the fix ───────────────────────────────────────────────────────────

    def test_the_sandbox_makes_the_command_mean_the_fixtures_own_repository(self):
        repo = self._fixture()

        with self._as_a_hook():
            env = sandbox_env(self.tmp)  # the hook's variables are in the environment it copies
            _git('init', '-q', '-b', 'main', cwd=repo, env=env)
            toplevel = _git('rev-parse', '--show-toplevel', cwd=repo, env=env).stdout.strip()

        self.assertEqual(repo.resolve(), pathlib.Path(toplevel).resolve())

    def test_a_commit_under_the_hook_lands_in_the_fixture_and_nowhere_else(self):
        repo = self._fixture()

        with self._as_a_hook():
            env = sandbox_env(self.tmp)
            _git('init', '-q', '-b', 'main', cwd=repo, env=env)
            (repo / 'a.txt').write_text('a\n', encoding='utf-8')
            _git('add', '-A', cwd=repo, env=env)
            _git('commit', '-q', '-m', 'a file', cwd=repo, env=env)

        fixture_head = _git('rev-parse', 'HEAD', cwd=repo, env=sandbox_env(self.tmp)).stdout.strip()
        outer_head = _git('rev-parse', 'HEAD', cwd=self.outer, env=sandbox_env(self.tmp)).stdout.strip()

        self.assertEqual(self.outer_head, outer_head, 'the commit landed in the outer repository')
        self.assertEqual(
            'a file',
            _git('log', '-1', '--format=%s', cwd=repo, env=sandbox_env(self.tmp)).stdout.strip(),
        )
        self.assertEqual('tracked\n', (self.outer / 'tracked.txt').read_text(encoding='utf-8'))

    def test_the_outer_repository_still_has_its_own_history(self):
        """The outer repository is a repository like any other: one commit, its own file."""
        log = _git('log', '--format=%s', cwd=self.outer, env=sandbox_env(self.tmp)).stdout.splitlines()
        self.assertEqual(['the project'], log)

    # ── the identity ───────────────────────────────────────────────────────

    def test_the_sandbox_can_commit_where_there_is_no_git_identity(self):
        """A commit needs an author, and a runner may have none to give.

        Git refuses a commit it cannot attribute, and it looks for the
        identity in the environment first and in the configuration files after.
        A workstation has a global `~/.gitconfig`; a runner may have none — and
        may have `user.useConfigOnly` set, which is exactly the setting that
        forbids git's own guess at an identity. The fixture then committed on
        the machine that wrote it and failed everywhere else, and the failure
        said only "exit status 128": a reader of a red CI run saw a number and
        no reason.

        So the sandbox carries the identity, and this test removes every place
        the identity could come from — the global configuration, the system
        configuration, and the environment the developer has — and forbids the
        guess the same way the runner does.
        """
        repo = self._fixture()
        env = sandbox_env(self.tmp)
        env['GIT_CONFIG_GLOBAL'] = str(self.tmp / 'no-global-config')
        env['GIT_CONFIG_SYSTEM'] = str(self.tmp / 'no-system-config')
        env['GIT_CONFIG_NOSYSTEM'] = '1'
        # The setting a runner may carry: git must not guess an identity.
        env['GIT_CONFIG_COUNT'] = '1'
        env['GIT_CONFIG_KEY_0'] = 'user.useConfigOnly'
        env['GIT_CONFIG_VALUE_0'] = 'true'

        _git('init', '-q', '-b', 'main', cwd=repo, env=env)
        (repo / 'a.txt').write_text('a\n', encoding='utf-8')
        _git('add', '-A', cwd=repo, env=env)
        _git('commit', '-q', '-m', 'a file', cwd=repo, env=env)

        author = _git(
            'log', '-1', '--format=%an <%ae>', cwd=repo, env=env,
        ).stdout.strip()
        self.assertEqual('Sandbox Fixture <sandbox@example.com>', author)

    def test_the_sandbox_identity_does_not_come_from_the_developers_config(self):
        """The commit belongs to the fixture, not to whoever ran the test."""
        repo = self._fixture()
        env = sandbox_env(self.tmp)

        _git('init', '-q', '-b', 'main', cwd=repo, env=env)
        (repo / 'a.txt').write_text('a\n', encoding='utf-8')
        _git('add', '-A', cwd=repo, env=env)
        _git('commit', '-q', '-m', 'a file', cwd=repo, env=env)

        author = _git(
            'log', '-1', '--format=%an <%ae>', cwd=repo, env=env,
        ).stdout.strip()
        self.assertEqual('Sandbox Fixture <sandbox@example.com>', author)

    def test_a_failing_git_command_says_what_git_said(self):
        """A red CI run must show the reason, not only the exit code.

        `subprocess` captures git's stderr and drops it: the exception carries
        the output, but its message does not. So the reason the reader needs
        was in the machine and not in the report.
        """
        repo = self._fixture()
        env = sandbox_env(self.tmp)
        _git('init', '-q', '-b', 'main', cwd=repo, env=env)

        with self.assertRaises(subprocess.CalledProcessError) as caught:
            _git('rev-parse', '--verify', 'no-such-ref', cwd=repo, env=env)

        message = str(caught.exception)
        self.assertIn('no-such-ref', message, 'the message must name the command')
        self.assertIn('fatal', message, "the message must carry git's own words")


if __name__ == '__main__':
    unittest.main()
