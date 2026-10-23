"""Tests for check-published-measurements.py.

The gate exists because the repository publishes its coverage measurements in
prose — in the ADRs, in the change's tasks file, in the two Gradle scripts and
in the Kover reader's docstring — and nothing compared a published number with
the artifact it was read from. The number "2.82M instructions" was the sum of
every `INSTRUCTION` counter in the Kover XML, so it read five times the
report's own denominator, and the percentages were unaffected: that is how a
wrong denominator survived a real measurement and looked plausible.

The cases that matter are the ones a clean tree never shows, and each of them
is a defect class the gate was rewritten for:

  - a published class count that differs from the record is a finding, and the
    message names the file, the line and BOTH numbers;
  - a published instruction total that differs is a finding too — it is the
    number the wrong denominator was;
  - a published PERCENTAGE that differs is a finding, in the shape that names
    the unit of every number, and a number published for a day the record does
    not hold is a finding as well;
  - a number published for a population the record holds no measurement of is
    a finding: it is a measurement nobody recorded;
  - a number NOBODY attributes to a population is not a finding but a warning:
    it is a number the gate cannot check, and the gate says so instead of
    dropping it;
  - a document that publishes no number is not a finding, and neither is a
    class count that is not the report's class count ("49 of 62 classes are
    slow" counts a module's slow test classes), nor one entry of a list of
    counters ("440 classes / 3689 tests");
  - the attribution is read out of the clause the number stands in, so a
    sentence that talks about two populations does not hand one of them the
    other's numbers;
  - the record itself has to hold every measure of every population, and its
    days have to be days, or a published number cannot be compared with a
    measurement at all;
  - a document that publishes the measurements and is not there is a finding:
    a check that switches itself off is a check nobody can trust.

The three document tests at the end are the gate's own promise: the ADR, the
Gradle script and the gate's docstring say what the gate does, and a claim
that is not checked is a claim nobody checks.
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

#: The tree the script lives in — the documents the gate reads.
REPO = MODULE_PATH.parent.parent

#: The decision that promises what the gate compares.
ADR22 = "docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md"

#: The document the tests publish their numbers in.
PUBLISHER_FILE = ADR22

#: The Gradle script that publishes the measurements in a comment.
BUILD = "build.gradle.kts"

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

The full population's report of 2026-10-22 measured **55.88 instruction / 40.44
branch / 57.23 line**.

The measurements are recorded in `config/docs/coverage-measurements.properties`.
"""

#: A document that publishes no measurement at all.
SILENT = """\
# Decision

The floor stays where it is. The numbers are in the record.
"""


def _silent_of(relative: str) -> str:
    """A publisher file that publishes nothing — in the language it is written in."""
    if relative.endswith('.py'):
        return '"""A reader that publishes no measurement at all."""\n'
    if relative.endswith('.kts'):
        return "// A script that publishes no measurement at all.\n"
    return SILENT


class PublishedMeasurementsTest(unittest.TestCase):
    """Every published coverage number has to be the recorded one."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.write('config/docs/coverage-measurements.properties', RECORD)
        self.write('config/test-populations.properties', POPULATIONS)
        for relative in mod.PUBLISHERS:
            self.write(relative, _silent_of(relative))
        self.write(PUBLISHER_FILE, PUBLISHER)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def findings(self) -> list[str]:
        return mod.check(self.root)

    def warnings(self) -> list[str]:
        return mod.check_all(self.root)[1]

    # --- the counts -------------------------------------------------------

    def test_a_tree_that_satisfies_the_rule_has_no_findings(self) -> None:
        self.assertEqual(self.findings(), [])

    def test_a_published_class_count_that_differs_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the published class count is changed."""
        self.write(PUBLISHER_FILE, PUBLISHER.replace('4735 classes', '4700 classes'))
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        finding = findings[0]
        self.assertIn(f'{PUBLISHER_FILE}:', finding)
        self.assertIn('4700', finding)
        self.assertIn('4735', finding)
        self.assertIn('fast.classes', finding)

    def test_a_published_instruction_total_that_differs_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the published instruction total is changed.

        This is the number the wrong denominator was: a denominator read at the
        wrong level is five times the report's own.
        """
        self.write(PUBLISHER_FILE, PUBLISHER.replace('579,002 instructions', '2,820,000 instructions'))
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('2820000', findings[0])
        self.assertIn('579002', findings[0])

    def test_a_finding_carries_the_line_the_number_stands_on(self) -> None:
        """The finding names the line, not just the file."""
        self.write(PUBLISHER_FILE, PUBLISHER.replace('4735 classes', '4700 classes'))
        findings = self.findings()
        line = PUBLISHER_FILE
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
        self.write(PUBLISHER_FILE, _silent_of(PUBLISHER_FILE))
        self.assertEqual(self.findings(), [])

    # --- the attribution (defect class 1) ---------------------------------

    def test_a_class_count_that_is_not_the_report_class_count_is_not_a_finding(self) -> None:
        """`49 of 62 classes are slow` counts a module's slow test classes."""
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "And `desktopApp` is where 49 of 62 classes are slow, so the gate could\n"
            "only answer for `shared`.\n",
        )
        self.assertEqual(self.findings(), [])

    def test_a_list_of_test_counters_is_not_a_measurement(self) -> None:
        """`shared:jvmTest 440 classes / 3689 tests` counts a module's classes.

        The number is one entry of a list of counters, and the report's class
        count is a count that stands alone. The sentence names the fast
        population, and the old gate handed it these four numbers.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "The fast population's run reported shared:jvmTest 440 classes / 3689 tests,\n"
            "desktopApp:test 62 classes / 197 tests, mcp-server:test 7 classes / 39 tests,\n"
            "shared:testAndroidHostTest 225 classes / 2097 tests.\n",
        )
        self.assertEqual(self.findings(), [])
        self.assertEqual(self.warnings(), [])

    def test_the_populations_swapped_in_one_sentence_are_not_a_finding(self) -> None:
        """One sentence, two populations: each number belongs to its own.

        The sentence says the full run of 2026-10-22 held 4642 classes and that
        the report of 2026-10-23 holds 4735 — and the record says exactly that.
        The old gate read the nearest mention in the block and handed 4735 to
        the full population.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "The full run of 2026-10-22 held 4642 classes over 565,144 instructions, and the\n"
            "report of 2026-10-23 holds 4735 classes over 579,002 instructions.\n",
        )
        self.assertEqual(self.findings(), [])

    def test_a_number_before_the_population_that_names_it_is_not_a_finding(self) -> None:
        """The population named after the number does not claim it either.

        The sentence says the report of 2026-10-22 holds 4642 classes, and the
        NEXT sentence names the fast population. The old gate looked for the
        nearest mention and found the one after the number.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "The report of 2026-10-22 holds 4642 classes over 565,144 instructions. The fast\n"
            "population's report of 2026-10-23 holds 4735 classes over 579,002 instructions.\n",
        )
        self.assertEqual(self.findings(), [])

    def test_a_contradiction_is_attributed_to_neither_population(self) -> None:
        """A name and a day that disagree are not evidence for either.

        The clause names the fast population and states a day the record holds
        the full population's measurement of. The gate does not pick one.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "The fast population's report of 2026-10-22 holds 4700 classes over 579,002\n"
            "instructions.\n",
        )
        self.assertEqual(self.findings(), [])

    # --- the percentages and the day (defect class 2) ---------------------

    def test_a_published_percentage_that_differs_is_a_finding(self) -> None:
        """The percentages are compared too, in the shape that names the unit.

        This is the diff the gate was written for: the full population measures
        55.88 / 40.44 / 57.23, and two documents still said 55.89 / 40.45.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "A later full run (2026-10-22) measured **55.89 instruction / 40.45 branch /\n"
            "57.23 line**.\n",
        )
        findings = self.findings()
        self.assertTrue(findings, findings)
        self.assertIn('55.89', findings[0])
        self.assertIn('55.88', findings[0])
        self.assertIn('full.instructionCoverage', findings[0])

    def test_a_recorded_percentage_that_differs_is_a_finding(self) -> None:
        """A record that was corrupted is a finding, not a silent mismatch."""
        self.write(
            'config/docs/coverage-measurements.properties',
            RECORD.replace('full.instructionCoverage=55.88', 'full.instructionCoverage=99'),
        )
        findings = self.findings()
        self.assertTrue(findings, findings)
        self.assertIn('55.88', findings[0])
        self.assertIn('99', findings[0])

    def test_a_published_day_the_record_does_not_hold_is_a_finding(self) -> None:
        """A number published for another day is a claim about another measurement."""
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "The fast population's report of 2026-10-24 holds 4735 classes over 579,002\n"
            "instructions.\n",
        )
        findings = self.findings()
        self.assertTrue(findings, findings)
        self.assertIn('2026-10-24', findings[0])
        self.assertIn('2026-10-23', findings[0])

    def test_a_record_whose_day_is_not_a_day_is_a_finding(self) -> None:
        """A day the gate cannot read is a measurement it cannot check."""
        self.write(
            'config/docs/coverage-measurements.properties',
            RECORD.replace('fast.measuredAt=2026-10-23', 'fast.measuredAt=whenever'),
        )
        findings = self.findings()
        self.assertTrue(findings, findings)
        self.assertIn('fast.measuredAt', findings[0])

    def test_the_floors_are_not_measurements(self) -> None:
        """A floor is a policy number, and the gate does not read it as one.

        The bare shape "X / Y / Z" carries the floors, the measurements of
        other days and the current measurement at once, so the gate reads the
        percentages only in the shape that names the unit of every number.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "The fast set's floors are 27 / 23 / 31, and the full set's are 27 / 20 / 27.\n",
        )
        self.assertEqual(self.findings(), [])

    # --- the documents the gate reads (defect class 3) --------------------

    def test_a_gradle_script_publishes_the_measurements(self) -> None:
        """The Gradle scripts publish the numbers, and the gate reads them.

        The class count in the root build script's comment is a published
        measurement like any other; the old list of documents did not name it.
        """
        self.write(
            BUILD,
            "// The CLASS column is the one that says WHICH report this is: the\n"
            "// fast population's report held 4736 classes over 579,002 instructions\n"
            "// on 2026-10-23.\n",
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn(f'{BUILD}:', findings[0])
        self.assertIn('4736', findings[0])
        self.assertIn('4735', findings[0])

    def test_a_list_of_report_counters_in_a_gradle_script_is_not_a_measurement(self) -> None:
        """`reported 3 classes / 220 instructions / 0 covered` counts one report.

        The shape is a list of counters, and the same script publishes the
        report's own class count next to it.
        """
        self.write(
            BUILD,
            "// Per-project Kover instruments only that project's own classes, so\n"
            "// `:desktopApp:koverXmlReport` reported 3 classes / 220 instructions / 0 covered\n"
            "// while those tests were executing.\n",
        )
        self.assertEqual(self.findings(), [])
        self.assertEqual(self.warnings(), [])

    # --- the gaps that used to be silent (defect class 4) ------------------

    def test_a_number_with_no_population_is_a_warning(self) -> None:
        """A number nobody attributes is a warning, not a silent skip.

        The gate cannot check a number whose population it cannot read, and it
        says which number that is instead of dropping it.
        """
        self.write(
            PUBLISHER_FILE,
            "# Context\n\n"
            "A separate run held 4711 classes over 578,000 instructions.\n",
        )
        self.assertEqual(self.findings(), [])
        warnings = self.warnings()
        self.assertTrue(warnings, warnings)
        self.assertIn(f'{PUBLISHER_FILE}:', warnings[0])
        self.assertIn('4711', warnings[0])

    def test_a_missing_document_that_publishes_the_measurements_is_a_finding(self) -> None:
        """A check that switches itself off is a check nobody can trust."""
        (self.root / PUBLISHER_FILE).unlink()
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('missing', findings[0])

    # --- the record itself -------------------------------------------------

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

    def test_the_abbreviated_figure_of_the_defect_is_not_a_measurement(self) -> None:
        """The number the defect published is not a number the gate can read.

        `2.82M instructions` is a rounded figure, and the record holds exact
        numbers — so the gate does not read it. That limitation is named in the
        script's own docstring rather than left implicit.
        """
        self.write(
            PUBLISHER_FILE,
            "# Decision\n\n"
            'The number this decision published at first was "2.82M instructions" for\n'
            "both runs. It was the sum of every `INSTRUCTION` counter in the XML.\n",
        )
        self.assertEqual(self.findings(), [])

    # --- the gate's own contract -------------------------------------------

    def test_the_root_option_points_the_gate_at_another_tree(self) -> None:
        """The `--root` flag is how the unit tests aim the gate at a tree."""
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 0, result.output)
        self.assertIn('OK', result.output)

        self.write(PUBLISHER_FILE, PUBLISHER.replace('4735 classes', '4700 classes'))
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

        self.write(PUBLISHER_FILE, PUBLISHER.replace('4735 classes', '4700 classes'))
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


class GatePromiseTest(unittest.TestCase):
    """What the gate promises is checked against what it does."""

    def test_the_decision_promise_names_what_the_gate_compares(self) -> None:
        """The ADR promises the gate's reach — and the reach is what it says.

        The ADR used to claim that a number published in it and not in the
        record is a measurement nobody recorded "and the gate that compares
        the two says so", while the gate compared the counts and nothing else.
        """
        adr = ' '.join((REPO / ADR22).read_text(encoding='utf-8').split())
        self.assertIn(
            'the class count, the instruction total and the three percentages',
            adr,
            'the ADR has to name the measures the gate compares',
        )
        self.assertNotIn(
            'compares every number published here',
            adr,
            'the ADR must not promise more than the gate compares',
        )

    def test_no_document_calls_a_stale_measurement_today(self) -> None:
        """A number a document calls today's is the one the record holds today.

        The Gradle script said the 2026-10-15 figures are "almost exactly what
        the FAST population measures today (27.73 / 23.23 / 31.26)", while the
        record holds 28.78 / 23.92 / 32.06 for the fast population today.
        """
        recorded = {
            (28.78, 23.92, 32.06),  # the fast population, measured 2026-10-23
            (55.88, 40.44, 57.23),  # the full population, measured 2026-10-22
        }
        for relative in mod.PUBLISHERS:
            text = (REPO / relative).read_text(encoding='utf-8')
            for clause in _clauses(text):
                if 'today' not in clause:
                    continue
                # the triples the clause calls today's are the ones it states
                # after the word: a triple it states before it is another day's
                for triple in _triples(clause[clause.index('today'):]):
                    self.assertIn(
                        triple,
                        recorded,
                        f'{relative}: a document calls {triple} "today", and the record '
                        f'holds no such measurement today',
                    )

    def test_the_docstring_does_not_misdescribe_the_parser(self) -> None:
        """The docstring states the rule the parser applies — and it is its own.

        `java.util.Properties` starts a comment only at the beginning of a
        line, and the build does not read the record at all: the gate does.
        """
        doc = mod._properties.__doc__ or ''
        self.assertNotIn(
            'the same rule `java.util.Properties` applies',
            doc,
            'the docstring must not claim the record follows java.util.Properties',
        )
        self.assertNotIn(
            'the same rule the build reads',
            doc,
            'the docstring must not claim the build reads the record',
        )
        self.assertIn('the build does not', doc)


def _clauses(text: str) -> list[str]:
    """The clauses of a document: the pieces the gate reads one by one."""
    return [
        text[start:end]
        for start, end in mod._clauses(text)
        if text[start:end].strip()
    ]


def _triples(clause: str) -> list[tuple[float, float, float]]:
    """The bare percentage triples of a clause: "X / Y / Z"."""
    import re

    found = []
    for match in re.finditer(r'(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)', clause):
        found.append(tuple(float(match.group(index)) for index in (1, 2, 3)))
    return found


if __name__ == '__main__':
    unittest.main()
