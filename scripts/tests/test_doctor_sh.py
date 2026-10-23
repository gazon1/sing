"""Tests for `scripts/doctor.sh` — the repairs it writes, and where it writes them.

The doctor exists because a broken checkout is silent: a lost `core.hooksPath`
means git runs no hooks at all and every push reads as a green one, and a
`core.bare = true` makes every command in the checkout refuse.

The repair used to be written with a plain `git config`, which lands in the
SHARED config — the one every worktree of the clone reads. So one worktree's
repair became twenty checkouts' configuration: an absolute `core.hooksPath`
made every worktree run one checkout's hooks, and a `core.bare = false`
un-bared the repository for everyone at once (ADR
`docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md`).
The gate that holds the doctor and the gate to one declaration
(`scripts/check-checkout-invariants.py` against `config/checkout-invariants.json`)
went red over it, because the doctor wrote an absolute path where the
declaration says a relative one.

Three properties are held here, each on a throwaway repository in a temporary
directory — the real checkout of this machine is never judged, never repaired
and never written to:

  - the repair lands in THIS worktree's own `config.worktree`, and the shared
    config keeps no `core.hooksPath` at all;
  - the verdict does not depend on the directory the doctor was started from:
    the same checkout, started from its root and from a subdirectory, is
    judged the same way and repaired the same way;
  - a relative hooks path that leads nowhere is reported BROKEN, and the report
    names the path relative to the repository root — because that is how git
    resolves it, and a path resolved against the current directory is a
    different path entirely.

The environment is the sandbox the repository already uses
(`scripts/tests/git_sandbox.py`): the variables git exports to a hook are
removed, the upward walk stops at the fixture's own directory, and HOME and the
global git config point inside the temporary directory — so a test that writes
a config value writes it into the fixture and nowhere else.
"""

import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'doctor.sh'
DECLARATION = REPO_ROOT / 'config' / 'checkout-invariants.json'
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from git_sandbox import assert_own_repository, git as _git, sandbox_env  # noqa: E402


class DoctorShTest(unittest.TestCase):
    """The doctor judges a checkout, and repairs it where it is allowed to."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='doctor-sh-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

        self.home = self.tmp / 'home'
        self.home.mkdir()

        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.repo, env=self._env())

        # The hooks the doctor is expected to point at.
        hooks = self.repo / '.githooks'
        hooks.mkdir()
        pre_push = hooks / 'pre-push'
        pre_push.write_text('#!/usr/bin/env bash\nexit 0\n', encoding='utf-8')
        pre_push.chmod(0o755)

        # The private Gradle home the doctor asks about.
        gradle_home = self.repo / 'gw'
        gradle_home.write_text('#!/usr/bin/env bash\nexit 0\n', encoding='utf-8')
        gradle_home.chmod(0o755)

        # The declaration the doctor and the gate both read.
        config = self.repo / 'config'
        config.mkdir()
        (config / 'checkout-invariants.json').write_text(
            DECLARATION.read_text(encoding='utf-8'), encoding='utf-8',
        )

        # A remote, so that the checkout is healthy in every other respect.
        _git('remote', 'add', 'origin', str(self.tmp / 'remote.git'), cwd=self.repo, env=self._env())

        # A subdirectory: the doctor must judge the same checkout from there.
        self.subdir = self.repo / 'nested' / 'deeper'
        self.subdir.mkdir(parents=True)

        self.script = self._install()
        assert_own_repository(self, self.repo, self._env())

    # ── the fixture ─────────────────────────────────────────────────────────

    def _env(self) -> dict[str, str]:
        """The fixture's own environment: git cannot reach past its own tree.

        `sandbox_env` removes the variables git exports to a hook — a fixture
        that inherits `GIT_DIR` writes its configuration into the repository
        the hook is about — and stops the upward walk at the fixture's own
        directory. HOME and the global git config point inside the temporary
        directory, so nothing of the developer's own configuration is read, and
        nothing of it is written.
        """
        env = sandbox_env(self.tmp)
        env['HOME'] = str(self.home)
        env['XDG_CONFIG_HOME'] = str(self.home / '.config')
        env['GIT_CONFIG_GLOBAL'] = str(self.home / '.gitconfig')
        env['GIT_CONFIG_NOSYSTEM'] = '1'
        return env

    def _install(self) -> pathlib.Path:
        """Put the doctor into the fixture, the way a checkout carries it."""
        scripts = self.repo / 'scripts'
        scripts.mkdir(exist_ok=True)
        target = scripts / 'doctor.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        return target

    def _run(self, *args: str, cwd: pathlib.Path | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(self.script), *args],
            cwd=cwd or self.repo,
            capture_output=True,
            text=True,
            env=self._env(),
        )

    def _shared_config(self) -> pathlib.Path:
        """The config every worktree of this repository reads."""
        return self.repo / '.git' / 'config'

    def _worktree_config(self) -> pathlib.Path:
        """This worktree's own config — the only place the doctor may write."""
        return self.repo / '.git' / 'config.worktree'

    def _config_get(self, config_file: pathlib.Path, key: str) -> str:
        result = subprocess.run(
            ['git', 'config', '--file', str(config_file), '--get', key],
            env=self._env(),
            capture_output=True, text=True,
        )
        return result.stdout.strip()

    def _effective(self, key: str) -> str:
        """The value git itself reads in this checkout."""
        result = subprocess.run(
            ['git', 'config', '--get', key],
            cwd=self.repo,
            env=self._env(),
            capture_output=True, text=True,
        )
        return result.stdout.strip()

    def _verdict(self, stdout: str) -> list[str]:
        """The verdict lines — the `OK` and `BROKEN` ones."""
        return [
            line for line in stdout.splitlines()
            if line.startswith('  OK ') or line.startswith('  BROKEN ')
        ]

    # ── the properties ──────────────────────────────────────────────────────

    def test_the_repair_lands_in_this_worktree_and_not_in_the_shared_config(self):
        """The defect: a plain `git config` lands in the config of every worktree."""
        # The extension makes the per-worktree config visible to git — the
        # switch the setup script turns on, and the reason a per-worktree
        # value can be a repair at all.
        _git('config', 'extensions.worktreeConfig', 'true', cwd=self.repo, env=self._env())

        result = self._run('--fix')

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        shared = self._shared_config().read_text(encoding='utf-8')
        self.assertNotIn(
            'hooksPath', shared,
            f'the repair was written into the shared config: {shared}',
        )
        self.assertTrue(
            self._worktree_config().exists(),
            'the repair did not land in this worktree\'s own config',
        )
        self.assertEqual(
            '.githooks',
            self._config_get(self._worktree_config(), 'core.hooksPath'),
        )
        # And the repair took effect: git reads it in this checkout.
        self.assertEqual('.githooks', self._effective('core.hooksPath'))

    def test_the_bare_flag_is_never_written_into_the_shared_config(self):
        """ADR: a `core.bare = false` in the shared config un-bares every worktree."""
        _git('config', 'extensions.worktreeConfig', 'true', cwd=self.repo, env=self._env())
        _git('config', 'core.bare', 'true', cwd=self.repo, env=self._env())

        result = self._run('--fix')

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            'true',
            self._config_get(self._shared_config(), 'core.bare'),
            'the shared config\'s bare flag was overwritten',
        )
        self.assertEqual(
            'false',
            self._config_get(self._worktree_config(), 'core.bare'),
        )

    def test_the_verdict_does_not_depend_on_the_directory_the_doctor_started_in(self):
        """A subdirectory is another directory, but it is the same checkout."""
        from_root = self._run()
        from_subdir = self._run(cwd=self.subdir)

        self.assertEqual(from_root.returncode, from_subdir.returncode)
        self.assertEqual(
            self._verdict(from_root.stdout),
            self._verdict(from_subdir.stdout),
            'the doctor judged the checkout differently from a subdirectory',
        )
        self.assertEqual(from_root.stdout, from_subdir.stdout)

    def test_the_repair_is_the_same_from_a_subdirectory(self):
        """The write goes to the same file, whatever the directory was."""
        _git('config', 'extensions.worktreeConfig', 'true', cwd=self.repo, env=self._env())

        self._run('--fix', cwd=self.subdir)

        self.assertEqual(
            '.githooks',
            self._config_get(self._worktree_config(), 'core.hooksPath'),
        )
        self.assertNotIn(
            'hooksPath',
            self._shared_config().read_text(encoding='utf-8'),
        )

    def test_a_relative_hooks_path_that_leads_nowhere_is_broken(self):
        """Git resolves a relative path against the top level — not the CWD."""
        _git('config', 'core.hooksPath', 'nowhere-hooks', cwd=self.repo, env=self._env())

        result = self._run()

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        broken = [
            line for line in self._verdict(result.stdout)
            if 'core.hooksPath' in line
        ]
        self.assertEqual(1, len(broken), result.stdout)
        self.assertTrue(broken[0].startswith('  BROKEN '), broken[0])
        self.assertIn('nowhere-hooks', broken[0])
        # The path is named as it is configured — relative to the root — and
        # not resolved against the directory the doctor ran in.
        self.assertNotIn(str(self.subdir), broken[0])

    def test_a_healthy_checkout_passes(self):
        """The doctor has to be able to say that everything holds."""
        _git('config', 'extensions.worktreeConfig', 'true', cwd=self.repo, env=self._env())
        _git('config', 'core.hooksPath', '.githooks', cwd=self.repo, env=self._env())
        _git(
            'config', 'core.sshCommand', 'ssh -o ServerAliveInterval=30',
            cwd=self.repo, env=self._env(),
        )

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('every invariant holds', result.stdout)


class DoctorShBareRepositoryTest(unittest.TestCase):
    """The bare repository ITSELF: it is not a worktree, and must stay bare.

    A `core.bare = false` written into the main entry of a bare repository
    turns that repository into a non-bare one — and nothing else in the tree
    would notice. The doctor therefore says so instead of repairing it.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='doctor-sh-bare-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

        self.home = self.tmp / 'home'
        self.home.mkdir()

        self.bare = self.tmp / 'bare.git'
        self.bare.mkdir()
        _git('init', '-q', '--bare', cwd=self.bare, env=self._env())

        self.script = self._install()
        self._assert_own_bare_repository()

    def _assert_own_bare_repository(self) -> None:
        """The fixture is a repository of its own, and it is the bare one.

        `assert_own_repository` from the sandbox cannot be used here: a bare
        repository has no work tree, and `--show-toplevel` refuses in it. The
        git dir is the answer instead.
        """
        result = subprocess.run(
            ['git', 'rev-parse', '--absolute-git-dir'],
            cwd=self.bare,
            env=self._env(),
            capture_output=True, text=True,
        )
        self.assertEqual(self.bare.resolve(), pathlib.Path(result.stdout.strip()).resolve())
        self.assertEqual('true', self._is_bare())

    def _is_bare(self) -> str:
        result = subprocess.run(
            ['git', 'rev-parse', '--is-bare-repository'],
            cwd=self.bare,
            env=self._env(),
            capture_output=True, text=True,
        )
        return result.stdout.strip()

    def _env(self) -> dict[str, str]:
        env = sandbox_env(self.tmp)
        env['HOME'] = str(self.home)
        env['XDG_CONFIG_HOME'] = str(self.home / '.config')
        env['GIT_CONFIG_GLOBAL'] = str(self.home / '.gitconfig')
        env['GIT_CONFIG_NOSYSTEM'] = '1'
        return env

    def _install(self) -> pathlib.Path:
        scripts = self.bare / 'scripts'
        scripts.mkdir(exist_ok=True)
        target = scripts / 'doctor.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        return target

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(self.script), *args],
            cwd=self.bare,
            capture_output=True,
            text=True,
            env=self._env(),
        )

    def _config_get(self, config_file: pathlib.Path, key: str) -> str:
        result = subprocess.run(
            ['git', 'config', '--file', str(config_file), '--get', key],
            env=self._env(),
            capture_output=True, text=True,
        )
        return result.stdout.strip()

    def test_the_bare_repository_is_not_un_bared(self):
        result = self._run('--fix')

        self.assertEqual(
            'true',
            self._config_get(self.bare / 'config', 'core.bare'),
            'the bare repository was un-bared',
        )
        self.assertNotEqual(
            'false',
            self._config_get(self.bare / 'config.worktree', 'core.bare'),
            'the bare repository\'s main entry was given a core.bare = false',
        )
        self.assertIn('bare repository', result.stdout)


if __name__ == '__main__':
    unittest.main()
