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
    already holds the fix (the pushed tree still carries it);
  - the gate suite's own tests pass inside the pushed tree even when the hook
    runs with the git environment git hands it.

The last two are the ones that matter: together they say the verdict is about
the tree that is being pushed, in both directions.

The last case is the one that was broken for months. Git hands a hook the
environment it runs in, and GIT_DIR in it points at the worktree the push was
made from — in a linked worktree, GIT_DIR is that worktree's own git dir.
The hook runs the gate suite inside a throwaway worktree, and without
dropping that environment git read the ORIGINAL worktree's index there: a
test that shells out to git failed with "git add: exit status 128", and the
suite reported a failure that had nothing to do with the code. Nothing
noticed, because the only thing that tested the hook was the hook.

Nothing here touches the developer's repository: the repository, the remote
and the hook copy are all temporary, and the Gradle wrapper is a stand-in, so
the tests take well under a second and need no build.
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

from sgate.commands._registry import PRE_PUSH_SCRIPTS  # noqa: E402
from sgate.gatekit.gitenv import INHERITED_FROM_HOOK, tree_env as _clean_git_env  # noqa: E402

HOOK = REPO_ROOT / '.githooks' / 'pre-push'

#: The verdict script the hook calls, and the module graph it reads to decide
#: which modules to judge. Both travel with the repository — the hook calls the
#: script, and the script computes the modules from the graph.
VERDICT_SCRIPT = REPO_ROOT / 'scripts' / 'pre-push-verdict.py'
CHANGED_MODULES = REPO_ROOT / 'scripts' / 'changed-modules.py'
# The runner the hook calls for the gate suite's own tests. The fixture tree
# carries it, because the hook calls it by name: a fixture that does not carry
# the file the hook runs proves nothing about the hook.
GATE_TEST_RUNNER = REPO_ROOT / 'scripts' / 'run-gate-tests.py'

#: The tool that prints the CI verdict. The hook calls it before the push
#: goes out, so the verdict is a line in the output of the push and not a
#: thing a reader has to remember to ask for.
CI_VERDICT_SCRIPT = REPO_ROOT / 'scripts' / 'ci-verdict.py'

#: The canonical answer to "which git variables must a command inside a tree
#: not inherit". The verdict script loads it by path, the way the checkout
#: carries it.
GITENV_SCRIPT = REPO_ROOT / 'scripts' / 'src' / 'sgate' / 'gatekit' / 'gitenv.py'

#: The one way to reach that answer when the package is out of reach. The
#: verdict script imports it, and the fixture tree has to carry it for the
#: same reason it carries the answer itself.
GITENV_LOADER = REPO_ROOT / 'scripts' / 'gitenv_loader.py'

#: A stand-in for the GitHub CLI: it reports one run of the CI workflow, the
#: way `gh run list` reports it. The hook asks about the commit it pushes, and
#: the answer has to name the workflow, the run and the conclusion.
#:
#: It writes down what it was asked, so the test can check the question and
#: not only the answer: a stand-in that answers every question alike proves
#: that the hook printed a line, not that it asked about the commit it pushes.
FAKE_GH = """#!/usr/bin/env bash
if [ -n "${FAKE_GH_LOG:-}" ]; then
  printf '%s\\n' "$*" >> "$FAKE_GH_LOG"
fi
cat <<'JSON'
[{"databaseId": 39888579980, "conclusion": "success", "status": "completed", "url": "https://github.com/gazon1/sing/actions/runs/39888579980", "workflowName": "CI"}]
JSON
"""

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

# The environment variable the probe writes what it saw to. The hook inherits
# the environment of the push, so the probe inside the pushed tree can tell
# the test outside what the git environment looked like from in there.
MARKER_ENV = 'PRE_PUSH_GIT_ENV_MARKER'

# A gate suite test that shells out to git — the shape the real suite has.
#
# It is committed into the pushed tree, so the hook's gate-suite step runs it
# inside that tree. It asserts the one thing that broke: git, run inside the
# tree under test, must talk about THAT tree. Under a foreign GIT_DIR it talks
# about the worktree the push was made from, and the assertion fails.
#
# The marker is written BEFORE the assertion, so a failing probe still says
# what it saw: that is what tells the test outside whether the tree under test
# was the tree git talked about.
GIT_ENV_PROBE = '''"""A gate suite test that shells out to git — the shape the suite has.

The tree this test runs in is the tree being pushed. Git, run inside it, must
talk about THIS tree and not about the worktree the push was made from.
"""

import os
import pathlib
import subprocess
import unittest

MARKER_ENV = '{marker_env}'


class GitEnvironmentTest(unittest.TestCase):
    """git inside the tree under test must talk about that tree."""

    def _git_dir_of_this_tree(self) -> str:
        root = pathlib.Path(__file__).resolve().parents[2]
        dot_git = root / '.git'
        if dot_git.is_dir():
            return str(dot_git.resolve())
        # A linked worktree: a `.git` FILE that says where the git dir is.
        pointer = dot_git.read_text(encoding='utf-8').strip()
        _, _, path = pointer.partition('gitdir:')
        return str((root / path.strip()).resolve())

    def test_git_inside_this_tree_sees_this_tree(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        observed = subprocess.run(
            ['git', 'rev-parse', '--absolute-git-dir'],
            cwd=root,
            capture_output=True,
            text=True,
        )
        marker = os.environ.get(MARKER_ENV)
        if marker:
            pathlib.Path(marker).write_text(
                'observed=' + observed.stdout.strip() + '\\n'
                'GIT_DIR=' + os.environ.get('GIT_DIR', '') + '\\n',
                encoding='utf-8',
            )
        self.assertEqual(0, observed.returncode, observed.stderr)
        self.assertEqual(
            self._git_dir_of_this_tree(),
            str(pathlib.Path(observed.stdout.strip()).resolve()),
            'git inside the tree under test talks about a DIFFERENT tree — the '
            'git environment of the hook leaked into the tree being pushed',
        )
'''.format(marker_env=MARKER_ENV)


def _git(*args: str, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=_clean_git_env(),
    )



class PrePushHookTest(unittest.TestCase):
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

    def _install_stubs(self, *, with_runner: bool = True) -> None:
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
            if not with_runner and script == 'scripts/run-gate-tests.py':
                # A tree that predates the runner has no runner at all — not
                # even a stub of one.
                continue
            self._write(script, PASSING_PYTHON_GATE)
        # The verdict and the module graph it reads are part of the repository:
        # the hook calls the script, and the script computes the modules to
        # judge from the graph. They are the real files, not stubs — the
        # verdict is what these tests are about.
        self._write('scripts/pre-push-verdict.py', VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._write('scripts/changed-modules.py', CHANGED_MODULES.read_text(encoding='utf-8'))
        # The gate suite's own runner is called by the hook by name, so the
        # fixture tree carries it — the same way it carries the scripts the
        # hook reads. A tree that predates the runner has none, and the hook
        # falls back to the plain `unittest` for it.
        if with_runner:
            self._write('scripts/run-gate-tests.py', GATE_TEST_RUNNER.read_text(encoding='utf-8'))

        # The answer the verdict script takes is in the checkout too: the hook
        # runs the script from the checkout, and the checkout carries the file
        # the script loads. A checkout without it is not a checkout.
        self._write('scripts/src/sgate/gatekit/gitenv.py', GITENV_SCRIPT.read_text(encoding='utf-8'))
        # And the way the script reaches that answer: the loader that reads
        # the file by path, because the package that also holds it cannot be
        # imported by the ambient interpreter the hook runs.
        self._write('scripts/gitenv_loader.py', GITENV_LOADER.read_text(encoding='utf-8'))

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
            env=_clean_git_env(),
        )

    def _linked_worktree(self) -> pathlib.Path:
        """A linked worktree of the fixture repository — where the defect lived."""
        worktree = self.tmp / 'worktree'
        _git('worktree', 'add', '--detach', str(worktree), 'HEAD', cwd=self.repo)
        return worktree

    def _the_environment_git_hands_a_hook(self, worktree: pathlib.Path) -> str:
        """What git exports to a hook in a linked worktree.

        The premise of the case below: in a linked worktree, git exports
        GIT_DIR to the hook, and it points at that worktree's own git dir — a
        tree that is NOT the tree the hook checks out. A probe hook writes it
        down; it runs with a hooks path of its own, so the hook under test is
        the only hook that ever gates a push in this fixture.
        """
        hooks = worktree / '.probehooks'
        hooks.mkdir()
        hook = hooks / 'pre-push'
        hook.write_text(
            '#!/usr/bin/env bash\nprintf "%s" "${GIT_DIR:-}" > "$PROBE_MARKER"\n',
            encoding='utf-8',
        )
        hook.chmod(0o755)
        marker = self.tmp / 'probe-marker.txt'
        env = _clean_git_env()
        env['PROBE_MARKER'] = str(marker)
        subprocess.run(
            ['git', '-c', 'core.hooksPath=.probehooks', 'push', 'origin', 'HEAD:refs/heads/probe'],
            cwd=worktree,
            capture_output=True,
            text=True,
            env=env,
            check=True,
        )
        return marker.read_text(encoding='utf-8').strip()

    def _push_from_worktree(self, worktree: pathlib.Path, marker: pathlib.Path) -> subprocess.CompletedProcess:
        """Push from a linked worktree, with the marker in the environment.

        The hook inherits the environment of the push, so the probe inside the
        pushed tree sees the environment the hook ran with.
        """
        env = _clean_git_env()
        env[MARKER_ENV] = str(marker)
        return subprocess.run(
            ['git', 'push', 'origin', 'HEAD:refs/heads/main'],
            cwd=worktree,
            capture_output=True,
            text=True,
            env=env,
        )

    # ── the cases ────────────────────────────────────────────────────────

    def test_a_clean_push_passes(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('=== Pre-push OK ===', result.stdout + result.stderr)

    def test_a_tree_without_the_runner_still_runs_the_suite(self):
        # A branch that predates the runner has no runner, and a push of an
        # old branch is not blocked by a file that branch does not have.
        self._install_hook()
        self._install_stubs(with_runner=False)
        self._write(
            'scripts/tests/test_generated.py',
            'import unittest\n\n\nclass GeneratedTest(unittest.TestCase):\n'
            '    def test_it_passes(self):\n        self.assertTrue(True)\n',
        )
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('gate suite tests: OK', output)
        self.assertIn('the tree has no runner', output)

    def test_the_push_prints_the_ci_verdict_of_the_commit_it_pushes(self):
        """The CI verdict is a line of the push, not a thing to remember to ask for.

        The tool that prints it is `scripts/ci-verdict.py`, and the hook calls
        it about the commit that is going out. The answer names the workflow,
        the run and the conclusion — a run id on its own is not a verdict, and
        a repository has several workflows.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # A stand-in for the GitHub CLI, so the answer does not depend on a
        # network and on what the real CI happens to have concluded.
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # The push goes to the local remote, and the *fetch* url of that remote
        # is the GitHub repository the hook asks about — a fork, so that the
        # derivation of the repository is part of the question.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:example/fork.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'origin', str(self.remote), cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)
        pushed = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()

        result = subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertIn('ci.yml  run 39888579980  success', output)
        self.assertEqual(0, result.returncode, output)
        # The question, and not only the answer: the hook asked about the
        # commit it pushes, and about the repository it pushes to.
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn(f'--commit {pushed}', asked)
        self.assertIn('--repo example/fork', asked)

    def test_the_verdict_is_about_the_repository_the_push_reaches(self):
        """The push url is the repository that receives the push.

        The fetch url and the push url are two answers to two questions, and
        the question here is the second one: CI runs in the repository the
        push reaches. The urls are read from the configuration, so a
        developer who pushes to a mirror is asked about the repository they
        mean and not about the mirror the rewrite points at.

        The hook is run the way git runs it — the ref lines on stdin, the
        remote and its url as arguments — and the push itself is not made:
        the question the hook asks is not a question about the network.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # Two GitHub repositories: the one the push is read from and the one
        # the push goes to.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:upstream/sing.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'origin', 'git@github.com:fork/mine.git', cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)
        sha = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()

        result = subprocess.run(
            [str(self.repo / '.githooks' / 'pre-push'), 'origin', 'git@github.com:fork/mine.git'],
            cwd=self.repo,
            input=f'refs/heads/main {sha} refs/heads/main {sha}\n',
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn('--repo fork/mine', asked)

    def test_the_verdict_is_about_the_repository_the_push_names_and_not_the_mirror_it_is_rewritten_to(self):
        """A mirror the push is rewritten to is not the repository CI runs in.

        The urls are read from the configuration, so a rewrite the developer
        has set up does not change the answer: the repository the push names
        is the repository whose runs are asked about.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # The push names a GitHub repository, and a rule rewrites what it
        # names to a mirror that is not one.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:owner/repo.git', cwd=self.repo)
        _git('config', 'url.https://mirror.example.com/.insteadOf', 'git@github.com:', cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)
        sha = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()

        result = subprocess.run(
            [str(self.repo / '.githooks' / 'pre-push'), 'origin', 'git@github.com:owner/repo.git'],
            cwd=self.repo,
            input=f'refs/heads/main {sha} refs/heads/main {sha}\n',
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn('--repo owner/repo', asked)

    def test_a_push_to_a_remote_that_is_not_github_asks_nobody(self):
        """The step must not ask about a repository the remote is not.

        The tool's own default names the upstream, so a step that ran without
        naming the repository would ask about somebody else's runs — the very
        mistake the derivation exists to avoid.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)

        result = subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertIn('ci verdict: SKIPPED (origin is not a GitHub repository)', output)
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertEqual('', asked, 'nobody was asked about a repository the remote is not')

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
        # The reason is the verdict's own, not one the hook invented.
        self.assertIn('no detekt run is needed', output)

    def test_the_summary_carries_the_verdicts_own_reason(self):
        """A change that moves Kotlin into a module with no detekt task.

        The verdict has TWO reasons for taking none, and they are different
        claims: a change that moves no Kotlin at all, and a change that moves
        Kotlin into a module whose verdict is its test suite — it applies the
        plain kotlin("jvm") plugin, so it has no detekt task of its own.

        The hook used to substitute the first reason for the second: it told
        the reader that a change which moves Kotlin moves no Kotlin. The
        summary must carry the reason the verdict gave.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The trunk the change is measured against.
        head = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()
        _git('update-ref', 'refs/remotes/origin/main', head, cwd=self.repo)
        # A change in the module whose verdict is its test suite.
        self._write(
            'tools/unwritten-properties/src/main/kotlin/Example.kt',
            CLEAN_SOURCE,
        )
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', 'a change in a module without a detekt task', cwd=self.repo)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('detekt verdict: not taken', output)
        self.assertIn(
            'no detekt task exists for it',
            output,
            'the summary did not carry the verdict\'s own reason:\n' + output,
        )
        self.assertNotIn(
            'this change moves no Kotlin and no rules',
            output,
            'the hook substituted its own reason for the verdict\'s:\n' + output,
        )

    def test_the_coverage_claim_is_true(self):
        """CI judges a hand-written list of modules, not every module.

        The claim the hook used to make — "CI judges every module" — is false
        as a statement about the mechanism: CI's detekt list is written out by
        hand, while the hook's set comes from the module graph. A module the
        graph knows and CI's list does not name is judged here and by nobody
        else.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('CI judges the modules its own list names', output)
        self.assertNotIn('CI judges every module', output)

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

    def test_the_gate_suite_step_passes_with_the_git_environment_git_hands_a_hook(self):
        """The suite runs inside the pushed tree, under the hook's environment.

        Git exports GIT_DIR to a hook in a linked worktree, and it points at
        that worktree — the one the push was made from. The hook runs the gate
        suite in a throwaway worktree, so the environment has to be dropped
        there: otherwise git reads the original worktree's index and the suite
        fails for a reason that has nothing to do with the code.

        The assertion is about the hook's own behaviour — the gate suite step
        passes — and the premise (a foreign GIT_DIR) is checked too, so the
        case cannot pass by having no environment to drop.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_git_environment.py', GIT_ENV_PROBE)
        # The probe travels with the tree being pushed: the commit that carries
        # the source carries the gate suite test as well.
        self._commit_source(CLEAN_SOURCE, 'a clean file and a gate suite test')
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        worktree = self._linked_worktree()

        # The premise: git really does hand a hook in a linked worktree a
        # GIT_DIR, and it points at that worktree — the one the push is made
        # from, and not the tree the hook checks out.
        foreign = self._the_environment_git_hands_a_hook(worktree)
        self.assertTrue(
            foreign,
            'git handed the hook no GIT_DIR at all — the case would prove nothing',
        )
        self.assertEqual(
            foreign,
            subprocess.run(
                ['git', 'rev-parse', '--absolute-git-dir'],
                cwd=worktree,
                capture_output=True,
                text=True,
                env=_clean_git_env(),
            ).stdout.strip(),
            'the GIT_DIR of the hook is not the git dir of the worktree the push '
            'was made from',
        )

        marker = self.tmp / 'git-env-marker.txt'
        result = self._push_from_worktree(worktree, marker)
        output = result.stdout + result.stderr

        # The probe inside the pushed tree ran — so the gate suite step was
        # exercised, and it saw a tree of its own.
        self.assertTrue(
            marker.exists(),
            'the probe inside the pushed tree did not run — the gate suite step '
            'was not exercised:\n' + output,
        )
        seen = marker.read_text(encoding='utf-8')
        self.assertNotIn(
            'observed=' + foreign,
            seen,
            'git inside the pushed tree talked about the worktree the push was '
            'made from:\n' + seen,
        )

        # The behaviour: the suite passed inside the pushed tree anyway.
        self.assertIn(
            'gate suite tests: OK',
            output,
            'the gate suite failed inside the pushed tree under the git '
            'environment of the hook:\n' + output,
        )
        self.assertEqual(0, result.returncode, output)


class HookGitEnvironmentTest(unittest.TestCase):
    """The hook drops the git environment, and it drops all of it.

    The hook runs its steps inside the tree being pushed, and git hands a hook
    the environment of the worktree the push was made from. Every step that
    runs there has to drop that environment — and the list of names it has to
    drop lives in one place, `sgate.gatekit.gitenv`, because a step that drops
    three of the four names breaks in a way nobody sees: the tree is read
    through the wrong index, and the command reports about a tree nobody
    pushed.
    """

    def test_every_step_drops_every_name_the_canonical_answer_names(self):
        source = HOOK.read_text(encoding='utf-8')
        steps = [line for line in source.splitlines() if 'env -u GIT_DIR' in line]
        self.assertTrue(steps, 'the hook no longer drops the git environment at all')
        for line in steps:
            for name in INHERITED_FROM_HOOK:
                self.assertIn(
                    f'-u {name}',
                    line,
                    f'the hook drops the git environment incompletely: {line.strip()}',
                )


if __name__ == '__main__':
    unittest.main()
