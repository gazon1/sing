"""Tests for check-recorded-ci-jobs.py.

The gate exists because a criterion nobody checks is a hope: the ADR of
2026-10-21 says the recorded CI run goes stale when the workflow's jobs change,
and for a change nothing checked it. The false names were exactly that — the
job was renamed, the published page kept naming the old one, and the record
that priced the loop said nothing about it.

So the gate has to be right in the cases a clean tree never shows:

  - a recorded job the workflow no longer declares is a finding — the case the
    gate was written for, and the one a rename produces;
  - a clean tree is clean, and the gate says how many recorded jobs it checked;
  - **a matrix job is expanded before the comparison**. `Android (${{
    matrix.variant }})` declares ONE job and the runner shows two; a comparison
    that does not expand the matrix would call both of them unknown, and the
    gate would be red on a tree that is right.
  - a job that declares no `name:` is shown under its own key — the runner
    shows the key, and the record holds the key.
  - a baseline that carries no CI record has nothing to check, and says so
    rather than reporting a clean tree it never looked at.
  - a record that cannot be read is an error, not a clean tree.

The tests write their own tree: a gate exercised only against the data of one
particular repository is a gate whose behaviour nobody knows when the tree
changes shape.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-recorded-ci-jobs.py'
)
spec = importlib.util.spec_from_file_location('check_recorded_ci_jobs', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_recorded_ci_jobs'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


WORKFLOW = """\
name: CI

on:
  push:
    branches: [main]

jobs:
  changes:
    runs-on: ubuntu-24.04
    steps:
      - run: echo changes
  static:
    name: Static gates
    runs-on: ubuntu-24.04
    steps:
      - run: echo static
  android:
    name: Android (${{ matrix.variant }})
    runs-on: ubuntu-24.04
    strategy:
      fail-fast: false
      matrix:
        include:
          - variant: free
            flags: ''
          - variant: pro
            flags: '-PwithPro=true'
    steps:
      - run: echo android
  ci-gate:
    name: CI gate
    runs-on: ubuntu-24.04
    steps:
      - run: echo gate
"""


WORKFLOW_WITH_EXCLUDE = """\
name: CI

on:
  push:
    branches: [main]

jobs:
  android:
    name: Android (${{ matrix.variant }})
    runs-on: ubuntu-24.04
    strategy:
      matrix:
        variant: [free, pro, tablet]
        exclude:
          - variant: tablet
    steps:
      - run: echo android
"""

WORKFLOW_WITH_EXPRESSION = """\
name: CI

on:
  schedule: []

jobs:
  nightly:
    name: Nightly ${{ github.event.schedule }}
    runs-on: ubuntu-24.04
    steps:
      - run: echo nightly
"""

WORKFLOW_WITH_EXPRESSION_AND_A_JOB = """\
name: CI

on:
  schedule: []

jobs:
  nightly:
    name: Nightly ${{ github.event.schedule }}
    runs-on: ubuntu-24.04
    steps:
      - run: echo nightly
  tests:
    name: JVM tests
    runs-on: ubuntu-24.04
    steps:
      - run: echo tests
"""

WORKFLOW_WITH_INCLUDE_AND_EXCLUDE = """\
name: CI

on:
  push:
    branches: [main]

jobs:
  android:
    name: Android (${{ matrix.variant }})
    runs-on: ubuntu-24.04
    strategy:
      matrix:
        include:
          - variant: free
          - variant: pro
        exclude:
          - variant: pro
    steps:
      - run: echo android
"""


def _record(jobs: dict[str, int] | None) -> str:
    """A baseline holding a CI record with the given jobs."""
    data: dict[str, object] = {'schemaVersion': 1, 'environment': {}}
    if jobs is not None:
        data['ci'] = {
            'runId': 1,
            'url': 'https://example.invalid/run/1',
            'createdAt': '2026-10-24T10:12:52Z',
            'conclusion': 'success',
            'jobs': jobs,
        }
    return json.dumps(data, indent=2)


class RecordedJobsTest(unittest.TestCase):
    """The record and the workflow, read from a tree the test writes."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        (self.root / 'config' / 'docs').mkdir(parents=True)
        (self.root / '.github' / 'workflows').mkdir(parents=True)
        self.record = self.root / 'config' / 'docs' / 'gate-benchmarks.json'
        self.workflow = self.root / '.github' / 'workflows' / 'ci.yml'
        self.workflow.write_text(WORKFLOW, encoding='utf-8')

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _write(self, jobs: dict[str, int] | None) -> None:
        self.record.write_text(_record(jobs), encoding='utf-8')

    def _run(self) -> int:
        """Run the gate over the tree the test wrote.

        Click's `main` with `standalone_mode=False` CATCHES its own `Exit` and
        returns the code — `ctx.exit(2)` comes back as the value `2`, not as a
        raised exception. A harness that waits for a `SystemExit` sees a clean
        tree for a gate that errored.
        """
        self.output = io.StringIO()
        with contextlib.redirect_stdout(self.output):
            code = mod.cli.main(args=['--root', str(self.root)], standalone_mode=False)
        return code


class TheRuleTest(RecordedJobsTest):
    """A recorded job the workflow no longer declares is a finding."""

    def test_a_recorded_job_the_workflow_does_not_declare_is_a_finding(self) -> None:
        self._write({'JVM tests': 1_000})
        self.workflow.write_text(
            WORKFLOW.replace('name: CI gate', 'name: The gate'), encoding='utf-8'
        )

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)

    def test_a_renamed_job_is_the_case_the_gate_is_written_for(self) -> None:
        """The false names: the job was renamed, and the record still named it."""
        self._write({'CI gate': 4_000})
        self.workflow.write_text(
            WORKFLOW.replace('name: CI gate', 'name: The gate'), encoding='utf-8'
        )

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)

    def test_a_clean_tree_is_clean(self) -> None:
        self._write({'Static gates': 474_000, 'CI gate': 4_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)

    def test_a_recorded_job_the_workflow_declares_is_not_a_finding(self) -> None:
        """The gate must not fire on the tree it was written for."""
        self._write({'changes': 6_000, 'Static gates': 474_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)


class TheMatrixTest(RecordedJobsTest):
    """A matrix job is expanded before the comparison."""

    def test_a_matrix_leg_the_record_names_is_a_job_the_workflow_declares(self) -> None:
        """`Android (${{ matrix.variant }})` shows as two jobs, and the record holds both."""
        self._write({'Android (free)': 129_000, 'Android (pro)': 220_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)

    def test_a_matrix_leg_that_is_not_declared_is_a_finding(self) -> None:
        self._write({'Android (tablet)': 129_000})

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)

    def test_the_unexpanded_template_is_not_a_declared_name(self) -> None:
        """The runner never shows `Android (${{ matrix.variant }})`."""
        self._write({'Android (${{ matrix.variant }})': 129_000})

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)


class TheNamesTest(RecordedJobsTest):
    """A job that declares no `name:` is shown under its own key."""

    def test_a_job_without_a_name_is_declared_under_its_key(self) -> None:
        self._write({'changes': 6_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)

    def test_an_unknown_key_is_a_finding(self) -> None:
        self._write({'nowhere': 6_000})

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)


class TheEmptyRecordTest(RecordedJobsTest):
    """A baseline with no CI record has nothing to check."""

    def test_a_baseline_without_a_ci_record_is_nothing_to_check(self) -> None:
        self._write(None)

        code = self._run()

        self.assertEqual(code, mod.CLEAN)

    def test_a_record_with_no_jobs_is_nothing_to_check(self) -> None:
        self._write({})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)


class TheUnreadableTreeTest(RecordedJobsTest):
    """A tree that cannot be read is an error, not a clean tree."""

    def test_a_missing_record_is_an_error(self) -> None:
        self._write({'changes': 6_000})
        self.record.unlink()

        code = self._run()

        self.assertEqual(code, mod.IO_ERROR)

    def test_a_missing_workflow_is_an_error(self) -> None:
        self._write({'changes': 6_000})
        self.workflow.unlink()

        code = self._run()

        self.assertEqual(code, mod.IO_ERROR)

    def test_a_record_that_is_not_json_is_an_error(self) -> None:
        self.record.write_text('{not json', encoding='utf-8')

        code = self._run()

        self.assertEqual(code, mod.IO_ERROR)


class TheExcludedLegTest(RecordedJobsTest):
    """An `exclude` takes a leg away, and the runner never shows it."""

    def test_a_recorded_excluded_leg_is_a_finding(self) -> None:
        self.workflow.write_text(WORKFLOW_WITH_EXCLUDE, encoding='utf-8')
        self._write({'Android (tablet)': 129_000})

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)

    def test_a_recorded_leg_the_matrix_keeps_is_clean(self) -> None:
        self.workflow.write_text(WORKFLOW_WITH_EXCLUDE, encoding='utf-8')
        self._write({'Android (free)': 129_000, 'Android (pro)': 220_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)

    def test_an_exclude_does_not_take_an_include_leg_away(self) -> None:
        """GitHub processes every `exclude` before every `include`."""
        self.workflow.write_text(WORKFLOW_WITH_INCLUDE_AND_EXCLUDE, encoding='utf-8')
        self._write({'Android (free)': 129_000, 'Android (pro)': 220_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)


class TheUncomputableNameTest(RecordedJobsTest):
    """A name the gate cannot compute cannot prove that a recorded job is gone."""

    def test_an_uncomputable_name_does_not_report_a_healthy_tree_as_stale(self) -> None:
        self.workflow.write_text(WORKFLOW_WITH_EXPRESSION, encoding='utf-8')
        self._write({'Nightly 2026-10-24': 60_000})

        code = self._run()

        self.assertEqual(code, mod.CLEAN)

    def test_the_gate_says_which_recorded_job_it_could_not_prove(self) -> None:
        self.workflow.write_text(WORKFLOW_WITH_EXPRESSION, encoding='utf-8')
        self._write({'Nightly 2026-10-24': 60_000})

        self._run()

        self.assertIn('Nightly 2026-10-24', self.output.getvalue())

    def test_an_uncomputable_name_does_not_silence_the_rest_of_the_gate(self) -> None:
        """One job the gate cannot compute must not stop it from proving another one gone."""
        self.workflow.write_text(WORKFLOW_WITH_EXPRESSION, encoding='utf-8')
        self._write({'Nightly 2026-10-24': 60_000, 'nowhere': 1_000})

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)

    def test_a_renamed_job_is_still_a_finding_next_to_an_uncomputable_name(self) -> None:
        self.workflow.write_text(WORKFLOW_WITH_EXPRESSION_AND_A_JOB, encoding='utf-8')
        self._write({'JVM tests [renamed]': 1_000})

        code = self._run()

        self.assertEqual(code, mod.VALIDATION_FAILURE)


class TheMalformedRecordTest(RecordedJobsTest):
    """A record that says it holds CI jobs and does not is a configuration error."""

    def test_a_ci_block_that_is_not_a_mapping_is_an_error(self) -> None:
        self.record.write_text('{"ci": "not a mapping"}', encoding='utf-8')

        code = self._run()

        self.assertEqual(code, mod.IO_ERROR)

    def test_a_jobs_block_that_is_not_a_mapping_is_an_error(self) -> None:
        self.record.write_text('{"ci": {"jobs": ["a", "b"]}}', encoding='utf-8')

        code = self._run()

        self.assertEqual(code, mod.IO_ERROR)


class TheReadingTest(unittest.TestCase):
    """The two readers, on their own."""

    def test_the_recorded_jobs_are_the_keys_of_the_ci_block(self) -> None:
        names = mod.recorded_jobs(_record({'b': 2, 'a': 1}))

        self.assertEqual(names, ['a', 'b'])

    def test_a_baseline_without_a_ci_block_reads_as_no_jobs(self) -> None:
        self.assertEqual(mod.recorded_jobs('{"schemaVersion": 1}'), [])

    def test_the_declared_jobs_include_the_key_of_a_nameless_job(self) -> None:
        names, patterns = mod.declared_jobs(WORKFLOW)

        self.assertIn('changes', names)
        self.assertIn('Static gates', names)
        self.assertIn('CI gate', names)
        self.assertEqual(patterns, [])

    def test_the_declared_jobs_expand_the_matrix(self) -> None:
        names, _ = mod.declared_jobs(WORKFLOW)

        self.assertIn('Android (free)', names)
        self.assertIn('Android (pro)', names)
        self.assertNotIn('Android (${{ matrix.variant }})', names)

    def test_an_excluded_matrix_leg_is_not_declared(self) -> None:
        """The runner never shows an excluded leg, so the gate must not declare it."""
        names, _ = mod.declared_jobs(WORKFLOW_WITH_EXCLUDE)

        self.assertIn('Android (free)', names)
        self.assertNotIn('Android (tablet)', names)

    def test_a_name_the_gate_cannot_compute_becomes_a_pattern(self) -> None:
        names, patterns = mod.declared_jobs(WORKFLOW_WITH_EXPRESSION)

        self.assertEqual(patterns, ['Nightly *'])
        self.assertNotIn('Nightly ${{ github.event.schedule }}', names)

    def test_a_malformed_ci_block_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            mod.recorded_jobs('{"ci": "not a mapping"}')

    def test_a_malformed_jobs_block_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            mod.recorded_jobs('{"ci": {"jobs": ["a", "b"]}}')


if __name__ == '__main__':
    unittest.main()
