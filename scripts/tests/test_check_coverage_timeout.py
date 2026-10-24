"""Tests for check-coverage-timeout.py.

The gate exists because the CI workflow carries the coverage job's timeout as
a literal number and the cost of the instrumented run that timeout has to
cover is measured in another file, and nothing connected the two: a
re-measurement that doubled the instrumented cost would leave the timeout
exactly where it was.

So the cases that matter are the ones a clean tree never shows:

  - a timeout that no longer covers the measured cost is a finding, and the
    message names BOTH numbers — the measurement and the timeout;
  - a re-measurement that doubles the cost is a finding even though the
    workflow was not touched at all: that is the gap the gate closes;
  - a job that runs `:koverVerify` without a `timeout-minutes` is a finding,
    not a pass with nothing to say;
  - a workflow in which no job runs `:koverVerify` is a finding, not a crash;
  - the timeout that is checked is the one of the job that runs
    `:koverVerify` — not the first `timeout-minutes` in the file, and not the
    timeout of the job that runs the sibling task `:detekt-rules:koverVerify`;
  - a tree that satisfies the rule produces no findings at all.
"""

import importlib.util
import math
import pathlib
import sys
import tempfile
import unittest

from click.testing import CliRunner

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-coverage-timeout.py'
spec = importlib.util.spec_from_file_location('check_coverage_timeout', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_coverage_timeout'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


WORKFLOW = """\
name: CI

on:
  pull_request:

jobs:
  static:
    runs-on: ubuntu-24.04
    timeout-minutes: 25
    steps:
      - name: Static gates
        run: python3 scripts/check-script-conventions.py

  tests:
    name: JVM tests, detekt, coverage
    runs-on: ubuntu-24.04
    timeout-minutes: 75
    steps:
      - name: Coverage floors
        run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"

  detekt-rules:
    runs-on: ubuntu-24.04
    timeout-minutes: 10
    steps:
      - name: Detekt rules coverage
        run: ./gradlew :detekt-rules:koverVerify -Ptest.population="$TEST_POPULATION"
"""

COSTS = """\
# The measured cost of the INSTRUMENTED coverage run.
fast.ms=2245000
full.ms=2003000
measuredAt=2026-10-22
machine=16 vCPU, warm Gradle cache
"""


class CoverageTimeoutTest(unittest.TestCase):
    """The coverage job's timeout has to cover the measured cost."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.write('.github/workflows/ci.yml', WORKFLOW)
        self.write('config/docs/coverage-cost.properties', COSTS)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def findings(self) -> list[str]:
        return mod.check(self.root)

    def test_a_tree_that_satisfies_the_rule_has_no_findings(self) -> None:
        self.assertEqual(self.findings(), [])

    def test_the_coverage_job_declares_the_timeout_of_the_larger_population(self) -> None:
        """The coverage job's timeout is the LARGER population's, recomputed.

        The number is not a constant somebody chose: it is the published
        measurement of the more expensive population, doubled, because a
        timeout has to cover the runner as well as the run. A timeout that is
        too tight truncates a slow run and reports a failure about the clock.

        This reads the repository's own files, because the claim is about them:
        a synthetic workflow would prove that the reader computes a number, not
        that the workflow declares it.
        """
        repo = pathlib.Path(__file__).resolve().parent.parent.parent
        workflow = (repo / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        costs = (repo / 'config/docs/coverage-cost.properties').read_text(encoding='utf-8')

        measurements: dict[str, str] = {}
        for line in costs.splitlines():
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            name, _, value = line.partition('=')
            measurements[name.strip()] = value.strip()

        measured = max(int(measurements['fast.ms']), int(measurements['full.ms']))
        required = math.ceil(measured * 2 / 60000)

        job = mod._coverage_job(workflow)
        self.assertEqual('coverage', job.job_id)
        self.assertEqual(required, job.timeout_minutes)

        # And the gate agrees with the arithmetic on the real tree.
        self.assertEqual([], mod.check(repo))

    def test_the_timeout_of_the_coverage_job_is_the_one_checked(self) -> None:
        """The first `timeout-minutes` in the file is not the one that matters.

        The workflow has three of them: 25 on the static job, 75 on the JVM
        tier and 10 on the job that runs the sibling task
        `:detekt-rules:koverVerify`. Only the JVM tier's is the coverage
        job's.
        """
        job = mod._coverage_job(WORKFLOW)
        self.assertEqual(job.job_id, 'tests')
        self.assertEqual(job.timeout_minutes, 75)

    def test_the_text_fallback_finds_the_same_job(self) -> None:
        """The same answer without a YAML parser."""
        lines = WORKFLOW.splitlines()
        blocks, jobs_line = mod._job_blocks(WORKFLOW)
        job = mod._text_coverage_job(lines, blocks, jobs_line)
        self.assertEqual(job.job_id, 'tests')
        self.assertEqual(job.timeout_minutes, 75)

    def test_a_timeout_that_is_too_small_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the workflow's timeout is shrunk below the cost."""
        self.write(
            '.github/workflows/ci.yml',
            WORKFLOW.replace('    timeout-minutes: 75', '    timeout-minutes: 40'),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        finding = findings[0]
        self.assertIn('.github/workflows/ci.yml:', finding)
        self.assertIn('40-minute timeout', finding)
        self.assertIn('2245000 ms', finding)

    def test_a_doubled_measurement_is_a_finding(self) -> None:
        """The gap the gate closes: the cost doubled, the workflow did not.

        Nothing was changed in the workflow — the timeout is still the 75
        minutes it always was — and the timeout no longer covers the run.
        """
        self.write(
            'config/docs/coverage-cost.properties',
            COSTS.replace('fast.ms=2245000', 'fast.ms=4490000'),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('4490000 ms', findings[0])
        self.assertIn('75-minute timeout', findings[0])

    def test_a_job_without_a_timeout_is_a_finding(self) -> None:
        self.write(
            '.github/workflows/ci.yml',
            WORKFLOW.replace('    timeout-minutes: 75\n', ''),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('declares no', findings[0])
        self.assertIn("job 'tests'", findings[0])

    def test_a_workflow_without_the_coverage_step_is_a_finding(self) -> None:
        self.write(
            '.github/workflows/ci.yml',
            WORKFLOW.replace(
                '        run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"\n',
                '',
            ),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('no job in the workflow runs :koverVerify', findings[0])

    def test_a_missing_cost_file_is_a_finding(self) -> None:
        (self.root / 'config/docs/coverage-cost.properties').unlink()
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('the measured cost of the instrumented coverage run is missing', findings[0])

    def test_a_cost_that_is_not_a_number_is_a_finding(self) -> None:
        self.write(
            'config/docs/coverage-cost.properties',
            COSTS.replace('fast.ms=2245000', 'fast.ms=about half an hour'),
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('is not a whole number of milliseconds', findings[0])

    def test_a_cost_file_without_any_cost_is_a_finding(self) -> None:
        self.write('config/docs/coverage-cost.properties', "machine=16 vCPU\n")
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn("holds no '<population>.ms' cost", findings[0])

    def test_a_missing_workflow_is_a_finding(self) -> None:
        (self.root / '.github/workflows/ci.yml').unlink()
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('the CI workflow is missing', findings[0])

    def test_the_root_option_points_the_gate_at_another_tree(self) -> None:
        """The `--root` flag is how the unit tests aim the gate at a tree."""
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 0, result.output)
        self.assertIn('OK', result.output)

        self.write(
            '.github/workflows/ci.yml',
            WORKFLOW.replace('    timeout-minutes: 75', '    timeout-minutes: 5'),
        )
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 1, result.output)
        self.assertIn('2245000 ms', result.output)

    def test_a_root_that_is_not_a_directory_is_a_configuration_error(self) -> None:
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root / 'nowhere')])
        self.assertEqual(result.exit_code, 3, result.output)


if __name__ == '__main__':
    unittest.main()
