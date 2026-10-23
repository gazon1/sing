"""Tests for `.githooks/pre-push` — the verdict must be about the pushed tree.

The defect this exists for: the hook's detekt step ran against the CURRENT
worktree and called its verdict "did auto-correct change any file". A push of a
tree nobody had in front of them was therefore judged by a tree that was not
being pushed, and a violation the author had already fixed locally was
reported as a violation of the tree that was going out — or, the other way
round, a violation that WAS in the pushed tree went out unjudged.

These tests drive the real hook through a real `git push` into a local bare
remote, in a temporary repository, with a stand-in for the Gradle wrapper that
reports a planted violation the way detekt reports one:

  - a clean push passes;
  - a violation in the pushed tree fails the push;
  - a violation that is only in the worktree does NOT fail the push (it is not
    in the tree being pushed);
  - a violation in the pushed tree fails the push even when the worktree
    already holds the fix (the pushed tree still carries it).

The last two are the ones that matter: together they say the verdict is about
the tree that is being pushed, in both directions.

Nothing here touches the developer's repository: the repository, the remote
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

from sgate.commands._registry import PRE_PUSH_SCRIPTS  # noqa: E402

from scripts.tests._git_env import GitEnvTestCase  # noqa: E402

HOOK = REPO_ROOT / '.githooks' / 'pre-push'

#: The verdict script the hook calls, and the module graph it reads to decide
#: which modules to judge. Both travel with the repository — the hook calls the
#: script, and the script computes the modules from the graph.
VERDICT_SCRIPT = REPO_ROOT / 'scripts' / 'pre-push-verdict.py'
CHANGED_MODULES = REPO_ROOT / 'scripts' / 'changed-modules.py'

# The marker detekt would report on. The stand-in Gradle wrapper looks for it
# in the Kotlin files of the tree it is run in.
VIOLATION = 'PLANTED_VIOLATION'

# A stand-in for the Gradle wrapper: it is what detekt would report for the
# tree it is run in, and it is committed, so a temporary worktree holds it too.
FAKE_GRADLEW = """#!/usr/bin/env bash
# A stand-in for the Gradle wrapper. detekt's verdict for the tree it runs in.
set -uo pipefail
if grep -rl --include='*.kt' '{marker}' . >/dev/null 2>&1; then
    echo "Example.kt:1:1: ClassSignature: the class signature must be on one line" >&2
    exit 1
fi
exit 0
""".format(marker=VIOLATION)

# A gate stub that passes. The real gates read the repository; the hook only
# needs them to succeed, and the point of these tests is the detekt verdict.
PASSING_PYTHON_GATE = """#!/usr/bin/env python3
import sys
sys.exit(0)
"""

SOURCE = 'package com.example\n\nclass Example\n' + VIOLATION + '\n'
CLEAN_SOURCE = 'package com.example\n\nclass Example\n'


def _git(*args: str, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
    )


class PrePushHookTest(GitEnvTestCase):
    """The hook, driven through a real push."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-hook-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.remote = self.tmp / 'remote.git'
        self.repo.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.repo)
        _git('config', 'user.email', 'hook@example.com', cwd=self.repo)
        _git('config', 'user.name', 'Hook Test', cwd=self.repo)
        _git('config', 'commit.gpgsign', 'false', cwd=self.repo)
        # The hook under test travels with the repository.
        _git('init', '-q', '--bare', str(self.remote), cwd=self.tmp)

    def _write(self, relative: str, content: str, *, executable: bool = False) -> pathlib.Path:
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        if executable:
            path.chmod(0o755)
        return path

    def _install_hook(self) -> None:
        self._write('.githooks/pre-push', HOOK.read_text(encoding='utf-8'), executable=True)
        _git('config', 'core.hooksPath', '.githooks', cwd=self.repo)

    def _install_stubs(self) -> None:
        """The scripts the hook calls, and a Gradle wrapper that reports findings.

        The list of scripts comes from the registry — the same declaration the
        hook reads its rules from. It used to be written out here, and the two
        lists drifted: the hook called five scripts and this fixture stubbed
        three, so every script added to the hook was a failing test that only
        the full run found.
        """
        self._write('gradlew', FAKE_GRADLEW, executable=True)
        # The verdict runs `./gw` — the wrapper that gives a worktree its own
        # GRADLE_USER_HOME — so the stand-in is installed under that name too.
        self._write('gw', FAKE_GRADLEW, executable=True)
        for script in PRE_PUSH_SCRIPTS:
            self._write(script, PASSING_PYTHON_GATE)
        # The verdict and the module graph it reads are part of the repository:
        # the hook calls the script, and the script computes the modules to
        # judge from the graph. They are the real files, not stubs — the
        # verdict is what these tests are about.
        self._write('scripts/pre-push-verdict.py', VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._write('scripts/changed-modules.py', CHANGED_MODULES.read_text(encoding='utf-8'))

    def _commit_source(self, content: str, message: str) -> None:
        self._write('shared/src/main/kotlin/Example.kt', content)
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', message, cwd=self.repo)

    def _push(self) -> subprocess.CompletedProcess:
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        return subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
        )

    # ── the cases ────────────────────────────────────────────────────────

    def test_a_clean_push_passes(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('=== Pre-push OK ===', result.stdout + result.stderr)

    def test_a_violation_in_the_pushed_tree_fails_the_push(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(SOURCE, 'a file with a finding')

        result = self._push()

        self.assertNotEqual(0, result.returncode, 'the push went out with a finding in it')
        output = result.stdout + result.stderr
        self.assertIn('detekt', output)
        self.assertIn('FAILED', output)

    def test_a_push_that_moves_no_kotlin_is_not_a_clean_verdict(self):
        """Nothing was judged, so nothing was found — the summary must not claim clean."""
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The trunk the change is measured against.
        head = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()
        _git('update-ref', 'refs/remotes/origin/main', head, cwd=self.repo)
        # A change that moves neither Kotlin nor rules.
        self._write('docs/note.md', 'a note\n')
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', 'a note', cwd=self.repo)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('nothing to judge', output)
        self.assertIn('detekt verdict: not taken', output)

    def test_the_verdict_is_taken_in_the_worktree_when_it_holds_the_pushed_tree(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertIn('byte-identical', output, 'a clean worktree is the pushed tree')

    def test_a_violation_only_in_the_worktree_does_not_fail_the_push(self):
        """The worktree is not the tree being pushed, so it cannot be the verdict."""
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The author breaks the file locally and pushes the clean commit.
        self._write('shared/src/main/kotlin/Example.kt', SOURCE)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('temporary worktree', output, 'the verdict was taken about the pushed tree')

    def test_a_violation_in_the_pushed_tree_fails_even_when_the_worktree_is_clean(self):
        """The fix in the worktree does not make the pushed tree clean."""
        self._install_hook()
        self._install_stubs()
        self._commit_source(SOURCE, 'a file with a finding')
        # The author fixes it locally — after committing, before pushing.
        self._write('shared/src/main/kotlin/Example.kt', CLEAN_SOURCE)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, 'the push went out with a finding in it')
        self.assertIn('temporary worktree', output, 'the verdict was taken about the pushed tree')

    def test_a_changed_editorconfig_sends_the_verdict_to_the_pushed_tree(self):
        """ktlint reads .editorconfig from inside detekt, so it is part of the verdict."""
        self._install_hook()
        self._install_stubs()
        self._write('.editorconfig', 'root = true\n')
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The editorconfig changes locally: the rules detekt applies change with it.
        self._write('.editorconfig', 'root = true\nmax_line_length = 120\n')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('temporary worktree', output, 'the verdict was taken about the pushed tree')


if __name__ == '__main__':
    unittest.main()
