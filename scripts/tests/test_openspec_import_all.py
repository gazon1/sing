"""Tests for openspec-import-all.py — the importer leaves hand-written records alone.

The importer writes each record wholesale, so `--replace` replaces the file and
not its fields. Some changes have no proposal and no `adr.md` for the importer
to read, so their records are written by hand — with a title, a date, the ADRs
and the tags the importer cannot infer. The first version of this guard did not
exist, and the loss was invisible: the registry gate still passed, because the
file was still there, and it was still a record — just not the record anybody
wrote.

The tests write their own tree: an importer exercised only against the data of
one particular repository is an importer whose behaviour nobody knows when the
tree changes shape.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'openspec-import-all.py'
)
spec = importlib.util.spec_from_file_location('openspec_import_all', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


HAND_WRITTEN = """---
version: '1.0'
type: openspec-change
slug: task-series-invariants
date: '2026-10-22'
title: 'The series says one thing about the work'
status: accepted
change_type: active
speckit_imported: false
adr_refs:
- 2026-10-22-the-series-says-one-thing-about-the-work
tags:
- tasks
- series
"""

IMPORTED = """---
version: '1.0'
type: openspec-change
slug: a-change
date: unknown
title: a-change
status: accepted
change_type: active
speckit_imported: true
adr_refs: []
tags: []
"""


class HandWrittenMarkerTest(unittest.TestCase):
    """The marker tells a hand-written record from an imported one."""

    def test_a_record_that_says_false_is_hand_written(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            record = pathlib.Path(tmp) / 'record.yaml'
            record.write_text(HAND_WRITTEN, encoding='utf-8')
            self.assertTrue(mod.is_hand_written(record))

    def test_a_record_the_importer_wrote_is_not_hand_written(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            record = pathlib.Path(tmp) / 'record.yaml'
            record.write_text(IMPORTED, encoding='utf-8')
            self.assertFalse(mod.is_hand_written(record))

    def test_a_file_that_is_not_there_is_not_hand_written(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(mod.is_hand_written(pathlib.Path(tmp) / 'nothing.yaml'))


class ImportChangeTest(unittest.TestCase):
    """The importer writes its own records and leaves the other ones alone."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = pathlib.Path(self.tmp.name)

        self.issues = root / 'issues'
        self.issues.mkdir()
        self._saved_issues_dir = mod.ISSUES_DIR
        mod.ISSUES_DIR = self.issues
        self.addCleanup(lambda: setattr(mod, 'ISSUES_DIR', self._saved_issues_dir))

        self.change_dir = root / 'openspec' / 'changes' / '2026-10-23-a-change'
        self.change_dir.mkdir(parents=True)
        (self.change_dir / 'proposal.md').write_text(
            '# a change\n\nWhy: because.\n', encoding='utf-8'
        )

    def test_replace_leaves_a_hand_written_record_untouched(self) -> None:
        record = self.issues / 'a-change.yaml'
        record.write_text(HAND_WRITTEN, encoding='utf-8')

        ok, message = mod.import_change(self.change_dir, replace=True)

        self.assertTrue(ok)
        self.assertIn('hand-written', message)
        self.assertEqual(record.read_text(encoding='utf-8'), HAND_WRITTEN)

    def test_replace_writes_a_record_the_importer_owns(self) -> None:
        # The control for the guard above: a record the importer wrote is
        # replaced, so the guard protects the hand-written records and does
        # not freeze the registry.
        record = self.issues / 'a-change.yaml'
        record.write_text(IMPORTED, encoding='utf-8')

        ok, message = mod.import_change(self.change_dir, replace=True)

        self.assertTrue(ok)
        self.assertNotIn('hand-written', message)
        written = record.read_text(encoding='utf-8')
        self.assertIn('speckit_imported: true', written)
        self.assertNotIn('The series says one thing about the work', written)

    def test_a_hand_written_record_is_left_alone_without_replace_too(self) -> None:
        record = self.issues / 'a-change.yaml'
        record.write_text(HAND_WRITTEN, encoding='utf-8')

        ok, _ = mod.import_change(self.change_dir)

        self.assertTrue(ok)
        self.assertEqual(record.read_text(encoding='utf-8'), HAND_WRITTEN)


if __name__ == '__main__':
    unittest.main()
