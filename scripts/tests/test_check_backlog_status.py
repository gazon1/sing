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


class ParseEntriesTest(unittest.TestCase):

    FILE = (
        'preamble\n\n'
        + entry('a-annotated', '**Status: OPEN**\n\n**Tracked as:** #1')
        + entry('b-annotated-late', '**Found in:**\n\n' + 'x\n' * 12 + '\n**Status: CLOSED**\n\n**Tracked as:** #2')
        + entry('c-unannotated', '**Found in:** something')
        + entry('d-open-untracked', '**Status: OPEN**')
    )

    def setUp(self):
        self.entries = cbs.parse_entries(self.FILE)

    def test_finds_every_entry(self):
        self.assertEqual(4, len(self.entries))

    def test_status_under_a_long_found_in_is_still_read(self):
        """A status under 12 lines of preamble is a status, not prose."""
        by_slug = {e['slug']: e for e in self.entries}
        self.assertEqual('CLOSED', by_slug['b-annotated-late']['status'])

    def test_a_missing_status_reads_as_none_not_as_empty_string(self):
        by_slug = {e['slug']: e for e in self.entries}
        self.assertIsNone(by_slug['c-unannotated']['status'])
        self.assertEqual('unclassifiable', cbs.classify(by_slug['c-unannotated']['status']))

    def test_tracked_flag_distinguishes_where_work_lives(self):
        by_slug = {e['slug']: e for e in self.entries}
        self.assertTrue(by_slug['a-annotated']['tracked'])
        self.assertFalse(by_slug['d-open-untracked']['tracked'])

    def test_line_numbers_point_at_the_heading(self):
        by_slug = {e['slug']: e for e in self.entries}
        first = self.FILE.index('## a-annotated')
        self.assertEqual(self.FILE[:first].count('\n') + 1, by_slug['a-annotated']['line'])


class RepositoryTest(unittest.TestCase):
    """The live file must stay in a state the gate accepts."""

    BACKLOG = pathlib.Path(__file__).resolve().parent.parent.parent / 'docs' / 'decisions' / 'deferred-backlog.md'

    @unittest.skipUnless(BACKLOG.is_file(), 'backlog not present in this tree')
    def test_every_live_entry_is_classifiable(self):
        text = self.BACKLOG.read_text(encoding='utf-8')
        bad = [
            e
            for e in cbs.parse_entries(text)
            if cbs.classify(e['status']) == 'unclassifiable'
        ]
        self.assertEqual(
            [],
            [f"{e['slug']} (status reads {e['status']!r})" for e in bad],
        )

    @unittest.skipUnless(BACKLOG.is_file(), 'backlog not present in this tree')
    def test_every_open_live_entry_is_tracked(self):
        text = self.BACKLOG.read_text(encoding='utf-8')
        untracked = [
            e
            for e in cbs.parse_entries(text)
            if cbs.classify(e['status']) in ('open', 'partial') and not e['tracked']
        ]
        self.assertEqual([], [e['slug'] for e in untracked])


if __name__ == '__main__':
    unittest.main()
