#!/usr/bin/env python3
"""Tests for check-rule-intent.py.

This gate answers one question — does every rule producing findings in the
baseline appear in detekt.yml, so that somebody decided it applies — and it had
a line above that answer that could only ever print `0`.

`declared_and_reporting` compared the raw baseline rule name against
`declared_rules()`, which returns *normalised* names. Since no raw name is ever
its own normalised form except by accident, the count was structurally zero:
with 102 rules declared and 33 reporting, it printed "declared + reporting: 0",
which reads as a measurement and in fact said nothing. The verdict underneath
was computed correctly, so the gate returned 0 on a tree where the line above
it described a different repository than the one being checked.

That is the text-shape family again, one level down: not a check that cannot
fail, but a reported number that cannot vary. Both defects are pinned here —
the count must equal the rules that are actually declared, and the gate must
still fail on an undeclared rule afterwards.
"""

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "check-rule-intent.py"

_spec = importlib.util.spec_from_file_location("check_rule_intent", SCRIPT)
intent = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(intent)


class TestNormalisation(unittest.TestCase):
    """`_norm` is what makes the two spellings of a rule comparable."""

    def test_separators_and_case_are_insensitive(self):
        self.assertEqual(
            intent._norm("BackingPropertyNaming"),
            intent._norm("backing-property-naming"),
        )

    def test_a_ktlint_style_name_matches_its_config_key(self):
        # The case that motivated `_norm`: detekt reports the rule one way and
        # the config spells the key another, and comparing raw strings would
        # report the rule as undeclared. `_norm` is case- and separator-blind;
        # it does not expand an abbreviation, so the pair has to be a real one.
        self.assertEqual(
            intent._norm("BackingPropertyNaming"),
            intent._norm("backing-property-naming"),
        )

    def test_two_different_rules_do_not_collapse(self):
        self.assertNotEqual(intent._norm("MaxLineLength"), intent._norm("MaxLineCount"))


class TestDeclaredRules(unittest.TestCase):
    def test_ruleset_and_rule_level_keys_both_count(self):
        config = """
style:
  MagicNumber:
    active: false
  MaxLineLength:
    active: true
"""
        declared = intent.declared_rules(config)
        self.assertIn(intent._norm("MagicNumber"), declared)
        self.assertIn(intent._norm("MaxLineLength"), declared)

    def test_a_value_on_the_key_line_is_not_a_declaration(self):
        # Only `Name:` with nothing or a comment after it is a declaration; a
        # mapping value belongs to some other key.
        config = "style:\n  active: true\n"
        self.assertNotIn(intent._norm("style"), intent.declared_rules(config))


class TestBaselineRules(unittest.TestCase):
    def test_counts_findings_per_rule(self):
        baseline = (
            "<SmellBaseline>\n"
            "  <CurrentIssues>\n"
            "    <ID>MagicNumber:Foo.kt:12</ID>\n"
            "    <ID>MagicNumber:Bar.kt:7</ID>\n"
            "    <ID>MaxLineLength:Baz.kt:3</ID>\n"
            "  </CurrentIssues>\n"
            "</SmellBaseline>\n"
        )
        self.assertEqual(
            intent.baseline_rules(baseline),
            {"MagicNumber": 2, "MaxLineLength": 1},
        )


class TestDeclaredAndReportingIsNotStructurallyZero(unittest.TestCase):
    """The regression: this count used to be 0 for every possible input."""

    def _partition(self, config_text: str, baseline_text: str):
        """Call the script's own function. The first version of this test
        re-implemented the comparison inline, so it passed on the broken code:
        the copy in the test was correct and the copy in the script was not."""
        return intent.partition_reporting(config_text, baseline_text)

    def test_a_declared_reporting_rule_is_counted(self):
        config = "style:\n  MaxLineLength:\n    active: true\n"
        baseline = (
            "<SmellBaseline><CurrentIssues>"
            "<ID>MaxLineLength:Foo.kt:1</ID>"
            "</CurrentIssues></SmellBaseline>\n"
        )
        _, _, undeclared, both = self._partition(config, baseline)
        self.assertEqual(both, {"MaxLineLength": 1})
        self.assertEqual(undeclared, {})

    def test_the_two_sets_partition_the_reporting_rules(self):
        """`both + undeclared` must equal `reporting`, which is what the old
        line made impossible to check by eye."""
        config = "style:\n  MaxLineLength:\n    active: true\n"
        baseline = (
            "<SmellBaseline><CurrentIssues>"
            "<ID>MaxLineLength:Foo.kt:1</ID>"
            "<ID>UndeclaredRule:Bar.kt:2</ID>"
            "</CurrentIssues></SmellBaseline>\n"
        )
        _, reporting, undeclared, both = self._partition(config, baseline)
        self.assertEqual(set(both) | set(undeclared), set(reporting))
        self.assertEqual(set(both) & set(undeclared), set())

    def test_kebab_case_key_is_recognised_as_declared(self):
        # The spelling mismatch is the whole reason `_norm` exists; the count
        # must not silently miss it.
        config = "ktlint:\n  backing-property-naming:\n    active: true\n"
        baseline = (
            "<SmellBaseline><CurrentIssues>"
            "<ID>BackingPropertyNaming:Foo.kt:1</ID>"
            "</CurrentIssues></SmellBaseline>\n"
        )
        _, _, undeclared, both = self._partition(config, baseline)
        self.assertEqual(both, {"BackingPropertyNaming": 1})
        self.assertEqual(undeclared, {})

    def test_an_undeclared_rule_stays_undeclared(self):
        """The fix must not have widened `declared` until nothing is undeclared."""
        config = "style:\n  MaxLineLength:\n    active: true\n"
        baseline = (
            "<SmellBaseline><CurrentIssues>"
            "<ID>NeverHeardOfIt:Foo.kt:1</ID>"
            "</CurrentIssues></SmellBaseline>\n"
        )
        _, _, undeclared, both = self._partition(config, baseline)
        self.assertEqual(undeclared, {"NeverHeardOfIt": 1})
        self.assertEqual(both, {})


if __name__ == "__main__":
    unittest.main()
