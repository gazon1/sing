"""Tests for ci-verdict.py.

A CI claim that names a run id and nothing else is not evidence: a repository
has several workflows, and the one that answered "success" may have been the
cache cleanup. The tool exists so that the claim is a whole line — the
workflow, the run, the conclusion — and so that the exit code is the
conclusion. These tests cover the cases a real run list shows:

  - a list that holds several workflows answers with the CI one, not with the
    first success in the list — the mistake that produced a green report of a
    red branch;
  - the most recent run of the workflow wins, because a rerun is the answer
    and the run before it is the one that failed and was retried;
  - a run that has not concluded is not a pass, and is not a "green" either;
  - a list with no run of that workflow is "no run", not a pass — the
    vacuous green the repository keeps paying for;
  - the display name of the workflow ("CI") is matched against the file name
    ("ci.yml"), because that is what `gh run list` reports.
"""

import importlib.util
import pathlib
import sys
import unittest
import unittest.mock

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'ci-verdict.py'
spec = importlib.util.spec_from_file_location('ci_verdict', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['ci_verdict'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


def _run(run_id: int, conclusion: str, workflow: str = 'CI', url: str = 'u') -> dict:
    return {
        'databaseId': run_id,
        'conclusion': conclusion,
        'status': 'completed',
        'workflowName': workflow,
        'url': url,
    }


class VerdictFromRunsTest(unittest.TestCase):
    """The run list is read, and the conclusion is the exit code."""

    def test_the_ci_run_answers_and_not_the_cache_cleanup(self) -> None:
        runs = [_run(39888582872, 'success', 'Clean up PR caches'), _run(39888579980, 'failure')]
        line, code = mod.verdict_from_runs(runs, 'ci.yml')
        self.assertEqual(code, mod.NOT_SUCCESS)
        self.assertIn('39888579980', line)
        self.assertNotIn('39888582872', line)

    def test_a_green_ci_run_answers_zero(self) -> None:
        line, code = mod.verdict_from_runs([_run(39888579980, 'success')], 'ci.yml')
        self.assertEqual(code, mod.CLEAN)
        self.assertIn('ci.yml', line)
        self.assertIn('success', line)

    def test_the_newest_run_of_the_workflow_wins(self) -> None:
        runs = [_run(100, 'failure'), _run(200, 'success')]
        line, code = mod.verdict_from_runs(runs, 'ci.yml')
        self.assertEqual(code, mod.CLEAN)
        self.assertIn('200', line)

    def test_a_run_that_has_not_concluded_is_not_a_pass(self) -> None:
        line, code = mod.verdict_from_runs(
            [{'databaseId': 1, 'conclusion': '', 'status': 'in_progress', 'workflowName': 'CI'}],
            'ci.yml',
        )
        self.assertEqual(code, mod.NOT_SUCCESS)
        self.assertIn('has not concluded', line)

    def test_no_run_of_that_workflow_is_no_run_and_not_a_pass(self) -> None:
        line, code = mod.verdict_from_runs([_run(1, 'success', 'Clean up PR caches')], 'ci.yml')
        self.assertEqual(code, mod.NO_RUN)
        self.assertIn('no run', line)

    def test_an_empty_list_is_no_run(self) -> None:
        line, code = mod.verdict_from_runs([], 'ci.yml')
        self.assertEqual(code, mod.NO_RUN)
        self.assertIn('no run', line)

    def test_a_run_without_a_workflow_name_is_not_the_one_asked_for(self) -> None:
        # `gh run list --workflow ci.yml` filters on the server and always
        # reports the name. A list captured without it cannot tell the build
        # from the cache cleanup, and the cache cleanup succeeds often — so an
        # unattributable run is not a run of the workflow, and not a pass.
        line, code = mod.verdict_from_runs([{'databaseId': 5, 'conclusion': 'success'}], 'ci.yml')
        self.assertEqual(code, mod.NO_RUN)
        self.assertIn('no run', line)
        self.assertIn('none of the listed runs names it', line)

    def test_the_display_name_matches_the_file_name(self) -> None:
        self.assertTrue(mod._is_the_workflow({'workflowName': 'CI'}, 'ci.yml'))
        self.assertTrue(mod._is_the_workflow({'workflowName': 'ci.yml'}, 'ci.yml'))
        self.assertFalse(mod._is_the_workflow({'workflowName': 'Clean up PR caches'}, 'ci.yml'))


class ExitCodeTest(unittest.TestCase):
    """The exit codes are the ones the tool's docstring declares."""

    def test_the_codes_are_the_documented_ones(self) -> None:
        self.assertEqual((mod.CLEAN, mod.NOT_SUCCESS, mod.NO_RUN, mod.IO_ERROR), (0, 1, 2, 3))


class DeadlineTest(unittest.TestCase):
    """The call to `gh` has a deadline, and the deadline is visible.

    The pre-push hook asks for the verdict in the middle of a push: a push
    must not wait for the network, and it must not need a program it cannot
    count on having — which is why the deadline is here and not a `timeout`
    call in the caller.
    """

    def test_the_call_to_gh_carries_the_deadline(self) -> None:
        seen: dict = {}

        def fake_run(args, **kwargs):
            seen.update(kwargs)
            return unittest.mock.Mock(returncode=0, stdout='[]', stderr='')

        with unittest.mock.patch.object(mod.subprocess, 'run', fake_run):
            mod._gh(['run', 'list'], 'owner/repo')
        self.assertEqual(mod.GH_TIMEOUT_SECONDS, seen.get('timeout'))

    def test_a_call_that_does_not_answer_says_so(self) -> None:
        def fake_run(args, **kwargs):
            raise mod.subprocess.TimeoutExpired(cmd='gh', timeout=mod.GH_TIMEOUT_SECONDS)

        with unittest.mock.patch.object(mod.subprocess, 'run', fake_run):
            with self.assertRaises(RuntimeError) as caught:
                mod._gh(['run', 'list'], 'owner/repo')
        self.assertIn('did not answer', str(caught.exception))


class ResolveCommitTest(unittest.TestCase):
    """A number is a pull request; a name is a revision of this checkout."""

    def setUp(self) -> None:
        self._gh = mod._gh
        self._git = mod._git
        self.addCleanup(setattr, mod, '_gh', self._gh)
        self.addCleanup(setattr, mod, '_git', self._git)

    def test_a_number_is_read_as_a_pull_request(self) -> None:
        seen: list[list[str]] = []

        def fake_gh(args: list[str], repo: str) -> str:
            seen.append(args)
            return '{"headRefOid": "1cb18e3935622927120e8ac9b6f8ae9a50107a74"}'

        mod._gh = fake_gh
        mod._git = lambda args: self.fail('a number must not be read as a revision')

        commit = mod.resolve_commit('973', 'gazon1/sing')

        self.assertEqual('1cb18e3935622927120e8ac9b6f8ae9a50107a74', commit)
        self.assertEqual(['pr', 'view', '973', '--json', 'headRefOid'], seen[0])

    def test_a_name_is_read_as_a_revision(self) -> None:
        def fake_git(args: list[str]) -> str:
            self.assertEqual(['rev-parse', 'origin/main'], args)
            return '1cb18e3935622927120e8ac9b6f8ae9a50107a74\n'

        mod._gh = lambda args, repo: self.fail('a name must not be read as a pull request')
        mod._git = fake_git

        self.assertEqual(
            '1cb18e3935622927120e8ac9b6f8ae9a50107a74',
            mod.resolve_commit('origin/main', 'gazon1/sing'),
        )


if __name__ == '__main__':
    unittest.main()
