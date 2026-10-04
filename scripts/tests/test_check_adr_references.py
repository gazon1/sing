"""Tests for check-adr-references.py.

The gate exists so ADR archiving and deletion in a later phase cannot silently break
links. Two things it must get right:

  - a dated-ADR token with no matching file is an error (it is the whole point)
  - it must not fire on generated or archived files, or it becomes noise and gets
    disabled — which is how the size-budget gate ended up running with --warn-only.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-adr-references.py'
spec = importlib.util.spec_from_file_location('check_adr_references', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class TokenRegexTest(unittest.TestCase):
    def test_matches_a_dated_slug(self):
        m = mod.TOKEN_RE.search('see 2026-09-27-di-module-aggregator-narrative for detail')
        self.assertEqual(m.group(1), '2026-09-27-di-module-aggregator-narrative')

    def test_ignores_a_bare_date(self):
        """`2026-10-05` is not a reference to an ADR; it is a date."""
        self.assertIsNone(mod.TOKEN_RE.search('merged on 2026-10-05 after review'))

    def test_ignores_a_version_like_string(self):
        self.assertIsNone(mod.TOKEN_RE.search('uses kotlinx-datetime-0.6.2 today'))


class ResolutionTest(unittest.TestCase):
    def test_existing_slugs_include_the_archive_subdirectory(self):
        """An archived ADR is still a resolvable reference — moving is not deleting."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'docs' / 'decisions').mkdir(parents=True)
            (root / 'docs' / 'decisions' / '2026-01-01-live.md').write_text('x')
            archive = root / 'docs' / 'decisions' / 'archive'
            archive.mkdir()
            (archive / '2026-01-02-archived.md').write_text('x')
            old = mod.DECISIONS_DIR
            mod.DECISIONS_DIR = root / 'docs' / 'decisions'
            try:
                slugs = mod.existing_slugs()
            finally:
                mod.DECISIONS_DIR = old
        self.assertIn('2026-01-01-live', slugs)
        self.assertIn('2026-01-02-archived', slugs)

    def test_md_suffix_and_prefix_are_tolerated(self):
        """Prose cites ADRs as `slug.md` or `docs/decisions/slug.md`."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            d = root / 'docs' / 'decisions'
            d.mkdir(parents=True)
            (d / '2026-01-01-real.md').write_text('x')
            doc = root / 'AGENTS.md'
            doc.write_text('see 2026-01-01-real.md and docs/decisions/2026-01-01-real.md\n')
            old_dec, old_scan = mod.DECISIONS_DIR, mod.iter_sources
            mod.DECISIONS_DIR = d
            mod.SCAN_FILES = [doc]
            mod.SCAN_GLOBS = []
            try:
                dangling = mod.find_dangling(mod.existing_slugs())
            finally:
                mod.DECISIONS_DIR, mod.iter_sources = old_dec, old_scan
        self.assertEqual(dangling, [], f'unexpected dangling refs: {dangling}')


class ExclusionTest(unittest.TestCase):
    def test_generated_digest_is_excluded(self):
        """DIGEST.md is generated and gitignored; scanning it double-counts citations."""
        self.assertIn('DIGEST.md', mod.EXCLUDE_FILES)

    def test_archive_directory_is_excluded_from_scanning(self):
        self.assertIn('archive', mod.EXCLUDE_PARTS)


class CorpusTest(unittest.TestCase):
    def test_the_real_corpus_has_no_new_dangling_refs(self):
        """The shipped baseline must cover every dangling reference in the repo."""
        if not mod.DECISIONS_DIR.is_dir():
            self.skipTest('ADR corpus not present')
        dangling = mod.find_dangling(mod.existing_slugs())
        if not mod.BASELINE.exists():
            self.skipTest('baseline not present')
        accepted = {l.strip() for l in mod.BASELINE.read_text().splitlines()
                    if l.strip() and not l.startswith('#')}
        new = [d for d in dangling if d not in accepted]
        self.assertEqual(new, [], f'{len(new)} unbaselined dangling ref(s): {new[:5]}')


    def test_baseline_keys_are_line_independent(self):
        """A baselined reference must stay baselined when an unrelated line shifts.

        A line-keyed baseline turns any edit above a cited token into a spurious
        failure. On 2026-10-05 a single status-normalization shifted one line and the
        gate went red for a reference that had not changed.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            d = root / 'docs' / 'decisions'
            d.mkdir(parents=True)
            (d / '2026-01-01-real.md').write_text('x')
            doc = root / 'AGENTS.md'
            saved = (mod.DECISIONS_DIR, mod.ROOT, mod.SCAN_FILES, mod.SCAN_GLOBS)
            mod.DECISIONS_DIR, mod.ROOT = d, root
            mod.SCAN_FILES, mod.SCAN_GLOBS = [doc], []
            try:
                doc.write_text('line one\nsee 2026-01-02-missing-adr for detail\n')
                before = mod.find_dangling(mod.existing_slugs())
                doc.write_text('a\nb\nc\nd\nsee 2026-01-02-missing-adr for detail\n')
                after = mod.find_dangling(mod.existing_slugs())
            finally:
                mod.DECISIONS_DIR, mod.ROOT, mod.SCAN_FILES, mod.SCAN_GLOBS = saved
        self.assertEqual(before, after, 'finding changed when only line numbers shifted')
        self.assertEqual(before, ['AGENTS.md:2026-01-02-missing-adr'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
