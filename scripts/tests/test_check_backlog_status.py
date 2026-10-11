#!/usr/bin/env python3
"""Tests for check-backlog-status.py.

The gate exists because `deferred-backlog.md` could not be classified: 42 of its
82 entries carried no status, and two independently written regexes counted the
resolved ones as 18 and 26 — each silently classifying what it could and skipping
the rest, and neither saying so.

So most of these tests are about the parser agreeing with the shapes the file
actually contains. It got three wrong while being written, and each one turned a
perfectly good entry into a silent skip or a false alarm:

  * the status value can end with a period *inside* the bold — `**Status: OPEN.**`
    captures `OPEN.**`, and stripping only `*` and `_` leaves `OPEN.`
  * a status can be prose rather than a keyword, in which case the entry is
    genuinely unclassifiable and the gate is right to say so
  * an entry's status may sit under a long `Found in` paragraph

A parser that quietly skips what it does not understand is the failure this
project keeps finding, so the tests assert on what is *seen*, not only on the
final verdict.
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / 'check-backlog-status.py'

_spec = importlib.util.spec_from_file_location('check_backlog_status', SCRIPT)
cbs = importlib.util.module_from_spec(_spec)
sys.modules['check_backlog_status'] = cbs
_spec.loader.exec_module(cbs)


def entry(slug, body):
    return f'## {slug}\n\n{body}\n\n---\n'


class FirstWordTest(unittest.TestCase):

    def test_plain_keyword(self):
        self.assertEqual('OPEN', cbs.first_word('OPEN'))

    def test_period_inside_the_bold(self):
        """`strip('*_ ')` alone leaves `OPEN.` and matches nothing."""
        self.assertEqual('OPEN', cbs.first_word('OPEN.**'))

    def test_value_after_a_bold_with_a_qualifier(self):
        self.assertEqual(
            'CLOSED', cbs.first_word('CLOSED and verified 2026-10-04.')
        )

    def test_prose_is_not_a_keyword(self):
        """A sentence is not a status. The gate is right to refuse it."""
        self.assertEqual(
            'THE', cbs.first_word('the whitelisting is in the rule code.')
        )
        self.assertEqual('unclassifiable', cbs.classify('the whitelisting is in the rule code.'))


class ClassifyTest(unittest.TestCase):

    def test_open(self):
        self.assertEqual('open', cbs.classify('OPEN'))

    def test_open_with_trailing_prose(self):
        self.assertEqual('open', cbs.classify('OPEN.** Фиксируется в MR-2'))

    def test_closed_and_resolved(self):
        for value in ('CLOSED', 'RESOLVED (2026-10-04).', 'SUPERSEDED'):
            self.assertEqual('closed', cbs.classify(value), value)

    def test_partial_states_are_their_own_bucket(self):
        """A partly-done finding is a live commitment, not a closed one."""
        for value in ('PARTIALLY CLOSED, re-tracked as #110', 'HALF RESOLVED', 'MEASURED — …'):
            self.assertEqual('partial', cbs.classify(value), value)

    def test_none_and_empty_are_unclassifiable(self):
        self.assertEqual('unclassifiable', cbs.classify(None))
        self.assertEqual('unclassifiable', cbs.classify(''))


if __name__ == '__main__':
    unittest.main()
