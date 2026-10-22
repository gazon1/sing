"""Tests for check-di-fixture-sync.py.

The gate compares the Koin definitions that take runtime parameters with the
fixture table that says what to hand them. The drift it exists for is the one
that was committed on 2026-10-22: `TaskDetailCoordinator` was given a route,
the definition was updated, and the fixture kept handing it a `TaskId` — so
the graph could not be created.

The gate is a text comparison, so these tests are about the comparison: which
differences it reports, and which it deliberately leaves to the runtime test.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

MODULE_PATH = SCRIPTS / "check-di-fixture-sync.py"
spec = importlib.util.spec_from_file_location("check_di_fixture_sync", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def write(root: pathlib.Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


DI_MODULE = "shared/src/commonMain/kotlin/com/singularity/todo/core/di/TasksDiModule.kt"
FIXTURE = "desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt"

DEFINITION = """    viewModel { (route: com.singularity.todo.feature.nav.TasksRoute.Detail) ->
        TaskDetailCoordinator(
            deps = TaskDetailDeps(),
        )
    }
"""

FIXTURE_ROW = '        TaskDetailCoordinator::class to listOf(TasksRoute.Detail(TaskId("task-1"))),'


class ValueHelpersTest(unittest.TestCase):
    def test_split_keeps_a_comma_inside_brackets(self):
        values = mod.split_top_level('TasksRoute.Detail(TaskId("task-1")), "section-1"')
        self.assertEqual(values, ['TasksRoute.Detail(TaskId("task-1"))', '"section-1"'])

    def test_a_literal_satisfies_only_its_own_type(self):
        self.assertTrue(mod.value_can_satisfy('"section-1"', "String"))
        self.assertFalse(mod.value_can_satisfy('"section-1"', "Int"))
        self.assertTrue(mod.value_can_satisfy("2026", "Int"))

    def test_a_type_named_in_the_value_satisfies_it(self):
        self.assertTrue(mod.value_can_satisfy('TasksRoute.Detail(TaskId("task-1"))', "Detail"))
        self.assertFalse(mod.value_can_satisfy('TaskId("task-1")', "Detail"))

    def test_a_factory_function_is_left_to_the_runtime_test(self):
        # The text does not say what a lower-case factory returns, so the gate
        # stays silent rather than guessing.
        self.assertIsNone(mod.value_can_satisfy("sampleDefinition()", "AgendaDefinition"))

    def test_a_factory_whose_name_carries_the_type_name_satisfies_it(self):
        # `sampleAgendaDefinition` names `AgendaDefinition`, so the text does
        # say enough — and the gate says so rather than deferring.
        self.assertTrue(mod.value_can_satisfy("sampleAgendaDefinition()", "AgendaDefinition"))


class ComparisonTest(unittest.TestCase):
    def build(self, definition: str, fixture: str) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(root, DI_MODULE, definition)
            write(root, FIXTURE, fixture)
            return mod.mismatches(root)

    def test_a_matching_pair_has_no_findings(self):
        self.assertEqual(self.build(DEFINITION, FIXTURE_ROW), [])

    def test_a_fixture_of_the_wrong_type_is_reported(self):
        findings = self.build(DEFINITION, '        TaskDetailCoordinator::class to listOf(TaskId("task-1")),')
        self.assertEqual(len(findings), 1)
        self.assertIn("Detail", findings[0].message)
        self.assertIn("TaskId", findings[0].message)

    def test_a_definition_without_a_fixture_row_is_reported(self):
        findings = self.build(DEFINITION, "        val empty = emptyMap<KClass<*>, List<Any>>()")
        self.assertEqual(len(findings), 1)
        self.assertIn("no row", findings[0].message)

    def test_a_fixture_row_without_a_definition_is_reported(self):
        findings = self.build(
            "    viewModel { AiUsageViewModel() }",
            '        AiUsageViewModel::class to listOf("nothing"),',
        )
        self.assertEqual(len(findings), 1)
        self.assertIn("no Koin definition", findings[0].message)

    def test_the_wrong_number_of_values_is_reported(self):
        definition = """    viewModel { (year: Int, month: Month) ->
        CalendarViewModel(
            year = year,
        )
    }
"""
        fixture = '        CalendarViewModel::class to listOf(2026),'
        findings = self.build(definition, fixture)
        self.assertEqual(len(findings), 1)
        self.assertIn("supplies 1 value", findings[0].message)


if __name__ == "__main__":
    unittest.main()
