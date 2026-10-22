"""Tests for check-review-verdicts.py.

A review that leaves no trace is a promise with nothing behind it, so the gate
has to be right in the cases a clean tree never shows:

  - a change with no verdict is a finding — the gap the gate was written for;
  - a change in the baseline is accepted — the changes that predate the rule
    are history, not a verdict to be invented;
  - a verdict that names another change is a finding — it is a verdict about
    something else;
  - a verdict with no verdict word, no date, or no pull request is a finding —
    a verdict has to say what it is;
  - a verdict that says nothing but headings is a finding — the review has to
    have reviewed something;
  - a verdict for a change that is not open is a finding — it is a verdict
    about nothing;
  - the baseline may only shrink: `--update-baseline` prunes and never adds.

The tests write their own tree: a gate exercised only against one repository
is a gate whose behaviour nobody knows when the tree changes shape.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-review-verdicts.py'
)
spec = importlib.util.spec_from_file_location('check_review_verdicts', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_review_verdicts'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


VERDICT = """---
change: {change}
verdict: {verdict}
date: {date}
pr: {pr}
---

## What was reviewed

The diff of the pull request, with no session history.

## Findings

- one thing, and what was done about it
"""


class ReviewVerdictsTest(unittest.TestCase):
    """Every open change carries the verdict of its review."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.changes = self.root / 'openspec' / 'changes'
        self.reviews = self.root / '.specify' / 'reviews'
        self.changes.mkdir(parents=True)
        self.reviews.mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _change(self, name: str) -> None:
        (self.changes / name).mkdir()

    def _verdict(self, slug: str, **overrides: str) -> None:
        fields = {
            'change': slug,
            'verdict': 'pass',
            'date': '2026-10-22',
            'pr': 'https://github.com/gazon1/sing/pull/970',
        }
        fields.update(overrides)
        (self.reviews / f'{slug}.md').write_text(VERDICT.format(**fields), encoding='utf-8')

    # ── the gap the gate was written for ───────────────────────────────────────

    def test_a_change_without_a_verdict_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')

        problems, stale, scaffolds, counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems), 'a change with no review verdict is the gap')
        self.assertIn('the-backup-keeps-the-verb', problems[0])
        self.assertEqual([], stale)

    def test_a_change_with_a_verdict_is_clean(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb')

        problems, stale, scaffolds, counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems)
        self.assertEqual([], stale)

    def test_a_change_in_the_baseline_is_accepted(self) -> None:
        self._change('the-backup-keeps-the-verb')

        problems, _, scaffolds, _counted = mod.find_problems(
            self.changes, self.reviews, {'the-backup-keeps-the-verb'}
        )

        self.assertEqual([], problems, 'the changes that predate the rule are history')

    def test_a_baseline_entry_for_a_change_that_is_gone_is_stale(self) -> None:
        problems, stale, scaffolds, counted = mod.find_problems(
            self.changes, self.reviews, {'a-change-that-was-archived'}
        )

        self.assertEqual([], problems)
        self.assertEqual(['a-change-that-was-archived'], stale)

    # ── the shape of a verdict ─────────────────────────────────────────────────

    def test_a_verdict_that_names_another_change_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', change='some-other-change')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('reviews', problems[0])

    def test_a_verdict_without_a_verdict_word_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', verdict='maybe')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('verdict', problems[0])

    def test_a_verdict_without_a_date_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', date='last Tuesday')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('date', problems[0])

    def test_a_verdict_without_a_pull_request_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', pr='')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('pull request', problems[0])

    def test_a_verdict_without_frontmatter_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        (self.reviews / 'the-backup-keeps-the-verb.md').write_text(
            '# A review\n\nIt looked fine.\n', encoding='utf-8'
        )

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('frontmatter', problems[0])

    def test_a_verdict_that_says_nothing_but_headings_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        (self.reviews / 'the-backup-keeps-the-verb.md').write_text(
            '---\nchange: the-backup-keeps-the-verb\nverdict: pass\n'
            'date: 2026-10-22\npr: 970\n---\n\n## What was reviewed\n\n## Findings\n',
            encoding='utf-8',
        )

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('headings', problems[0])

    def test_a_verdict_for_a_change_that_is_not_open_is_a_finding(self) -> None:
        self._verdict('a-change-that-was-archived')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('neither an open change nor an archived one', problems[0])

    def test_a_failed_review_is_a_clean_state(self) -> None:
        # A `fail` is allowed, and is the interesting case: it says the review
        # found something. What is not allowed is a verdict nobody wrote.
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', verdict='fail')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems)

    def test_an_archived_change_needs_no_verdict(self) -> None:
        archive = self.changes / 'archive'
        archive.mkdir()
        (archive / '2026-10-22-the-backup-keeps-the-verb').mkdir()

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems, 'an archived change is a decision that was made')

    def test_a_date_prefix_is_dropped_from_the_slug(self) -> None:
        self._change('2026-10-22-the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb')

        problems, _, scaffolds, _counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems)

    def test_a_verdict_for_an_archived_change_is_not_a_finding(self) -> None:
        # Archiving is a routine operation: the change is filed away, and the
        # verdict that recorded its review stays — a verdict for a change that
        # is not open any more is a record of a review that happened.
        archive = self.changes / 'archive'
        archive.mkdir()
        (archive / '2026-10-22-the-backup-keeps-the-verb').mkdir()
        self._verdict('the-backup-keeps-the-verb')

        problems, _, _, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems)

    def test_a_scaffold_change_is_exempt_until_it_is_written_up(self) -> None:
        # The spec-create workflow writes the change and leaves the template in
        # place. Nobody has written it up, so nobody has reviewed it, and there
        # is nothing to have reviewed yet.
        change = self.changes / '2026-10-22-a-new-idea'
        change.mkdir()
        (change / '.openspec.yaml').write_text(
            'schema: adr-workflow\nslug: a-new-idea\n', encoding='utf-8'
        )
        (change / 'proposal.md').write_text(
            '# A new idea\n\n_Describe the problem or opportunity here._\n', encoding='utf-8'
        )

        problems, _, scaffolds, counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems, 'a scaffold is not a decision yet')
        self.assertEqual(['a-new-idea'], scaffolds)
        self.assertEqual(1, counted['scaffolds'])

    def test_a_written_up_scaffold_needs_a_verdict(self) -> None:
        change = self.changes / '2026-10-22-a-new-idea'
        change.mkdir()
        (change / '.openspec.yaml').write_text(
            'schema: adr-workflow\nslug: a-new-idea\n', encoding='utf-8'
        )
        (change / 'proposal.md').write_text(
            '# A new idea\n\nThe problem, written up.\n', encoding='utf-8'
        )

        problems, _, scaffolds, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems), 'once it is written up, it needs a review')
        self.assertEqual([], scaffolds)


if __name__ == '__main__':
    unittest.main()
