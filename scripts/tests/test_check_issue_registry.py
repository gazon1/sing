"""Tests for check-issue-registry.py.

The gate exists because a rule that is written down and not checked is a hope,
not a property: four changes landed in `main` with no entry in the registry,
and nothing failed while it happened. So the gate has to be right in the cases
that matter, and the cases that matter are the ones a clean tree never shows:

  - a change with no registry entry is a finding — the gap the gate was written for;
  - a registry entry that names another change is a finding — a record of
    something else is worse than no record, because it looks like coverage;
  - an archived change counts — the registry is the source of truth for what
    was decided, and an archived change is still a decision;
  - **archiving must not break the record**. The repository files a change as
    `openspec/changes/<slug>` and archives it as
    `openspec/changes/archive/<date>-<slug>`: the move *adds* a date prefix to
    the directory, while the record is named after the slug and keeps the path
    the change lived at. A gate that compares raw paths fails on the next
    ordinary archive operation — and the first version of this gate did.
  - a record that names a directory that does not exist is a finding: the
    comparison is by slug *and* by where the path lives, so
    `openspec/changes/nowhere/<slug>` is not a path to a change.
  - two changes claiming one slug are a finding — one record cannot be the
    record of both, and the second would pass on the first one's record.

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

    def _archived_change(self, name: str) -> pathlib.Path:
        archive = self.changes / 'archive'
        archive.mkdir(exist_ok=True)
        change = archive / name
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

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual(1, len(missing), 'a change with no registry entry is the gap')
        self.assertIn('the-backup-keeps-the-verb', missing[0])
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    def test_a_registered_change_is_clean(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'openspec/changes/the-backup-keeps-the-verb')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    # ── the other direction: a record of something else ────────────────────────

    def test_an_entry_pointing_at_another_change_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'openspec/changes/some-other-change')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(1, len(mispointed), 'a record of another change is not coverage')

    def test_an_entry_without_a_recorded_path_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        (self.issues / 'the-backup-keeps-the-verb.yaml').write_text(
            "slug: the-backup-keeps-the-verb\n", encoding='utf-8'
        )

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(1, len(mispointed), 'a record that points at nothing points at nothing')

    def test_an_entry_pointing_at_a_directory_that_does_not_exist_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'openspec/changes/nowhere/the-backup-keeps-the-verb')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(
            1,
            len(mispointed),
            'a path under openspec/changes that is not a change directory names nothing',
        )

    def test_an_entry_pointing_outside_the_changes_tree_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'docs/decisions/the-backup-keeps-the-verb')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(1, len(mispointed), 'a path outside the changes tree is not a change')

    # ── the shapes of a change directory ───────────────────────────────────────

    def test_an_archived_change_counts(self) -> None:
        self._archived_change('2026-10-21-the-search-asks-the-index')
        self._entry(
            'the-search-asks-the-index',
            'openspec/changes/2026-10-21-the-search-asks-the-index',
        )

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing, 'an archived change is still a decision')
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    def test_a_date_prefix_is_dropped_for_the_entry_name(self) -> None:
        self._change('2026-10-09-detekt-class-signature-gotcha')
        self._entry(
            'detekt-class-signature-gotcha',
            'openspec/changes/2026-10-09-detekt-class-signature-gotcha',
        )

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    def test_archiving_a_change_does_not_break_its_record(self) -> None:
        # The repository's convention: a change is filed as
        # `openspec/changes/<slug>` and archived as
        # `openspec/changes/archive/<date>-<slug>`. The move adds a date prefix
        # to the *directory*; the record is named after the slug and keeps the
        # path the change lived at. The gate compares slugs, so the next
        # ordinary archive operation is not a red CI run.
        self._archived_change('2026-10-22-android-test-hardening')
        self._entry('android-test-hardening', 'openspec/changes/android-test-hardening')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual(
            [],
            mispointed,
            'a record may keep the path it had before the change was filed away',
        )
        self.assertEqual([], dangling)

    def test_a_record_regenerated_after_the_move_is_accepted_too(self) -> None:
        self._archived_change('2026-10-22-android-test-hardening')
        self._entry(
            'android-test-hardening',
            'openspec/changes/archive/2026-10-22-android-test-hardening',
        )

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    def test_two_changes_claiming_one_slug_are_a_finding(self) -> None:
        self._change('close-rule-consolidation')
        self._archived_change('close-rule-consolidation')
        self._entry('close-rule-consolidation', 'openspec/changes/close-rule-consolidation')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual(
            1,
            len(missing),
            'one record cannot be the record of two changes wearing one slug',
        )
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    def test_an_empty_changes_directory_is_clean(self) -> None:
        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    # ── the record's own links ─────────────────────────────────────────────────

    def test_a_record_naming_a_document_that_is_not_there_is_a_finding(self) -> None:
        # The importer used to write all four document names — proposal, design,
        # adr, tasks — whether the change had them or not, so a record pointed at
        # a design.md that was never written. A record is a link to the change,
        # and a link to a file that is not there is a link to nothing.
        self._change('the-backup-keeps-the-verb')
        (self.issues / 'the-backup-keeps-the-verb.yaml').write_text(
            ENTRY_TEMPLATE.format(
                slug='the-backup-keeps-the-verb',
                path='openspec/changes/the-backup-keeps-the-verb',
            )
            + 'documents:\n'
            + '  proposal: openspec/changes/the-backup-keeps-the-verb/proposal.md\n'
            + '  design: openspec/changes/the-backup-keeps-the-verb/design.md\n',
            encoding='utf-8',
        )

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual(1, len(dangling), 'a record naming a document that is not there')
        self.assertIn('design', dangling[0])

    def test_a_record_naming_the_documents_that_exist_is_clean(self) -> None:
        self._change('the-backup-keeps-the-verb')
        (self.issues / 'the-backup-keeps-the-verb.yaml').write_text(
            ENTRY_TEMPLATE.format(
                slug='the-backup-keeps-the-verb',
                path='openspec/changes/the-backup-keeps-the-verb',
            )
            + 'documents:\n'
            + '  proposal: openspec/changes/the-backup-keeps-the-verb/proposal.md\n',
            encoding='utf-8',
        )

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)

    def test_a_record_naming_a_specs_directory_that_is_not_there_is_a_finding(self) -> None:
        self._change('the-backup-keeps-the-verb')
        (self.issues / 'the-backup-keeps-the-verb.yaml').write_text(
            ENTRY_TEMPLATE.format(
                slug='the-backup-keeps-the-verb',
                path='openspec/changes/the-backup-keeps-the-verb',
            )
            + 'specs_dir: openspec/changes/the-backup-keeps-the-verb/specs\n',
            encoding='utf-8',
        )
        (self.changes / 'the-backup-keeps-the-verb' / 'specs').rmdir()

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual(1, len(dangling), 'a record naming a specs directory that is not there')

    def test_a_record_of_an_archived_change_keeps_its_documents(self) -> None:
        # Archiving moves the change and renames it:
        # `openspec/changes/<slug>` becomes
        # `openspec/changes/archive/<date>-<slug>`. The record keeps the path
        # the change had before the move — the same convention the slug
        # comparison already follows — so the documents it names are looked up
        # in the archived directory. A routine archive operation must not turn
        # the registry red.
        archived = self._archived_change('2026-10-22-the-backup-keeps-the-verb')
        (self.issues / 'the-backup-keeps-the-verb.yaml').write_text(
            ENTRY_TEMPLATE.format(
                slug='the-backup-keeps-the-verb',
                path='openspec/changes/the-backup-keeps-the-verb',
            )
            + 'documents:\n'
            + '  proposal: openspec/changes/the-backup-keeps-the-verb/proposal.md\n'
            + '  tasks: openspec/changes/the-backup-keeps-the-verb/tasks.md\n',
            encoding='utf-8',
        )
        (archived / 'tasks.md').write_text('# tasks\n', encoding='utf-8')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual(
            [],
            dangling,
            'a document the archived change still has is not a document that is not there',
        )

    def test_a_record_with_no_documents_block_is_clean(self) -> None:
        # The legacy shape: a record that says nothing about documents. It says
        # no more than it can be held to.
        self._change('the-backup-keeps-the-verb')
        self._entry('the-backup-keeps-the-verb', 'openspec/changes/the-backup-keeps-the-verb')

        missing, mispointed, dangling = mod.find_problems(self.changes, self.issues)

        self.assertEqual([], missing)
        self.assertEqual([], mispointed)
        self.assertEqual([], dangling)


if __name__ == '__main__':
    unittest.main()
