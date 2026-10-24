"""Tests for `scripts/setup-worktree.sh` — the knobs it documents and the `.env` it copies.

Two properties are held here, each on a throwaway repository — the developer's
own worktrees are never touched:

  - the venv goes where `SINGULARITY_TOOLS_VENV` says it goes. The header of
    the script has always said "Pass SINGULARITY_TOOLS_VENV to choose where the
    venv goes", and the script always ignored it: the path was hardcoded and
    the variable was only forwarded to the installer afterwards. A knob that
    does nothing is worse than no knob — the reader believes the venv is
    somewhere it is not;

  - the `.env` of the canonical checkout is copied here, with mode 600, and an
    `.env` that is already here is NOT overwritten. The file holds the secrets
    a local run needs, it is gitignored, and it lives in ONE place; replacing
    a hand-made file with the canonical one would swap one set of secrets for
    another without saying so.

The fixture is a throwaway layout, not a mock: a canonical checkout with a
`.env`, a linked worktree of it, and the real script copied in. The script
resolves the git dir and the common dir WITHOUT git — from the `.git` file
that names the git dir — so the layout can be built to match.

Nothing here writes to the shared git config of any real repository: the
fixture's git commands run in the sandbox environment of `git_sandbox`, and
the only configuration the script writes belongs to the fixture's own
repository.
"""

import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'setup-worktree.sh'
HELPER = REPO_ROOT / 'config' / 'git-layout.sh'
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from git_sandbox import git as _git, sandbox_env  # noqa: E402

# The script asks for a Gradle home before it does anything else; a stub keeps
# the test from touching the developer's real one.
STUB_GRADLE_HOME = """#!/usr/bin/env bash
echo "$(mktemp -d)"
"""

#: The contents of the canonical checkout's `.env`.
CANONICAL_ENV = 'API_TOKEN=canonical-secret\n'


class SetupWorktreeShTest(unittest.TestCase):
    """The knobs the script documents, and the `.env` it copies."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-worktree-sh-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.canonical = self.tmp / 'canonical'
        self.canonical.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.canonical, env=self._env())
        (self.canonical / 'README.md').write_text('the canonical checkout\n', encoding='utf-8')
        _git('add', '-A', cwd=self.canonical, env=self._env())
        _git('commit', '-q', '-m', 'initial', cwd=self.canonical, env=self._env())
        self.worktree = self.tmp / 'worktree'
        _git(
            'worktree', 'add', '-b', 'feature', str(self.worktree),
            cwd=self.canonical, env=self._env(),
        )
        self._install(self.worktree)

    def _env(self) -> dict[str, str]:
        """The fixture's own environment: git cannot reach past the fixture's tree."""
        return sandbox_env(self.tmp)

    def _install(self, root: pathlib.Path) -> None:
        """Copy the script, the layout helper and a Gradle-home stub in."""
        scripts = root / 'scripts'
        scripts.mkdir(exist_ok=True)
        stub = scripts / 'setup-gradle-home.sh'
        stub.write_text(STUB_GRADLE_HOME, encoding='utf-8')
        stub.chmod(0o755)
        target = scripts / 'setup-worktree.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        # The layout helper travels with the script: it is where the checkout
        # keeps what both scripts read.
        helper = root / 'config' / 'git-layout.sh'
        helper.parent.mkdir(exist_ok=True)
        helper.write_text(HELPER.read_text(encoding='utf-8'), encoding='utf-8')

    def _run(self, **extra_env: str) -> subprocess.CompletedProcess:
        env = self._env()
        env.update(extra_env)
        return subprocess.run(
            ['bash', str(self.worktree / 'scripts' / 'setup-worktree.sh')],
            cwd=self.worktree,
            capture_output=True,
            text=True,
            env=env,
        )

    # ── the venv knob ────────────────────────────────────────────────────

    def test_the_documented_knob_chooses_where_the_venv_goes(self):
        """The header says the variable chooses the venv's path, so it must."""
        chosen = self.tmp / 'chosen-venv'
        (chosen / 'bin').mkdir(parents=True)
        (chosen / 'bin' / 'python').write_text('#!/bin/sh\n', encoding='utf-8')
        (chosen / 'bin' / 'python').chmod(0o755)

        result = self._run(SINGULARITY_TOOLS_VENV=str(chosen))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            f'Python venv  -> {chosen}',
            result.stdout,
            'the knob did not decide where the venv goes:\n' + result.stdout,
        )
        self.assertIn('(already there)', result.stdout)

    def test_without_the_knob_the_venv_is_named_after_the_worktree(self):
        """The default is unchanged — the knob adds a choice, it does not remove one."""
        home = self.tmp / 'home'
        home.mkdir()
        expected = home / '.venvs' / f'singularity-todo-{self.worktree.name}'

        result = self._run(HOME=str(home))

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f'Python venv  -> {expected}', result.stdout)

    # ── the `.env` copy ──────────────────────────────────────────────────

    def test_the_env_of_the_canonical_checkout_is_copied_here(self):
        """The canonical checkout is the one place the file lives."""
        (self.canonical / '.env').write_text(CANONICAL_ENV, encoding='utf-8')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        copied = self.worktree / '.env'
        self.assertTrue(copied.exists(), 'the .env was not copied:\n' + result.stdout)
        self.assertEqual(
            CANONICAL_ENV,
            copied.read_text(encoding='utf-8'),
            'the copied file is not the canonical one',
        )
        self.assertIn(f'copied from {self.canonical}', result.stdout)

    def test_the_copied_env_is_private_to_its_owner(self):
        """The file holds secrets: it is readable by its owner and by nobody else."""
        (self.canonical / '.env').write_text(CANONICAL_ENV, encoding='utf-8')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        mode = stat.S_IMODE((self.worktree / '.env').stat().st_mode)
        self.assertEqual(
            0o600, mode,
            f'the copied .env has mode {mode:o}, expected 600',
        )

    def test_an_env_that_is_already_here_is_not_overwritten(self):
        """A file that is already here was put there by hand."""
        (self.canonical / '.env').write_text(CANONICAL_ENV, encoding='utf-8')
        mine = self.worktree / '.env'
        mine.write_text('API_TOKEN=mine\n', encoding='utf-8')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('already here', result.stdout)
        self.assertEqual(
            'API_TOKEN=mine\n',
            mine.read_text(encoding='utf-8'),
            'the .env that was already here was replaced by the canonical one',
        )


class SetupWorktreeSeparateGitDirTest(unittest.TestCase):
    """The layout in which the shared git dir sits BESIDE the checkout.

    The common dir is then `…/checkout.git`, and its parent is not the
    canonical checkout — the checkout is the directory beside it. A lookup that
    only knows the parent finds nothing, and the worktree is left without the
    secrets a local run needs.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-worktree-sep-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.canonical = self.tmp / 'canonical'
        self.canonical.mkdir()
        _git(
            'init', '-q', '-b', 'main',
            '--separate-git-dir', str(self.tmp / 'canonical.git'),
            str(self.canonical),
            cwd=self.tmp,
            env=self._env(),
        )
        (self.canonical / 'README.md').write_text('the canonical checkout\n', encoding='utf-8')
        _git('add', '-A', cwd=self.canonical, env=self._env())
        _git('commit', '-q', '-m', 'initial', cwd=self.canonical, env=self._env())
        self.worktree = self.tmp / 'worktree'
        _git(
            'worktree', 'add', '-b', 'feature', str(self.worktree),
            cwd=self.canonical, env=self._env(),
        )
        self._install(self.worktree)

    def _env(self) -> dict[str, str]:
        return sandbox_env(self.tmp)

    def _install(self, root: pathlib.Path) -> None:
        scripts = root / 'scripts'
        scripts.mkdir(exist_ok=True)
        stub = scripts / 'setup-gradle-home.sh'
        stub.write_text(STUB_GRADLE_HOME, encoding='utf-8')
        stub.chmod(0o755)
        target = scripts / 'setup-worktree.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        helper = root / 'config' / 'git-layout.sh'
        helper.parent.mkdir(exist_ok=True)
        helper.write_text(HELPER.read_text(encoding='utf-8'), encoding='utf-8')

    def _run(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(self.worktree / 'scripts' / 'setup-worktree.sh')],
            cwd=self.worktree,
            capture_output=True,
            text=True,
            env=self._env(),
        )

    def test_the_env_is_found_in_the_checkout_beside_the_git_dir(self):
        (self.canonical / '.env').write_text(CANONICAL_ENV, encoding='utf-8')

        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        copied = self.worktree / '.env'
        self.assertTrue(
            copied.exists(),
            'the .env was not found in the layout with a separate git dir:\n'
            + result.stdout + result.stderr,
        )
        self.assertEqual(CANONICAL_ENV, copied.read_text(encoding='utf-8'))


class SetupWorktreeBareRepositoryTest(unittest.TestCase):
    """The bare repository ITSELF: the repository, not a worktree of it.

    A `core.bare = false` written into the main entry of a bare repository
    turns that repository into a non-bare one — and nothing else in the tree
    would notice. The script must leave the bare flag alone there, and repair
    the worktrees of it all the same.

    The layout is the one that makes the resolution WITHOUT git necessary:
    the bare repository has no `.git` of its own, so the checkout the script
    stands in IS the git dir.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='setup-worktree-bare-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

        self.home = self.tmp / 'home'
        self.home.mkdir()

        self.bare = self.tmp / 'bare.git'
        self.bare.mkdir()
        _git('init', '-q', '--bare', cwd=self.bare, env=self._env())

        self.linked = self.tmp / 'linked'
        _git(
            'worktree', 'add', '-q', '-b', 'b1', str(self.linked),
            cwd=self.bare, env=self._env(),
        )
        self._install(self.bare)

    def _env(self) -> dict[str, str]:
        env = sandbox_env(self.tmp)
        env['HOME'] = str(self.home)
        # A bare repository's `config` is a FILE, not a directory: there is no
        # config/ to carry the layout helper in. It sits next to the script
        # instead, and GIT_LAYOUT_HELPER says so.
        env['GIT_LAYOUT_HELPER'] = str(self.bare / 'scripts' / 'git-layout.sh')
        return env

    def _install(self, root: pathlib.Path) -> None:
        scripts = root / 'scripts'
        scripts.mkdir(exist_ok=True)
        stub = scripts / 'setup-gradle-home.sh'
        stub.write_text(STUB_GRADLE_HOME, encoding='utf-8')
        stub.chmod(0o755)
        target = scripts / 'setup-worktree.sh'
        target.write_text(SCRIPT.read_text(encoding='utf-8'), encoding='utf-8')
        target.chmod(0o755)
        helper = scripts / 'git-layout.sh'
        helper.write_text(HELPER.read_text(encoding='utf-8'), encoding='utf-8')

    def _run(self) -> subprocess.CompletedProcess:
        env = self._env()
        # A venv that is already there: the script must not reach the network.
        venv = self.tmp / 'venv'
        (venv / 'bin').mkdir(parents=True)
        python = venv / 'bin' / 'python'
        python.write_text('#!/bin/sh\n', encoding='utf-8')
        python.chmod(0o755)
        env['SINGULARITY_TOOLS_VENV'] = str(venv)
        return subprocess.run(
            ['bash', str(self.bare / 'scripts' / 'setup-worktree.sh')],
            cwd=self.bare,
            capture_output=True,
            text=True,
            env=env,
        )

    def _config_get(self, config_file: pathlib.Path, key: str) -> str:
        result = subprocess.run(
            ['git', 'config', '--file', str(config_file), '--get', key],
            env=self._env(),
            capture_output=True, text=True,
        )
        return result.stdout.strip()

    # ── the properties ──────────────────────────────────────────────────────

    def test_the_bare_repository_itself_is_not_un_bared(self):
        """The bare flag of the repository itself is left alone."""
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            'true',
            self._config_get(self.bare / 'config', 'core.bare'),
            'the bare repository was un-bared',
        )
        self.assertNotEqual(
            'false',
            self._config_get(self.bare / 'config.worktree', 'core.bare'),
            "the bare repository's main entry was given a core.bare = false",
        )
        self.assertIn('bare repository itself', result.stdout)

    def test_the_linked_worktree_is_repaired_all_the_same(self):
        """The worktrees of the repository still get their bare flag."""
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            'false',
            self._config_get(
                self.bare / 'worktrees' / 'linked' / 'config.worktree', 'core.bare',
            ),
        )

    def test_the_script_refuses_to_run_without_the_layout_helper(self):
        """The helper is where the layout lives; without it there is no answer."""
        (self.bare / 'scripts' / 'git-layout.sh').unlink()

        result = self._run()

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('layout helper is missing', result.stderr)


if __name__ == '__main__':
    unittest.main()
