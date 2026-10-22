"""Tests for check-maestro-keyboard-commands.py.

The gate exists because a `hideKeyboard` with nothing to hide is a BACK
keypress, and the editors are dialogs: BACK dismisses them, and the draft that
was in the editor is gone. Measured on a real phone (2026-10-28):
`tasks/17-create-daily-recurring` failed with `Element not found: Id matching
regex: task_editor_save`, and the hierarchy showed the agenda.

The properties the tests hold:

  - a `hideKeyboard` after a text input is fine — that is what it is for;
  - a second `hideKeyboard` is a finding — the keyboard is already hidden;
  - a `hideKeyboard` with no text input before it is a finding — there was
    never anything to hide;
  - the gate's own tree is clean, so a green gate means the tree is clean and
    not that the gate is looking in the wrong place.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-maestro-keyboard-commands.py'
spec = importlib.util.spec_from_file_location('check_maestro_keyboard_commands', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

HEADER = 'appId: com.singularity.todo\nname: probe\n---\n'


def flow(*commands: str) -> str:
    """A flow file with the given command lines."""
    return HEADER + ''.join(f'- {command}\n' for command in commands)


class HideKeyboardRuleTest(unittest.TestCase):
    def _findings(self, body: str):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            path = root / 'probe.yaml'
            path.write_text(body, encoding='utf-8')
            return mod.check_flow(path, root)

    def test_a_hide_keyboard_after_a_text_input_is_fine(self):
        findings = self._findings(flow('inputText: "hello"', 'hideKeyboard'))
        self.assertEqual([], findings, 'the keyboard is up after typing, so hiding it is the point')

    def test_a_second_hide_keyboard_is_a_finding(self):
        findings = self._findings(flow('inputText: "hello"', 'hideKeyboard', 'hideKeyboard'))
        self.assertEqual(1, len(findings), 'the second hideKeyboard has nothing left to hide')

    def test_a_hide_keyboard_without_a_text_input_is_a_finding(self):
        findings = self._findings(flow('tapOn:\n    id: tasks_fab', 'hideKeyboard'))
        self.assertEqual(1, len(findings), 'no text was typed into, so no keyboard was raised')

    def test_erase_text_also_raises_the_keyboard(self):
        findings = self._findings(flow('eraseText', 'hideKeyboard'))
        self.assertEqual([], findings, 'erasing is typing: the field was focused')

    def test_a_flow_without_a_hide_keyboard_has_no_findings(self):
        findings = self._findings(flow('inputText: "hello"', 'tapOn:\n    id: task_editor_save'))
        self.assertEqual([], findings)

    def test_a_helper_flow_is_read_on_its_own(self):
        findings = self._findings(flow('runFlow: ../../helpers/launch-clean.yaml'))
        self.assertEqual([], findings, 'a helper that types nothing raises no keyboard')


class CommandParsingTest(unittest.TestCase):
    def test_a_bare_command_is_read_as_its_own_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'probe.yaml'
            path.write_text(flow('hideKeyboard'), encoding='utf-8')
            self.assertEqual(['hideKeyboard'], [mod._command_name(c) for c in mod._commands(path)])

    def test_a_mapping_command_is_read_as_its_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'probe.yaml'
            path.write_text(flow('tapOn:\n    id: tasks_fab'), encoding='utf-8')
            self.assertEqual(['tapOn'], [mod._command_name(c) for c in mod._commands(path)])


class RealTreeTest(unittest.TestCase):
    def test_the_repository_flows_are_clean(self):
        """The gate must pass on the tree it guards.

        A gate that fails on the tree it was written for is either broken or
        the tree is wrong — and the first thing to know is which.
        """
        findings = mod.check_flows(mod.DEFAULT_FLOWS_DIR)
        self.assertEqual(
            [],
            [f._asdict() for f in findings],
            'every `hideKeyboard` in the tree must have something to hide',
        )


if __name__ == '__main__':
    unittest.main()
