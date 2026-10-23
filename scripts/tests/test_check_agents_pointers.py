"""Tests for check-agents-pointers.py.

A pointer that does not resolve is a stop that costs what the note it came
from was written to remove, so the gate has to be right in the cases a clean
tree never shows:

  - a pointer to a file that is not there is a finding — the gap the gate was
    written for;
  - a pointer with `{a,b}` is checked in every alternative — the note claims
    both exist;
  - a pointer with `...` resolves through any number of directories;
  - a path relative to prose around it is NOT checked — where it is relative
    to is not written down, and a gate that guesses is a gate that cries wolf;
  - a path inside a generated directory is not checked — `build/` is a build
    product, not the tree;
  - a document that names no pointer at all is a finding — a check that finds
    nothing has proved nothing.

The tests write their own tree: a gate exercised only against one repository
is a gate whose behaviour nobody knows when the tree changes shape.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-agents-pointers.py'
)
spec = importlib.util.spec_from_file_location('check_agents_pointers', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_agents_pointers'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


class AgentsPointersTest(unittest.TestCase):
    """Every path the document names has to be there."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        (self.root / 'docs').mkdir()
        (self.root / 'docs' / 'decisions').mkdir()
        (self.root / 'docs' / 'decisions' / 'a-rule.md').write_text('# rule\n', encoding='utf-8')
        (self.root / 'scripts').mkdir()
        (self.root / 'scripts' / 'a-gate.py').write_text('# gate\n', encoding='utf-8')
        (self.root / 'shared' / 'src' / 'commonTest' / 'kotlin' / 'deep').mkdir(parents=True)
        (self.root / 'shared' / 'src' / 'commonTest' / 'kotlin' / 'deep' / 'Helper.kt').write_text(
            '# helper\n', encoding='utf-8'
        )

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _document(self, text: str) -> pathlib.Path:
        document = self.root / 'AGENTS.md'
        document.write_text(text, encoding='utf-8')
        return document

    def test_a_pointer_to_a_file_that_is_not_there_is_a_finding(self) -> None:
        document = self._document('The rule lives in `docs/decisions/not-a-file.md`.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual(['docs/decisions/not-a-file.md'], broken)
        self.assertEqual(['docs/decisions/not-a-file.md'], pointers)

    def test_a_pointer_to_a_file_that_is_there_is_clean(self) -> None:
        document = self._document('The rule lives in `docs/decisions/a-rule.md`.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual([], broken)
        self.assertEqual(['docs/decisions/a-rule.md'], pointers)

    def test_every_alternative_of_a_choice_is_checked(self) -> None:
        document = self._document('The gate is `scripts/{a-gate,not-a-gate}.py`.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual(
            ['scripts/not-a-gate.py'],
            broken,
            'the note claims both exist, so one missing is a finding',
        )
        self.assertEqual(['scripts/a-gate.py', 'scripts/not-a-gate.py'], pointers)

    def test_a_wildcard_pointer_resolves_through_any_number_of_directories(self) -> None:
        document = self._document('The helper is `shared/src/commonTest/.../Helper.kt`.\n')

        broken, _ = mod.find_problems(document, self.root)

        self.assertEqual([], broken, '`...` stands for any number of directories')

    def test_a_path_relative_to_prose_is_not_checked(self) -> None:
        document = self._document('Next to it, `core/di/Modules.kt` holds the modules.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual([], broken)
        self.assertEqual([], pointers, 'a relative path has no root to resolve against')

    def test_a_path_in_a_generated_directory_is_not_checked(self) -> None:
        document = self._document('The findings are in `shared/build/reports/detekt/detekt.xml`.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual([], broken)
        self.assertEqual([], pointers, 'a build product is not the tree')

    def test_a_command_is_not_a_pointer(self) -> None:
        document = self._document('Run `adb shell run-as com.singularity.todo ls` first.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual([], broken)
        self.assertEqual([], pointers)

    def test_a_placeholder_is_not_a_pointer(self) -> None:
        document = self._document('The record is `.specify/issues/<slug>.yaml`.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual([], broken)
        self.assertEqual([], pointers)

    def test_a_document_that_names_no_pointer_is_not_vacuously_clean(self) -> None:
        # The gate itself refuses this: `find_problems` reports no pointers, and
        # the CLI turns that into a failure rather than a pass.
        document = self._document('Nothing to see here.\n')

        broken, pointers = mod.find_problems(document, self.root)

        self.assertEqual([], broken)
        self.assertEqual([], pointers)
        self.assertLess(
            len(pointers),
            mod.MIN_POINTERS,
            'fewer pointers than the floor means the check proves nothing',
        )


if __name__ == '__main__':
    unittest.main()
