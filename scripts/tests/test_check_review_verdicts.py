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
reviewed: {reviewed}
documents: {documents}
---

## What was reviewed

The diff of the pull request, with no session history.

## Findings

- one thing, and what was done about it
"""

#: A commit sha — the field names the commit a review was made against.
COMMIT = '1cb18e3935622927120e8ac9b6f8ae9a50107a74'


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

    def _change(self, name: str) -> pathlib.Path:
        directory = self.changes / name
        directory.mkdir()
        return directory

    def _verdict(self, slug: str, **overrides: str) -> None:
        fields = {
            'change': slug,
            'verdict': 'pass',
            'date': '2026-10-22',
            'pr': 'https://github.com/gazon1/sing/pull/970',
            'reviewed': COMMIT,
            # The digest of the change as it is now — a verdict that says it
            # reviewed these documents, and the gate recomputes it.
            'documents': mod.documents_digest(self.changes / slug),
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
        digest = mod.documents_digest(self.changes / 'the-backup-keeps-the-verb')
        (self.reviews / 'the-backup-keeps-the-verb.md').write_text(
            '---\nchange: the-backup-keeps-the-verb\nverdict: pass\n'
            f'date: 2026-10-22\npr: 970\nreviewed: {COMMIT}\ndocuments: {digest}\n---\n\n'
            '## What was reviewed\n\n## Findings\n',
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

    def _scaffold_template(self) -> list[str]:
        """Copy the spec-create workflow into the tree and return its template.

        The gate compares a proposal with the template the workflow itself
        writes, so a test that builds a scaffold without the workflow is
        testing a tree that cannot exist. The workflow is copied from the
        repository rather than written out here, so the template the test
        asserts against is the one the workflow really writes.
        """
        source = pathlib.Path(__file__).resolve().parent.parent.parent / mod.SCAFFOLD_WORKFLOW
        target = self.root / mod.SCAFFOLD_WORKFLOW
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding='utf-8'), encoding='utf-8')
        template = mod.scaffold_template(self.root)
        self.assertIsNotNone(template, 'the workflow the gate compares against must be there')
        return template or []

    def _proposal(self, change: pathlib.Path, template: list[str], title: str) -> None:
        """Write the proposal the workflow writes, with the title filled in."""
        lines = list(template)
        for index, line in enumerate(lines):
            if line.startswith('# '):
                lines[index] = f'# {title}'
                break
        (change / 'proposal.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')

    def test_a_scaffold_change_is_exempt_until_it_is_written_up(self) -> None:
        # The spec-create workflow writes the change and leaves the template in
        # place. Nobody has written it up, so nobody has reviewed it, and there
        # is nothing to have reviewed yet.
        template = self._scaffold_template()
        change = self.changes / '2026-10-22-a-new-idea'
        change.mkdir()
        (change / '.openspec.yaml').write_text(
            'schema: adr-workflow\nslug: a-new-idea\n', encoding='utf-8'
        )
        self._proposal(change, template, 'A new idea')

        problems, _, scaffolds, counted = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems, 'a scaffold is not a decision yet')
        self.assertEqual(['a-new-idea'], scaffolds)
        self.assertEqual(1, counted['scaffolds'])

    def test_a_written_up_scaffold_needs_a_verdict(self) -> None:
        template = self._scaffold_template()
        change = self.changes / '2026-10-22-a-new-idea'
        change.mkdir()
        (change / '.openspec.yaml').write_text(
            'schema: adr-workflow\nslug: a-new-idea\n', encoding='utf-8'
        )
        self._proposal(change, template, 'A new idea')
        with (change / 'proposal.md').open('a', encoding='utf-8') as handle:
            handle.write('\nThe problem, written up.\n')

        problems, _, scaffolds, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems), 'once it is written up, it needs a review')
        self.assertEqual([], scaffolds)

    # ── what the verdict is about ──────────────────────────────────────────────

    def test_a_verdict_without_the_reviewed_commit_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', reviewed='')

        problems, _, _, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('reviewed', problems[0])

    def test_a_verdict_with_a_reviewed_commit_that_is_not_a_sha_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', reviewed='the commit I looked at')

        problems, _, _, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('reviewed', problems[0])

    def test_a_verdict_without_the_documents_digest_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._verdict('the-backup-keeps-the-verb', documents='')

        problems, _, _, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('documents', problems[0])

    def test_a_verdict_about_documents_that_changed_after_the_review_is_a_finding(self) -> None:
        change = self._change('the-backup-keeps-the-verb')
        (change / 'proposal.md').write_text('# The proposal\n', encoding='utf-8')
        self._verdict('the-backup-keeps-the-verb')
        # The contract changed after the review: the verdict is now about a
        # change that is not there any more.
        (change / 'proposal.md').write_text('# The proposal, rewritten\n', encoding='utf-8')

        problems, _, _, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual(1, len(problems))
        self.assertIn('moved after the verdict was written', problems[0])
        self.assertIn('re-issue the verdict', problems[0])

    def test_ticking_a_task_does_not_stale_the_verdict(self) -> None:
        change = self._change('the-backup-keeps-the-verb')
        (change / 'proposal.md').write_text('# The proposal\n', encoding='utf-8')
        (change / 'tasks.md').write_text('- [ ] 1.1 the first step\n', encoding='utf-8')
        self._verdict('the-backup-keeps-the-verb')
        # The checklist moves while the work is done; the contract does not.
        (change / 'tasks.md').write_text('- [x] 1.1 the first step\n', encoding='utf-8')

        problems, _, _, _ = mod.find_problems(self.changes, self.reviews, set())

        self.assertEqual([], problems, 'the checklist is not the contract')

    def test_the_digest_covers_the_proposal_and_the_spec_deltas(self) -> None:
        change = self._change('the-backup-keeps-the-verb')
        (change / 'proposal.md').write_text('# The proposal\n', encoding='utf-8')
        specs = change / 'specs' / 'change-review-integrity'
        specs.mkdir(parents=True)
        (specs / 'spec.md').write_text('## MODIFIED Requirements\n', encoding='utf-8')

        documents = mod.contract_documents(change)

        self.assertEqual(
            [change / 'proposal.md', specs / 'spec.md'],
            documents,
            'the contract is the proposal and the spec deltas',
        )

    def test_a_spec_delta_that_moved_changes_the_digest(self) -> None:
        change = self._change('the-backup-keeps-the-verb')
        first = change / 'specs' / 'one' / 'spec.md'
        first.mkdir(parents=True)
        (first / 'spec.md').write_text('## ADDED Requirements\n', encoding='utf-8')
        before = mod.documents_digest(change)
        (first / 'spec.md').unlink()
        second = change / 'specs' / 'two' / 'spec.md'
        second.mkdir(parents=True)
        (second / 'spec.md').write_text('## ADDED Requirements\n', encoding='utf-8')

        self.assertNotEqual(before, mod.documents_digest(change), 'the path is part of the digest')


if __name__ == '__main__':
    unittest.main()
