"""Tests for `scripts/document_corpus.py` — the reader of the document corpora.

The policy in `config/docs/document-corpus.json` is read by this reader and by the
Kotlin search engine, and `DocumentCorpusReadersAgreeTest` compares what the two
find. These tests check the reader's own rules: what a document is, how deep the walk
goes, and what happens when the policy file is not there.

The fallback matters as much as the rules. A checkout without the policy must still
answer the same question the Kotlin reader answers, or the agreement test fails on a
machine that simply has not got the file — and a test that fails for that reason is a
test people learn to ignore.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


corpus = _load('document_corpus', 'document_corpus.py')


class PolicyTest(unittest.TestCase):
    """What the policy says."""

    def test_the_policy_declares_the_document_corpora(self):
        config = corpus.load()

        self.assertEqual(6, config.max_depth)
        self.assertEqual('.md', config.file_extension)
        for scope in ('architecture', 'skills', 'specs'):
            self.assertTrue(config.is_declared(scope), f'{scope} is not declared')
            self.assertTrue(config.roots_for(scope), f'{scope} has no roots')

    def test_a_scope_that_is_not_declared_has_no_roots(self):
        config = corpus.load()

        self.assertFalse(config.is_declared('nonexistent'))
        self.assertEqual([], config.roots_for('nonexistent'))

    def test_a_checkout_without_the_policy_falls_back_to_the_same_roots(self):
        """The Kotlin reader falls back too, so the two must fall back alike."""
        with tempfile.TemporaryDirectory() as empty:
            config = corpus.load(pathlib.Path(empty))

            self.assertEqual(
                corpus.DEFAULT_ROOTS,
                config.roots,
                'the fallback must be the roots the search used to name in code',
            )
            self.assertEqual(corpus.DEFAULT_MAX_DEPTH, config.max_depth)
            self.assertEqual(corpus.DEFAULT_FILE_EXTENSION, config.file_extension)


class WalkTest(unittest.TestCase):
    """What the reader finds."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = pathlib.Path(self._tmp.name)
        self.config = corpus.DocumentCorpusConfig(
            max_depth=2,
            file_extension='.md',
            roots={'architecture': ['docs/architecture']},
        )

    def _write(self, relative: str) -> pathlib.Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('# A document\n', encoding='utf-8')
        return path

    def test_a_document_is_found(self):
        self._write('docs/architecture/one.md')

        paths = corpus.relative_paths(self.root, self.config, 'architecture')

        self.assertEqual(['docs/architecture/one.md'], paths)

    def test_a_file_that_is_not_markdown_is_not_a_document(self):
        self._write('docs/architecture/one.md')
        (self.root / 'docs' / 'architecture' / 'notes.txt').write_text('x', encoding='utf-8')

        paths = corpus.relative_paths(self.root, self.config, 'architecture')

        self.assertEqual(['docs/architecture/one.md'], paths)

    def test_the_walk_stops_at_the_depth_bound(self):
        """The bound is the depth of the *directory* a document sits in.

        The Kotlin walk lists a directory while its depth is not past the bound, and
        the documents in it are read — so with a bound of 2, a document in the third
        directory below the root is read, and one in the fourth is not.
        """
        self._write('docs/architecture/one.md')
        self._write('docs/architecture/a/two.md')
        self._write('docs/architecture/a/b/three.md')
        self._write('docs/architecture/a/b/c/four.md')

        paths = corpus.relative_paths(self.root, self.config, 'architecture')

        self.assertIn('docs/architecture/a/b/three.md', paths)
        self.assertNotIn(
            'docs/architecture/a/b/c/four.md',
            paths,
            'the walk must stop at the bound the policy declares',
        )

    def test_a_root_that_does_not_exist_is_not_an_error(self):
        """A missing directory is a scope with nothing in it, not a crash."""
        paths = corpus.relative_paths(self.root, self.config, 'architecture')

        self.assertEqual([], paths)

    def test_the_paths_are_relative_and_use_forward_slashes(self):
        self._write('docs/architecture/nested/one.md')

        paths = corpus.relative_paths(self.root, self.config, 'architecture')

        self.assertEqual(['docs/architecture/nested/one.md'], paths)
        for path in paths:
            self.assertNotIn('\\', path)


if __name__ == '__main__':
    unittest.main()
