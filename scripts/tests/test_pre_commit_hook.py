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
  - a commit in the main checkout still compiles;
  - a commit that touches no module compiles NOTHING (the compile follows the
    change, so a change to a gate script or a document does not pay for a
    Kotlin compile that cannot say anything about it).

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

from sgate.gatekit.gitenv import sandbox_env  # noqa: E402

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

#: A file inside a module — the change the compile is about. A change outside
#: every module compiles nothing, and that is a case of its own.
MODULE_FILE = 'shared/src/main/kotlin/Example.kt'

#: A file no module owns — a document, a script, a configuration file.
GATE_FILE = 'notes.md'


def _git(*args: str, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=sandbox_env(cwd),
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
        # The compile step is a script of its own, and it reads the module
        # graph — both are the repository's, so both are copied in. The fixture
        # is a real repository then, not a stand-in for one.
        self._write(
            'scripts/changed-modules.py',
            (REPO_ROOT / 'scripts' / 'changed-modules.py').read_text(encoding='utf-8'),
        )
        self._write(
            'scripts/compile-changed.py',
            (REPO_ROOT / 'scripts' / 'compile-changed.py').read_text(encoding='utf-8'),
        )
        (self.repo / 'README.md').write_text('seed\n', encoding='utf-8')
        _git('add', '-A', cwd=self.repo)
        # The seed is the fixture, not the thing under test, and the hook needs
        # `origin/main` to exist before it can measure anything.
        _git('commit', '-q', '-m', 'seed', '--no-verify', cwd=self.repo)
        # The hook measures the change against `origin/main`, so the fixture
        # needs that ref. A clone has it; without it the hook stops for the
        # right reason and the test would measure the wrong thing.
        _git('update-ref', 'refs/remotes/origin/main', 'HEAD', cwd=self.repo)

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
        # The worktree is a checkout of the seed commit, so it already holds
        # the hook, the wrapper and the lint step — the hook resolves its root
        # from Git, so what runs in the worktree is the worktree's own copy.
        return worktree

    def _commit(self, where: pathlib.Path, relative: str = MODULE_FILE) -> subprocess.CompletedProcess:
        # The seed commit ran the hook as well, so the marker of the commit
        # under test starts empty — otherwise a test would read the seed's
        # call and prove nothing about the commit it made.
        (where / 'gw-calls.txt').unlink(missing_ok=True)
        target = where / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('change\n', encoding='utf-8')
        _git('add', '-A', cwd=where)
        return subprocess.run(
            ['git', 'commit', '-m', 'change'],
            cwd=where,
            capture_output=True,
            text=True,
            env=sandbox_env(where),
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

        self.assertIn(
            ':shared:compileKotlinJvm',
            self._calls(worktree),
            'the compile was never attempted, so the failure is not about it',
        )
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(
            _committed(worktree),
            'the commit happened even though the compile failed',
        )

    def test_a_commit_in_the_main_checkout_still_compiles(self):
        result = self._commit(self.repo)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(':shared:compileKotlinJvm', self._calls(self.repo))

    def test_a_commit_that_touches_no_module_compiles_nothing(self):
        """The compile follows the change.

        The hook used to compile `:shared` and `:desktopApp` for every commit,
        whatever the change was: a change to a document paid for a Kotlin
        compile that could not say anything about it. A change no module owns
        compiles nothing — and the commit still happens.
        """
        worktree = self._linked_worktree()

        result = self._commit(worktree, GATE_FILE)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn(
            ':shared:compileKotlinJvm',
            self._calls(worktree),
            'a change that touches no module must not pay for a module compile',
        )

    def test_a_commit_compiles_the_modules_the_change_touches(self):
        """The compile is the modules the change touches, and no others.

        A change in `:desktopApp` compiles `:desktopApp` — not `:shared`, which
        it does not compile against. The hardcoded pair compiled `:shared` for
        every commit, and a change that did not touch it was blocked by
        somebody else's breakage in it.
        """
        worktree = self._linked_worktree()

        result = self._commit(worktree, 'desktopApp/src/main/kotlin/Example.kt')

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        calls = self._calls(worktree)
        self.assertIn(':desktopApp:compileKotlin', calls)
        self.assertNotIn(':shared:compileKotlinJvm', calls)


def _committed(worktree: pathlib.Path) -> bool:
    """Whether the worktree's branch carries the commit that was attempted."""
    completed = subprocess.run(
        ['git', 'log', '--oneline', '-1'],
        cwd=worktree,
        capture_output=True,
        text=True,
        env=sandbox_env(worktree),
    )
    return 'change' in completed.stdout
