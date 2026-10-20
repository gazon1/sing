#!/usr/bin/env python3
"""Tests for check-waiver-references.py.

The gate's own failure mode is the one it exists to prevent: a resolver that
stops recognising a reference yields an empty finding list, and an empty
finding list reads as a pass. So the fixtures assert on *what the resolver
saw*, not on an exit code alone — and the synthetic trees are built around the
three ways a reference can rot:

  1. the file it names is gone;
  2. the anchor it names is no longer there — including the case where the
     anchor still occurs in the file but only inside a comment, which a
     substring check would accept and a step-name check must not;
  3. the step it names still exists but no longer runs the task the waiver
     was granted for, on one surface or on the other.
"""

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

GATE = pathlib.Path(__file__).resolve().parent.parent / 'check-waiver-references.py'


def load_gate():
    """Import the gate as a module — the file name is not a valid identifier."""
    spec = importlib.util.spec_from_file_location('_waiver_gate', GATE)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    finally:
        sys.modules.pop(spec.name, None)
    return module


# A workflow whose step runs the task the waiver names.
WORKFLOW_WITH_STEP = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Kover verify (detekt-rules)
        run: ./gradlew :detekt-rules:koverVerify -Ptest.tags=fast,slow
'''

# The same workflow after the step has been renamed. The old name survives —
# twice — in the comments that explain why the step is where it is. A
# substring check would accept both; only a step-name check tells the truth.
WORKFLOW_WITH_RENAMED_STEP = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      # `Kover verify (detekt-rules)`, any of which pulls the shared test task.
      - name: Kover verify detekt rules
        run: ./gradlew :detekt-rules:koverVerify -Ptest.tags=fast,slow
'''

# The step survives; the task it ran does not. The waiver's premise — that
# the compensating pass runs a step later — is now false.
WORKFLOW_WITHOUT_TASK = '''
name: ci
on: [push]
jobs:
  tests:
    runs-on: ubuntu-24.04
    steps:
      - name: Kover verify (detekt-rules)
        run: echo "moved elsewhere"
'''


class FakeControl:
    """The fields of a `Control` the gate reads."""

    def __init__(self, kind='sabotage', needs_clean_run=False, argv=(), until='',
                 tracked_by=''):
        self.kind = kind
        self.needs_clean_run = needs_clean_run
        self.argv = argv
        self.until = until
        self.tracked_by = tracked_by


class WaiverReferenceTest(unittest.TestCase):
    """The resolver, against synthetic trees."""

    def setUp(self):
        self.gate = load_gate()

    def _tree(self, files: dict) -> pathlib.Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        for rel, content in files.items():
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        return root

    def test_an_anchor_that_names_a_step_resolves(self):
        root = self._tree({'.github/workflows/ci.yml': WORKFLOW_WITH_STEP})
        anchor, kind, problems = self.gate.resolve_reference(
            '.github/workflows/ci.yml#Kover verify (detekt-rules)', root
        )
        self.assertEqual(problems, [])
        self.assertEqual(anchor, 'Kover verify (detekt-rules)')
        self.assertEqual(kind, 'step')

    def test_an_anchor_that_only_survives_in_a_comment_does_not_resolve(self):
        """The anchor occurs twice in the file — and names no step."""
        root = self._tree({'.github/workflows/ci.yml': WORKFLOW_WITH_RENAMED_STEP})
        anchor, kind, problems = self.gate.resolve_reference(
            '.github/workflows/ci.yml#Kover verify (detekt-rules)', root
        )
        self.assertTrue(problems, 'a comment is not the step the reference points at')
        self.assertIn('no step named', problems[0])
        self.assertEqual(kind, 'step')

    def test_a_missing_file_is_reported(self):
        root = self._tree({})
        _, _, problems = self.gate.resolve_reference(
            '.github/workflows/ci.yml#Kover verify (detekt-rules)', root
        )
        self.assertTrue(problems)
        self.assertIn('not a file in this repository', problems[0])

    def test_a_non_workflow_file_resolves_by_content(self):
        root = self._tree({'docs/notes.md': 'the anchor text is here\n'})
        anchor, kind, problems = self.gate.resolve_reference(
            'docs/notes.md#the anchor text is here', root
        )
        self.assertEqual(problems, [])
        self.assertEqual(kind, 'text')

    def test_an_anchor_missing_from_a_non_workflow_file_is_reported(self):
        root = self._tree({'docs/notes.md': 'something else\n'})
        _, _, problems = self.gate.resolve_reference('docs/notes.md#the anchor', root)
        self.assertTrue(problems)
        self.assertIn('does not contain the anchor', problems[0])

    def test_a_bare_reference_may_name_an_issue_slug(self):
        root = self._tree({'.specify/issues/some-slug.yaml': 'id: some-slug\n'})
        _, _, problems = self.gate.resolve_reference('some-slug', root)
        self.assertEqual(problems, [])

    def test_the_gradle_task_is_read_out_of_the_control_command(self):
        self.assertEqual(
            self.gate._gradle_tasks(('./gw', '-q', ':detekt-rules:koverVerify')),
            [':detekt-rules:koverVerify'],
        )
        self.assertEqual(self.gate._gradle_tasks(('{python}', 'scripts/x.py')), [])


class WaiverPremiseTest(unittest.TestCase):
    """The premise: the compensating pass still runs, on both surfaces."""

    def setUp(self):
        self.gate = load_gate()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        workflow = self.root / '.github' / 'workflows' / 'ci.yml'
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow.write_text(WORKFLOW_WITH_STEP, encoding='utf-8')

        self.control = FakeControl(
            argv=('./gw', '-q', ':detekt-rules:koverVerify'),
            tracked_by='.github/workflows/ci.yml#Kover verify (detekt-rules)',
        )

    def _premise(self, check_sh_text: str) -> list[str]:
        return self.gate._premise_problems(
            name='detekt-rule-coverage-floor',
            control=self.control,
            reference=self.control.tracked_by,
            anchor='Kover verify (detekt-rules)',
            kind='step',
            check_sh_text=check_sh_text,
            root=self.root,
        )

    def test_a_waiver_whose_premise_holds_has_no_problems(self):
        self.assertEqual(self._premise('./gw :detekt-rules:koverVerify\n'), [])

    def test_a_step_that_no_longer_runs_the_task_is_reported(self):
        workflow = self.root / '.github' / 'workflows' / 'ci.yml'
        workflow.write_text(WORKFLOW_WITHOUT_TASK, encoding='utf-8')
        problems = self._premise('./gw :detekt-rules:koverVerify\n')
        self.assertTrue(problems)
        self.assertIn('does not run', problems[0])

    def test_a_surface_that_no_longer_names_the_task_is_reported(self):
        problems = self._premise('# the task moved to another script\n')
        self.assertTrue(problems)
        self.assertIn('check.sh never names', problems[0])

    def test_a_control_that_proves_no_gradle_task_has_no_premise_to_check(self):
        control = FakeControl(argv=('{python}', 'scripts/check-x.py'))
        problems = self.gate._premise_problems(
            name='x',
            control=control,
            reference='.github/workflows/ci.yml#a step',
            anchor='a step',
            kind='step',
            check_sh_text='',
            root=self.root,
        )
        self.assertEqual(problems, [])


class GateEndToEndTest(unittest.TestCase):
    """The gate, run as the gate suite runs it."""

    def test_the_gate_is_green_on_this_repository(self):
        result = subprocess.run(
            [sys.executable, str(GATE)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(
            result.returncode, 0,
            f"the gate failed on a clean tree:\n{result.stdout}\n{result.stderr}",
        )
        self.assertIn('OK', result.stdout)

    def test_the_gate_emits_json(self):
        result = subprocess.run(
            [sys.executable, str(GATE), '--json'],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertTrue(payload['passed'])
        self.assertEqual(payload['blocking_errors'], [])


if __name__ == '__main__':
    unittest.main()
