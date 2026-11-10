"""Tests for pre-push-verdict.py — the verdict is about the tree that is pushed.

The pre-push hook's detekt step used to be bash with two branches nothing
exercised: which tree to judge, and whether the tree changed under detekt's
feet. Both are decided here, and these tests hold them.

The properties:

  - the worktree is judged when it holds byte-identical content for everything
    detekt can read — then it *is* the pushed tree;
  - otherwise a tree that holds the pushed content is judged, so a violation
    that is only in the worktree cannot be reported against a tree nobody
    pushed;
  - a run that reports findings makes the verdict red;
  - a run that CHANGES the tree makes the verdict red too, even when it exits
    0 — the content it changed is the only evidence that the tree held a
    violation;
  - a clean tree and a clean run make the verdict green.
"""

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

import importlib.util

#: The loader the script uses, imported the way the script imports it — the
#: loader is one file for the whole repository, and a test that wrote its own
#: would be the second copy of the way to reach the answer.
SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import gitenv_loader  # noqa: E402

#: The canonical answer, and the file it comes from.
GITENV_PATH = pathlib.Path(__file__).resolve().parent.parent / 'src' / 'sgate' / 'gatekit' / 'gitenv.py'
GITENV_MODULE_NAME = gitenv_loader.GITENV_MODULE_NAME

#: The canonical module, loaded the way the script loads it.
#:
#: The script registers the module under a name of its own, so the script and
#: this test hold the *same* module object — the identity check below is
#: therefore a statement about the script, not about the cache. The file the
#: module came from is checked separately, so the cache cannot vouch for
#: itself.
load_gitenv = gitenv_loader.load_gitenv

gitenv = load_gitenv()
_clean_git_env = gitenv.tree_env

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'pre-push-verdict.py'
spec = importlib.util.spec_from_file_location('pre_push_verdict', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SOURCE = 'package com.example\n\nclass Example\n'


def _every_detekt_task() -> set[str]:
    """The detekt task of every module the graph judges.

    Read from the graph, not written down: the number of modules moves when
    the build moves, and a test that names the old number fails on the change
    that added a module instead of on the behaviour it means to check.
    """
    graph = mod.load_module_graph()
    return {f':{module}:detekt' for module in graph.TASKS.detekt_modules()}

#: A stand-in for ./gw. The mode says what detekt would do in this tree.
#: It also records how it was called, so a test can see whether the verdict
#: paid the Gradle configuration phase once or once per module.
FAKE_GRADLE = """#!/usr/bin/env bash
echo "$@" >> {record}
case "{mode}" in
  fail)
    echo "Example.kt:1:1: ClassSignature: the class signature must be on one line" >&2
    for task in "$@"; do
      case "$task" in
        :*:detekt) echo "> Task $task FAILED" ;;
      esac
    done
    exit 1
    ;;
  crash)
    echo "FAILURE: Build failed with an exception." >&2
    exit 1
    ;;
  mutate)
    printf 'package com.example\\n\\nclass Example\\n// mutated\\n' > shared/src/main/kotlin/Example.kt
    exit 0
    ;;
  *)
    exit 0
    ;;
esac
"""


#: A stand-in for detekt that reports ONE finding, in the file the test
#: names, in the format detekt prints it — and exits 1.
#:
#: The format matters: the verdict reads the findings out of the log with a
#: pattern that matches what detekt really prints. A stand-in that prints
#: something else produces a run that failed without naming a finding, which
#: is a different case — and the case that hid the defect below.
FAKE_DETEKT_FINDING = """#!/usr/bin/env bash
echo "TooManyFunctions - [Class 'Example' has 19 functions] at $PWD/{target}:3:1" >&2
for task in "$@"; do
  case "$task" in
    :*:detekt) echo "> Task $task FAILED" ;;
  esac
done
exit 1
"""


class VerdictTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-verdict-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=self.repo, check=True, env=_clean_git_env())
        source = self.repo / 'shared/src/main/kotlin/Example.kt'
        source.parent.mkdir(parents=True)
        source.write_text(SOURCE, encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', 'a file'], cwd=self.repo, check=True, env=_clean_git_env())
        self.sha = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
            env=_clean_git_env(),
        ).stdout.strip()

    def _gradle(self, mode: str) -> str:
        path = self.tmp / f'gradle-{mode}'
        path.write_text(FAKE_GRADLE.format(mode=mode, record=self.tmp / 'calls.log'), encoding='utf-8')
        path.chmod(0o755)
        return str(path)

    def _calls(self) -> list[str]:
        """One line per Gradle invocation the verdict made."""
        record = self.tmp / 'calls.log'
        if not record.exists():
            return []
        return [line for line in record.read_text(encoding='utf-8').splitlines() if line.strip()]

    def _judge(self, mode: str = 'clean', fallback: pathlib.Path | None = None) -> dict:
        return mod.judge(
            self.repo,
            self.sha,
            fallback,
            self._gradle(mode),
            (":shared:detekt",),
            self.tmp / 'logs',
        )

    # ── which tree ────────────────────────────────────────────────────────

    def test_a_worktree_that_holds_the_sha_is_the_tree_that_is_judged(self):
        report = self._judge()
        self.assertIn('current worktree', report['tree_label'])

    def test_a_worktree_that_differs_is_not_judged_in_place(self):
        (self.repo / 'shared/src/main/kotlin/Example.kt').write_text(SOURCE + '// local\n', encoding='utf-8')
        fallback = self.tmp / 'fallback'
        fallback.mkdir()
        report = self._judge(fallback=fallback)
        self.assertIn('temporary worktree', report['tree_label'])
        self.assertEqual(str(fallback), report['tree'])

    def test_an_untracked_kotlin_file_also_makes_the_worktree_a_different_tree(self):
        (self.repo / 'shared/src/main/kotlin/Extra.kt').write_text(SOURCE, encoding='utf-8')
        fallback = self.tmp / 'fallback'
        fallback.mkdir()
        report = self._judge(fallback=fallback)
        self.assertIn('temporary worktree', report['tree_label'])

    # ── what the verdict says ─────────────────────────────────────────────

    def test_a_clean_run_on_a_clean_tree_is_green(self):
        report = self._judge(mode='clean')
        self.assertTrue(report['clean'])
        self.assertEqual([], report['failed_tasks'])
        self.assertFalse(report['mutated'])

    def test_a_run_that_reports_findings_is_red(self):
        report = self._judge(mode='fail')
        self.assertFalse(report['clean'])
        self.assertEqual([":shared:detekt"], report['failed_tasks'])

    def test_a_run_that_changes_the_tree_is_red_even_when_it_exits_zero(self):
        report = self._judge(mode='mutate')
        self.assertFalse(report['clean'], 'a changed tree is the evidence of a violation')
        self.assertTrue(report['mutated'])
        self.assertEqual([], report['failed_tasks'])

    def test_a_run_that_fails_without_naming_a_task_is_red(self):
        """A broken build is not a clean tree, and it names no module that failed."""
        report = self._judge(mode='crash')
        self.assertFalse(report['clean'])
        self.assertEqual(['<the detekt run itself>'], report['failed_tasks'])

    def test_the_verdict_reads_the_tree_and_does_not_correct_it(self):
        """The correction is a separate step; the verdict only reports."""
        self._judge(mode='fail')
        for call in self._calls():
            self.assertNotIn('--auto-correct', call)

    def test_all_the_judged_modules_cost_one_gradle_invocation(self):
        """One invocation pays the configuration phase once, not once per module."""
        report = mod.judge(
            self.repo,
            self.sha,
            None,
            self._gradle('clean'),
            (":shared:detekt", ":desktopApp:detekt"),
            self.tmp / 'logs',
        )
        self.assertEqual(2, len(report['tasks']))
        calls = self._calls()
        self.assertEqual(1, len(calls), calls)
        self.assertIn(':shared:detekt', calls[0])
        self.assertIn(':desktopApp:detekt', calls[0])

    def test_the_digest_sees_a_change_the_status_line_would_hide(self):
        """A file that was already modified keeps its status line after a correction."""
        source = self.repo / 'shared/src/main/kotlin/Example.kt'
        source.write_text(SOURCE + '// already dirty\n', encoding='utf-8')
        before = mod.kotlin_digest(self.repo)
        source.write_text(SOURCE + '// already dirty, then corrected\n', encoding='utf-8')
        after = mod.kotlin_digest(self.repo)
        self.assertNotEqual(before, after)


class ModuleSelectionTest(unittest.TestCase):
    """Which modules the verdict judges — computed from the module graph.

    The properties:

      - a change in a module judges that module and the modules that compile
        against it, and nothing else;
      - a change in the rules or in the build judges every module, because
        every module's verdict can move;
      - a change that moves neither Kotlin nor rules needs no detekt run, and
        the verdict says so instead of claiming a clean tree;
      - a range with no known base is judged in full — the safe direction.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-modules-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=self.repo, check=True, env=_clean_git_env())
        self.base = self._commit('base', {'README.md': 'base\n'})

    def _give_the_repo_an_identity(self, repo: pathlib.Path) -> None:
        """A runner has no git identity; a commit without one is refused."""
        subprocess.run(
            ['git', 'config', 'user.email', 'verdict@example.com'], cwd=repo, check=True,
            env=_clean_git_env(),
        )
        subprocess.run(
            ['git', 'config', 'user.name', 'Verdict'], cwd=repo, check=True, env=_clean_git_env(),
        )

    def _commit(self, message: str, files: dict[str, str]) -> str:
        for name, content in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', message], cwd=self.repo, check=True, env=_clean_git_env())
        return subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
            env=_clean_git_env(),
        ).stdout.strip()

    def _tasks(self, files: dict[str, str]) -> tuple[list[str], str]:
        sha = self._commit('change', files)
        return mod.detekt_tasks_for_change(self.repo, self.base, sha)

    def test_a_change_in_one_module_judges_it_and_its_dependents(self):
        tasks, _ = self._tasks({'desktopApp/src/main/kotlin/Main.kt': 'class Main\n'})
        self.assertIn(':desktopApp:detekt', tasks)
        self.assertNotIn(':shared:detekt', tasks)

    def test_a_change_in_shared_judges_shared_and_the_modules_that_use_it(self):
        tasks, _ = self._tasks({'shared/src/commonMain/kotlin/Thing.kt': 'class Thing\n'})
        for task in (':shared:detekt', ':desktopApp:detekt', ':androidApp:detekt', ':mcp-server:detekt'):
            self.assertIn(task, tasks)

    def test_a_change_in_the_rules_judges_every_module(self):
        tasks, reason = self._tasks({'config/detekt/detekt.yml': 'complexity:\n'})
        self.assertEqual(_every_detekt_task(), set(tasks), reason)
        self.assertIn('rules', reason)

    def test_a_change_in_the_build_judges_every_module(self):
        tasks, _ = self._tasks({'gradle/libs.versions.toml': 'x = "1"\n'})
        self.assertEqual(_every_detekt_task(), set(tasks))

    def test_a_change_that_moves_no_kotlin_needs_no_detekt_run(self):
        tasks, reason = self._tasks({'docs/notes.md': 'a note\n'})
        self.assertEqual([], tasks)
        self.assertIn('no detekt run', reason)

    def test_the_digest_is_of_the_tree_and_not_of_the_git_environment(self):
        """Git exports GIT_DIR for the hook; a digest of the wrong tree is a false green."""
        tree = self.tmp / 'tree'
        tree.mkdir()
        (tree / 'A.kt').write_text('class A\n', encoding='utf-8')
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=tree, check=True, env=_clean_git_env())
        self._give_the_repo_an_identity(tree)
        subprocess.run(['git', 'add', '-A'], cwd=tree, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', 'a'], cwd=tree, check=True, env=_clean_git_env())

        other = self.tmp / 'other'
        other.mkdir()
        (other / 'B.kt').write_text('class B\n', encoding='utf-8')
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=other, check=True, env=_clean_git_env())
        self._give_the_repo_an_identity(other)
        subprocess.run(['git', 'add', '-A'], cwd=other, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', 'b'], cwd=other, check=True, env=_clean_git_env())

        saved = dict(os.environ)
        os.environ['GIT_DIR'] = str(other / '.git')
        os.environ['GIT_WORK_TREE'] = str(other)
        try:
            digest = mod.kotlin_digest(tree)
        finally:
            os.environ.clear()
            os.environ.update(saved)

        self.assertNotEqual(digest, mod.kotlin_digest(other), 'the digest came from the wrong tree')
        (tree / 'A.kt').write_text('class A2\n', encoding='utf-8')
        self.assertNotEqual(digest, mod.kotlin_digest(tree), 'the digest did not follow the tree')

    def test_a_range_with_no_known_base_is_judged_in_full(self):
        tasks, reason = mod.detekt_tasks_for_change(self.repo, None, self.base)
        self.assertEqual(_every_detekt_task(), set(tasks))
        self.assertIn('no known base', reason)

    # ── the base a push does not name ─────────────────────────────────────

    def test_the_default_base_is_the_trunk(self):
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
            env=_clean_git_env(),
        )
        self.assertEqual('origin/main', mod.default_base(self.repo))

    def test_a_branch_with_an_upstream_is_still_measured_against_the_trunk(self):
        """The upstream is the remote's copy of this branch, not the trunk.

        Measuring against it calls the branch's own earlier work "the
        repository's" — everything the branch already carries is above the
        upstream, so the finding the branch brought is cleared. The trunk is
        the base.
        """
        subprocess.run(
            ['git', 'remote', 'add', 'origin', 'https://example.com/sing.git'], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/release', self.base], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'config', 'branch.release.remote', 'origin'], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'config', 'branch.release.merge', 'refs/heads/release'], cwd=self.repo, check=True,
        )
        subprocess.run(['git', 'branch', 'release'], cwd=self.repo, check=True)
        self.assertEqual('origin/main', mod.default_base(self.repo))

    def test_an_upstream_is_not_a_base_when_the_trunk_is_unknown(self):
        """No trunk, no base — the upstream does not stand in for it.

        Without a base every finding counts as the push's own, which is the
        safe direction; the upstream would measure the branch against its own
        remote copy and clear the findings the branch brought.
        """
        subprocess.run(
            ['git', 'remote', 'add', 'origin', 'https://example.com/sing.git'], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/release', self.base], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'config', 'branch.release.remote', 'origin'], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'config', 'branch.release.merge', 'refs/heads/release'], cwd=self.repo, check=True,
        )
        subprocess.run(['git', 'branch', 'release'], cwd=self.repo, check=True)
        self.assertIsNone(mod.default_base(self.repo))

    def test_a_checkout_that_does_not_know_the_trunk_has_no_default_base(self):
        self.assertIsNone(mod.default_base(self.repo))

    def test_a_branch_measured_against_the_trunk_judges_only_what_it_touches(self):
        """A branch that does not exist on the remote yet is a change against the trunk."""
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
            env=_clean_git_env(),
        )
        sha = self._commit('change', {'desktopApp/src/main/kotlin/Main.kt': 'class Main\n'})
        tasks, _ = mod.detekt_tasks_for_change(self.repo, mod.default_base(self.repo), sha)
        self.assertEqual([':desktopApp:detekt'], tasks)

    def test_the_command_measures_the_change_against_the_trunk_when_the_push_does_not_say(self):
        """The whole path, through the command the hook calls."""
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
            env=_clean_git_env(),
        )
        sha = self._commit('change', {'docs/note.md': 'a note\n'})
        result = subprocess.run(
            [
                sys.executable, str(MODULE_PATH),
                '--sha', sha,
                '--repo', str(self.repo),
                '--log-dir', str(self.tmp / 'logs'),
                '--json',
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        report = json.loads(result.stdout)
        self.assertEqual([], report['tasks'], report)
        self.assertIn('no detekt run', report['coverage_reason'])
        self.assertFalse(report['mutated'], 'nothing ran, so nothing changed')
        self.assertTrue(report['clean'], 'a change that needs no detekt run is not a violation')
        self.assertEqual(0, result.returncode)


class FindingOwnershipTest(unittest.TestCase):
    """Who owns a finding: the push that carried it, or the repository.

    The verdict splits the findings detekt reports into the ones the push
    brought and the ones the repository already had. The properties:

      - a finding in a file the pushed range carries is the push's own, and
        the verdict is red;
      - a finding in a file the range does not carry is the repository's, and
        the verdict is green — the repository's CI run is where it is
        reported;
      - without a base there is nothing to compare with, so every finding is
        the push's own: the ratchet is a way of not blocking on somebody
        else's debt, not a way of skipping a gate;
      - a comparison that could not be made — a diff that cannot be computed —
        counts every finding as the push's own too;
      - a change in the rules or in the build counts every finding as the
        push's own, because it can change what every file looks like.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-ownership-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=self.repo, check=True, env=_clean_git_env())
        # The base: a repository that already holds a file the push never touches.
        self.base = self._commit({
            'README.md': 'base\n',
            'shared/src/main/kotlin/Other.kt': SOURCE,
        })
        # The push: a range that carries one new file.
        self.sha = self._commit({
            'shared/src/main/kotlin/Example.kt': SOURCE,
        })

    def _commit(self, files: dict[str, str]) -> str:
        for name, content in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', 'a change'], cwd=self.repo, check=True, env=_clean_git_env())
        return subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
            env=_clean_git_env(),
        ).stdout.strip()

    def _gradle(self, target: str) -> str:
        # The script is written as bytes: a target whose name is not valid
        # UTF-8 has to reach the fake detekt exactly as git would write it.
        path = self.tmp / 'detekt'
        path.write_bytes(FAKE_DETEKT_FINDING.format(target=target).encode('utf-8', 'surrogateescape'))
        path.chmod(0o755)
        return str(path)

    def _judge(self, target: str, base: str | None, sha: str | None = None) -> dict:
        return mod.judge(
            self.repo,
            sha or self.sha,
            None,
            self._gradle(target),
            (":shared:detekt",),
            self.tmp / 'logs',
            base,
        )

    def test_a_finding_in_a_file_the_range_carries_is_the_push_s_own(self):
        report = self._judge('shared/src/main/kotlin/Example.kt', self.base)
        self.assertFalse(report['clean'], 'the push brought this finding')
        self.assertEqual(
            ['shared/src/main/kotlin/Example.kt'],
            [finding['path'] for finding in report['own_findings']],
        )
        self.assertEqual([], report['inherited_findings'])

    def test_a_finding_in_a_file_the_range_does_not_carry_is_the_repository_s(self):
        report = self._judge('shared/src/main/kotlin/Other.kt', self.base)
        self.assertTrue(report['clean'], "the repository's finding is CI's to report")
        self.assertEqual([], report['own_findings'])
        self.assertEqual(
            ['shared/src/main/kotlin/Other.kt'],
            [finding['path'] for finding in report['inherited_findings']],
        )

    def test_a_finding_in_a_file_whose_name_is_not_valid_utf8_is_the_push_s_own(self):
        """The finding's path and the carried path have to be the same path.

        A finding read with a different decoder than the changed-path reader
        is a finding in *another* file: it is called inherited, the push is
        waved through, and the finding is nobody's.
        """
        name = 'shared/src/main/kotlin/caf\udce9.kt'
        target = os.path.join(bytes(self.repo), name.encode('utf-8', 'surrogateescape'))
        with open(target, 'wb') as handle:
            handle.write(SOURCE.encode('utf-8'))
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(
            ['git', 'commit', '-q', '-m', 'a change'], cwd=self.repo, check=True, env=_clean_git_env(),
        )
        sha = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
            env=_clean_git_env(),
        ).stdout.strip()

        report = self._judge(name, self.base, sha)

        self.assertFalse(report['clean'], 'the push brought this finding')
        self.assertEqual([name], [finding['path'] for finding in report['own_findings']])
        self.assertEqual([], report['inherited_findings'])

    def test_without_a_base_every_finding_is_the_push_s_own(self):
        """No base, no comparison — the safe direction is red, not green."""
        report = self._judge('shared/src/main/kotlin/Other.kt', None)
        self.assertFalse(report['clean'])
        self.assertEqual(
            ['shared/src/main/kotlin/Other.kt'],
            [finding['path'] for finding in report['own_findings']],
        )
        self.assertEqual([], report['inherited_findings'])

    def test_a_comparison_that_could_not_be_made_counts_every_finding_as_the_push_s_own(self):
        """A base from an unrelated history: the diff cannot be computed."""
        other = self.tmp / 'other'
        other.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=other, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=other, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=other, check=True, env=_clean_git_env())
        (other / 'a.txt').write_text('a\n', encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=other, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', 'another history'], cwd=other, check=True, env=_clean_git_env())
        alien = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=other, capture_output=True, text=True, check=True,
            env=_clean_git_env(),
        ).stdout.strip()

        report = self._judge('shared/src/main/kotlin/Other.kt', alien)
        self.assertFalse(report['clean'], 'a comparison that could not be made is red')
        self.assertEqual(
            ['shared/src/main/kotlin/Other.kt'],
            [finding['path'] for finding in report['own_findings']],
        )

    def test_a_change_in_the_rules_counts_every_finding_as_the_push_s_own(self):
        sha = self._commit({'config/detekt/detekt.yml': 'complexity:\n'})
        report = self._judge('shared/src/main/kotlin/Other.kt', self.base, sha=sha)
        self.assertFalse(report['clean'], 'the rules moved, so every finding counts')
        self.assertEqual(
            ['shared/src/main/kotlin/Other.kt'],
            [finding['path'] for finding in report['own_findings']],
        )

    def test_a_change_in_a_build_file_counts_every_finding_as_the_push_s_own(self):
        """A module's own build file holds its own detekt block."""
        sha = self._commit({'shared/build.gradle.kts': 'plugins { }\n'})
        report = self._judge('shared/src/main/kotlin/Other.kt', self.base, sha=sha)
        self.assertFalse(report['clean'], 'the build moved, so every finding counts')
        self.assertEqual(
            ['shared/src/main/kotlin/Other.kt'],
            [finding['path'] for finding in report['own_findings']],
        )

    def test_a_change_in_the_root_build_file_counts_every_finding_as_the_push_s_own(self):
        sha = self._commit({'build.gradle.kts': 'plugins { }\n'})
        report = self._judge('shared/src/main/kotlin/Other.kt', self.base, sha=sha)
        self.assertFalse(report['clean'], 'the build moved, so every finding counts')

    def test_a_change_in_the_build_file_judges_every_module(self):
        """The wide change reaches the modules too, not only the findings."""
        sha = self._commit({'build.gradle.kts': 'plugins { }\n'})
        tasks, reason = mod.detekt_tasks_for_change(self.repo, self.base, sha)
        self.assertEqual(_every_detekt_task(), set(tasks))
        self.assertIn('build.gradle.kts', reason)

    def test_a_path_with_non_ascii_letters_is_the_push_s_own_finding(self):
        """Git escapes a path with non-ASCII letters unless it is told not to.

        The escaped path (`Caf\\303\\251.kt`) matches nothing, so the finding
        is called the repository's and the verdict is green — and the module
        does not claim the file, so nothing is judged at all.
        """
        sha = self._commit({'shared/src/main/kotlin/Café.kt': SOURCE})
        report = self._judge('shared/src/main/kotlin/Café.kt', self.base, sha=sha)
        self.assertFalse(report['clean'], 'the push brought this finding')
        self.assertEqual(
            ['shared/src/main/kotlin/Café.kt'],
            [finding['path'] for finding in report['own_findings']],
        )

    def test_the_changed_paths_are_read_the_way_the_tree_names_them(self):
        """The paths come out of git as the tree names them, not as git quotes them."""
        sha = self._commit({'shared/src/main/kotlin/Café.kt': SOURCE})
        paths = mod.changed_paths(self.repo, self.base, sha)
        self.assertIn('shared/src/main/kotlin/Café.kt', paths)
        self.assertNotIn('shared/src/main/kotlin/Caf\\303\\251.kt', paths)


class BaseResolutionTest(unittest.TestCase):
    """What the verdict measures the push against.

    The base is the trunk. Not the branch's upstream: the upstream is the
    remote's copy of this branch, so everything the branch already carries is
    above it, and measuring against it calls the branch's own earlier work
    "the repository's" — the finding the branch brought is cleared.

    The properties:

      - the default base is the trunk's remote-tracking ref, even when the
        branch has an upstream that points somewhere else;
      - an upstream is not a base at all: with no trunk there is no base, and
        every finding counts as the push's own;
      - the caller that knows there is no base says so — `--no-base` — so the
        verdict and the hook's message agree;
      - a base and a no-base at once is a contradiction the command refuses.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-base-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=self.repo, check=True, env=_clean_git_env())
        # The trunk: a repository that already holds a file the branch never touches.
        self.base = self._commit({
            'README.md': 'base\n',
            'shared/src/main/kotlin/Other.kt': SOURCE,
        })
        # The branch: a range that carries one new file.
        self.sha = self._commit({
            'shared/src/main/kotlin/Example.kt': SOURCE,
        })
        # The remote's copy of this branch — the upstream.
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/feature', self.sha], cwd=self.repo, check=True,
            env=_clean_git_env(),
        )
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
            env=_clean_git_env(),
        )
        subprocess.run(
            ['git', 'config', 'branch.feature.remote', 'origin'], cwd=self.repo, check=True,
        )
        subprocess.run(
            ['git', 'config', 'branch.feature.merge', 'refs/heads/feature'], cwd=self.repo, check=True,
        )
        subprocess.run(['git', 'branch', 'feature'], cwd=self.repo, check=True)

    def _commit(self, files: dict[str, str]) -> str:
        for name, content in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(['git', 'commit', '-q', '-m', 'a change'], cwd=self.repo, check=True, env=_clean_git_env())
        return subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
            env=_clean_git_env(),
        ).stdout.strip()

    def _gradle(self, target: str) -> str:
        path = self.tmp / 'detekt'
        path.write_text(FAKE_DETEKT_FINDING.format(target=target), encoding='utf-8')
        path.chmod(0o755)
        return str(path)

    def _run(self, target: str, *extra: str) -> tuple[int, dict]:
        result = subprocess.run(
            [
                sys.executable, str(MODULE_PATH),
                '--sha', self.sha,
                '--repo', str(self.repo),
                '--gradle-cmd', self._gradle(target),
                '--tasks', ':shared:detekt',
                '--log-dir', str(self.tmp / 'logs'),
                '--json',
                *extra,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode not in (0, 1):
            return result.returncode, {}
        return result.returncode, json.loads(result.stdout)

    def test_the_push_is_measured_against_the_trunk_not_the_upstream(self):
        """The finding the branch brought is the branch's own.

        Measured against the upstream — the remote's copy of this branch —
        the range is empty, the finding looks inherited and the verdict is
        green. Measured against the trunk, it is the push's own.
        """
        rc, report = self._run('shared/src/main/kotlin/Example.kt')
        self.assertEqual(1, rc, report)
        self.assertFalse(report['clean'], report)
        self.assertEqual(
            ['shared/src/main/kotlin/Example.kt'],
            [finding['path'] for finding in report['own_findings']],
        )

    def test_a_push_that_has_no_base_is_told_so_explicitly(self):
        """No base: every finding counts as the push's own — red, not green."""
        rc, report = self._run('shared/src/main/kotlin/Other.kt', '--no-base')
        self.assertEqual(1, rc, report)
        self.assertFalse(report['clean'], report)
        self.assertEqual(
            ['shared/src/main/kotlin/Other.kt'],
            [finding['path'] for finding in report['own_findings']],
        )
        self.assertEqual([], report['inherited_findings'])

    def test_the_command_refuses_a_base_and_a_no_base_at_once(self):
        result = subprocess.run(
            [
                sys.executable, str(MODULE_PATH),
                '--sha', self.sha,
                '--repo', str(self.repo),
                '--base', self.base,
                '--no-base',
                '--json',
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn('--no-base', result.stderr)

    def test_the_command_has_no_branch_option(self):
        """The upstream is not a base, so there is no option to name one.

        The option the verdict used to take is how the upstream became the
        base: the caller named the branch, and the script measured the
        branch against the remote's copy of itself — so everything the branch
        carried was "the repository's" and the finding the branch brought
        was cleared. The option is gone, so the mistake cannot be made
        again by a caller that means well.
        """
        result = subprocess.run(
            [
                sys.executable, str(MODULE_PATH),
                '--sha', self.sha,
                '--repo', str(self.repo),
                '--branch', 'feature',
                '--json',
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn('--branch', result.stderr)


class GitEnvironmentTest(unittest.TestCase):
    """The verdict takes its git environment from the one canonical answer.

    The script used to keep a copy of the list of variables a git command
    inside a tree must not inherit. The list was right — and the copy was a
    second answer to the same question, so the next variable git exports would
    have been dropped here and not in the gates. The answer lives in
    `sgate.gatekit.gitenv`, and this test says which one the script uses.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-gitenv-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _repo(self, name: str) -> pathlib.Path:
        repo = self.tmp / name
        repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=repo, check=True, env=_clean_git_env())
        (repo / 'a.txt').write_text('a\n', encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=repo, check=True, env=_clean_git_env())
        subprocess.run(
            ['git', '-c', 'user.email=verdict@example.com', '-c', 'user.name=Verdict',
             'commit', '-q', '-m', 'a file'],
            cwd=repo, check=True, env=_clean_git_env(),
        )
        return repo

    def test_the_verdict_uses_the_canonical_git_environment(self):
        """One answer: the function the gates and the tests use is this one."""
        self.assertIs(mod.tree_env, gitenv.tree_env)
        self.assertEqual(
            pathlib.Path(gitenv.__file__).resolve(),
            GITENV_PATH.resolve(),
            'the answer is the one the repository owns, not a copy of it',
        )

    def test_a_foreign_git_environment_does_not_leak_into_the_judged_tree(self):
        """The behaviour that answer buys: git inside the tree means that tree."""
        tree = self._repo('tree')
        other = self._repo('other')
        hook_env = {
            'GIT_DIR': str(other / '.git'),
            'GIT_WORK_TREE': str(other),
            'GIT_INDEX_FILE': str(other / '.git' / 'index'),
            'GIT_COMMON_DIR': str(other / '.git'),
        }
        with unittest.mock.patch.dict(os.environ, hook_env):
            top = mod.git(tree, 'rev-parse', '--show-toplevel').strip()
        self.assertEqual(str(tree.resolve()), top)


class PathDecodingTest(unittest.TestCase):
    """A filename is a byte string, and a byte string is not always UTF-8.

    Reading such a path strictly raises `UnicodeDecodeError`, and the reader
    that raises takes the whole verdict with it: the push is stopped by a
    message about a filename nobody can read, and the message says detekt
    reported findings — which is not what happened.

    The paths are read as bytes and decoded with `surrogateescape`, so the
    path survives the round trip and still equals the path git reports.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-path-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(
            ['git', '-c', 'user.email=verdict@example.com', '-c', 'user.name=Verdict',
             'commit', '-q', '--allow-empty', '-m', 'base'],
            cwd=self.repo, check=True, env=_clean_git_env(),
        )
        self.base = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True,
            check=True, env=_clean_git_env(),
        ).stdout.strip()

    def _commit(self, name: str) -> str:
        """A file with a name given in bytes, committed, and the sha it is at."""
        (self.repo / 'shared').mkdir(exist_ok=True)
        target = os.path.join(bytes(self.repo / 'shared'), name)
        with open(target, 'wb') as handle:
            handle.write(b'val a = 1\n')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True, env=_clean_git_env())
        subprocess.run(
            ['git', '-c', 'user.email=verdict@example.com', '-c', 'user.name=Verdict',
             'commit', '-q', '-m', 'a file'],
            cwd=self.repo, check=True, env=_clean_git_env(),
        )
        return subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True,
            check=True, env=_clean_git_env(),
        ).stdout.strip()

    def test_a_path_that_is_not_valid_utf8_is_read_rather_than_raised_on(self):
        """The bytes of a filename git accepts are the bytes it reports."""
        sha = self._commit(b'caf\xe9.kt')

        paths = mod.changed_paths(self.repo, self.base, sha)

        self.assertIn('shared/caf\udce9.kt', paths)

    def test_a_path_that_is_not_valid_utf8_survives_the_round_trip(self):
        """The path that is read is the path git wrote — byte for byte."""
        sha = self._commit(b'caf\xe9.kt')

        paths = mod.changed_paths(self.repo, self.base, sha)

        written = {path.encode('utf-8', 'surrogateescape') for path in paths}
        self.assertIn(b'shared/caf\xe9.kt', written)

    def test_a_carriage_return_in_a_filename_is_not_turned_into_a_newline(self):
        """A text-mode subprocess rewrites it; a byte-mode one does not.

        The path is not merely unreadable — it is *different* from the path
        git reports, so the change would be judged as carrying no Kotlin.
        """
        sha = self._commit(b'cr\rX.kt')

        paths = mod.changed_paths(self.repo, self.base, sha)

        self.assertIn('shared/cr\rX.kt', paths)
        self.assertNotIn('shared/cr\nX.kt', paths)

    def test_the_reader_does_not_raise_on_a_path_that_is_not_valid_utf8(self):
        """The whole point: the verdict survives the filename."""
        sha = self._commit(b'caf\xe9.kt')

        try:
            paths = mod.changed_paths(self.repo, self.base, sha)
        except UnicodeDecodeError as exc:  # pragma: no cover - the defect
            self.fail(f'reading a path raised instead of reading it: {exc}')

        self.assertTrue(any('caf' in path for path in paths))


if __name__ == '__main__':
    unittest.main()
