"""Unit tests for `scanners.source_text` — the shared Kotlin source reader.

Run with: python3 -m unittest discover -s scripts/tests

The module under test is the answer to a measured defect, not a convenience.
`scripts/src/scanners/test_classes.py` counted braces to find the end of a class
body, and its stripper understood a plain double-quoted string but not a raw
string and not a block comment. Five real test classes were invisible to the
test-class inventory because of it, and the tripwire that was supposed to catch
exactly that (`ClassBodyScannerAgreementTest`) shared the implementation's
blind spot, so it could not fire.

These tests pin the four things the walk has to get right, and the two it has
to get *fast*: an unbalanced quote must cost a wrong answer, never a wedged
gate.
"""

import pathlib
import sys
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(_ROOT / "scripts" / "src"))

from scanners.source_text import (  # noqa: E402
    block_body,
    line_depths,
    matching_brace,
    strip_code,
    strip_comments,
)


class TheStripperIsLengthPreserving(unittest.TestCase):
    """Every removed character becomes a space, so offsets stay meaningful."""

    def test_length_is_unchanged(self):
        for source in [
            'val a = "x{ }y"',
            'val b = """{"k":1}"""',
            "val c = '}'",
            "/* { */ val d = 1",
            'val e = "unterminated',
            'val f = "a\\"b{",',
            "// a comment {",
        ]:
            with self.subTest(source=source):
                self.assertEqual(len(source), len(strip_code(source)))
                self.assertEqual(len(source), len(strip_comments(source)))

    def test_newlines_are_preserved(self):
        source = "a\nb /* x\ny */\nc\n"
        self.assertEqual(source.count("\n"), strip_code(source).count("\n"))


class TheStripperKnowsWhereCodeEnds(unittest.TestCase):
    """Only real code may contribute a brace."""

    def test_a_brace_inside_a_string_is_not_a_brace(self):
        self.assertNotIn("{", strip_code('val a = "{"'))

    def test_a_brace_inside_a_raw_string_is_not_a_brace(self):
        self.assertNotIn("{", strip_code('val a = """{"k": 1}"""'))

    def test_a_brace_inside_a_block_comment_is_not_a_brace(self):
        self.assertNotIn("{", strip_code("/* { */ val a = 1"))

    def test_a_brace_inside_a_kdoc_is_not_a_brace(self):
        source = "/**\n * a `{` in prose\n */\nval a = 1"
        self.assertNotIn("{", strip_code(source))

    def test_a_brace_inside_a_char_literal_is_not_a_brace(self):
        self.assertNotIn("{", strip_code("val a = '{'"))

    def test_real_braces_survive(self):
        self.assertIn("{", strip_code("fun a() { }"))
        self.assertIn("}", strip_code("fun a() { }"))


class TheStripperHandlesTheAwkwardForms(unittest.TestCase):
    """The forms that actually occur in this repository."""

    def test_adjacent_raw_strings_are_two_literals_not_one(self):
        # `"""AAA""" + """BBB"""` — the form tree-sitter mis-lexes.
        source = 'val g = """AAA""" + """BBB"""'
        out = strip_code(source)
        self.assertNotIn("AAA", out)
        self.assertNotIn("BBB", out)

    def test_a_nested_block_comment_is_one_comment(self):
        # Kotlin block comments nest. A KDoc in this repository quotes a code
        # sample that contains a comment of its own, and a scanner that ends
        # the comment at the first `*/` then reads the rest of the KDoc as
        # code — meets a quote inside it, and swallows the file.
        source = "/**\n * `onFailure { /* inner */ }` — one wrong sentence\n */\nval a = 1"
        out = strip_code(source)
        self.assertIn("val a = 1", out)

    def test_a_run_of_four_quotes_closes_on_the_last_three(self):
        # A plain string literal written as a raw string whose content starts
        # and ends with a quote: the content is a quote, then the text, then a
        # quote, then the closing three.
        source = 'val literal = Regex(' + ('"' * 4) + 'text' + ('"' * 4) + ')'
        out = strip_code(source)
        self.assertIn("val literal", out)
        # The trailing code after the literal is still code, not swallowed.
        self.assertIn(")", out)

    def test_an_escaped_quote_does_not_end_the_literal(self):
        # `"a\"b{",` — the literal ends at the second quote, so the brace
        # inside it is still inside it.
        out = strip_code('val f = "a\\"b{",')
        self.assertNotIn("{", out)

    def test_a_raw_string_keeps_its_own_quotes(self):
        out = strip_code('val b = """x"""')
        self.assertIn('"""', out)


class TheStripperDoesNotHang(unittest.TestCase):
    """A gate that wedges on one unbalanced quote is a gate nobody runs."""

    def test_an_unterminated_string_costs_an_answer_not_a_wedge(self):
        out = strip_code('val e = "unterminated')
        self.assertEqual(len('val e = "unterminated'), len(out))

    def test_an_unterminated_block_comment_costs_an_answer_not_a_wedge(self):
        out = strip_code("/* never closed {")
        self.assertEqual(len("/* never closed {"), len(out))


class StripCommentsKeepsLiteralContent(unittest.TestCase):
    """The log gate reads the message text, so its variant keeps the content."""

    def test_the_message_text_survives(self):
        out = strip_comments('log.i { "a $b" }')
        self.assertIn("a $b", out)

    def test_the_comment_does_not(self):
        out = strip_comments('log.i { "a $b" } // note {')
        self.assertNotIn("note", out)

    def test_a_url_in_a_string_is_not_a_comment(self):
        # `substringBefore("//")` — the trick the tag catalogue used — cuts the
        # line at the URL and takes the declaration with it.
        out = strip_comments('val url = "https://example.com"')
        self.assertIn("https://example.com", out)


class TemplatesAreCode(unittest.TestCase):
    """A template's body is code; its delimiters are text."""

    def test_a_template_body_keeps_its_braces(self):
        out = strip_code('val s = "${run { 1 }}"')
        self.assertIn("{ 1 }", out)

    def test_the_template_delimiters_are_not_braces(self):
        # The `${` and the `}` that ends the template are text, so the depth
        # they contribute is zero — a scanner that counted them would end the
        # enclosing block early.
        out = strip_code('val s = "${run { 1 }}"')
        self.assertEqual(out.count("{"), out.count("}"))

    def test_a_brace_in_the_text_of_a_template_is_not_a_brace(self):
        out = strip_code('val s = "${f("}")}"')
        self.assertEqual(out.count("{"), out.count("}"))


class TheBlockEndIsFound(unittest.TestCase):
    """`matching_brace` and `block_body`: where does a block end?"""

    def test_a_plain_block_ends_at_its_closing_brace(self):
        self.assertEqual("a", block_body("{a}"))

    def test_a_brace_in_a_string_does_not_end_the_block(self):
        self.assertEqual('\n    val s = "}"\n', block_body('{\n    val s = "}"\n}'))

    def test_a_brace_in_a_kdoc_does_not_end_the_block(self):
        self.assertEqual("\n    /* { */\n", block_body("{\n    /* { */\n}"))

    def test_an_unclosed_block_has_no_body(self):
        self.assertIsNone(block_body("{ unclosed"))

    def test_a_file_without_a_block_has_no_body(self):
        self.assertIsNone(block_body("no braces here"))

    def test_matching_brace_reports_the_index(self):
        source = "{\n    val s = \"}\"\n}"
        self.assertEqual(source.rindex("}"), matching_brace(source, 0))


class TheDepthIsCountedPerLine(unittest.TestCase):
    """`line_depths`: the depth at the end of each line."""

    def test_a_string_brace_does_not_change_the_depth(self):
        depths = line_depths('val s = "}"\nval t = 1\n')
        self.assertEqual([0, 0], depths)

    def test_a_real_brace_changes_the_depth(self):
        depths = line_depths("class A {\n    val a = 1\n}\n")
        self.assertEqual([1, 1, 0], depths)

    def test_a_kdoc_brace_does_not_change_the_depth(self):
        depths = line_depths("class A {\n    /** { */\n    val a = 1\n}\n")
        self.assertEqual([1, 1, 1, 0], depths)


if __name__ == "__main__":
    unittest.main()
