#!/usr/bin/env python3
"""Tests for check-baseline-ratchet.py's phantom-entry invariant.

The gate's own failure mode is the one it exists to prevent. A parser that
stops matching returns an empty finding list, and an empty finding list reads
as a pass — so the tests here are mostly about proving the check still *sees* a
planted violation. Each fixture is a small detekt config plus a small baseline,
and the assertions are on what the check finds, not on an exit code alone.

Two of these tests exist because this check got both things wrong on the first
attempt, in ways that would have shipped a gate that could not fail:

  * the `active: false` parser keyed on the nearest preceding header regardless
    of indent, and so read the *ruleset*-level `active:` lines as rule flags. It
    reported 11 "rules" for a config with 5, and would have accepted a baseline
    full of phantoms.
  * `rule_of_entry` sliced a tagged `<ID>…</ID>` string at its first colon and
    produced `<ID>BackingPropertyNaming`, which matches no rule. On a baseline
    with a planted entry, the invariant reported zero phantoms.
"""

import importlib.util
import pathlib
import unittest

SCRIPT = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-baseline-ratchet.py'
)

_spec = importlib.util.spec_from_file_location('check_baseline_ratchet', SCRIPT)
ratchet = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ratchet)


# A config with one disabled rule, one enabled rule, and a ruleset-level
# `active:` — the three shapes the parser has to tell apart.
CONFIG = '''
comments:
  active: true
  UndocumentedPublicClass:
    active: true
naming:
  active: true
  BackingPropertyNaming:
    # 53 findings, all AGENTS.md's canonical VM pattern.
    active: false
  FunctionNaming:
    active: true
ktlint:
  active: true
  Filename:
    active: false
'''


class DisabledRulesTest(unittest.TestCase):

    def test_finds_a_rule_marked_inactive(self):
        found = ratchet.disabled_rules(CONFIG)
        self.assertIn('BackingPropertyNaming', found)

    def test_does_not_treat_ruleset_level_active_as_a_rule_flag(self):
        """`naming:` and `ktlint:` carry `active: true` at indent 2.

        A parser that confuses a ruleset's flag with its rules' flags is the
        failure that shipped here once: it produced 11 disabled rules for a
        config with 5, and the names it invented were ruleset names.
        """
        found = ratchet.disabled_rules(CONFIG)
        self.assertNotIn('naming', found)
        self.assertNotIn('ktlint', found)
        self.assertNotIn('comments', found)
        # Only the two rules that actually say so.
        self.assertEqual({'BackingPropertyNaming', 'Filename'}, set(found))

    def test_enabled_rule_is_not_reported(self):
        self.assertNotIn('FunctionNaming', ratchet.disabled_rules(CONFIG))

    def test_a_rule_closed_by_the_next_rule_does_not_inherit_the_previous_flag(self):
        cfg = '''
style:
  Aaa:
    active: false
  Bbb:
    active: true
'''
        found = ratchet.disabled_rules(cfg)
        self.assertIn('Aaa', found)
        self.assertNotIn('Bbb', found)

    def test_comments_between_header_and_flag_do_not_break_the_rule(self):
        cfg = '''
style:
  Ccc:
    # the reason is the valuable part
    active: false
'''
        self.assertIn('Ccc', ratchet.disabled_rules(cfg))


class RuleOfEntryTest(unittest.TestCase):

    def test_strips_the_id_wrapper(self):
        """The tag is what this got wrong first.

        Slicing `<ID>BackingPropertyNaming:…` at its first colon yields
        `<ID>BackingPropertyNaming`, which matches no rule name — so the
        invariant reported zero phantoms on a baseline that had one planted.
        """
        got = ratchet.rule_of_entry(
            '<ID>BackingPropertyNaming:Probe.kt:Probe$private val _x = 1</ID>'
        )
        self.assertEqual('BackingPropertyNaming', got)

    def test_handles_an_untagged_entry(self):
        self.assertEqual(
            'Filename', ratchet.rule_of_entry('Filename:Box.kt:Box')
        )

    def test_a_signature_containing_colons_does_not_shift_the_rule(self):
        got = ratchet.rule_of_entry('<ID>MaximumLineLength:A.kt:A$fun x(): Unit = f(1, 2)</ID>')
        self.assertEqual('MaximumLineLength', got)


class SuppressedForDisabledTest(unittest.TestCase):

    BASELINE = '''<?xml version="1.0" ?>
<SmellBaseline>
  <ManuallySuppressedIssues/>
  <CurrentIssues>
    <ID>BackingPropertyNaming:A.kt:A$private val _x = 1</ID>
    <ID>Filename:Box.kt:Box</ID>
    <ID>FunctionNaming:B.kt:B</ID>
  </CurrentIssues>
</SmellBaseline>
'''

    def test_reports_entries_for_disabled_rules_only(self):
        """The returned items keep their `<ID>` wrapper.

        That is `entries()`'s contract — the growth report prints elements
        verbatim — and `rule_of_entry` is what strips the tags. Asserting the
        untagged form here would have pinned a second, different contract onto
        the same function and hidden the one that actually broke.
        """
        disabled = ratchet.disabled_rules(CONFIG)
        found = ratchet.suppressed_for_disabled(self.BASELINE, disabled)
        self.assertEqual(
            [
                '<ID>BackingPropertyNaming:A.kt:A$private val _x = 1</ID>',
                '<ID>Filename:Box.kt:Box</ID>',
            ],
            found,
        )

    def test_a_clean_baseline_yields_nothing(self):
        disabled = ratchet.disabled_rules(CONFIG)
        clean = self.BASELINE.replace(
            '    <ID>BackingPropertyNaming:A.kt:A$private val _x = 1</ID>\n', ''
        ).replace('    <ID>Filename:Box.kt:Box</ID>\n', '')
        self.assertEqual([], ratchet.suppressed_for_disabled(clean, disabled))

    def test_count_agrees_with_planted_entries(self):
        """Guards the premise the other tests rest on: the entries are seen."""
        disabled = ratchet.disabled_rules(CONFIG)
        self.assertEqual(3, ratchet.count_entries(self.BASELINE))
        self.assertEqual(2, len(ratchet.suppressed_for_disabled(self.BASELINE, disabled)))


if __name__ == '__main__':
    unittest.main()
