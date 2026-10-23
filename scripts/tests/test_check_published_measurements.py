"""Tests for check-published-measurements.py.

The gate exists because the repository publishes its coverage measurements in
prose — in the ADRs and in the change's tasks file — and nothing compared a
published number with the artifact it was read from. The number "2.82M
instructions" was the sum of every `INSTRUCTION` counter in the Kover XML, so
it read five times the report's own denominator, and the percentages were
unaffected: that is how a wrong denominator survived a real measurement and
looked plausible.

So the cases that matter are the ones a clean tree never shows:

  - a published class count that differs from the record is a finding, and the
    message names the file, the line and BOTH numbers;
  - a published instruction total that differs is a finding too — it is the
    number the wrong denominator was;
  - a number published for a population the record holds no measurement of is
    a finding: it is a measurement nobody recorded;
  - a document that publishes no number is not a finding, and neither is a
    class count that is not the report's class count ("49 of 62 classes are
    slow" counts a module's slow test classes);
  - the record itself has to hold every measure of every population, or a
    published number cannot be compared with a measurement at all;
  - a tree that satisfies the rule produces no findings at all.
"""

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

from click.testing import CliRunner

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-published-measurements.py'
spec = importlib.util.spec_from_file_location('check_published_measurements', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_published_measurements'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


RECORD = """\
# The measured coverage of the application, per population.
fast.classes=4735
fast.instructions=579002
fast.branches=28930
fast.lines=66909
fast.instructionCoverage=28.78
fast.branchCoverage=23.92
fast.lineCoverage=32.06
fast.measuredAt=2026-10-23
full.classes=4642
full.instructions=565144
full.branches=28079
full.lines=65480
full.instructionCoverage=55.88
full.branchCoverage=40.44
full.lineCoverage=57.23
full.measuredAt=2026-10-22
"""

#: The names of the populations — the file that defines them.
POPULATIONS = """\
# The named test populations and the JUnit tags each one selects.
fast=fast
full=fast,slow
"""

#: A document that publishes the recorded numbers of both populations.
PUBLISHER = """\
# Decision

**D3. Two sets of floors.** The fast set was measured on 2026-10-23: the fast
population's report holds 4735 classes over 579,002 instructions, and the full
population's report of 2026-10-22 holds 4642 classes over 565,144 — the fast
report is not the smaller of the two universes.

The measurements are recorded in `config/docs/coverage-measurements.properties`.
"""

#: A document that publishes no measurement at all.
SILENT = """\
# Decision

The floor stays where it is. The numbers are in the record.
"""


class PublishedMeasurementsTest(unittest.TestCase):
    """Every published coverage number has to be the recorded one."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.write('config/docs/coverage-measurements.properties', RECORD)
        self.write('config/test-populations.properties', POPULATIONS)
        self.write(mod.PUBLISHERS[0], PUBLISHER)
        self.write(mod.PUBLISHERS[1], SILENT)
        self.write(mod.PUBLISHERS[2], SILENT)

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

    def test_a_published_class_count_that_differs_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the published class count is changed."""
        self.write(mod.PUBLISHERS[0], PUBLISHER.replace('4735 classes', '4700 classes'))
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        finding = findings[0]
        self.assertIn(f'{mod.PUBLISHERS[0]}:', finding)
        self.assertIn('4700', finding)
        self.assertIn('4735', finding)
        self.assertIn('fast.classes', finding)

    def test_a_published_instruction_total_that_differs_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the published instruction total is changed.

        This is the number the wrong denominator was: a denominator read at the
        wrong level is five times the report's own.
        """
        self.write(mod.PUBLISHERS[0], PUBLISHER.replace('579,002 instructions', '2,820,000 instructions'))
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('2820000', findings[0])
        self.assertIn('579002', findings[0])

    def test_a_finding_carries_the_line_the_number_stands_on(self) -> None:
        """The finding names the line, not just the file."""
        self.write(mod.PUBLISHERS[0], PUBLISHER.replace('4735 classes', '4700 classes'))
        findings = self.findings()
        line = mod.PUBLISHERS[0]
        # the number stands on the fourth line of the document
        self.assertIn(f'{line}:4:', findings[0])

    def test_a_number_for_a_population_the_record_does_not_hold_is_a_finding(self) -> None:
        """A measurement nobody recorded is a finding."""
        self.write(
            'config/docs/coverage-measurements.properties',
            '\n'.join(
                line for line in RECORD.splitlines()
                if not line.startswith('full.')
            ) + '\n',
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('measurement nobody recorded', findings[0])

    def test_a_document_that_publishes_no_number_is_not_a_finding(self) -> None:
        """A document without a number says nothing the gate can check."""
        self.write(mod.PUBLISHERS[0], SILENT)
        self.assertEqual(self.findings(), [])

    def test_a_class_count_that_is_not_the_report_class_count_is_not_a_finding(self) -> None:
        """`49 of 62 classes are slow` counts a module's slow test classes."""
        self.write(
            mod.PUBLISHERS[0],
            "# Context\n\n"
            "And `desktopApp` is where 49 of 62 classes are slow, so the gate could\n"
            "only answer for `shared`.\n",
        )
        self.assertEqual(self.findings(), [])

    def test_the_abbreviated_figure_of_the_defect_is_not_a_measurement(self) -> None:
        """The number the defect published is not a number the gate can read.

        `2.82M instructions` is a rounded figure, and the record holds exact
        numbers — so the gate does not read it. That limitation is named in the
        script's own docstring rather than left implicit.
        """
        self.write(
            mod.PUBLISHERS[0],
            "# Decision\n\n"
            'The number this decision published at first was "2.82M instructions" for\n'
            "both runs. It was the sum of every `INSTRUCTION` counter in the XML.\n",
        )
        self.assertEqual(self.findings(), [])

    def test_a_record_without_a_measure_is_a_finding(self) -> None:
        """The record has to hold every measure, or nothing can be compared."""
        self.write(
            'config/docs/coverage-measurements.properties',
            RECORD.replace('fast.branches=28930\n', ''),
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('fast.branches', findings[0])

    def test_a_record_value_that_is_not_a_number_is_a_finding(self) -> None:
        self.write(
            'config/docs/coverage-measurements.properties',
            RECORD.replace('fast.classes=4735', 'fast.classes=about four thousand'),
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('is not a', findings[0])

    def test_a_missing_record_is_a_finding(self) -> None:
        (self.root / 'config/docs/coverage-measurements.properties').unlink()
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('the record of the measured coverage is missing', findings[0])

    def test_the_root_option_points_the_gate_at_another_tree(self) -> None:
        """The `--root` flag is how the unit tests aim the gate at a tree."""
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 0, result.output)
        self.assertIn('OK', result.output)

        self.write(mod.PUBLISHERS[0], PUBLISHER.replace('4735 classes', '4700 classes'))
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 1, result.output)
        self.assertIn('4700', result.output)
        self.assertIn('4735', result.output)

    def test_the_json_contract(self) -> None:
        """The verdict is one JSON object: passed, blocking_errors, warnings."""
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root), '--json'])
        self.assertEqual(result.exit_code, 0, result.output)
        payload = json.loads(result.output)
        self.assertEqual(payload['passed'], True)
        self.assertEqual(payload['blocking_errors'], [])
        self.assertEqual(payload['warnings'], [])

        self.write(mod.PUBLISHERS[0], PUBLISHER.replace('4735 classes', '4700 classes'))
        result = runner.invoke(mod.cli, ['--root', str(self.root), '--json'])
        self.assertEqual(result.exit_code, 1, result.output)
        payload = json.loads(result.output)
        self.assertEqual(payload['passed'], False)
        self.assertEqual(len(payload['blocking_errors']), 1)
        self.assertEqual(payload['warnings'], [])

    def test_a_root_that_is_not_a_directory_is_a_configuration_error(self) -> None:
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root / 'nowhere')])
        self.assertEqual(result.exit_code, 3, result.output)


if __name__ == '__main__':
    unittest.main()
