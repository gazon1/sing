#!/usr/bin/env python3
"""Tests for scripts/src/workflow_gradle.py — the shared Gradle-invocation parser.

Three gates answer questions about the same command line, and before this module
each carried its own definition of "an invocation". Two of them disagreed, and the
disagreement was silent: the readers gate fed the parser one line at a time, so a
Gradle command written across `\\` continuations was read as `--continue` with no
task in it — not test-capable — and the run that *produces* the results became
invisible. Its own sabotage control found it by passing a sabotaged workflow
(run 38961950540).

So these tests assert the three properties nothing asserted before, each with the
shape from the tree that broke:

1. a backslash-continued command is ONE invocation carrying all its tasks
2. a commented-out command is not an invocation
3. a Kover aggregate is test-capable, because it reads the test tasks' reports
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / 'src'))

import workflow_gradle as wg  # noqa: E402

# The exact shape PR #690 gave `ci.yml`'s main Gradle step.
CONTINUED = """set -o pipefail
./gradlew --continue \\
  --no-build-cache \\
  :shared:jvmTest \\
  -Ptest.tags=fast,slow \\
  2>&1 | tee build.log
"""

FOLDED = "./gradlew --continue :shared:jvmTest -Ptest.tags=fast,slow 2>&1 | tee build.log\n"

ONE_LINE = "./gw :koverVerify -Ptest.tags=fast,slow\n"


class ContinuationsAreOneInvocation(unittest.TestCase):
    def test_a_continued_command_names_all_its_tasks(self):
        invocations = list(wg.iter_invocations(CONTINUED))
        self.assertEqual(len(invocations), 1, invocations)
        self.assertIn(':shared:jvmTest', invocations[0].tasks)

    def test_a_continued_command_is_test_capable(self):
        # The defect: read line-by-line, line one is `./gradlew --continue \`, which
        # names no task, so the invocation looked compile-only and the whole job
        # looked like it had a single test-capable invocation.
        (invocation,) = list(wg.iter_invocations(CONTINUED))
        self.assertTrue(invocation.test_capable)

    def test_a_continued_command_keeps_its_selector(self):
        (invocation,) = list(wg.iter_invocations(CONTINUED))
        self.assertEqual(invocation.selector, 'fast,slow')

    def test_the_same_command_is_one_invocation_in_every_spelling(self):
        for label, text in (('continued', CONTINUED), ('folded', FOLDED),
                            ('one line', ONE_LINE)):
            with self.subTest(label):
                self.assertEqual(len(list(wg.iter_invocations(text))), 1, label)


class CommentsAreNotInvocations(unittest.TestCase):
    def test_a_commented_out_command_is_skipped(self):
        invocations = list(wg.iter_invocations('# ./gradlew :koverVerify\n'))
        self.assertEqual(invocations, [])

    def test_a_trailing_comment_does_not_hide_a_real_invocation(self):
        (invocation,) = list(wg.iter_invocations('./gradlew :koverVerify # coverage\n'))
        self.assertTrue(invocation.test_capable)


class KoverIsTestCapable(unittest.TestCase):
    """The finding itself was a Kover step: it reads the binary reports the test
    tasks produce, so a stale one runs them."""

    def test_kover_aggregate_counts_as_a_test_run(self):
        for token in (':koverVerify', ':koverReport', ':koverMarkdownTable',
                      ':detekt-rules:koverVerify', 'koverLog'):
            with self.subTest(token):
                self.assertTrue(wg.is_test_task(token), token)

    def test_a_compile_task_does_not(self):
        for token in (':androidApp:assembleDebug', ':shared:compileKotlinJvm',
                      ':adr-cli:installDist'):
            with self.subTest(token):
                self.assertFalse(wg.is_test_task(token), token)

    def test_a_flag_is_never_a_task(self):
        # `-Ptest.tags=fast,slow` contains "test"; if it counted, every invocation
        # carrying a selector would look test-capable for the wrong reason.
        self.assertFalse(wg.is_test_task('-Ptest.tags=fast,slow'))


class StepsArePositionsNotLines(unittest.TestCase):
    def test_position_is_the_steps_ordinal_within_the_job(self):
        workflow = pathlib.Path(self._write(WORKFLOW))
        steps = list(wg.iter_steps(workflow))
        self.assertEqual([s.name for s in steps], ['Gradle', 'counts', 'other job'])
        self.assertEqual([s.position for s in steps], [0, 1, 0])
        self.assertEqual(steps[0].job, 'tests')
        self.assertEqual(steps[2].job, 'other')

    def test_a_step_without_a_run_body_is_not_a_step(self):
        workflow = pathlib.Path(self._write(WORKFLOW))
        names = [s.name for s in wg.iter_steps(workflow)]
        self.assertNotIn('uses action', names)

    def _write(self, text: str) -> str:
        tmp = tempfile.mkdtemp()
        path = pathlib.Path(tmp) / 'workflow.yml'
        path.write_text(text, encoding='utf-8')
        return str(path)


WORKFLOW = """name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Gradle
        run: |
          set -o pipefail
          ./gradlew --continue \\\\
            :shared:jvmTest \\\\
            -Ptest.tags=fast,slow
      - name: counts
        run: python3 scripts/check-test-runs.py --current shared/build/test-results/jvmTest
      - uses: actions/checkout@v7
  other:
    runs-on: ubuntu-24.04
    steps:
      - name: other job
        run: ./gradlew :koverVerify
"""


if __name__ == '__main__':
    unittest.main()