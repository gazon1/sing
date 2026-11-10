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
    it as "the push carries findings" — the safe direction;
  - a run that failed without reporting anything did not find anything, so
    the two runs cannot be compared — the exit code of each run is part of
    what is compared, not only the text it printed.
"""

import importlib.util
import os
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
        new = mod.compare_outputs(str(pushed), str(base), None, None, 1, 1)
        self.assertEqual([], new)

    def test_a_finding_the_base_does_not_have_is_the_push_s_own(self):
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', '')
        new = mod.compare_outputs(str(pushed), str(base), None, None, 1, 0)
        self.assertEqual(['A.kt: rule'], new)

    def test_a_finding_that_changed_is_the_push_s_own(self):
        """The same file, a different rule: that is a different finding."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', 'A.kt:1:1: other\n')
        new = mod.compare_outputs(str(pushed), str(base), None, None, 1, 1)
        self.assertEqual(['A.kt: rule'], new)

    def test_a_missing_output_is_the_push_s_own(self):
        """A file that cannot be read says nothing about what it held."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        new = mod.compare_outputs(str(pushed), str(self.tmp / 'absent.log'), None, None, 1, 0)
        self.assertEqual(['A.kt: rule'], new)

    def test_a_gate_that_passed_carries_nothing(self):
        """A gate that passed found nothing, so there is nothing to compare."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', 'A.kt:1:1: rule\n')
        self.assertEqual([], mod.compare_outputs(str(pushed), str(base), None, None, 0, 1))

    def test_a_pushed_run_that_failed_without_reporting_anything_cannot_be_compared(self):
        """A gate that broke did not find anything — it said nothing.

        Both runs failing without printing anything used to compare as equal,
        and the push was accepted on the strength of a gate that validated
        neither tree. A comparison that could not be made is not a comparison
        that found nothing new.
        """
        pushed = self._write('pushed.log', '')
        base = self._write('base.log', '')
        self.assertIsNone(mod.compare_outputs(str(pushed), str(base), None, None, 1, 1))

    def test_a_base_run_that_failed_without_reporting_anything_cannot_be_compared(self):
        """The base tree broke as well, so it cannot account for anything."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', '')
        self.assertIsNone(mod.compare_outputs(str(pushed), str(base), None, None, 1, 1))

    def test_a_base_that_passed_makes_every_finding_the_push_s_own(self):
        """The base gate passed: it has no findings of its own."""
        pushed = self._write('pushed.log', 'A.kt:1:1: rule\n')
        base = self._write('base.log', '0 blocking finding(s)\n')
        new = mod.compare_outputs(str(pushed), str(base), None, None, 1, 0)
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


class BaseTreeRunTest(unittest.TestCase):
    """The failing tests are re-run on the base tree, and read from its output."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-ratchet-'))
        tests = self.tmp / 'scripts' / 'tests'
        tests.mkdir(parents=True)
        # A checkout carries its own packages: the base run is bound to them,
        # and a base tree without any is a tree that cannot be measured.
        package = self.tmp / 'scripts' / 'src' / 'sgate'
        package.mkdir(parents=True)
        (package / '__init__.py').write_text('MARKER = "BASE"\n', encoding='utf-8')
        (tests / 'test_sample.py').write_text(
            'import unittest\n'
            '\n'
            '\n'
            'class SampleTest(unittest.TestCase):\n'
            '    def test_a_plain_failure(self):\n'
            '        self.fail("boom")\n'
            '\n'
            '    def test_a_sub_test_fails(self):\n'
            '        for i in (1, 2):\n'
            '            with self.subTest(i=i):\n'
            '                self.fail("boom")\n',
            encoding='utf-8',
        )

    def test_a_failure_is_read_as_the_failure_of_its_test(self):
        failed = mod.run_tests_on_base(
            str(self.tmp),
            ['test_sample.SampleTest.test_a_plain_failure'],
        )
        self.assertIn('test_sample.SampleTest.test_a_plain_failure', failed)

    def test_a_failure_with_sub_test_parameters_is_the_failure_of_the_test(self):
        """The parameters belong to the sub-test, the failure belongs to the test.

        `unittest` prints the parameters after the id — `FAIL: test_x
        (mod.Class.test_x) (i=1)` — and a reader that stops at the id is right
        about the test and wrong about the line: a line that does not parse is
        a failure the base tree cannot be said to have, and the push is then
        blocked on somebody else's debt.
        """
        failed = mod.run_tests_on_base(
            str(self.tmp),
            ['test_sample.SampleTest.test_a_sub_test_fails'],
        )
        self.assertIn('test_sample.SampleTest.test_a_sub_test_fails', failed)


class BaseTreeBindingTest(unittest.TestCase):
    """The base run measures the base tree, whatever the environment serves.

    The packages are installed editable, so the environment decides which
    checkout answers `import sgate`. The legacy form of that install puts the
    checkout at the **front of `sys.path`** — in front of `PYTHONPATH` — so a
    base run that only sets `PYTHONPATH` measures another checkout's code,
    and a failure it reports there is the other checkout's failure. The base
    run therefore binds itself to the base tree, the way the gate suite binds
    itself to the tree it runs in.
    """

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-ratchet-'))
        self.base = self.tmp / 'base'
        self.foreign = self.tmp / 'foreign'
        self._package(self.base, 'BASE')
        self._package(self.foreign, 'FOREIGN')
        self._test_module()

    def _package(self, root: pathlib.Path, marker: str) -> None:
        """A checkout with a package of its own."""
        package = root / 'scripts' / 'src' / 'sgate'
        package.mkdir(parents=True)
        (package / '__init__.py').write_text(f'MARKER = "{marker}"\n', encoding='utf-8')

    def _test_module(self) -> None:
        """A test that fails when another checkout's package answers."""
        tests = self.base / 'scripts' / 'tests'
        tests.mkdir(parents=True)
        (tests / 'test_sample.py').write_text(
            'import unittest\n'
            'import sgate\n'
            '\n'
            '\n'
            'class SampleTest(unittest.TestCase):\n'
            '    def test_the_base_trees_own_package_answers(self):\n'
            '        self.assertEqual(sgate.MARKER, "BASE")\n',
            encoding='utf-8',
        )

    def _foreign_install(self) -> None:
        """An editable install of another checkout, in the legacy form.

        It is a user site of its own, so the developer's own install is not
        in the picture, and it does the two things an editable install does:
        it puts the checkout at the front of `sys.path`, and it registers a
        meta-path finder for the package.
        """
        site = (
            self.tmp
            / 'userbase'
            / 'lib'
            / f'python{sys.version_info[0]}.{sys.version_info[1]}'
            / 'site-packages'
        )
        site.mkdir(parents=True)
        (site / 'foreign_editable_install.py').write_text(
            'import sys\n'
            'import importlib.util\n'
            '\n'
            f'FOREIGN = {str(self.foreign / "scripts" / "src")!r}\n'
            '\n'
            'sys.path.insert(0, FOREIGN)\n'
            '\n'
            '\n'
            'class ForeignFinder:\n'
            '    @classmethod\n'
            '    def find_spec(cls, name, path=None, target=None):\n'
            '        if name != "sgate":\n'
            '            return None\n'
            '        return importlib.util.spec_from_file_location(\n'
            '            name, FOREIGN + "/sgate/__init__.py")\n'
            '\n'
            '\n'
            'sys.meta_path.append(ForeignFinder)\n',
            encoding='utf-8',
        )
        (site / 'easy-install.pth').write_text('import foreign_editable_install\n', encoding='utf-8')
        saved = os.environ.get('PYTHONUSERBASE')
        os.environ['PYTHONUSERBASE'] = str(self.tmp / 'userbase')
        self.addCleanup(self._restore_userbase, saved)

    def _restore_userbase(self, saved: str | None) -> None:
        if saved is None:
            os.environ.pop('PYTHONUSERBASE', None)
        else:
            os.environ['PYTHONUSERBASE'] = saved

    def test_the_base_run_measures_the_base_tree_and_not_another_checkout(self):
        """A foreign editable install must not decide what the base run measures.

        Without the binding, the base run imports the other checkout's
        `sgate`, the base tree's own test fails there, and the failure is
        read as "the base tree fails this test as well" — the push's own
        failure is called somebody else's, and the push is waved through.
        """
        self._foreign_install()
        failed = mod.run_tests_on_base(
            str(self.base),
            ['test_sample.SampleTest.test_the_base_trees_own_package_answers'],
        )
        self.assertEqual(
            set(),
            failed,
            'the base run measured another checkout, and its failure was called the base tree’s',
        )

    def test_a_base_tree_without_packages_of_its_own_cannot_be_compared(self):
        """A base tree that cannot be bound is not measured at all.

        The comparison cannot be made then, and a comparison that could not be
        made must not be read as "the push carries nothing of its own".
        """
        bare = self.tmp / 'bare'
        (bare / 'scripts' / 'tests').mkdir(parents=True)
        (bare / 'scripts' / 'tests' / 'test_sample.py').write_text('', encoding='utf-8')
        with self.assertRaises(mod.ComparisonUnavailable):
            mod.run_tests_on_base(str(bare), ['test_sample.SampleTest.test_x'])

    def test_a_test_that_does_not_exist_on_the_base_is_not_pre_existing(self):
        """A test the base tree does not have cannot be the base tree's failure.

        The base tree is asked about a test it does not carry, and answers
        that the test is not there — which is the answer the caller needs.
        """
        failed = mod.run_tests_on_base(str(self.base), ['test_other.OtherTest.test_x'])
        self.assertNotIn(
            'test_other.OtherTest.test_x',
            failed,
            'a test the base tree does not have was called the base tree’s failure',
        )

    def test_a_base_module_that_fails_to_import_is_not_pre_existing(self):
        """A base tree whose module cannot be imported says nothing about the test.

        The import error is reported for the module, not for the test inside
        it, so it cannot account for the test's failure: the failure is the
        push's own until somebody can say otherwise.
        """
        (self.base / 'scripts' / 'tests' / 'test_broken.py').write_text(
            'import a_module_that_is_not_there\n',
            encoding='utf-8',
        )
        failed = mod.run_tests_on_base(str(self.base), ['test_broken.BrokenTest.test_x'])
        self.assertNotIn(
            'test_broken.BrokenTest.test_x',
            failed,
            'a module that could not be imported was called the base tree’s failure',
        )


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
        result = self._run(
            'outputs', '--pushed', str(pushed), '--base', str(base),
            '--pushed-rc', '1', '--base-rc', '1',
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_outputs_names_what_the_push_adds(self):
        pushed = self.tmp / 'pushed.log'
        pushed.write_text('A.kt:1:1: rule\n', encoding='utf-8')
        base = self.tmp / 'base.log'
        base.write_text('', encoding='utf-8')
        result = self._run(
            'outputs', '--pushed', str(pushed), '--base', str(base),
            '--pushed-rc', '1', '--base-rc', '0',
        )
        self.assertEqual(1, result.returncode)
        self.assertIn('A.kt: rule', result.stdout)

    def test_outputs_returns_two_when_the_gate_failed_without_reporting_anything(self):
        """The gate broke in both trees: the two answers cannot be compared.

        This is the fail-open the review found. Both runs exited nonzero
        without printing anything, the two normalised outputs were equal
        (both empty), and the comparison reported nothing new — so the push
        was accepted on the strength of a gate that validated neither tree.
        """
        pushed = self.tmp / 'pushed.log'
        pushed.write_text('', encoding='utf-8')
        base = self.tmp / 'base.log'
        base.write_text('', encoding='utf-8')
        result = self._run(
            'outputs', '--pushed', str(pushed), '--base', str(base),
            '--pushed-rc', '1', '--base-rc', '1',
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)

    def test_outputs_returns_two_when_the_base_gate_failed_without_reporting_anything(self):
        """The base tree broke too, so it cannot account for the findings."""
        pushed = self.tmp / 'pushed.log'
        pushed.write_text('A.kt:1:1: rule\n', encoding='utf-8')
        base = self.tmp / 'base.log'
        base.write_text('', encoding='utf-8')
        result = self._run(
            'outputs', '--pushed', str(pushed), '--base', str(base),
            '--pushed-rc', '1', '--base-rc', '1',
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)

    def test_tests_returns_two_when_the_report_cannot_be_read(self):
        result = self._run(
            'tests',
            '--report',
            str(self.tmp / 'absent.xml'),
            '--base-tree',
            str(self.tmp),
        )
        self.assertEqual(2, result.returncode)

    def test_tests_returns_two_when_the_base_tree_cannot_be_measured(self):
        """A base tree that cannot be bound is not compared, and not called clean.

        The comparison could not be made, so the caller must stop the push —
        reading it as "the push carries nothing" would accept a push on the
        strength of a measurement that never happened.
        """
        report = self.tmp / 'report.xml'
        report.write_text(
            '<testsuites><testsuite><testcase classname="mod.Class" name="test_x">'
            '<failure message="boom"/></testcase></testsuite></testsuites>',
            encoding='utf-8',
        )
        base = self.tmp / 'base'
        (base / 'scripts' / 'tests').mkdir(parents=True)
        (base / 'scripts' / 'tests' / 'test_sample.py').write_text('', encoding='utf-8')
        result = self._run(
            'tests',
            '--report',
            str(report),
            '--base-tree',
            str(base),
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn('cannot be made', result.stdout)

    def test_tests_returns_two_when_the_report_names_no_failing_test(self):
        """A suite that failed without a failing test cannot be compared.

        The report is empty of failures, so it cannot say which test the
        failure belongs to — and a comparison that could not be made must
        not be read as "the push carries nothing of its own".
        """
        report = self.tmp / 'report.xml'
        report.write_text('<testsuites></testsuites>\n', encoding='utf-8')
        result = self._run(
            'tests',
            '--report',
            str(report),
            '--base-tree',
            str(self.tmp),
        )
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
