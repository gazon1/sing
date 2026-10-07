"""Tests for check-yaml-duplicate-keys.py.

The gate stops one thing: a YAML mapping that repeats a key. Three ways it could
pass while measuring nothing, and each has a test here.

  - a parser that stopped rejecting repeats. Covered by DuplicateFiresTest, which
    feeds shapes that *do* contain one.
  - a checker so eager it reports the legal shapes — a repeated key in two
    documents of one multi-document stream, which is the Maestro flow format and
    is most of this repository's YAML. Covered by LegalShapeTest. A gate that
    cries wolf gets switched off, and a gate that is switched off protects
    nothing; this is the failure mode that killed the earlier regex prototype.
  - a checker that only inspects the root mapping, so a nested repeat passes.
    Covered explicitly in DuplicateFiresTest, because that is the shape a
    plausible implementation misses and it looks like it works.

The nested case is not hypothetical: `detekt-rules-module.yml` had `Filename:`
twice inside the `ktlint:` mapping, not at the root, and :detekt-rules:detekt
refused to load its config because of it.
"""

import importlib.util
import pathlib
import subprocess
import sys
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = REPO / 'scripts' / 'check-yaml-duplicate-keys.py'
spec = importlib.util.spec_from_file_location('check_yaml_duplicate_keys', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class DuplicateFiresTest(unittest.TestCase):
    """A repeated key must be reported, at any depth."""

    def _assert_duplicate(self, text: str, expected_key: object = None) -> None:
        with self.assertRaises(mod.DuplicateKeyError) as caught:
            mod.parse_documents(text)
        if expected_key is not None:
            self.assertEqual(caught.exception.key, expected_key)

    def test_top_level_repeat(self):
        self._assert_duplicate(
            'a: 1\nFilename:\n  active: false\nFilename:\n  active: true\n',
            expected_key='Filename',
        )

    def test_nested_repeat_is_not_missed(self):
        # The real instance: the repeat was inside `ktlint:`, two levels down. A
        # checker that only inspects the root mapping reports this as clean.
        self._assert_duplicate(
            'ktlint:\n  standard:\n    Filename:\n      active: true\n'
            '    Filename:\n      active: false\n',
            expected_key='Filename',
        )

    def test_repeat_of_the_same_value_still_fires(self):
        # A repeat that changes nothing is still a repeat: the file disagrees
        # with itself about how many times the author meant to say it.
        self._assert_duplicate('a: 1\na: 1\n', expected_key='a')

    def test_repeat_inside_one_document_of_a_stream(self):
        # Multi-document support must not have weakened the check.
        self._assert_duplicate('appId: com.example\n---\nname: a\nname: b\n', expected_key='name')

    def test_three_way_repeat(self):
        self._assert_duplicate('k: 1\nk: 2\nk: 3\n', expected_key='k')

    def test_sequence_of_mappings(self):
        # A list of mappings is not one mapping: every element repeats the same
        # key names, and that is the normal shape of a Maestro step list.
        mod.parse_documents('- tapOn:\n    id: a\n- tapOn:\n    id: b\n')


class LegalShapeTest(unittest.TestCase):
    """The shapes that must stay quiet, or the gate gets switched off."""

    def test_multi_document_stream(self):
        # Maestro's own format: a header document, then the flow body, separated
        # by `---`. `name` appears in both documents legitimately.
        mod.parse_documents('appId: com.example\nname: flow\n---\n- tapOn:\n    id: a\n')

    def test_same_key_name_in_two_sibling_mappings(self):
        mod.parse_documents('first:\n  name: a\nsecond:\n  name: b\n')

    def test_clean_nested_document(self):
        mod.parse_documents('a:\n  b: 1\n  c: 2\nd:\n  - 1\n  - 2\n')

    def test_empty_stream(self):
        self.assertEqual(mod.parse_documents(''), 0)

    def test_repeated_document_separator(self):
        mod.parse_documents('---\na: 1\n---\nb: 2\n')


class RepositoryIsCleanTest(unittest.TestCase):
    """The real tree, through the real entry point."""

    def test_no_tracked_yaml_has_a_duplicate_key(self):
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), '--quiet'],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(
            result.returncode, 0,
            f'the repository has a duplicate key:\n{result.stdout}\n{result.stderr}',
        )

    def test_the_scan_is_not_vacuous(self):
        """An empty file list must fail, not pass.

        The vacuous-gate failure: a glob that stops matching reports success having
        checked nothing. The gate refuses to report clean on zero files.
        """
        files = mod.tracked_yaml_files(REPO)
        self.assertGreater(len(files), 0, 'the gate found no YAML at all; it would verify nothing')
        # And every file it claims to check is a real one.
        for path in files:
            self.assertTrue(path.exists(), f'{path} was listed but does not exist')


class SelfTestEntryPointTest(unittest.TestCase):
    """`--self-test` is what the gate registry calls to prove it can fail."""

    def test_self_test_exits_zero(self):
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), '--self-test'],
            capture_output=True, text=True, cwd=str(REPO),
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_self_test_would_fail_on_a_broken_detector(self):
        """The self-test is itself a claim, so break the detector and check it says so.

        Without this, a self-test that has been reduced to `return 0` is
        indistinguishable from one that verifies something.
        """
        saved = mod._no_duplicate_keys
        # A loader that never rejects anything is PyYAML's own behaviour.
        mod._no_duplicate_keys = lambda: __import__('yaml').SafeLoader
        try:
            self.assertEqual(mod.self_test(), 1)
        finally:
            mod._no_duplicate_keys = saved
        self.assertEqual(mod.self_test(), 0, 'restored detector must pass again')


if __name__ == '__main__':
    unittest.main()