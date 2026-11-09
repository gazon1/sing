"""Tests for the generator of the detekt rule inventory table.

`scripts/gen-detekt-rule-table.py` writes the rule table in
`.agents/skills/singularity-todo-detekt-rules-authoring/RULES.md` — one row per rule, with
the rule's first KDoc sentence as its description. The description lands in a Markdown
table cell, so it has to survive the trip into a cell and back.

It did not. The generator cut the first sentence at the first `. ` it found, and a KDoc
sentence often carries a period inside an inline code span — `combine(...) { ... }`,
`scope.launch { ... }`. The cut left the code span unterminated, and a table cell whose
backtick is never closed swallows the column separator that follows it: the row rendered
as five cells instead of six, and the markdown linter said so for four rules.

One property is held here: the first sentence of a line whose periods live inside inline
code spans is cut at a sentence boundary that is really one — the code spans come back
whole, and the result is one cell's worth of text.
"""

import importlib.util
import pathlib
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
GENERATOR = SCRIPTS / "gen-detekt-rule-table.py"


def load_generator():
    """Import the generator module by path — its name is not importable as-is."""
    spec = importlib.util.spec_from_file_location("gen_detekt_rule_table", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FirstSentenceTest(unittest.TestCase):
    """The sentence cut that feeds a table cell."""

    def setUp(self):
        self.generator = load_generator()

    def test_a_period_inside_a_code_span_does_not_end_the_sentence(self):
        line = "Bans side effects inside `combine(...) { ... }` transform lambdas."
        self.assertEqual(
            self.generator.split_first_sentence(line),
            line,
        )

    def test_the_code_span_comes_back_whole(self):
        line = "Bans `scope.launch { ... }` and `scope.async { ... }` inside `init { ... }` blocks."
        sentence = self.generator.split_first_sentence(line)
        self.assertIn("`scope.launch { ... }`", sentence)
        self.assertIn("`scope.async { ... }`", sentence)
        self.assertIn("`init { ... }`", sentence)

    def test_a_real_sentence_boundary_still_cuts(self):
        line = "Bans a thing. Explains why."
        self.assertEqual(self.generator.split_first_sentence(line), "Bans a thing.")

    def test_the_result_has_an_even_number_of_backticks(self):
        # An odd number means an unterminated code span, and an unterminated code span
        # is what swallows the table's column separator.
        line = "Bans `kotlinx.coroutines.runBlocking { ... }` in production code."
        sentence = self.generator.split_first_sentence(line)
        self.assertEqual(sentence.count("`") % 2, 0)

    def test_a_pipe_inside_a_code_span_stays_inside_the_cell(self):
        # The cell separator is the pipe; a pipe inside a code span is text, and the
        # renderer escapes the ones that are not.
        line = "Bans `a | b` in production code."
        sentence = self.generator.split_first_sentence(line)
        self.assertIn("`a | b`", sentence)


if __name__ == "__main__":
    unittest.main()
