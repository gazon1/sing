"""Tests for pre-push-ratchet.py — is this failure the push's own?

The hook judges the tree that is being pushed, and a repository's trees are
not green: the trunk itself can carry findings that have nothing to do with
the branch being pushed. The ratchet answers one question — *did the push
bring this, or was it already there?* — and these tests hold that answer.

The properties:

  - a finding the base tree has as well is pre-existing: the push carries
    none, and the push is not blocked;
  - a finding the base tree does not have is the push's own, and it is
    named;
  - what says *where* a finding is (the tree's path, the line, the column,
    the timestamp, the file count) is normalised away, so the same finding in
    two trees is one finding;
  - what says *what* a finding is (the file, the rule, the message) is kept,
    so a finding that moved is the same finding and a finding that changed is
    a new one;
  - the failing tests of the gate suite are compared by their own id, and a
    test that fails on the base tree as well is pre-existing;
  - a comparison that cannot be made says so (exit 2), and the caller treats
    it as "the push carries findings" — the safe direction.
"""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

#: The loader the script uses, imported the way the script imports it.
SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

MODULE_PATH = SCRIPTS / 'pre-push-ratchet.py'
spec = importlib.util.spec_from_file_location('pre_push_ratchet', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class NormalizeTest(unittest.TestCase):
    """What is normalised away, and what is kept."""

    def test_the_position_of_a_finding_is_normalised_away(self):
        """The same finding in two trees is one finding.

        A file grows, a finding moves down the file, and the finding is the
        same one. A comparison that noticed the move would call every
        finding new every time a file above it grew.
        """
        first = mod.normalize('shared/src/A.kt:44:7: TooManyFunctions')
        second = mod.normalize('shared/src/A.kt:51:9: TooManyFunctions')
        self.assertEqual(first, second)

    def test_the_rule_and_the_message_are_kept(self):
        """What says *what* the finding is is what identifies it."""
        lines = mod.normalize('shared/src/A.kt:44:7: TooManyFunctions')
        self.assertIn('shared/src/A.kt: TooManyFunctions', lines)

    def test_the_tree_is_normalised_away(self):
        """Two trees, two paths, one finding."""
        lines = mod.normalize(
            '/tmp/tree-0/shared/src/A.kt:44:7: TooManyFunctions',
            '/tmp/tree-0',
        )
        self.assertEqual(lines, ['<TREE>/shared/src/A.kt: TooManyFunctions'])

    def test_the_timestamp_is_normalised_away(self):
        """Two runs of one gate are minutes apart."""
        first = mod.normalize('23:02:27.339 [main] WARN something')
        second = mod.normalize('23:04:51.001 [main] WARN something')
        self.assertEqual(first, second)

    def test_the_file_count_is_normalised_away(self):
        """The pushed tree has more files than the base whenever it adds any."""
        first = mod.normalize('ktlint-lint: checking 2471 file(s) with x')
        second = mod.normalize('ktlint-lint: checking 2472 file(s) with x')
        self.assertEqual(first, second)

    def test_the_colour_codes_are_normalised_away(self):
        """One run is a terminal, the other is not."""
        coloured = mod.normalize('\x1b[33mA.kt:1:1: rule\x1b[0m')
        plain = mod.normalize('A.kt:1:1: rule')
        self.assertEqual(coloured, plain)


class CompareOutputsTest(unittest.TestCase):
    """Two answers of one gate, compared."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-ratchet-'))
        self.addCleanup(lambda: None)

    def _write(self, name: str, text: str) -> pathlib.Path:
        path = self.tmp / name
        path.write_text(text, encoding='utf-8')
        return path

    def test_a_finding_the_base_has_as_well_is_not_the_push_s_own(self):
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', 'A.kt:9:9: rule\n')
        new = mod.compare_outputs(str(pushed), str(base), None, None)
        self.assertEqual([], new)

    def test_a_finding_the_base_does_not_have_is_the_push_s_own(self):
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', '')
        new = mod.compare_outputs(str(pushed), str(base), None, None)
        self.assertEqual(['A.kt: rule'], new)

    def test_a_finding_that_changed_is_the_push_s_own(self):
        """The same file, a different rule: that is a different finding."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', 'A.kt:1:1: other\n')
        new = mod.compare_outputs(str(pushed), str(base), None, None)
        self.assertEqual(['A.kt: rule'], new)

    def test_a_missing_output_is_the_push_s_own(self):
        """A file that cannot be read says nothing about what it held."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        new = mod.compare_outputs(str(pushed), str(self.tmp / 'absent.log'), None, None)
        self.assertEqual(['A.kt: rule'], new)


class FailingTestIdsTest(unittest.TestCase):
    """The failing tests of the gate suite, from the report."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-ratchet-'))

    def _report(self, body: str) -> str:
        path = self.tmp / 'report.xml'
        path.write_text(body, encoding='utf-8')
        return str(path)

    def test_a_failed_test_is_named_by_its_own_id(self):
        report = self._report(
            '<testsuites><testsuite><testcase classname="mod.Class" name="test_x">'
            '<failure message="boom"/></testcase></testsuite></testsuites>',
        )
        self.assertEqual(['mod.Class.test_x'], mod.failing_test_ids(report))

    def test_a_class_that_failed_to_set_up_is_named_by_the_class(self):
        report = self._report(
            '<testsuites><testsuite><testcase classname="mod.Class" name="setUpClass">'
            '<failure message="boom"/></testcase></testsuite></testsuites>',
        )
        self.assertEqual(['mod.Class'], mod.failing_test_ids(report))

    def test_a_test_that_passed_is_not_named(self):
        report = self._report(
            '<testsuites><testsuite><testcase classname="mod.Class" name="test_x">'
            '</testcase></testsuite></testsuites>',
        )
        self.assertEqual([], mod.failing_test_ids(report))

    def test_a_report_that_cannot_be_read_says_so(self):
        with self.assertRaises(ValueError):
            mod.failing_test_ids(str(self.tmp / 'absent.xml'))


class CoversTest(unittest.TestCase):
    """When a failure on the base accounts for a failure on the pushed tree."""

    def test_the_same_test_covers_itself(self):
        self.assertTrue(mod.covers('mod.Class.test_x', 'mod.Class.test_x'))

    def test_a_module_that_failed_to_set_up_covers_its_tests(self):
        """The tests of a module that failed to set up are reported as failures.

        A module that cannot be set up has no tests that can pass, and the
        report names each of them. The base tree failing the same way
        accounts for all of them.
        """
        self.assertTrue(mod.covers('mod.test_x', 'mod'))
        self.assertTrue(mod.covers('mod.Class.test_x', 'mod'))

    def test_a_different_module_does_not_cover(self):
        self.assertFalse(mod.covers('other.test_x', 'mod'))


class CommandLineTest(unittest.TestCase):
    """The tool as the hook calls it."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-ratchet-'))

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(MODULE_PATH), *args],
            capture_output=True,
            text=True,
        )

    def test_outputs_returns_zero_when_the_push_carries_nothing(self):
        pushed = self.tmp / 'pushed.log'
        pushed.write_text('A.kt:1:1: rule\n', encoding='utf-8')
        base = self.tmp / 'base.log'
        base.write_text('A.kt:4:4: rule\n', encoding='utf-8')
        result = self._run('outputs', '--pushed', str(pushed), '--base', str(base))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_outputs_names_what_the_push_adds(self):
        pushed = self.tmp / 'pushed.log'
        pushed.write_text('A.kt:1:1: rule\n', encoding='utf-8')
        base = self.tmp / 'base.log'
        base.write_text('', encoding='utf-8')
        result = self._run('outputs', '--pushed', str(pushed), '--base', str(base))
        self.assertEqual(1, result.returncode)
        self.assertIn('A.kt: rule', result.stdout)

    def test_tests_returns_two_when_the_report_cannot_be_read(self):
        result = self._run(
            'tests',
            '--report',
            str(self.tmp / 'absent.xml'),
            '--base-tree',
            str(self.tmp),
        )
        self.assertEqual(2, result.returncode)


if __name__ == '__main__':
    unittest.main()
