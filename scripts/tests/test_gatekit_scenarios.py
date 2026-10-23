#!/usr/bin/env python3
"""Tests for sgate.gatekit.scenarios — the semantics of a migration, as data.

The generator turns a scenario file into a Kotlin test, and the Kotlin has to
be the kind of Kotlin this repository accepts: no line over the limit, no
class with more functions than detekt allows, and no code that a reader cannot
follow back to the sentence it came from.

The tests below pin the *shape* of the rendering rather than its exact text —
a generator whose output is checked by an exact-match test fails every time
the formatting is improved, and stops being read.
"""

import pathlib
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "src"))

from sgate.gatekit.scenarios import (  # noqa: E402
    MAX_FUNCTIONS_PER_CLASS,
    Expect,
    RowSeed,
    Scenario,
    ScenarioFile,
    SqlSeed,
    TaskSeed,
    kotlin_convention_findings,
    load_scenario_file,
    load_scenarios,
    render_scenario_test,
    sql_value,
)

SCENARIO_YAML = """
from: 31
to: 32
scenarios:
  - name: task_id is backfilled into target_id
    seed:
      - table: ai_proposal
        row:
          id: p1
          user_id: u1
          target_id: null
          created_at: 1
      - sql: "UPDATE ai_proposal SET task_id = 't-1' WHERE id = 'p1'"
      - task: t9
    expect:
      - sql: "SELECT target_id FROM ai_proposal WHERE id = 'p1'"
        equals: t-1
      - sql: "SELECT COUNT(*) FROM ai_proposal"
        equals: 2
"""


class SqlValueTest(unittest.TestCase):
    """A value written in YAML becomes the literal the database stores."""

    def test_a_string_is_quoted(self):
        self.assertEqual(sql_value("t-1"), "'t-1'")

    def test_a_quote_inside_a_string_is_doubled(self):
        self.assertEqual(sql_value("it's"), "'it''s'")

    def test_a_number_is_written_as_it_is(self):
        self.assertEqual(sql_value(7), "7")

    def test_a_boolean_is_one_or_zero(self):
        self.assertEqual(sql_value(True), "1")
        self.assertEqual(sql_value(False), "0")

    def test_null_is_null(self):
        self.assertEqual(sql_value(None), "NULL")

    def test_anything_else_is_refused(self):
        with self.assertRaises(ValueError):
            sql_value({"a": 1})


class LoadTest(unittest.TestCase):
    """The scenario file is read as it is written."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.path = self.root / "31-to-32.yaml"
        self.path.write_text(SCENARIO_YAML, encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def test_the_versions_are_read(self):
        loaded = load_scenario_file(self.path)
        self.assertEqual(loaded.from_version, 31)
        self.assertEqual(loaded.to_version, 32)

    def test_the_scenario_is_read(self):
        loaded = load_scenario_file(self.path)
        self.assertEqual(len(loaded.scenarios), 1)
        self.assertEqual(
            loaded.scenarios[0].name, "task_id is backfilled into target_id"
        )

    def test_the_three_kinds_of_seed_are_read(self):
        loaded = load_scenario_file(self.path)
        kinds = [type(seed) for seed in loaded.scenarios[0].seed]
        self.assertEqual(kinds, [RowSeed, SqlSeed, TaskSeed])

    def test_the_expectations_are_read(self):
        loaded = load_scenario_file(self.path)
        expect = loaded.scenarios[0].expect
        self.assertEqual([e.sql for e in expect][0], "SELECT target_id FROM ai_proposal WHERE id = 'p1'")
        self.assertEqual([e.equals for e in expect], ["t-1", "2"])

    def test_a_missing_directory_yields_no_scenarios(self):
        self.assertEqual(load_scenarios(self.root / "absent"), [])


class RenderTest(unittest.TestCase):
    """The rendering is Kotlin this repository accepts."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        (self.root / "31-to-32.yaml").write_text(SCENARIO_YAML, encoding="utf-8")
        self.files = load_scenarios(self.root)
        self.rendered = render_scenario_test(self.files)

    def tearDown(self):
        self._tmp.cleanup()

    def test_the_output_follows_the_conventions_detekt_enforces(self):
        self.assertEqual(kotlin_convention_findings(self.rendered), [])

    def test_the_output_is_a_parameterized_test(self):
        """One test function for every scenario — detekt allows sixteen per class."""
        self.assertEqual(self.rendered.count("@ParameterizedTest"), 1)
        self.assertLessEqual(
            self.rendered.count("fun "),
            MAX_FUNCTIONS_PER_CLASS,
            "the generated class must stay under detekt's function limit",
        )

    def test_the_scenario_reaches_the_output(self):
        self.assertIn("task_id is backfilled into target_id", self.rendered)
        self.assertIn("INSERT INTO ai_proposal", self.rendered)
        self.assertIn("UPDATE ai_proposal SET task_id", self.rendered)

    def test_the_expectation_reaches_the_output(self):
        self.assertIn("SELECT target_id FROM ai_proposal WHERE id = 'p1'", self.rendered)
        self.assertIn('equals = "t-1"', self.rendered)

    def test_a_task_row_is_planted_by_the_fixture(self):
        self.assertIn("MigrationSeed.LegacyTask(", self.rendered)
        self.assertIn('id = "t9"', self.rendered)

    def test_the_rendering_is_a_pure_function_of_the_scenarios(self):
        self.assertEqual(self.rendered, render_scenario_test(self.files))

    def test_a_long_statement_is_wrapped(self):
        """A row of ten columns is one long line, and the limit is 120."""
        long_row = RowSeed(
            table="t",
            values={f"column_number_{index}": f"value-{index}" for index in range(20)},
        )
        rendered = render_scenario_test(
            [
                ScenarioFile(
                    from_version=1,
                    to_version=2,
                    scenarios=(
                        Scenario(
                            name="a long row",
                            seed=(long_row,),
                            expect=(Expect(sql="SELECT 1", equals="1"),),
                        ),
                    ),
                )
            ]
        )
        self.assertEqual(kotlin_convention_findings(rendered), [])
        self.assertGreater(len(rendered.split("\n")), 10)

    def test_no_scenarios_render_an_empty_list(self):
        rendered = render_scenario_test([])
        self.assertEqual(kotlin_convention_findings(rendered), [])
        self.assertIn("listOf(", rendered)


if __name__ == "__main__":
    unittest.main()
