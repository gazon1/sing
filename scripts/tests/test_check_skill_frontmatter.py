"""Tests for check_skill_frontmatter.py.

The defect this locks down: the previous gate grepped for `^name:` / `^description:`
and reported all 112 skills valid while 15 were unparseable YAML (an unquoted ": "
inside the description value). The key was present; the document did not parse.
"""

import importlib.util
import pathlib
import tempfile
import unittest

import yaml

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check_skill_frontmatter.py'
spec = importlib.util.spec_from_file_location('check_skill_frontmatter', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

# Real shape of the 15 broken skills: a colon inside an unquoted description value.
BROKEN = """---
name: some-skill
description: Use for X: the Y case, and Z.
---

# Body
"""

VALID = """---
name: some-skill
description: "Use for X: the Y case, and Z."
---

# Body
"""


class CheckFileTest(unittest.TestCase):
    def _check(self, content, dirname='some-skill'):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp) / dirname
            d.mkdir()
            path = d / 'SKILL.md'
            path.write_text(content)
            return mod.check_file(path)

    def test_unquoted_colon_in_description_is_an_error(self):
        errors = self._check(BROKEN)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn('not valid YAML', errors[0])

    def test_quoted_colon_passes(self):
        self.assertEqual(self._check(VALID), [])

    def test_missing_frontmatter_is_an_error(self):
        errors = self._check('# No frontmatter here\n')
        self.assertTrue(any('no frontmatter block' in e for e in errors))

    def test_missing_description_is_an_error(self):
        errors = self._check('---\nname: some-skill\n---\n\n# Body\n')
        self.assertTrue(any('description' in e for e in errors))

    def test_empty_description_is_an_error(self):
        errors = self._check('---\nname: some-skill\ndescription: ""\n---\n\n# Body\n')
        self.assertTrue(any('empty' in e for e in errors))

    def test_name_directory_mismatch_is_an_error(self):
        errors = self._check(VALID, dirname='different-dir')
        self.assertTrue(any('does not match directory' in e for e in errors))

    def test_the_real_corpus_is_clean(self):
        """Every shipped SKILL.md must parse. Guards against regressions here."""
        skills_dir = mod.SKILLS_DIR
        if not skills_dir.is_dir():
            self.skipTest('skills dir not present')
        errors = []
        for path in sorted(skills_dir.rglob('SKILL.md')):
            errors.extend(mod.check_file(path))
        self.assertEqual(errors, [], f'{len(errors)} skill(s) have invalid frontmatter')


class FixScopeTest(unittest.TestCase):
    def test_needs_fix_only_for_files_that_actually_fail(self):
        """A correct-but-unquoted description is valid YAML and must be left alone.

        An earlier --fix quoted every unquoted description in the repo — 107 files —
        when only 15 were broken, burying the real change in noise.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp) / 'some-skill'
            d.mkdir()
            good = d / 'SKILL.md'
            good.write_text(VALID)
            self.assertFalse(mod.needs_fix(good))

            bad = d.parent / 'broken-skill'
            bad.mkdir()
            badf = bad / 'SKILL.md'
            badf.write_text(BROKEN)
            self.assertTrue(mod.needs_fix(badf))

    def test_quote_descriptions_preserves_the_value(self):
        fixed = mod.quote_descriptions(BROKEN)
        self.assertIsNotNone(fixed)
        data = yaml.safe_load(mod.split_frontmatter(fixed)[0])
        self.assertEqual(data['description'], 'Use for X: the Y case, and Z.')
        self.assertEqual(data['name'], 'some-skill')
        self.assertIn('# Body', fixed, 'body must not be touched')

    def test_quote_descriptions_is_a_noop_when_already_valid(self):
        self.assertIsNone(mod.quote_descriptions(VALID))


if __name__ == '__main__':
    unittest.main(verbosity=2)
