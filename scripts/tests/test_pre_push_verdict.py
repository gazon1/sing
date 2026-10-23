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
  - a run that CORRECTS a file makes the verdict red too, even when it exits
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

import importlib.util

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'pre-push-verdict.py'
spec = importlib.util.spec_from_file_location('pre_push_verdict', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SOURCE = 'package com.example\n\nclass Example\n'

#: A stand-in for ./gw. The mode says what detekt would do in this tree.
FAKE_GRADLE = """#!/usr/bin/env bash
case "{mode}" in
  fail)
    echo "Example.kt:1:1: ClassSignature: the class signature must be on one line" >&2
    exit 1
    ;;
  correct)
    printf 'package com.example\\n\\nclass Example\\n// corrected\\n' > shared/src/main/kotlin/Example.kt
    exit 0
    ;;
  *)
    exit 0
    ;;
esac
"""


class VerdictTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-verdict-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True)
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=self.repo, check=True)
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=self.repo, check=True)
        source = self.repo / 'shared/src/main/kotlin/Example.kt'
        source.parent.mkdir(parents=True)
        source.write_text(SOURCE, encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True)
        subprocess.run(['git', 'commit', '-q', '-m', 'a file'], cwd=self.repo, check=True)
        self.sha = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
        ).stdout.strip()

    def _gradle(self, mode: str) -> str:
        path = self.tmp / f'gradle-{mode}'
        path.write_text(FAKE_GRADLE.format(mode=mode), encoding='utf-8')
        path.chmod(0o755)
        return str(path)

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
        self.assertFalse(report['corrected'])

    def test_a_run_that_reports_findings_is_red(self):
        report = self._judge(mode='fail')
        self.assertFalse(report['clean'])
        self.assertEqual([":shared:detekt"], report['failed_tasks'])

    def test_a_run_that_corrects_a_file_is_red_even_when_it_exits_zero(self):
        report = self._judge(mode='correct')
        self.assertFalse(report['clean'], 'a corrected file is the evidence of a violation')
        self.assertTrue(report['corrected'])
        self.assertEqual([], report['failed_tasks'])

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
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=self.repo, check=True)
        subprocess.run(['git', 'config', 'user.email', 'verdict@example.com'], cwd=self.repo, check=True)
        subprocess.run(['git', 'config', 'user.name', 'Verdict'], cwd=self.repo, check=True)
        self.base = self._commit('base', {'README.md': 'base\n'})

    def _commit(self, message: str, files: dict[str, str]) -> str:
        for name, content in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=self.repo, check=True)
        subprocess.run(['git', 'commit', '-q', '-m', message], cwd=self.repo, check=True)
        return subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.repo, capture_output=True, text=True, check=True,
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
        self.assertEqual(8, len(tasks), reason)
        self.assertIn('rules', reason)

    def test_a_change_in_the_build_judges_every_module(self):
        tasks, _ = self._tasks({'gradle/libs.versions.toml': 'x = "1"\n'})
        self.assertEqual(8, len(tasks))

    def test_a_change_that_moves_no_kotlin_needs_no_detekt_run(self):
        tasks, reason = self._tasks({'docs/notes.md': 'a note\n'})
        self.assertEqual([], tasks)
        self.assertIn('no detekt run', reason)

    def test_the_digest_is_of_the_tree_and_not_of_the_git_environment(self):
        """Git exports GIT_DIR for the hook; a digest of the wrong tree is a false green."""
        tree = self.tmp / 'tree'
        tree.mkdir()
        (tree / 'A.kt').write_text('class A\n', encoding='utf-8')
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=tree, check=True)
        subprocess.run(['git', 'add', '-A'], cwd=tree, check=True)
        subprocess.run(['git', 'commit', '-q', '-m', 'a'], cwd=tree, check=True)

        other = self.tmp / 'other'
        other.mkdir()
        (other / 'B.kt').write_text('class B\n', encoding='utf-8')
        subprocess.run(['git', 'init', '-q', '-b', 'main'], cwd=other, check=True)
        subprocess.run(['git', 'add', '-A'], cwd=other, check=True)
        subprocess.run(['git', 'commit', '-q', '-m', 'b'], cwd=other, check=True)

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
        self.assertEqual(8, len(tasks))
        self.assertIn('no known base', reason)

    # ── the base a push does not name ─────────────────────────────────────

    def test_the_default_base_is_the_trunk(self):
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
        )
        self.assertEqual('origin/main', mod.default_base(self.repo))

    def test_a_checkout_that_does_not_know_the_trunk_has_no_default_base(self):
        self.assertIsNone(mod.default_base(self.repo))

    def test_a_branch_measured_against_the_trunk_judges_only_what_it_touches(self):
        """A branch that does not exist on the remote yet is a change against the trunk."""
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
        )
        sha = self._commit('change', {'desktopApp/src/main/kotlin/Main.kt': 'class Main\n'})
        tasks, _ = mod.detekt_tasks_for_change(self.repo, mod.default_base(self.repo), sha)
        self.assertEqual([':desktopApp:detekt'], tasks)

    def test_the_command_measures_the_change_against_the_trunk_when_the_push_does_not_say(self):
        """The whole path, through the command the hook calls."""
        subprocess.run(
            ['git', 'update-ref', 'refs/remotes/origin/main', self.base], cwd=self.repo, check=True,
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
        self.assertFalse(report['corrected'], 'nothing ran, so nothing was corrected')
        self.assertTrue(report['clean'], 'a change that needs no detekt run is not a violation')
        self.assertEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main()
