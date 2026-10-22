"""Tests for check-text-field-transformations-table.py.

The gate exists because the skill's table of text-field transformations is a
promise: "this is what the code does, and this is the rule it applies". A
promise nothing checks is a promise nobody can rely on — a transformation can
be added, renamed, or have its rule changed, and the table keeps claiming the
old thing.

So the properties that matter are the ways the table can lie:

  - a transformation the code declares and the table does not name (the code
    moved, the table did not);
  - a row that names a transformation the code does not declare (the table
    moved, the code did not);
  - a row whose facts are not the facts the code enforces (the rule changed
    and the table kept the old rule);
  - a row whose rule sentence no longer mentions what the code does (the
    sentence and the code drifted apart in the same edit);
  - a row whose parameter list is not the function's parameter list.

Each of them has a test here that mutates an input in the way the rule hunts
for and asserts that the gate reports it. A rule nobody has seen fail can be
broken rather than clean.
"""

import importlib.util
import pathlib
import sys
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent
    / 'check-text-field-transformations-table.py'
)
spec = importlib.util.spec_from_file_location(
    'check_text_field_transformations_table', MODULE_PATH
)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_text_field_transformations_table'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


#: The Kotlin the gate reads, written out in full: the four transformations,
#: their rules, and the helpers the rules are made of.
SOURCE = '''
package com.singularity.todo.core.ui.textfields

import androidx.compose.ui.text.input.InputTransformation

private const val TWO_DIGITS = 2

internal fun filterDigits(text: String, maxDigits: Int = Int.MAX_VALUE): String =
    text.filter { it.isDigit() }.take(maxDigits)

internal fun isWithinLength(text: String, maxLength: Int): Boolean =
    text.length <= maxLength

internal fun isValidRecurrenceInterval(text: String): Boolean =
    (text.toIntOrNull() ?: 0) > 0

internal fun isValidDayOfMonth(text: String): Boolean =
    (text.toIntOrNull() ?: 0) in 1..31

internal fun filterRecurrenceInterval(text: String): String {
    val filtered = filterDigits(text, maxDigits = TWO_DIGITS)
    return filtered.takeIf { isValidRecurrenceInterval(it) } ?: text
}

internal fun filterDayOfMonth(text: String): String {
    val filtered = filterDigits(text, maxDigits = TWO_DIGITS)
    return filtered.takeIf { isValidDayOfMonth(it) } ?: text
}

internal fun digitsOnlyTransformation(maxDigits: Int = Int.MAX_VALUE): InputTransformation = {
    val current = it.text
    val filtered = filterDigits(current, maxDigits)
    if (filtered != current) {
        it.replace(0, current.length, filtered)
    }
    it
}

internal fun maxLengthTransformation(maxLength: Int): InputTransformation = { text ->
    if (!isWithinLength(asCharSequence().toString(), maxLength)) {
        it.revertAllChanges()
    }
    it
}

internal fun recurrenceIntervalTransformation(): InputTransformation = { text ->
    val current = it.text
    val kept = filterRecurrenceInterval(current)
    if (kept != current) {
        it.replace(0, current.length, kept)
    } else {
        it.revertAllChanges()
    }
    it
}

internal fun dayOfMonthTransformation(): InputTransformation = { text ->
    val current = it.text
    val kept = filterDayOfMonth(current)
    if (kept != current) {
        it.replace(0, current.length, kept)
    } else {
        it.revertAllChanges()
    }
    it
}
'''


def document(rows):
    """A skill document whose table has the given rows."""
    body = '\n'.join(rows)
    return (
        '# Text-field states\n\n'
        '## Transformations\n\n'
        '| Transformation | Rule | Checked |\n'
        '|---|---|---|\n'
        f'{body}\n'
    )


THE_ROWS = [
    '| `digitsOnlyTransformation(maxDigits)` | drops non-digits, keeps at most '
    '`maxDigits` digits, rewrites the field with them | `drops=non-digit; '
    'limit=maxDigits; rewrite` |',
    '| `maxLengthTransformation(maxLength)` | rejects the whole edit when the text '
    'grows past `maxLength` | `rejects=edit; limit=maxLength` |',
    '| `recurrenceIntervalTransformation()` | drops non-digits, keeps at most two '
    'digits, rewrites the field with them, and rejects the edit unless the result '
    'is a whole number of at least one | `drops=non-digit; limit=2; rewrite; '
    'rejects=edit; min=1` |',
    '| `dayOfMonthTransformation()` | drops non-digits, keeps at most two digits, '
    'rewrites the field with them, and rejects the edit unless the result is '
    'between 1 and 31 | `drops=non-digit; limit=2; rewrite; rejects=edit; '
    'range=1..31` |',
]


def findings_for(rows, source=SOURCE):
    """The findings the gate reports for a table and a source."""
    return mod.check(mod.parse_table(document(rows)), mod.parse_functions(source),
                      mod.parse_constants(source))


def kinds(rows, source=SOURCE):
    return [finding.kind for finding in findings_for(rows, source)]


class TableTest(unittest.TestCase):
    """The table as it stands: it agrees with the code."""

    def test_the_table_that_matches_the_code_has_no_findings(self):
        self.assertEqual(findings_for(THE_ROWS), [])

    def test_every_transformation_the_code_declares_is_a_row(self):
        self.assertEqual(
            mod.transformations(mod.parse_functions(SOURCE)),
            [
                'dayOfMonthTransformation',
                'digitsOnlyTransformation',
                'maxLengthTransformation',
                'recurrenceIntervalTransformation',
            ],
        )

    def test_the_facts_are_derived_from_the_code(self):
        functions = mod.parse_functions(SOURCE)
        constants = mod.parse_constants(SOURCE)
        self.assertEqual(
            mod.derive_facts(functions, constants, 'recurrenceIntervalTransformation'),
            {'drops=non-digit', 'limit=2', 'rewrite', 'rejects=edit', 'min=1'},
        )


class DriftTest(unittest.TestCase):
    """Every way the table can lie, and the finding each one earns."""

    def test_a_transformation_the_table_does_not_name_is_a_finding(self):
        source = SOURCE + (
            '\ninternal fun probeTransformation(): InputTransformation = { it }\n'
        )
        self.assertIn('not-in-the-table', kinds(THE_ROWS, source))

    def test_a_row_naming_a_transformation_the_code_lacks_is_a_finding(self):
        rows = THE_ROWS + [
            '| `probeTransformation()` | drops non-digits | `drops=non-digit` |',
        ]
        self.assertIn('not-in-the-code', kinds(rows))

    def test_a_row_whose_facts_are_not_the_code_s_facts_is_a_finding(self):
        rows = [
            row.replace('limit=2', 'limit=3') if 'limit=2' in row else row
            for row in THE_ROWS
        ]
        self.assertIn('wrong-facts', kinds(rows))

    def test_a_rule_that_stops_mentioning_the_fact_is_a_finding(self):
        rows = [
            row.replace('drops non-digits, ', '') if 'digitsOnly' in row else row
            for row in THE_ROWS
        ]
        self.assertIn('rule-does-not-mention-it', kinds(rows))

    def test_a_row_with_the_wrong_parameters_is_a_finding(self):
        rows = [
            row.replace('digitsOnlyTransformation(maxDigits)',
                        'digitsOnlyTransformation(maxLength)')
            if 'digitsOnly' in row else row
            for row in THE_ROWS
        ]
        self.assertIn('wrong-parameters', kinds(rows))

    def test_a_document_without_the_table_is_not_vacuous(self):
        findings = mod._not_vacuous([], mod.parse_functions(SOURCE))
        self.assertIn('scan-found-nothing', [finding.kind for finding in findings])


class RepositoryTest(unittest.TestCase):
    """The table and the code the repository actually has."""

    ROOT = pathlib.Path(__file__).resolve().parents[2]

    def test_the_repository_table_agrees_with_the_repository_code(self):
        document_text = (self.ROOT / mod.SKILL).read_text(encoding='utf-8')
        source_text = (self.ROOT / mod.CODE).read_text(encoding='utf-8')
        findings = mod.check(
            mod.parse_table(document_text),
            mod.parse_functions(source_text),
            mod.parse_constants(source_text),
        )
        self.assertEqual([finding.render() for finding in findings], [])


if __name__ == '__main__':
    unittest.main()
