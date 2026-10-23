"""Tests for check-skill-drift.py — the declared toolbox must be the implemented toolbox.

The class of defect this gate closes is not "a wrong sentence in a doc". It is
**a hand-written description of code that nobody diffs against it**. The
description is edited rarely, the code is edited daily, so the description is
always the stale one, and it is always plausible — it reads like documentation,
not like a bug. Found in a single session: `write_adr` claimed to "create *or
update*" but only created; "all four tools" was five; a document said the ADR
tools were a `desktopApp` subcommand when they had been a separate module
since #522.

So the gate does three things over one tool surface, and the properties
asserted here are the ones that make those three things mean something:

* the generated "ADR tools" section of the two ADR documents is re-rendered and
  compared, so a hand edit inside the markers is drift — and a hand edit
  *outside* them is not, because the prose is the author's;
* the catalogue and the tool classes must declare the same set of tools, and
  the class each entry points at must declare that name — otherwise the client
  asks for a tool and gets an error;
* the exported surface (what the compiler saw) must agree with what the gate's
  own parser read out of the sources — otherwise the gate is reading the code
  wrongly, and every check it makes is about a surface that does not exist.

The gate's *exit codes and messages* are what this file pins, because the
renderer's own tests check the renderer: a gate that reports drift and exits 0
is a gate nobody runs, and a gate that fails without naming the document
forces the reader to diff the files by hand.

The documents are temporary copies; the tool surface and the catalogue are read
from this repository, because they are the code under test.
"""

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_skill_drift", SCRIPTS / "check-skill-drift.py"
)
drift = importlib.util.module_from_spec(_spec)
sys.modules["check_skill_drift"] = drift
_spec.loader.exec_module(drift)

renderer = drift.renderer


class CliTest(unittest.TestCase):
    """The gate's verdict on a temporary document."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.document = self.tmp / "mcp-server.md"
        self._saved_skills = renderer.SKILLS
        self.addCleanup(self._set_skills, self._saved_skills)
        self._set_skills([self.document])

    def _set_skills(self, skills):
        renderer.SKILLS = tuple(skills)
        drift.renderer.SKILLS = renderer.SKILLS

    def run_gate(self):
        buffer = io.StringIO()
        with unittest.mock.patch.object(drift, "ROOT", self.tmp):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = drift.run(drift.cli.main, [])
        return code, buffer.getvalue()

    def test_a_document_matching_the_rendered_block_passes(self):
        self.document.write_text(renderer.render(), encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("check-skill-drift: OK", output)

    def test_a_drifted_document_fails(self):
        """The positive control: a gate that has never been seen to fire."""
        self.document.write_text(
            renderer.render().replace("| Command | What it does |", "| Wrong | Header |", 1),
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("does not match the code", output)

    def test_the_drift_report_names_the_document_and_the_difference(self):
        """The reader must not have to diff two files by hand to find the change."""
        self.document.write_text(
            renderer.render().replace("| Command | What it does |", "| Wrong | Header |", 1),
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("mcp-server.md", output)
        self.assertIn("line ", output)

    def test_the_drift_report_says_how_to_fix_it(self):
        self.document.write_text(
            renderer.render().replace("| Command | What it does |", "| Wrong | Header |", 1),
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("python3 scripts/render-adr-tools-section.py", output)

    def test_a_document_without_a_block_is_reported(self):
        """A document with no generated section has nowhere for the surface to live."""
        self.document.write_text("# just prose\n", encoding="utf-8")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("block", output)

    def test_a_missing_document_is_reported(self):
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("does not exist", output)

    def test_hand_written_prose_outside_the_markers_is_not_drift(self):
        """The prose is the author's; only the block is generated."""
        self.document.write_text(
            renderer.render() + "\nA hand-written trailing paragraph.\n",
            encoding="utf-8",
        )
        code, output = self.run_gate()
        self.assertEqual(0, code, output)


class ExportedSurfaceTest(unittest.TestCase):
    """The export as the compiled classes declared it."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.export = self.tmp / "tool-surface.json"

    def _surface(self):
        with unittest.mock.patch.object(drift, "TOOL_SURFACE_JSON", self.export):
            return drift.exported_surface()

    def test_a_valid_export_is_keyed_by_tool_name(self):
        self.export.write_text(
            json.dumps({"tools": [{"name": "write_adr", "description": "writes"}]}),
            encoding="utf-8",
        )
        surface = self._surface()
        self.assertIn("write_adr", surface)
        self.assertEqual("writes", surface["write_adr"]["description"])

    def test_a_missing_export_reads_as_empty(self):
        """Empty means "not exported", which the caller reports as a problem."""
        self.assertEqual({}, self._surface())

    def test_an_unreadable_export_reads_as_empty(self):
        self.export.write_text("{not json", encoding="utf-8")
        self.assertEqual({}, self._surface())

    def test_an_export_whose_tools_are_not_a_list_reads_as_empty(self):
        self.export.write_text(json.dumps({"tools": {"write_adr": {}}}), encoding="utf-8")
        self.assertEqual({}, self._surface())

    def test_entries_that_are_not_objects_are_skipped(self):
        self.export.write_text(
            json.dumps({"tools": ["write_adr", {"name": "read_adr", "description": "reads"}]}),
            encoding="utf-8",
        )
        surface = self._surface()
        self.assertEqual(["read_adr"], list(surface))


class SurfaceProblemsTest(unittest.TestCase):
    """Where the gate's reading and the compiler's reading disagree."""

    def _problems(self, exported):
        with unittest.mock.patch.object(drift, "exported_surface", lambda: exported):
            return drift.surface_problems()

    def test_a_missing_export_is_a_problem(self):
        problems = self._problems({})
        self.assertTrue(problems)
        self.assertIn("has not been exported", problems[0])

    def test_a_tool_the_export_has_and_the_sources_do_not_is_reported(self):
        problems = self._problems({"ghost_tool": {"name": "ghost_tool", "description": ""}})
        self.assertTrue(
            any("the export declares the tool 'ghost_tool'" in p for p in problems),
            problems,
        )

    def test_a_tool_the_sources_have_and_the_export_does_not_is_reported(self):
        source_names = {name for name, _c, _d in renderer.catalog_entries()}
        missing = sorted(source_names - {"write_adr"})[0]
        problems = self._problems({"write_adr": {"name": "write_adr", "description": "x"}})
        self.assertTrue(
            any(f"the sources declare the tool {missing!r}" in p for p in problems),
            problems,
        )

    def test_a_description_the_gate_read_differently_is_reported(self):
        name = sorted({n for n, _c, _d in renderer.catalog_entries()})[0]
        problems = self._problems({name: {"name": name, "description": "something else"}})
        self.assertTrue(
            any("the shape the reader does not understand is in the source" in p for p in problems),
            problems,
        )

    def test_the_committed_surface_has_no_problems(self):
        """The real export, the real sources, the real parser: they agree today."""
        self.assertEqual([], drift.surface_problems())


class ToolboxProblemsTest(unittest.TestCase):
    """The declared toolbox and the implemented toolbox."""

    def test_the_committed_toolbox_has_no_problems(self):
        self.assertEqual([], drift.toolbox_problems())

    def test_a_catalogue_entry_the_export_does_not_have_is_reported(self):
        """The two faces of the corpus disagreeing is the defect, one level down."""
        with unittest.mock.patch.object(drift, "exported_surface", lambda: {}):
            problems = drift.toolbox_problems()
        self.assertTrue(
            any("has not been exported" in p for p in problems),
            problems,
        )


class FirstDifferenceTest(unittest.TestCase):
    """The line-level difference the drift report quotes."""

    def test_the_first_differing_line_is_named(self):
        difference = drift._first_difference("a\nb\nc", "a\nX\nc")
        self.assertIn("line 2", difference)
        self.assertIn("'b'", difference)
        self.assertIn("'X'", difference)

    def test_a_shorter_block_is_reported_as_a_length_difference(self):
        difference = drift._first_difference("a\nb", "a\nb\nc")
        self.assertIn("2 line(s)", difference)
        self.assertIn("3", difference)

    def test_identical_blocks_report_no_difference(self):
        self.assertIn("identical", drift._first_difference("a\nb", "a\nb"))


if __name__ == "__main__":
    unittest.main()
