"""Tests for check-issue-registry.py.

The gate exists because a rule that is written down and not checked is a hope,
not a property: four changes landed in `main` with no entry in the registry,
and nothing failed while it happened. So the gate has to be right in the cases
that matter, and the cases that matter are the ones a clean tree never shows:

  - a change with no registry entry is a finding — the gap the gate was written for;
  - a registry entry that points at another change is a finding — a record of
    something else is worse than no record, because it looks like coverage;
  - an archived change counts — the registry is the source of truth for what
    was decided, and an archived change is still a decision;
  - a date-prefixed directory drops the prefix for the entry's name, and an
    entry whose recorded path still names the change is fine even after the
    change moved into `archive/` — the path is compared by its last segment,
    because moving a change is a rename the registry does not follow.

The tests write their own tree: a gate exercised only against the data of one
particular repository is a gate whose behaviour nobody knows when the tree
changes shape.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-issue-registry.py'
)
spec = importlib.util.spec_from_file_location('check_issue_registry', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_issue_registry'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


ENTRY_TEMPLATE = """---
version: '1.0'
type: openspec-change
slug: {slug}
title: '{slug}'
status: accepted
openspec_path: {path}
"""


class IssueRegistryTest(unittest.TestCase):
    """The registry has to cover every change, and every record has to be about one."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.changes = self.root / 'openspec' / 'changes'
        self.issues = self.root / '.specify' / 'issues'
        self.changes.mkdir(parents=True)
        self.issues.mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _change(self, name: str) -> pathlib.Path:
        change = self.changes / name
        (change / 'specs').mkdir(parents=True)
        (change / 'proposal.md').write_text('# proposal\n', encoding='utf-8')
        return change

    def _entry(self, slug: str, recorded_path: str) -> None:
        (self.issues / f'{slug}.yaml').write_text(
            ENTRY_TEMPLATE.format(slug=slug, path=recorded_path),
            encoding='utf-8',
        )

    # ── the gap the gate was written for ───────────────────────────────────────

    def test_a_change_without_an_entry_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual(1, len(missing), 'a change with no registry entry is the gap')
        self.assertIn('the-backup-keeps-the-verb', missing[0])
        self.assertEqual([], mispointed)

    def test_a_registered_change_is_clean(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'openspec/changes/the-backup-keeps-the-verb')

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)

    # ── the other direction: a record of something else ────────────────────────

    def test_an_entry_pointing_at_another_change_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'openspec/changes/some-other-change')

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(1, len(mispointed), 'a record of another change is not coverage')

    def test_an_entry_without_a_recorded_path_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        (self.issues / 'the-backup-keeps-the-verb.yaml').write_text(
            "slug: the-backup-keeps-the-verb\n", encoding='utf-8'
        )

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(1, len(mispointed), 'a record that points at nothing points at nothing')

    # ── the shapes of a change directory ───────────────────────────────────────

    def test_an_archived_change_counts(self) -> None:
        archived = self.changes / 'archive'
        archived.mkdir()
        change = archived / '2026-10-21-the-search-asks-the-index'
        (change / 'specs').mkdir(parents=True)
        self._entry('the-search-asks-the-index', 'openspec/changes/2026-10-21-the-search-asks-the-index')

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing, 'an archived change is still a decision')
        self.assertEqual([], mispointed)

    def test_a_date_prefix_is_dropped_for_the_entry_name(self) -> None:
        self._change('2026-10-09-detekt-class-signature-gotcha')
        self._entry('detekt-class-signature-gotcha', 'openspec/changes/2026-10-09-detekt-class-signature-gotcha')

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)

    def test_a_record_may_keep_the_path_it_had_before_the_move_to_archive(self) -> None:
        # The archived change still records the path it had before the move, and
        # requiring that path to resolve would make every archive a second edit.
        archived = self.changes / 'archive'
        archived.mkdir()
        change = archived / '2026-10-21-the-search-asks-the-index'
        (change / 'specs').mkdir(parents=True)
        self._entry('the-search-asks-the-index', 'openspec/changes/2026-10-21-the-search-asks-the-index')

        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)

    def test_an_empty_changes_directory_is_clean(self) -> None:
        missing, mispointed = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)


if __name__ == '__main__':
    unittest.main()
