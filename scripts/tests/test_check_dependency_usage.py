#!/usr/bin/env python3
"""Tests for check-dependency-usage.py.

The gate answers a question the version catalog cannot: a Gradle coordinate does
not determine an import package, so `org.jetbrains.compose.material3:material3` is
imported as `androidx.compose.material3` and `io.insert-koin:koin-core` as
`org.koin.core`. #205 measured that the naive prefix heuristic reports 45 unused
dependencies on this tree and every one of the 45 is used — a 46%-false-positive
gate is worse than none, because it trains everyone to ignore it.

Two bugs shaped this file, and both are the same shape: the gate reported green
over something it never examined.

  * Only `.class` entries were read. A KMP library's compiled code reaches the
    classpath as a `.klib`, packaged inside the metadata jar as
    `linkdata/package_<fqcn>/`. Reading classes alone made every KMP dependency
    contribute *no* package, so the gate skipped exactly the majority it was
    written to check — and `material-kolor`, the dependency this all started
    from, is one of them.
  * The scan floor is checked before the comparison, so a run that resolved
    nothing reports a measurement failure rather than "no findings".

The claim-shape logic itself (what counts as a file describing its own platform)
lives in PlatformClaimWiringTest, next to the code it governs.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / 'check-dependency-usage.py'

_spec = importlib.util.spec_from_file_location('check_dependency_usage', SCRIPT)
cdu = importlib.util.module_from_spec(_spec)
sys.modules['check_dependency_usage'] = cdu
_spec.loader.exec_module(cdu)


def make_jar(path: pathlib.Path, entries: list[str]) -> pathlib.Path:
    """A jar containing exactly `entries` (no real classes needed)."""
    with zipfile.ZipFile(path, 'w') as archive:
        for name in entries:
            archive.writestr(name, 'x')
    return path


def make_aar(path: pathlib.Path, jar_entries: list[str]) -> pathlib.Path:
    """An .aar whose classes.jar contains `jar_entries`."""
    inner = pathlib.Path(tempfile.mkdtemp()) / 'classes.jar'
    make_jar(inner, jar_entries)
    with zipfile.ZipFile(path, 'w') as archive:
        archive.writestr('AndroidManifest.xml', 'x')
        archive.write(inner, 'classes.jar')
    return path


class PackageRootsTest(unittest.TestCase):
    """What an artifact contributes is read from its contents, not its name."""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())

    def test_reads_jvm_class_layout(self):
        jar = make_jar(
            self.tmp / 'a.jar',
            ['com/example/Foo.class', 'com/example/Bar.class', 'META-INF/MANIFEST.MF'],
        )
        self.assertEqual(cdu.package_roots(jar), {'com.example'})

    def test_reads_kmp_linkdata(self):
        # The shape that made every KMP dependency look package-free.
        jar = make_jar(
            self.tmp / 'k.jar',
            ['META-INF/kotlin-project-structure-metadata.json',
             'commonMain/default/linkdata/package_com.mikepenz.markdown.m3/0_m3.knm'],
        )
        self.assertEqual(cdu.package_roots(jar), {'com.mikepenz.markdown.m3'})

    def test_reads_aar_classes_jar(self):
        aar = make_aar(self.tmp / 'b.aar', ['androidx/backup/Thing.class'])
        self.assertEqual(cdu.package_roots(aar), {'androidx.backup'})

    def test_missing_file_is_empty_not_an_error(self):
        self.assertEqual(cdu.package_roots(self.tmp / 'nope.jar'), set())

    def test_non_zip_is_empty_not_a_crash(self):
        path = self.tmp / 'corrupt.jar'
        path.write_bytes(b'not a zip at all')
        self.assertEqual(cdu.package_roots(path), set())


class AllowlistTest(unittest.TestCase):
    """The list is debt, so it may shrink and may not grow."""

    def test_comments_and_blanks_are_not_entries(self):
        with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False) as handle:
            handle.write('# a comment\n\ncom.example:one  # trailing\n')
            path = pathlib.Path(handle.name)
        self.addCleanup(path.unlink)
        entries, _ = cdu.read_allowlist(path)
        self.assertEqual(entries, {'com.example:one'})

    def test_shipped_allowlist_matches_its_ceiling(self):
        entries, ceiling = cdu.load_allowlist()
        self.assertIsNotNone(ceiling, 'the ceiling is what makes the list a ratchet')
        self.assertEqual(len(entries), ceiling)
        self.assertTrue(entries, 'an empty allowlist with a ceiling of 0 is a gate that hides')

    def test_shipped_entries_are_well_formed(self):
        entries, _ = cdu.load_allowlist()
        for entry in entries:
            self.assertRegex(entry, r'^[\w.-]+:[\w.-]+$', entry)


if __name__ == '__main__':
    unittest.main()