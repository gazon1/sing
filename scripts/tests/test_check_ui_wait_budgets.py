"""Tests for check-ui-wait-budgets.py.

The gate holds one written budget against the layers that wait for it: the
instrumented suite's Kotlin constants, and every Maestro wait for an element,
held to the budget of the *kind* that element belongs to.

Five properties matter, and each of them is a way the gate could report green
while the drift it exists for is still there:

  - a wait whose timeout is not its kind's budget is a finding (the drift);
  - a wait with no timeout at all is a finding (the wait falls back to a
    default nobody wrote down);
  - an element nobody budgeted is a finding (no promise, nothing to keep);
  - a kind budget that disagrees with the instrumented shell budget is a
    finding (the two layers wait for the same screen);
  - a constant the catalogue does not carry is a finding (a budget nobody
    promised is a budget nobody keeps in step).

The tests build a whole miniature tree in a temp directory and point the
module at it, so nothing here depends on the repository's own numbers — a
budget change in `config/docs/ui-wait-budgets.txt` must not turn these tests
red, and must not be able to turn them green either.
"""

import importlib.util
import json
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
    """A miniature repository: a catalogue, a kind map, a Kotlin probe and flows."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.catalogue = self.root / 'ui-wait-budgets.txt'
        self.element_kinds = self.root / 'maestro-element-kinds.txt'
        self.shell_probe = self.root / 'ShellProbe.kt'
        self.maestro = self.root / 'Maestro'

        self._saved = (
            mod.ROOT,
            mod.CATALOGUE,
            mod.ELEMENT_KINDS,
            mod.SHELL_PROBE,
            mod.MAESTRO_DIR,
        )
        mod.ROOT = self.root
        mod.CATALOGUE = self.catalogue
        mod.ELEMENT_KINDS = self.element_kinds
        mod.SHELL_PROBE = self.shell_probe
        mod.MAESTRO_DIR = self.maestro

    def tearDown(self):
        (
            mod.ROOT,
            mod.CATALOGUE,
            mod.ELEMENT_KINDS,
            mod.SHELL_PROBE,
            mod.MAESTRO_DIR,
        ) = self._saved
        self._tmp.cleanup()

    def write_catalogue(self, text):
        self.catalogue.write_text(text, encoding='utf-8')

    def write_element_kinds(self, text):
        self.element_kinds.write_text(text, encoding='utf-8')

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
kind shell 30000
kind tab 10000
kind menu 5000
kind field 5000
kind button 5000
kind item 8000
kind dialog 5000
"""

ELEMENT_KINDS = """\
# a comment
menu_settings menu
nav_tab_today shell
task_editor_title_input field
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

FLOW_FOR_UNMAPPED_ELEMENT = """\
appId: com.singularity.todo
---
- extendedWaitUntil:
    visible:
      id: nobody_budgeted_this
    timeout: 5000
"""

FLOW_FOR_OTHER_ELEMENT = """\
appId: com.singularity.todo
---
- extendedWaitUntil:
    visible:
      id: menu_settings
    timeout: 5000
"""


class CatalogueParsingTest(unittest.TestCase):
    def test_comments_and_blank_lines_are_not_budgets(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text(
                '# a comment\n\nshell 30000\nkind shell 30000\n', encoding='utf-8'
            )
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                self.assertEqual({'shell': 30000}, mod.catalogue_budgets())
                self.assertEqual({'shell': 30000}, mod.kind_budgets())
            finally:
                mod.CATALOGUE = original

    def test_a_line_that_is_not_a_budget_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('shell = 30000\nkind shell 30000\n', encoding='utf-8')
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
            path.write_text(
                'shell 30000\nshell 8000\nkind shell 30000\n', encoding='utf-8'
            )
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.catalogue_budgets()
            finally:
                mod.CATALOGUE = original

    def test_a_kind_listed_twice_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text(
                'shell 30000\nkind shell 30000\nkind shell 8000\n', encoding='utf-8'
            )
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.kind_budgets()
            finally:
                mod.CATALOGUE = original

    def test_a_kind_line_without_a_budget_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'catalogue.txt'
            path.write_text('shell 30000\nkind shell\n', encoding='utf-8')
            original = mod.CATALOGUE
            mod.CATALOGUE = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.kind_budgets()
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


class ElementKindParsingTest(unittest.TestCase):
    def test_a_mapping_is_read_as_element_to_kind(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'kinds.txt'
            path.write_text('# c\nmenu_settings menu\n', encoding='utf-8')
            original = mod.ELEMENT_KINDS
            mod.ELEMENT_KINDS = path
            try:
                self.assertEqual({'menu_settings': 'menu'}, mod.element_kinds())
            finally:
                mod.ELEMENT_KINDS = original

    def test_a_mapping_to_a_kind_the_catalogue_does_not_price_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'kinds.txt'
            path.write_text('menu_settings unpriced\n', encoding='utf-8')
            original = mod.ELEMENT_KINDS
            mod.ELEMENT_KINDS = path
            try:
                with self.assertRaises(click.ClickException):
                    mod.element_kinds()
            finally:
                mod.ELEMENT_KINDS = original


class ScanTest(TreeFixture):
    def test_a_tree_that_agrees_has_no_findings(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
        self.assertEqual([], mod.scan())

    def test_a_maestro_wait_below_its_kind_budget_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_OTHER_BUDGET)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual("wait disagrees with its kind's budget", findings[0]['kind'])
        self.assertIn('nav_tab_today', findings[0]['detail'])
        self.assertIn('shell', findings[0]['detail'])

    def test_a_maestro_wait_without_a_timeout_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_WITHOUT_TIMEOUT)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('wait without a timeout', findings[0]['kind'])

    def test_an_element_nobody_budgeted_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_FOR_UNMAPPED_ELEMENT)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('element nobody budgeted', findings[0]['kind'])
        self.assertIn('nobody_budgeted_this', findings[0]['detail'])

    def test_a_wait_for_another_budgeted_element_is_not_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_FOR_OTHER_ELEMENT)
        self.assertEqual([], mod.scan())

    def test_a_kind_budget_that_disagrees_with_the_instrumented_shell_is_a_finding(self):
        self.write_catalogue(CATALOGUE.replace('kind shell 30000', 'kind shell 12000'))
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow(
            'flows/smoke/01.yaml',
            FLOW_AT_BUDGET.replace('timeout: 30000', 'timeout: 12000'),
        )
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual(
            'the shell kind disagrees with the instrumented shell budget',
            findings[0]['kind'],
        )

    def test_the_shell_element_must_be_budgeted_as_the_shell_kind(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(
            ELEMENT_KINDS.replace('nav_tab_today shell', 'nav_tab_today tab')
        )
        self.write_shell_probe()
        self.write_flow(
            'flows/smoke/01.yaml',
            FLOW_AT_BUDGET.replace('timeout: 30000', 'timeout: 10000'),
        )
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual(
            'the shell element is not budgeted as the shell kind',
            findings[0]['kind'],
        )

    def test_a_kind_budget_below_the_floor_is_a_finding(self):
        self.write_catalogue(CATALOGUE.replace('kind menu 5000', 'kind menu 1000'))
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('kind budget is below the floor', findings[0]['kind'])

    def test_a_constant_that_drifted_from_the_catalogue_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe(
            SHELL_PROBE_SOURCE.replace('SHELL_TIMEOUT_MS = 30_000L', 'SHELL_TIMEOUT_MS = 8_000L')
        )
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('constant disagrees with the catalogue', findings[0]['kind'])

    def test_a_constant_the_catalogue_does_not_carry_is_a_finding(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe(
            SHELL_PROBE_SOURCE + '\ninternal const val POPUP_TIMEOUT_MS = 5_000L\n'
        )
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
        findings = mod.scan()
        self.assertEqual(1, len(findings))
        self.assertEqual('constant is not in the catalogue', findings[0]['kind'])

    def test_a_catalogue_without_a_shell_budget_is_a_finding(self):
        self.write_catalogue(
            'tab 10000\ndevice 15000\n'
            'kind shell 30000\nkind tab 10000\nkind menu 5000\nkind field 5000\n'
            'kind button 5000\nkind item 8000\nkind dialog 5000\n'
        )
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
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
            (root / 'config' / 'docs' / 'maestro-element-kinds.txt').write_text(
                ELEMENT_KINDS, encoding='utf-8'
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
                CATALOGUE.replace('shell 30000', 'shell 8000'), encoding='utf-8'
            )
            findings = mod.scan(root)
            self.assertTrue(findings)
            self.assertEqual('constant disagrees with the catalogue', findings[0]['kind'])

            # And the tree that is named is the tree that is checked: a flow in
            # it that disagrees with its kind is a finding there.
            (root / 'config' / 'docs' / 'ui-wait-budgets.txt').write_text(
                CATALOGUE, encoding='utf-8'
            )
            (flow / '02.yaml').write_text(FLOW_AT_OTHER_BUDGET, encoding='utf-8')
            findings = mod.scan(root)
            self.assertEqual(1, len(findings))
            self.assertEqual(
                "wait disagrees with its kind's budget", findings[0]['kind']
            )


class CliContractTest(TreeFixture):
    """The gate's command line: --json, --quiet, --root, and the exit codes."""

    def test_a_clean_tree_exits_zero(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_BUDGET)
        self.assertEqual(0, mod.main(['--quiet']))

    def test_a_tree_with_a_finding_exits_one(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_OTHER_BUDGET)
        self.assertEqual(1, mod.main(['--quiet']))

    def test_json_output_carries_the_findings(self):
        self.write_catalogue(CATALOGUE)
        self.write_element_kinds(ELEMENT_KINDS)
        self.write_shell_probe()
        self.write_flow('flows/smoke/01.yaml', FLOW_AT_OTHER_BUDGET)
        import io
        from contextlib import redirect_stdout

        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = mod.main(['--json'])
        self.assertEqual(1, code)
        payload = json.loads(buffer.getvalue())
        self.assertEqual(1, len(payload['findings']))


if __name__ == '__main__':
    unittest.main()
