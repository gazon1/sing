"""Tests for the generated "ADR tools" skill section (#526).

The point of the generator is that a skill cannot describe tools the code does not
have. That property is worth nothing unless it is demonstrated, so the central test
here edits a generated block by hand and asserts the gate turns red — a generator
that is never seen failing is indistinguishable from a generator that is not wired up.
"""

import importlib.util
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
ROOT = SCRIPTS.parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


renderer = _load('render_adr_tools_section', 'render-adr-tools-section.py')
drift = _load('check_skill_drift', 'check-skill-drift.py')


class RenderTest(unittest.TestCase):
    """What the renderer reads out of the code."""

    def test_every_command_is_read(self):
        commands = renderer.commands()
        names = [c[0] for c in commands]
        for expected in ('new', 'list', 'list-open', 'read', 'update', 'stale',
                         'prune', 'validate'):
            self.assertIn(expected, names)

    def test_every_adr_domain_mcp_tool_is_read(self):
        names = [n for n, _ in renderer.mcp_adr_tools()]
        self.assertEqual(
            names,
            [
                'write_adr',
                'list_adrs',
                'update_adr',
                'read_adr',
                'list_open_deferred',
                'search_decisions',
            ],
        )

    def test_the_adr_domain_is_read_by_domain_not_by_a_slice_of_the_file(self):
        """The slice swallowed whatever section was declared next.

        `search_docs` is in the `docs` domain and is declared right after the ADR
        section, so a renderer that cut the file between the "ADR" and "AI Write Tools"
        markers listed it as an ADR tool. The domain is a property of the entry, and
        reading it from the entry is the only way the list stays right when a section is
        added in the middle.
        """
        adr_names = {n for n, _, domain in renderer.catalog_entries() if domain == 'adr'}
        self.assertNotIn('search_docs', adr_names)
        self.assertIn('search_docs', [n for n, _, _ in renderer.catalog_entries()])

    def test_a_description_belongs_to_its_own_tool(self):
        """The off-by-one this parser originally had.

        `write_adr`'s description is built by string concatenation, which pushes the
        `annotations` argument onto a later line. A single regex matching
        `name = ... .*? description = ...` therefore fails to close and walks on to the
        *next* entry — rendering `write_adr` with `list_adrs`'s description. Plausible,
        confident, and wrong.

        The texts asserted here are the ones the tool classes declare, because the
        tool class is where the description lives. The catalogue used to carry a second
        copy, and the two copies drifted apart in 32 of 39 tools — so asserting the
        catalogue's wording would have pinned a lie.
        """
        descriptions = dict(renderer.mcp_adr_tools())
        self.assertIn('Create a NEW Architecture Decision Record', descriptions['write_adr'])
        self.assertIn('List all Architecture Decision Records', descriptions['list_adrs'])
        self.assertIn('Read a specific ADR by its slug', descriptions['read_adr'])
        self.assertNotEqual(descriptions['write_adr'], descriptions['list_adrs'])

    def test_a_concatenated_description_is_read_whole(self):
        """Not just its first string literal."""
        descriptions = dict(renderer.mcp_adr_tools())
        self.assertIn('it leaves the predecessor unlinked', descriptions['write_adr'])

    def test_flags_have_summaries(self):
        """`AdrFlagSpec` has two arguments; reading three renders "--json — None"."""
        for flag, summary in renderer.global_flags():
            self.assertTrue(summary and summary != 'None', f'{flag} has no summary')

    def test_render_is_deterministic(self):
        self.assertEqual(renderer.render(), renderer.render())

    def test_render_carries_both_ends_of_the_markers(self):
        block = renderer.render()
        self.assertTrue(block.startswith(renderer.BEGIN))
        self.assertTrue(block.endswith(renderer.END))

    def test_an_unreadable_declaration_is_an_error_not_a_skip(self):
        """Skipping would drop a command while the section still looked complete."""
        with tempfile.TemporaryDirectory() as tmp:
            broken = pathlib.Path(tmp) / 'AdrSurface.kt'
            text = renderer.SURFACE_KT.read_text(encoding='utf-8')
            # Wrap the first declaration across lines — the exact reformatting a
            # developer would plausibly apply to a long line.
            text = text.replace(
                'AdrCommandSpec("new", "new <slug> <title> [tag ...]", "write a new decision"),',
                'AdrCommandSpec(\n            "new", "new <slug> <title> [tag ...]",\n'
                '            "write a new decision",\n        ),',
                1,
            )
            broken.write_text(text, encoding='utf-8')
            saved = renderer.SURFACE_KT
            renderer.SURFACE_KT = broken
            try:
                with self.assertRaises(renderer.ParseError):
                    renderer.commands()
            finally:
                renderer.SURFACE_KT = saved


class DriftGateTest(unittest.TestCase):
    """The gate turns red on a hand-edited skill and green after regeneration."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = pathlib.Path(self._tmp.name)
        self._saved = renderer.SKILLS
        self.copies = []
        for skill in self._saved:
            copy = self.tmp / skill.name
            shutil.copy(skill, copy)
            self.copies.append((skill, copy))
        renderer.SKILLS = tuple(c for _, c in self.copies)
        drift.renderer.SKILLS = renderer.SKILLS

    def tearDown(self):
        renderer.SKILLS = self._saved
        drift.renderer.SKILLS = self._saved

    def _gate(self):
        """Run the gate's comparison against the copies, not the repository."""
        drifted = []
        expected = renderer.render()
        for skill in renderer.SKILLS:
            if renderer.current_block(skill) != expected:
                drifted.append(skill)
        return drifted

    def test_committed_skills_are_in_sync(self):
        self.assertEqual(self._gate(), [],
                         'run: python3 scripts/render-adr-tools-section.py')

    def test_a_hand_edit_turns_the_gate_red(self):
        """The positive control: without this, the gate has never been seen to fire."""
        skill = renderer.SKILLS[0]
        text = skill.read_text(encoding='utf-8')
        skill.write_text(
            text.replace('| `singularity-adr validate` | run the repository',
                         '| `singularity-adr verifai` | run the repository', 1),
            encoding='utf-8',
        )
        self.assertIn(skill, self._gate())

    def test_text_after_the_block_is_hand_written_and_stays_green(self):
        """Appending prose below the markers is the author writing, not drift.

        Worth its own test because it is the boundary the whole design rests on: if
        touching anything outside the markers counted as drift, nobody would ever run
        the generator a second time.
        """
        skill = renderer.SKILLS[0]
        skill.write_text(skill.read_text(encoding='utf-8')
                         + '\nA hand-written trailing paragraph.\n', encoding='utf-8')
        self.assertEqual(self._gate(), [])

    def test_regenerating_restores_sync_and_keeps_the_hand_writing(self):
        skill = renderer.SKILLS[0]
        # Drift *inside* the block, plus hand-written prose appended after it. The
        # generator must repair the first and not touch the second.
        skill.write_text(
            skill.read_text(encoding='utf-8')
            .replace('| Command | What it does |', '| Wrong | Header |', 1)
            + '\nA hand-written trailing paragraph.\n',
            encoding='utf-8',
        )
        self.assertIn(skill, self._gate())

        skill.write_text(renderer.splice(skill.read_text(encoding='utf-8'),
                                         renderer.render()), encoding='utf-8')
        self.assertEqual(self._gate(), [])
        self.assertIn('A hand-written trailing paragraph.',
                      skill.read_text(encoding='utf-8'))

    def test_text_outside_the_markers_is_never_touched(self):
        """The generator is a dashboard bolted onto a hand-written document."""
        skill = renderer.SKILLS[0]
        original = skill.read_text(encoding='utf-8')
        block = renderer.render()
        skill.write_text(renderer.splice(original, block), encoding='utf-8')
        after = skill.read_text(encoding='utf-8')

        before_head = original[:original.index(renderer.BEGIN)]
        after_head = after[:after.index(renderer.BEGIN)]
        self.assertEqual(before_head, after_head)

    def test_a_skill_with_no_block_is_reported_not_passed(self):
        skill = renderer.SKILLS[0]
        text = skill.read_text(encoding='utf-8')
        start = text.index(renderer.BEGIN)
        end = text.index(renderer.END, start) + len(renderer.END)
        skill.write_text(text[:start] + text[end:], encoding='utf-8')
        self.assertIsNone(renderer.current_block(skill))
        self.assertIn(skill, self._gate())


class ToolboxParityTest(unittest.TestCase):
    """The declared toolbox and the implemented toolbox must be the same toolbox.

    The catalogue, the tool classes and the CLI are three declarations of one surface.
    Nothing diffs them against each other, so a tool can be added to one and forgotten
    in another, and every side keeps working — the client asks for a tool that is not
    registered, or a command reaches a corpus nobody can read over MCP.

    Each test below breaks one of the three and asserts the gate reports it. A check
    that has never been seen to fire is indistinguishable from a check that is not wired.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = pathlib.Path(self._tmp.name)
        # The gate loads its own instance of the renderer, so the paths to patch are
        # the gate's -- patching this module's copy would leave the gate reading the
        # repository and every case below would pass for the wrong reason.
        self._saved = (drift.renderer.CATALOG_KT, drift.renderer.SURFACE_KT,
                       drift.renderer.TOOLS_DIR)

    def tearDown(self):
        (drift.renderer.CATALOG_KT, drift.renderer.SURFACE_KT,
         drift.renderer.TOOLS_DIR) = self._saved

    def _copy_tree(self, source: pathlib.Path) -> pathlib.Path:
        target = self.tmp / source.name
        shutil.copytree(source, target)
        return target

    def _copy_file(self, source: pathlib.Path) -> pathlib.Path:
        target = self.tmp / source.name
        shutil.copy(source, target)
        return target

    def test_the_committed_surface_has_no_problems(self):
        self.assertEqual(drift.toolbox_problems(), [])

    def test_a_catalogue_tool_without_a_class_is_reported(self):
        """The client asks for a tool the code does not implement."""
        tools = self._copy_tree(drift.renderer.TOOLS_DIR)
        drift.renderer.TOOLS_DIR = tools
        target = tools / 'ListTasksTool.kt'
        target.write_text(
            target.read_text(encoding='utf-8').replace('const val NAME = "list_tasks"',
                                                       'const val NAME = "list_tasks_renamed"'),
            encoding='utf-8',
        )
        problems = drift.toolbox_problems()
        self.assertTrue(any('list_tasks' in p for p in problems), problems)

    def test_a_tool_class_missing_from_the_catalogue_is_reported(self):
        """A tool nobody wired up, and nobody will look for either."""
        catalog = self._copy_file(drift.renderer.CATALOG_KT)
        drift.renderer.CATALOG_KT = catalog
        text = catalog.read_text(encoding='utf-8')
        start = text.index('ToolEntry(\n            name = "list_tasks",')
        end = text.index('),', start) + len('),')
        catalog.write_text(text[:start] + text[end:], encoding='utf-8')
        problems = drift.toolbox_problems()
        self.assertTrue(any('list_tasks' in p for p in problems), problems)

    def test_a_catalogue_entry_pointing_at_the_wrong_class_is_reported(self):
        """The name is right and the implementation is not — the worst shape, because
        the name check passes."""
        catalog = self._copy_file(drift.renderer.CATALOG_KT)
        drift.renderer.CATALOG_KT = catalog
        text = catalog.read_text(encoding='utf-8')
        catalog.write_text(
            text.replace('toolClass = ListTasksTool::class', 'toolClass = GetTaskTool::class', 1),
            encoding='utf-8',
        )
        problems = drift.toolbox_problems()
        self.assertTrue(any('ListTasksTool' in p or 'GetTaskTool' in p for p in problems), problems)

    def test_an_adr_tool_without_a_cli_command_is_reported(self):
        """A tool an agent can call over MCP and nobody can reach from a terminal."""
        surface = self._copy_file(drift.renderer.SURFACE_KT)
        drift.renderer.SURFACE_KT = surface
        text = surface.read_text(encoding='utf-8')
        surface.write_text(
            text.replace('        AdrMcpToolSpec("update", "update_adr"),\n', '', 1),
            encoding='utf-8',
        )
        problems = drift.toolbox_problems()
        self.assertTrue(any('update_adr' in p for p in problems), problems)

    def test_a_cli_mapping_pointing_at_an_unknown_tool_is_reported(self):
        surface = self._copy_file(drift.renderer.SURFACE_KT)
        drift.renderer.SURFACE_KT = surface
        text = surface.read_text(encoding='utf-8')
        surface.write_text(
            text.replace('AdrMcpToolSpec("read", "read_adr")',
                         'AdrMcpToolSpec("read", "read_adf")', 1),
            encoding='utf-8',
        )
        problems = drift.toolbox_problems()
        self.assertTrue(any('read_adf' in p for p in problems), problems)

    def test_a_duplicate_catalogue_name_is_reported(self):
        catalog = self._copy_file(drift.renderer.CATALOG_KT)
        drift.renderer.CATALOG_KT = catalog
        text = catalog.read_text(encoding='utf-8')
        catalog.write_text(
            text.replace('name = "list_tasks"', 'name = "list_adrs"', 1),
            encoding='utf-8',
        )
        problems = drift.toolbox_problems()
        self.assertTrue(any('more than once' in p for p in problems), problems)


class FlagKeySpaceTest(unittest.TestCase):
    """The flag key space, as a generated block.

    An operator writes a remote configuration against these names, so a list that has
    drifted from the catalogue is a list that teaches the wrong keys. The block is
    generated, and the gate compares it with the catalogue — the same arrangement as
    the ADR tool block.
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='flag-keys-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_the_flag_key_space_is_generated_into_the_architecture_document(self):
        path = pathlib.Path(renderer.FLAG_DOCUMENTS[0])
        self.assertTrue(path.is_file(), f'{path} does not exist')
        block = renderer.current_flag_block(path)
        self.assertIsNotNone(block, f'{path} has no generated flag key space')

    def test_the_flag_key_space_lists_every_tool(self):
        declared = {name for _domain, name in renderer.flag_keys()}
        self.assertEqual(
            len(renderer.catalog_entries()),
            len(declared),
            'every tool is a key a configuration may use',
        )

    def test_a_hand_edited_flag_key_space_is_reported(self):
        path = pathlib.Path(renderer.FLAG_DOCUMENTS[0])
        planted = self.tmp / 'mcp-server.md'
        planted.write_text(
            path.read_text(encoding='utf-8').replace('| adr | `list_adrs` |', '| adr | `list_adrr` |', 1),
            encoding='utf-8',
        )
        original = renderer.FLAG_DOCUMENTS
        self.addCleanup(setattr, renderer, 'FLAG_DOCUMENTS', original)
        renderer.FLAG_DOCUMENTS = (planted,)

        drifted = renderer.current_flag_block(planted) != renderer.render_flag_block()

        self.assertTrue(drifted, 'a hand-edited key space must not pass unnoticed')


class ExportedSurfaceTest(unittest.TestCase):
    """The parser against the compiler.

    The gate reads the tool surface out of the Kotlin sources, because a gate that runs
    a compiler is a gate nobody runs. `config/docs/tool-surface.json` is the same
    surface as the *compiled* classes saw it, exported by `:shared:exportToolSurface`.

    Comparing the two is what makes the gate honest: a regex that misreads a class does
    not fail — it reads less, and the gate reports a surface smaller than the code. The
    export says what the class really declares, so a parser that reads less is caught.
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='tool-surface-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        # The gate module is shared by every test in the file, so a test that points it
        # at a planted export must point it back. A leak here would make an unrelated
        # test report a missing export, which is a failure nobody could explain.
        self.addCleanup(setattr, drift, 'TOOL_SURFACE_JSON', drift.TOOL_SURFACE_JSON)

    def _export_with(self, tool_name: str, **changes) -> pathlib.Path:
        """A copy of the committed export with the given tool's fields changed."""
        data = json.loads(drift.TOOL_SURFACE_JSON.read_text(encoding='utf-8'))
        for tool in data['tools']:
            if tool['name'] == tool_name:
                tool.update(changes)
        target = self.tmp / 'tool-surface.json'
        target.write_text(json.dumps(data), encoding='utf-8')
        return target

    def test_the_committed_surface_is_the_one_the_gate_reads(self):
        self.assertEqual(drift.surface_problems(), [])

    def test_a_tool_the_export_has_and_the_sources_do_not_is_reported(self):
        export = self._export_with('list_tasks', name='list_tasks_renamed')
        drift.TOOL_SURFACE_JSON = export
        problems = drift.surface_problems()
        self.assertTrue(any('list_tasks' in p for p in problems), problems)

    def test_a_description_the_gate_read_differently_is_reported(self):
        export = self._export_with('list_tasks', description='A different description.')
        drift.TOOL_SURFACE_JSON = export
        problems = drift.surface_problems()
        self.assertTrue(
            any("the sources read the description of" in p for p in problems),
            problems,
        )

    def test_a_flag_key_the_documentation_names_must_exist(self):
        """A flag that names no tool is silent, and the tool stays exposed."""
        self.assertEqual(drift.flag_problems(), [])

    def test_a_documented_flag_that_names_no_tool_is_reported(self):
        spec = pathlib.Path(drift.DOCUMENTED_FLAG_SOURCES[0])
        planted = self.tmp / 'spec.md'
        planted.write_text(
            spec.read_text(encoding='utf-8').replace(
                'mcpToolFlags = { "summarize_note": false }',
                'mcpToolFlags = { "summarize_notes": false }',
                1,
            ),
            encoding='utf-8',
        )
        original = drift.DOCUMENTED_FLAG_SOURCES
        self.addCleanup(setattr, drift, 'DOCUMENTED_FLAG_SOURCES', original)
        drift.DOCUMENTED_FLAG_SOURCES = (planted,) + tuple(original[1:])

        problems = drift.flag_problems()

        self.assertTrue(any('summarize_notes' in p for p in problems), problems)

    def test_a_missing_export_is_reported(self):
        drift.TOOL_SURFACE_JSON = self.tmp / 'absent.json'
        problems = drift.surface_problems()
        self.assertTrue(any('has not been exported' in p for p in problems), problems)


class TargetSetTest(unittest.TestCase):
    """What the gate actually checks.

    `check-skill-drift.py` iterates `renderer.SKILLS`, so the tuple is the gate's entire
    input. Shrinking it is the quiet failure: the gate keeps exiting 0, now against one
    document instead of two, and prints "OK — 1 skill(s)" in a line nobody reads. These
    pin the size and the existence so that failure cannot be silent.
    """

    def test_render_targets_two_documents_and_both_exist(self):
        self.assertEqual(len(renderer.DOCUMENTS), 2)
        for document in renderer.DOCUMENTS:
            self.assertTrue(document.is_file(), f'{document} does not exist')

    def test_the_alias_check_skill_drift_imports_is_the_same_tuple(self):
        """The drift checker reads `renderer.SKILLS`; a copy would drift from it."""
        self.assertIs(renderer.SKILLS, renderer.DOCUMENTS)

    def test_a_target_is_not_needed_because_another_one_matches(self):
        """Both documents are compared independently.

        The failure this guards against is a document drifting while its sibling stays
        correct: a gate that compared only the first target would be green.
        """
        expected = renderer.render()
        for document in renderer.DOCUMENTS:
            with self.subTest(document=document.name):
                self.assertEqual(renderer.current_block(document), expected)


if __name__ == '__main__':
    unittest.main(verbosity=2)