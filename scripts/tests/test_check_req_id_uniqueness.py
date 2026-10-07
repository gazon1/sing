"""Tests for check-req-id-uniqueness.py.

The gate stops one thing: an identifier naming two different requirements. Four ways it could
pass while measuring nothing, and each has a test here.

  - a checker that stopped finding headers at all. Covered by VacuousCorpusTest, which asserts
    the gate **fails** on an empty corpus — a checker whose resolver stopped matching looks
    exactly like a corpus that stopped having requirements.
  - a checker that rejects the corpus's own numbering. `genui-catalog-contract` adds
    `REQ-GC-001a` next to `REQ-GC-001` on purpose, and the first version of the identifier
    pattern called it malformed. Covered by LegalShapeTest, because a gate that cries wolf gets
    switched off, and a switched-off gate protects nothing.
  - a checker that calls a `MODIFIED` delta a collision. Restating an existing identifier under
    `MODIFIED` is what the delta language means by editing a requirement. Also
    LegalShapeTest.
  - a checker that reads the archive and reports every archived change as a duplicate of the
    spec it was folded into. Covered there too, because `openspec archive` is the normal path
    and a gate that fires on the tool's own output is a gate that fires forever.

The collision this gate exists for is real and was found by it: `REQ-OS-026` and `REQ-OS-027`
were claimed by `a-cycle-drains-the-feed-or-says-it-could-not` for requirements about sync
feed paging, while the spec's `REQ-OS-026` was the lost-race rule and its `REQ-OS-027` was
auto-sync settings. DuplicateFiresTest reproduces both shapes.
"""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = REPO / 'scripts' / 'check-req-id-uniqueness.py'
spec = importlib.util.spec_from_file_location('check_req_id_uniqueness', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def spec_file(capability: str, body: str) -> str:
    return f"# {capability}\n\n{body}\n"


def defined(identifier: str, text: str = 'It SHALL hold.') -> str:
    """A requirement already folded into `openspec/specs/` — no delta header."""
    return (
        f'### Requirement: {identifier}\n\n{text}\n\n'
        '#### Scenario: It holds\n- The thing happens\n- And is reported\n'
    )


def added(identifier: str, text: str = 'It SHALL hold.') -> str:
    return '## ADDED Requirements\n\n' + defined(identifier, text)


def corpus(root: pathlib.Path, *files: tuple[str, str]) -> list[str]:
    """Write `(<path under openspec/>, <body>)` files, then read them back the way the gate does."""
    for relative, body in files:
        target = root / 'openspec' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(spec_file(relative, body), encoding='utf-8')
    found = [d for path in mod.spec_files(root) for d in mod.definitions_in(path)]
    return mod.conflicts(found)


def in_spec(capability: str, identifier: str, text: str = 'It SHALL hold.') -> tuple[str, str]:
    return f'specs/{capability}/spec.md', defined(identifier, text)


def in_change(change: str, identifier: str, text: str = 'It SHALL hold.', kind: str = 'ADDED') -> tuple[str, str]:
    section = f'## {kind} Requirements\n\n' if kind else ''
    return f'changes/{change}/specs/offline-sync/spec.md', section + defined(identifier, text)


class DuplicateFiresTest(unittest.TestCase):
    """A reused identifier must be reported, with both locations."""

    def test_a_change_adding_a_requirement_the_spec_already_has(self):
        """The instance that produced this gate: REQ-OS-026, claimed twice."""
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_spec('offline-sync', 'REQ-OS-026', 'The device adopts the server state.'),
                in_change('a-cycle', 'REQ-OS-026', 'A cycle drains the feed.'),
            )
        self.assertTrue(problems, 'a change that ADDs an existing identifier must be reported')
        self.assertTrue(any('REQ-OS-026' in p for p in problems))

    def test_two_changes_adding_the_same_identifier(self):
        """The log-export pair: one requirement written twice in two folders."""
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_change('log-export-surface', 'REQ-LE-001'),
                in_change('add-log-export', 'REQ-LE-001'),
            )
        self.assertTrue(problems, 'two changes ADDing one identifier must be reported')
        self.assertTrue(any('REQ-LE-001' in p for p in problems))

    def test_the_report_names_both_locations(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_change('log-export-surface', 'REQ-LE-001'),
                in_change('add-log-export', 'REQ-LE-001'),
            )
        self.assertTrue(any('spec.md' in p for p in problems), problems)

    def test_a_malformed_identifier_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(pathlib.Path(tmp), in_spec('genui-catalog', 'must-have-an-id'))
        self.assertTrue(any('must-have-an-id' in p for p in problems), problems)

    def test_a_header_with_no_identifier_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(pathlib.Path(tmp), in_spec('genui-catalog', ''))
        self.assertTrue(any('no identifier' in p for p in problems), problems)


class LegalShapeTest(unittest.TestCase):
    """Shapes that are legal, and that a too-eager checker would report."""

    def test_a_modified_delta_may_restate_an_existing_identifier(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_spec('offline-sync', 'REQ-OS-015'),
                in_change('later', 'REQ-OS-015', kind='MODIFIED'),
            )
        self.assertEqual([], problems, 'editing a requirement restates its identifier by design')

    def test_a_removed_delta_may_restate_an_existing_identifier(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_spec('offline-sync', 'REQ-OS-015'),
                in_change('later', 'REQ-OS-015', kind='REMOVED'),
            )
        self.assertEqual([], problems)

    def test_a_letter_suffixed_identifier_is_legal(self):
        """`REQ-GC-001a` exists in this repository, next to `REQ-GC-001`, on purpose."""
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(pathlib.Path(tmp), in_spec('genui-catalog', 'REQ-GC-001a'))
        self.assertEqual([], problems, 'the corpus numbers sub-requirements with a letter')

    def test_archived_changes_are_not_read(self):
        """An archived delta is already folded into the spec, so it repeats by design."""
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_spec('offline-sync', 'REQ-OS-015'),
                (
                    'changes/archive/2026-01-01-old/specs/offline-sync/spec.md',
                    added('REQ-OS-015'),
                ),
            )
        self.assertEqual([], problems, 'the archive repeats every identifier by design')

    def test_a_delta_editing_an_absent_requirement_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = corpus(
                pathlib.Path(tmp),
                in_spec('offline-sync', 'REQ-OS-015'),
                in_change('later', 'REQ-OS-099', kind='MODIFIED'),
            )
        self.assertTrue(any('REQ-OS-099' in p for p in problems), problems)


class VacuousCorpusTest(unittest.TestCase):
    """A checker that stopped matching must fail, not pass."""

    def test_empty_corpus_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'openspec' / 'specs').mkdir(parents=True)
            argv = ['--root', str(root)]
            self.assertEqual(1, mod.main(argv))


class RepositoryTest(unittest.TestCase):
    """The gate on this repository, as a subprocess — the form the gate registry invokes."""

    def test_it_fails_and_names_the_open_collision(self):
        """Advisory, not blocking, and this is why: the corpus still has one live collision."""
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)],
            capture_output=True,
            text=True,
            cwd=str(REPO),
        )
        self.assertEqual(1, result.returncode)
        self.assertIn('REQ-LE-001', result.stdout)

    def test_it_no_longer_reports_the_renumbered_cycle_requirement(self):
        """The instance it was written for was fixed in the same commit."""
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH)],
            capture_output=True,
            text=True,
            cwd=str(REPO),
        )
        self.assertNotIn('REQ-OS-026', result.stdout)
        self.assertNotIn('REQ-GC-001a', result.stdout)


if __name__ == '__main__':
    unittest.main()