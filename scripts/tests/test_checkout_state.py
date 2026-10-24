"""Tests for `sgate checkout-state` — what the checkout is carrying.

The premise: a checkout on a machine with several clones is shared state. It
can be carrying commits that `origin/main` does not have — another session's
work, or a fixture nobody meant to push — and nothing in the tree says so.
The agent finds out at the worst moment: when it commits, and the commit
carries somebody else's work with it.

That happened on 2026-10-24: the canonical checkout carried 22 commits that
`origin/main` did not have, and its `AGENTS.md` was over the injection
budget, so the harness truncated the instructions and the agent read a file
that ended in the middle of a sentence.

These tests hold the command to its own claims:

  - it answers about the checkout the caller stands in, not the checkout the
    installed `sgate` belongs to;
  - a checkout that is behind and ahead of `origin/main` is reported as
    diverged — the state in which a commit carries somebody else's work;
  - a branch that is only ahead is not diverged: that is a feature branch;
  - the instructions are measured against the budget the harness gives them.
"""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / 'scripts' / 'src' / 'sgate' / 'commands' / 'checkout_state.py'
spec = importlib.util.spec_from_file_location('checkout_state', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def _git(*args: str, cwd: pathlib.Path) -> str:
    """One git command in a fixture repository."""
    result = subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=mod.tree_env(),
    )
    return result.stdout.strip()


class CheckoutStateTest(unittest.TestCase):
    """The report, on repositories built for it."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='checkout-state-'))
        self.addCleanup(self._remove)
        _git('init', '--quiet', '-b', 'main', cwd=self.tmp)
        _git('config', 'user.email', 'state@example.com', cwd=self.tmp)
        _git('config', 'user.name', 'State', cwd=self.tmp)

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _commit(self, name: str) -> None:
        (self.tmp / 'file.txt').write_text(name, encoding='utf-8')
        _git('add', '-A', cwd=self.tmp)
        _git('commit', '--quiet', '-m', name, cwd=self.tmp)

    def _upstream(self, name: str = 'origin/main') -> None:
        """A ref that stands for the upstream branch."""
        _git('update-ref', name, 'HEAD', cwd=self.tmp)

    def test_a_fresh_checkout_reports_its_root_and_branch(self):
        self._commit('first')
        state = mod._state(self.tmp)
        self.assertEqual(str(self.tmp), state['root'])
        self.assertEqual('main', state['branch'])

    def test_a_checkout_with_no_upstream_says_so(self):
        self._commit('first')
        state = mod._state(self.tmp)
        self.assertFalse(state['upstreamKnown'])

    def test_a_branch_that_is_only_ahead_is_not_diverged(self):
        self._commit('first')
        self._upstream()
        self._commit('second')
        state = mod._state(self.tmp)
        self.assertEqual(1, state['ahead'])
        self.assertEqual(0, state['behind'])
        report = mod._report(state)
        self.assertNotIn('DIVERGED', report)

    def test_a_checkout_that_is_behind_and_ahead_is_diverged(self):
        # The upstream moved on, and the checkout carries commits of its own:
        # the state in which a commit carries somebody else's work.
        self._commit('first')
        self._upstream()
        self._commit('second')
        _git('update-ref', 'origin/main', 'HEAD', cwd=self.tmp)
        _git('reset', '--hard', '--quiet', 'HEAD~1', cwd=self.tmp)
        _git('commit', '--quiet', '--allow-empty', '-m', 'a commit upstream does not have', cwd=self.tmp)
        state = mod._state(self.tmp)
        self.assertTrue(state['ahead'])
        self.assertTrue(state['behind'])
        self.assertIn('DIVERGED', mod._report(state))

    def test_the_carried_commits_are_named(self):
        self._commit('first')
        self._upstream()
        self._commit('second')
        state = mod._state(self.tmp)
        carried = state['carrying']
        self.assertEqual(1, len(carried), carried)
        self.assertEqual('second', carried[0]['subject'])

    def test_the_instructions_are_measured_against_the_budget(self):
        (self.tmp / 'AGENTS.md').write_text('x' * 10, encoding='utf-8')
        state = mod._state(self.tmp)
        instructions = state['instructions']
        self.assertEqual(10, instructions['bytes'])
        self.assertEqual(mod.PROJECT_INSTRUCTIONS_BUDGET, instructions['budget'])
        self.assertTrue(instructions['fits'])

    def test_instructions_over_the_budget_are_reported_as_not_fitting(self):
        (self.tmp / 'AGENTS.md').write_text(
            'x' * (mod.PROJECT_INSTRUCTIONS_BUDGET + 1), encoding='utf-8'
        )
        state = mod._state(self.tmp)
        instructions = state['instructions']
        self.assertFalse(instructions['fits'])
        self.assertIn('DOES NOT FIT', mod._report(state))


class TheCommandRunsTest(unittest.TestCase):
    """The command itself runs, and answers about the checkout it stands in."""

    def test_the_command_reports_the_caller_checkout(self):
        result = subprocess.run(
            [sys.executable, '-m', 'sgate', 'checkout-state', '--json'],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env={**mod.tree_env(), 'PYTHONPATH': str(ROOT / 'scripts' / 'src')},
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('"root"', result.stdout)
        self.assertIn(str(ROOT), result.stdout)


if __name__ == '__main__':
    unittest.main()
