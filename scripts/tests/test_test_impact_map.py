"""Tests for test-impact-map.py — the map of the tests a change requires.

The map is a promise: "run these and you have run what your change can
break". A promise that is wrong in the *narrow* direction sends the developer
to a green suite that never looked at the change; a promise that is wrong in
the *wide* direction costs time and nothing else.

So the properties asserted here are about the narrow direction:

* a change in a feature maps the tests of that feature;
* a change in a class maps the tests that mention that class, wherever they
  live — a mention is a coupling, and a coupling is a way to break;
* a changed test maps itself: it may now say something different about the
  code it checks;
* a change in the gates maps no app test at all — the gate suite is what has to
  run, and the app's tests do not read the gates;
* the command the map prints names the tag selection that actually runs the
  slow half, because the default run of a task excludes it;
* the commands are one per module: `--tests` filters the whole invocation, and
  a task left with no matching test fails the build.

The map is exercised against synthetic trees, so the assertions say what the
rule is and not what this repository happens to contain.
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "test_impact_map", SCRIPTS / "test-impact-map.py"
)
impact = importlib.util.module_from_spec(_spec)
sys.modules["test_impact_map"] = impact
_spec.loader.exec_module(impact)


def test_class(
    qualified_name: str,
    scope: str,
    body: str = '',
    module: str = 'shared',
) -> impact.TestClass:
    """A test class that lives in the tree — the map reads its scope and its body."""
    return impact.TestClass(
        qualified_name=qualified_name,
        path=impact.ROOT / 'shared/src/commonTest/kotlin' / f'{qualified_name.replace(".", "/")}.kt',
        module=module,
        scope=scope,
        body=body,
    )


class ScopeTest(unittest.TestCase):
    """The scope a path belongs to."""

    def test_a_feature_path_names_the_feature(self):
        self.assertEqual(
            'feature.tasks',
            impact._scope_from_tail('kotlin/com/singularity/todo/feature/tasks/data/X.kt'),
        )

    def test_a_core_path_names_the_core_part(self):
        self.assertEqual(
            'core.database',
            impact._scope_from_tail('kotlin/com/singularity/todo/core/database/Entities.kt'),
        )

    def test_a_path_without_a_marker_has_no_scope(self):
        self.assertEqual('', impact._scope_from_tail('kotlin/com/singularity/todo/App.kt'))


class ClassDiscoveryTest(unittest.TestCase):
    """Which classes the map is willing to name."""

    def test_a_nested_class_is_not_a_test_class(self):
        text = (
            'package com.example\n'
            '\n'
            'class OuterTest {\n'
            '    @Test\n'
            '    fun aTest() = Unit\n'
            '\n'
            '    class Inner {\n'
            '    }\n'
            '}\n'
        )
        blocks = impact._class_blocks(text)
        self.assertEqual(['OuterTest'], [name for name, _ in blocks])

    def test_a_helper_class_is_not_a_test_class(self):
        text = (
            'package com.example\n'
            '\n'
            'class FakeHost {\n'
            '}\n'
            '\n'
            'class RealTest {\n'
            '    @Test\n'
            '    fun aTest() = Unit\n'
            '}\n'
        )
        names = [name for name, body in impact._class_blocks(text)
                 if 'Test' in name or impact.TEST_MEMBER.search(body)]
        self.assertEqual(['RealTest'], names)


class MapTest(unittest.TestCase):
    """The map itself."""

    def setUp(self):
        self.tests = [
            test_class(
                'com.singularity.todo.feature.tasks.TaskTest',
                'feature.tasks',
                body='class TaskTest { val x = TaskRepositoryImpl() }',
            ),
            test_class('com.singularity.todo.feature.agenda.AgendaTest', 'feature.agenda'),
            test_class(
                'com.singularity.todo.arch.GraphTest',
                'arch',
                body='class GraphTest { val y = TaskRepositoryImpl() }',
            ),
        ]

    def map(self, paths):
        return impact.map_tests(paths, self.tests)

    def test_a_change_in_a_feature_maps_the_tests_of_that_feature(self):
        selected, _ = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/X.kt'],
        )
        self.assertIn('com.singularity.todo.feature.tasks.TaskTest', selected)
        self.assertNotIn('com.singularity.todo.feature.agenda.AgendaTest', selected)

    def test_a_change_in_a_class_maps_the_tests_that_mention_it(self):
        selected, reasons = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt'],
        )
        self.assertIn('com.singularity.todo.arch.GraphTest', selected)
        self.assertTrue(any('mentions it' in reason for reason in reasons))

    def test_a_changed_test_maps_itself(self):
        selected, reasons = self.map(
            ['shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/TaskTest.kt'],
        )
        self.assertIn('com.singularity.todo.feature.tasks.TaskTest', selected)
        self.assertTrue(any('the test itself changed' in reason for reason in reasons))

    def test_a_change_in_the_gates_maps_no_app_test(self):
        selected, reasons = self.map(['scripts/check-something.py'])
        self.assertEqual([], selected)
        self.assertTrue(any('gates' in reason for reason in reasons))

    def test_a_doc_change_maps_no_app_test(self):
        selected, _ = self.map(['docs/decisions/2026-10-22-something.md'])
        self.assertEqual([], selected)

    def test_every_selected_class_carries_its_reason(self):
        selected, reasons = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/X.kt'],
        )
        for name in selected:
            self.assertTrue(any(reason.startswith(name) for reason in reasons))


class CommandTest(unittest.TestCase):
    """The commands the map prints."""

    def test_the_command_names_the_tag_selection_that_runs_the_slow_half(self):
        tests = [test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks')]
        commands = impact.gradle_commands(tests)
        self.assertEqual(1, len(commands))
        self.assertIn('-Ptest.tags=fast,slow', commands[0])
        self.assertIn(':shared:jvmTest', commands[0])
        self.assertIn('--tests', commands[0])
        self.assertIn('com.singularity.todo.feature.tasks.TaskTest', commands[0])

    def test_no_tests_means_no_command(self):
        self.assertEqual([], impact.gradle_commands([]))

    def test_each_module_gets_its_own_command(self):
        tests = [
            test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks'),
            test_class('com.singularity.todo.flows.DesktopTest', 'flows', module='desktopApp'),
        ]
        commands = impact.gradle_commands(tests)
        self.assertEqual(2, len(commands))
        # A `--tests` filter that names a class of another module leaves that
        # module's task with nothing to run, and Gradle fails such a task.
        self.assertTrue(all(
            all(name in command for name in (
                test.qualified_name for test in tests if test.module == command[-1]
            ))
            for command in commands
        ))


if __name__ == '__main__':
    unittest.main()
