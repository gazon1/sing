"""Tests for check-ui-wait-budgets.py.

The gate holds one written budget against the layers that wait on it: the
instrumented suite's Kotlin constants and every Maestro wait for the shell.

Three properties matter, and each of them is a way the gate could report green
while the drift it exists for is still there:

  - a wait whose timeout is not the catalogue's is a finding (the drift);
  - a wait with no timeout at all is a finding (the wait falls back to a
    default nobody wrote down);
  - a constant the catalogue does not carry is a finding (a budget nobody
    promised is a budget nobody keeps in step).

The tests build a whole miniature tree in a temp directory and point the
module at it, so nothing here depends on the repository's own numbers — a
budget change in `config/docs/ui-wait-budgets.txt` must not turn these tests
red, and must not be able to turn them green either.
"""

import importlib.util
import pathlib
import tempfile
import unittest

import click

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-ui-wait-budgets.py'
spec = importlib.util.spec_from_file_location('check_ui_wait_budgets', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SHELL_PROBE_SOURCE = """\
package com.singularity.todo.ui

internal const val SHELL_TIMEOUT_MS = 30_000L
internal const val TAB_TIMEOUT_MS = 10_000L
internal const val UI_DEVICE_TIMEOUT_MS = 15_000L
"""


class TreeFixture(unittest.TestCase):
    """A miniature repository: a catalogue, a Kotlin probe and Maestro flows."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.catalogue = self.root / 'ui-wait-budgets.txt'
        self.shell_probe = self.root / 'ShellProbe.kt'
        self.maestro = self.root / 'Maestro'

        self._saved = (
            mod.ROOT,
            mod.CATALOGUE,
            mod.SHELL_PROBE,
            mod.MAESTRO_DIR,
        )
        mod.ROOT = self.root
        mod.CATALOGUE = self.catalogue
        mod.SHELL_PROBE = self.shell_probe
        mod.MAESTRO_DIR = self.maestro

    def tearDown(self):
        mod.ROOT, mod.CATALOGUE, mod.SHELL_PROBE, mod.MAESTRO_DIR = self._saved
        self._tmp.cleanup()

    def write_catalogue(self, text):
        self.catalogue.write_text(text, encoding='utf-8')

    def write_shell_probe(self, text=SHELL_PROBE_SOURCE):
        self.shell_probe.write_text(text, encoding='utf-8')

    def write_flow(self, relative_path, text):
        path = self.maestro / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')


CATALOGUE = """\
# a comment
shell 30000
tab 10000
device 15000
"""

FLOW_AT_BUDGET = """\
appId: com.singularity.todo
---
- extendedWaitUntil:
    visible:
      id: nav_tab_today
    timeout: 30000
"""

FLOW_AT_OTHER_BUDGET = """\
appId: com.singularity.todo
---
- extendedWaitUntil:
    visible:
      id: nav_tab_today
    timeout: 8000
"""

FLOW_WITHOUT_TIMEOUT = """\
appId: com.singularity.todo
---
- extendedWaitUntil:
    visible:
      id: nav_tab_today
"""

FLOW_FOR_OTHER_ELEMENT = """\
appId: com.singularity.todo
---
- extendedWaitUntil:
    visible:
      id: nav_tab_settings
    timeout: 5000
"""


class CatalogueParsingTest(unittest.TestCase):
    def test_comments_and_blank_lines_are_not_budgets(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('# a comment\n\nshell 30000\n', encoding='utf-8')
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                self.assertEqual({'shell': 30000}, mod.catalogue_budgets())
            finally:
                mod.CATALOGUE = original

    def test_a_line_that_is_not_a_budget_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('shell = 30000\n', encoding='utf-8')
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.catalogue_budgets()
            finally:
                mod.CATALOGUE = original

    def test_a_budget_listed_twice_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('shell 30000\nshell 8000\n', encoding='utf-8')
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.catalogue_budgets()
            finally:
                mod.CATALOGUE = original

    def test_an_empty_catalogue_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('# nothing here\n', encoding='utf-8')
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.catalogue_budgets()
            finally:
                mod.CATALOGUE = original


class KotlinConstantParsingTest(unittest.TestCase):
    def test_constants_are_read_including_underscores(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'ShellProbe.kt'
            path.write_text(SHELL_PROBE_SOURCE, encoding='utf-8')
            original = mod.SHELL_PROBE
            mod.SHELL_PROBE = path
            try:
                self.assertEqual(
                    {
                        'SHELL_TIMEOUT_MS': 30000,
                        'TAB_TIMEOUT_MS': 10000,
                        'UI_DEVICE_TIMEOUT_MS': 15000,
                    },
                    mod.kotlin_budgets(),
                )
            finally:
                mod.SHELL_PROBE = original


class ScanTest(TreeFixture):
    def test_a_tree_that_agrees_has_no_findings(self):
        self.write_catalogue(CATALOGUE)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
        self.assertEqual([], mod.scan())

    def test_a_maestro_wait_below_the_budget_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_OTHER_BUDGET)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('shell wait disagrees with the catalogue', findings[0]['kind'])

    def test_a_maestro_wait_without_a_timeout_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_WITHOUT_TIMEOUT)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('shell wait without a timeout', findings[0]['kind'])

    def test_a_wait_for_another_element_is_not_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_FOR_OTHER_ELEMENT)
        self.assertEqual([], mod.scan())

    def test_a_constant_that_drifted_from_the_catalogue_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_shell_probe(
            SHELL_PROBE_SOURCE.replace('SHELL_TIMEOUT_MS = 30_000L', 'SHELL_TIMEOUT_MS = 8_000L')
        )
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('constant disagrees with the catalogue', findings[0]['kind'])

    def test_a_constant_the_catalogue_does_not_carry_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_shell_probe(
            SHELL_PROBE_SOURCE + '\ninternal const val POPUP_TIMEOUT_MS = 5_000L\n'
        )
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('constant is not in the catalogue', findings[0]['kind'])

    def test_a_catalogue_without_a_shell_budget_is_a_finding(self):
        self.write_catalogue('tab 10000\ndevice 15000\n')
        self.write_shell_probe()
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('catalogue has no shell budget', findings[0]['kind'])


class RepositoryAgreementTest(unittest.TestCase):
    """The repository's own numbers agree — the gate's premise, checked."""

    def test_the_committed_tree_has_no_findings(self):
        self.assertEqual([], mod.scan())

    def test_a_root_names_the_tree_that_is_checked(self):
        """The pre-push hook checks the tree it is pushing, not its worktree."""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'config' / 'docs').mkdir(parents=True)
            (root / 'config' / 'docs' / 'ui-wait-budgets.txt').write_text(
                CATALOGUE, encoding='utf-8'
            )
            probe = root / 'androidApp' / 'src' / 'androidTest' / 'kotlin' / 'com' / 'singularity' / 'todo' / 'ui'
            probe.mkdir(parents=True)
            (probe / 'ShellProbe.kt').write_text(SHELL_PROBE_SOURCE, encoding='utf-8')
            flow = root / 'Maestro' / 'flows' / 'smoke'
            flow.mkdir(parents=True)
            (flow / '01.yaml').write_text(FLOW_AT_BUDGET, encoding='utf-8')

            self.assertEqual([], mod.scan(root))

            # A tree whose catalogue disagrees is a finding in that tree — even
            # when the repository this test runs in agrees.
            (root / 'config' / 'docs' / 'ui-wait-budgets.txt').write_text(
                'shell 8000\ntab 10000\ndevice 15000\n', encoding='utf-8'
            )
            findings = mod.scan(root)
            self.assertTrue(findings)
            self.assertEqual('constant disagrees with the catalogue', findings[0]['kind'])


if __name__ == '__main__':
    unittest.main()
