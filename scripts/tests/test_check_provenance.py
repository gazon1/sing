"""Tests for check-provenance.py.

The gate exists so a note acknowledging derivation can never again be added to
production code without a registry row. Two things it must get right, and both
are ways the gate could pass while measuring nothing:

  - a marked file with no registry row is a violation (the whole point)
  - the marker pattern still matches, and still does not match ordinary prose

The second is not hypothetical. The first draft of this rule matched
`derived from`, `taken from` and `mirrors <Thing>'s`, and produced 30 false
positives on its first run against the real tree — "a fixed name instead of one
derived from wall-clock time", "lifted from domainTask to avoid reference". A
gate that reports English prose is a gate that gets disabled, and the project's
standing rule is that a noisy gate is worse than no gate. These tests pin the
narrow pattern so it cannot drift back to the broad one.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-provenance.py'
spec = importlib.util.spec_from_file_location('check_provenance', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def write(path: pathlib.Path, text: str) -> pathlib.Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    return path


MARKED = 'package com.example\n\n/** Design notes (lifted from Orgzly). */\nclass T\n'
PLAIN = 'package com.example\n\n/** Reports the failure rather than swallowing it. */\nclass T\n'


class MarkerPatternTest(unittest.TestCase):
    """The pattern must catch upstreams, and must not catch English."""

    def test_catches_a_named_upstream(self):
        for line in (
            '* Design notes (lifted from Orgzly, stripped of annotations):',
            '* Mirrors Tasks.org naming conventions for familiarity:',
            '* Inspired by Tasks.org layered `SyncException` hierarchy.',
            '* Inspired by [Orgzly](https://github.com/orgzly/orgzly-android) sync model',
            '* Inspired by: [Orgzly] sync model',
            '// Parses Orgzly / Tasks.org compatible recurrence DSL strings.',
        ):
            with self.subTest(line=line):
                self.assertTrue(mod.MARKER_RE.search(line), line)

    def test_catches_a_licence_tag(self):
        """An SPDX or GPL tag in source is a claim about the file's licence."""
        for line in ('// SPDX-License-Identifier: GPL-3.0', '* GPL-3.0 derived', '* LGPL-2.1'):
            with self.subTest(line=line):
                self.assertTrue(mod.MARKER_RE.search(line), line)

    def test_ignores_ordinary_prose(self):
        """The false positives that made the first draft of this rule unusable."""
        for line in (
            '* Allows tests to use a fixed name instead of one derived from wall-clock time.',
            'val isPinned: Boolean = false, // MR-1: lifted from domainTask to avoid reference',
            '// Re-exported from core/llm/ for backward compatibility.',
            '// The clock the server orders by. Taken from the factory rather than from',
            '// Mirrors EmptyState\'s centered Box+Column layout so Loading/Empty/Error',
            '// The failure is reported through the event bus, not thrown into the caller.',
            '* Derived from the domain [com.singularity.todo.feature.tasks.domain.model.Task]:',
        ):
            with self.subTest(line=line):
                self.assertIsNone(mod.MARKER_RE.search(line), line)

    def test_ignores_unrelated_words_that_contain_an_upstream(self):
        """`organize` must not match `orgzly`; a bare `tasks` must not match `tasks.org`."""
        for line in ('val organized = true', 'val tasks = listOf<String>()', '// astroid collision'):
            with self.subTest(line=line):
                self.assertIsNone(mod.MARKER_RE.search(line), line)


class ScopeTest(unittest.TestCase):
    """What counts as Apache-2.0 production, and what is excluded on purpose."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.registry = write(
            self.root / 'config/legal/provenance-registry.tsv',
            '# path\tclass\tsource\tlicence\tnote\n',
        )

    def tearDown(self):
        self.tmp.cleanup()

    def prod(self, rel: str) -> pathlib.Path:
        return write(self.root / rel, MARKED)

    def rels(self) -> set:
        return {p.relative_to(self.root).as_posix() for p in mod.production_files(self.root)}

    def test_includes_a_common_main_source_set(self):
        self.prod('shared/src/commonMain/kotlin/com/example/A.kt')
        self.assertIn('shared/src/commonMain/kotlin/com/example/A.kt', self.rels())

    def test_includes_platform_source_sets(self):
        for rel in ('androidApp/src/main/kotlin/com/example/B.kt',
                    'desktopApp/src/jvmMain/kotlin/com/example/C.kt',
                    'mcp-server/src/main/kotlin/com/example/D.kt'):
            with self.subTest(rel=rel):
                self.prod(rel)
                self.assertIn(rel, self.rels())

    def test_excludes_test_source_sets(self):
        """A fixture quoting a marker is not a derivative work, and must not need a row."""
        for rel in ('shared/src/commonTest/kotlin/com/example/E.kt',
                    'shared/src/jvmTest/kotlin/com/example/F.kt',
                    'androidApp/src/androidTest/kotlin/com/example/G.kt'):
            with self.subTest(rel=rel):
                self.prod(rel)
                self.assertNotIn(rel, self.rels())

    def test_excludes_the_fsl_pro_catalogue(self):
        """`pro/` is FSL-1.1-Apache, not Apache-2.0, and is meant to hold derived code."""
        rel = 'shared/src/commonMain/kotlin/com/example/pro/Pro.kt'
        self.prod(rel)
        self.assertNotIn(rel, self.rels())

    def test_ignores_a_missing_source_root(self):
        """A root that does not exist is not an error; the scan is not tied to one layout."""
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt', MARKED)
        self.assertEqual(len(mod.production_files(self.root)), 1)


class RegistryParsingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.tmp.name) / 'r.tsv'

    def tearDown(self):
        self.tmp.cleanup()

    def test_parses_rows_and_skips_comments(self):
        self.path.write_text(
            '# a comment\n'
            'shared/A.kt\tPORTED\tOrgzly\tGPL-3.0\trewrite\n'
            'shared/B.kt\tSPEC-COMPATIBLE\t-\t-\t-\n',
            encoding='utf-8',
        )
        reg = mod.read_registry(self.path)
        self.assertEqual(len(reg), 2)
        self.assertEqual(reg['shared/A.kt']['class'], 'PORTED')
        self.assertEqual(reg['shared/A.kt']['note'], 'rewrite')

    def test_rejects_an_unknown_class(self):
        """A typo in the class must not read as ORIGINAL and pass silently."""
        self.path.write_text('shared/A.kt\tLIFTED\t-\t-\t-\n', encoding='utf-8')
        with self.assertRaises(mod.Violation):
            mod.read_registry(self.path)

    def test_rewritten_is_a_known_class(self):
        """
        `REWRITTEN` marks a file that WAS derived from a copyleft source and no longer is.

        It exists so the derivative history stays visible in the registry instead of being
        quietly promoted to ORIGINAL once the rewrite lands. A reader must be able to see
        that Orgzly was in the lineage even though the code is now the project's own.
        """
        self.path.write_text('shared/A.kt\tREWRITTEN\tOrgzly\tGPL-3.0\treimplemented from spec\n',
                             encoding='utf-8')
        self.assertEqual(mod.read_registry(self.path)['shared/A.kt']['class'], 'REWRITTEN')

    def test_rejects_a_duplicate_row(self):
        self.path.write_text(
            'shared/A.kt\tADAPTED\t-\t-\t-\nshared/A.kt\tPORTED\t-\t-\t-\n', encoding='utf-8')
        with self.assertRaises(mod.Violation):
            mod.read_registry(self.path)

    def test_rejects_a_truncated_row(self):
        self.path.write_text('shared/A.kt\n', encoding='utf-8')
        with self.assertRaises(mod.Violation):
            mod.read_registry(self.path)

    def test_rejects_an_empty_registry(self):
        """A check over nothing is the vacuous-gate pattern in its purest form."""
        self.path.write_text('# only a comment\n', encoding='utf-8')
        with self.assertRaises(mod.Violation):
            mod.read_registry(self.path)


class CheckBehaviourTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.registry = {
            'shared/src/commonMain/kotlin/com/example/A.kt': {
                'class': 'PORTED', 'source': 'Orgzly', 'licence': 'GPL-3.0', 'note': '-'},
            'shared/src/commonMain/kotlin/com/example/B.kt': {
                'class': 'SPEC-COMPATIBLE', 'source': '-', 'licence': '-', 'note': '-'},
        }

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_marked_unregistered_file_is_reported(self):
        p = write(self.root / 'shared/src/commonMain/kotlin/com/example/C.kt', MARKED)
        violations = mod.check_markers_registered(self.root, self.registry)
        self.assertEqual(len(violations), 1)
        self.assertIn('C.kt', violations[0].args[0])

    def test_a_registered_marked_file_is_not_reported(self):
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt', MARKED)
        self.assertEqual(mod.check_markers_registered(self.root, self.registry), [])

    def test_a_registry_row_for_a_deleted_file_is_reported(self):
        violations = mod.check_registry_targets_exist(self.root, self.registry)
        self.assertEqual(len(violations), 2)
        self.assertIn('does not exist', violations[0].args[0])

    def test_a_ported_file_must_carry_the_annotation(self):
        p = write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt', MARKED)
        violations = mod.check_annotations(self.root, self.registry)
        self.assertEqual(len(violations), 1)
        self.assertIn('no `// Provenance: PORTED`', violations[0].args[0])

    def test_a_matching_annotation_passes(self):
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt',
              'package p\n\n// Provenance: PORTED — Orgzly (GPL-3.0).\nclass T\n')
        self.assertEqual(mod.check_annotations(self.root, self.registry), [])

    def test_an_annotation_contradicting_the_registry_is_reported(self):
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt',
              'package p\n\n// Provenance: ADAPTED — Tasks.org (GPL-3.0).\nclass T\n')
        violations = mod.check_annotations(self.root, self.registry)
        self.assertEqual(len(violations), 1)
        self.assertIn('they must agree', violations[0].args[0])

    def test_a_rewritten_file_must_carry_the_annotation(self):
        """`REWRITTEN` is a claim about provenance, so it has to be visible in the file."""
        reg = {'shared/src/commonMain/kotlin/com/example/A.kt': {
            'class': 'REWRITTEN', 'source': 'Orgzly', 'licence': 'GPL-3.0', 'note': '-'}}
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt', MARKED)
        violations = mod.check_annotations(self.root, reg)
        self.assertEqual(len(violations), 1)
        self.assertIn('REWRITTEN', violations[0].args[0])

    def test_a_spec_compatible_file_needs_no_annotation(self):
        """Marking every file would train the annotation to mean nothing."""
        write(self.root / 'shared/src/commonMain/kotlin/com/example/B.kt', MARKED)
        self.assertEqual(mod.check_annotations(self.root, self.registry), [])

    def test_a_vacuous_scan_is_reported(self):
        """If nothing matches anywhere, rule 1 verified nothing and must say so."""
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt', PLAIN)
        write(self.root / 'shared/src/commonMain/kotlin/com/example/B.kt', PLAIN)
        violations = mod.check_not_vacuous(self.root)
        self.assertEqual(len(violations), 1)
        self.assertIn('vacuous', violations[0].args[0])

    def test_a_non_vacuous_scan_is_silent(self):
        write(self.root / 'shared/src/commonMain/kotlin/com/example/A.kt', MARKED)
        self.assertEqual(mod.check_not_vacuous(self.root), [])


class SelfTestEntryPointTest(unittest.TestCase):
    def test_self_test_passes(self):
        """`--self-test` is what CI runs to prove the rule still fires."""
        self.assertEqual(mod.self_test(), 0)


if __name__ == '__main__':
    unittest.main()
