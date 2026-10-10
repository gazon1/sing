"""Tests for adr_corpus.py — the single owner of ADR corpus discovery (#520).

The bug these guard against is not "a glob was wrong". It is that five consumers
each had a *correct-looking* glob and disagreed with each other, so the status gate
could report "all statuses in vocabulary" while 129 files under `deferred/` used a
vocabulary of their own. A test that only checks today's counts would pass again
the moment someone adds a sixth consumer with a sixth idea of what the corpus is.

So these tests assert the *classes* — what belongs to decisions, what does not, and
what `normalize_status` refuses to do.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import adr_corpus  # noqa: E402

CFG = adr_corpus.load()


def write(path: pathlib.Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')


def adr(status: str = 'accepted', **extra: str) -> str:
    lines = ['---', 'title: "T"', 'date: 2026-10-10', f'status: {status}']
    lines += [f'{k}: {v}' for k, v in extra.items()]
    return '\n'.join(lines) + '\n---\n\nbody\n'


class TestNormalizeStatus(unittest.TestCase):
    """Case and brackets only. Value mapping is a migration, not a read-time guess."""

    def test_case_is_folded(self):
        for raw in ('open', 'OPEN', 'Open', ' open '):
            self.assertEqual(CFG.normalize_status(raw), 'open', raw)

    def test_brackets_are_stripped(self):
        # The corpus really contains `[slug]` — a supersedes value nobody parsed.
        self.assertEqual(CFG.normalize_status('[open]'), 'open')

    def test_missing_is_empty_not_none(self):
        self.assertEqual(CFG.normalize_status(None), '')

    def test_out_of_vocabulary_is_not_remapped(self):
        # The whole point: a typo must not be silently reinterpreted as a decision.
        for raw in ('CLOSED', 'RESOLVED', 'PARTIALLY', 'acccepted', 'CLOSED.'):
            self.assertNotEqual(CFG.normalize_status(raw), 'accepted', raw)

    def test_gate_and_tool_agree_on_open(self):
        # The regression this fixes: AdrTools accepted five spellings, the gate one.
        self.assertEqual(CFG.normalize_status('OPEN'), 'open')
        self.assertTrue(CFG.is_valid_status(CFG.normalize_status('OPEN')))


class TestClasses(unittest.TestCase):
    """Three classes, deliberately not one."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        # A config rooted at the temp dir, without touching the real repo config.
        self.cfg = adr_corpus.AdrCorpusConfig(
            root=self.root,
            decision_pattern=__import__('re').compile(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}-'),
            archive_dir='archive',
            deferred_dir='deferred',
            ignored_files=frozenset({'DIGEST.md'}),
            statuses=frozenset({'accepted', 'deferred', 'superseded', 'open'}),
            archived_status='archived',
            stale_days=30,
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_dated_is_a_decision(self):
        write(self.root / '2026-10-10-real.md', adr('accepted'))
        self.assertEqual([p.name for p in self.cfg.decisions()], ['2026-10-10-real.md'])

    def test_deferred_is_not_a_decision(self):
        # The blind spot: slug-named, no date, and the gate never saw it.
        write(self.root / 'deferred' / 'some-backlog-item.md', adr('OPEN'))
        self.assertEqual(self.cfg.decisions(), [])
        self.assertEqual([p.name for p in self.cfg.deferred()], ['some-backlog-item.md'])

    def test_archive_is_separate(self):
        write(self.root / 'archive' / '2026-01-01-old.md', adr('archived'))
        self.assertEqual(self.cfg.decisions(), [])
        self.assertEqual(len(self.cfg.archived()), 1)
        self.assertEqual(
            [p.name for p in self.cfg.decisions(include_archived=True)],
            ['2026-01-01-old.md'],
        )

    def test_digest_is_ignored(self):
        write(self.root / 'DIGEST.md', 'not an adr')
        write(self.root / 'deferred' / 'DIGEST.md', 'not an adr')
        self.assertEqual(self.cfg.decisions(), [])
        self.assertEqual(self.cfg.deferred(), [])

    def test_all_slugs_covers_every_class(self):
        # A superseded target that was archived is still resolvable; moving is not
        # deleting. Deferred slugs are cited from prose too.
        write(self.root / '2026-10-10-live.md', adr('accepted'))
        write(self.root / 'archive' / '2026-01-01-old.md', adr('archived'))
        write(self.root / 'deferred' / 'backlog-thing.md', adr('OPEN'))
        self.assertEqual(
            self.cfg.all_slugs(),
            {'2026-10-10-live', '2026-01-01-old', 'backlog-thing'},
        )

    def test_missing_subdirs_are_empty_not_an_error(self):
        # A checkout with no deferred/ must not crash the gate.
        self.assertEqual(self.cfg.deferred(), [])
        self.assertEqual(self.cfg.archived(), [])


class TestRealCorpus(unittest.TestCase):
    """Against the actual repository, so a config typo fails here and nowhere later."""

    def test_config_points_at_a_real_directory(self):
        self.assertTrue(CFG.root.is_dir(), CFG.root)

    def test_classes_partition_the_corpus(self):
        decisions = CFG.decisions()
        archived = CFG.archived()
        deferred = CFG.deferred()
        self.assertGreater(len(decisions), 0)
        self.assertGreater(len(deferred), 0)
        # No file may be in two classes — that ambiguity is what broke the gate.
        names = [{p.name for p in c} for c in (decisions, archived, deferred)]
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                self.assertFalse(a & b, f'overlap between classes: {a & b}')

    def test_every_decision_carries_a_dated_name(self):
        for p in CFG.decisions():
            self.assertRegex(p.stem, r'^[0-9]{4}-\d{2}-\d{2}-', p.name)

    def test_no_deferred_file_is_treated_as_a_decision(self):
        for p in CFG.deferred():
            self.assertNotIn(p.stem, {d.stem for d in CFG.decisions()})


if __name__ == '__main__':
    unittest.main()