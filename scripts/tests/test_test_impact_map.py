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
* the command the map prints names a POPULATION, not a tag list, because a
  population is declared in one place and a tag list is a second spelling of
  it that drifts;
* the command names the TASK that runs the class, and the task belongs to the
  source set: a class of `androidHostTest` does not exist under
  `:shared:jvmTest`, so naming the JVM task for it runs nothing and reports
  green;
* a class the chosen population does not select is dropped from the command and
  named in the report — a filter that names it runs nothing and reports green;
* a base reference that does not resolve is an error, not an empty selection:
  a comparison that could not be made is not a comparison that found nothing;
* an empty selection is reported on stdout, where the caller reads it.

The map is exercised against synthetic trees, so the assertions say what the
rule is and not what this repository happens to contain.
"""

import contextlib
import importlib.util
import io
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))
sys.path.insert(0, str(SCRIPTS))

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
    source: str = '',
    task: str = ':shared:jvmTest',
) -> impact.TestClass:
    """A test class that lives in the tree — the map reads its scope and its body.

    `source` is the file's whole text. The class-level `@Tag` sits ABOVE the
    declaration, so a body that starts at the declaration cannot see it — and
    the population intersection asks exactly that question.

    `task` is the Gradle task that runs the class. It is a parameter and not a
    constant because the task belongs to the SOURCE SET: `shared` carries
    `commonTest`, `jvmTest` and `androidHostTest`, and each has its own task.
    """
    return impact.TestClass(
        qualified_name=qualified_name,
        path=impact.ROOT / 'shared/src/commonTest/kotlin' / f'{qualified_name.replace(".", "/")}.kt',
        module=module,
        task=task,
        scope=scope,
        body=body,
        source=source,
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
            test_class(
                'com.singularity.todo.arch.TreeWideGateTest',
                '',
                body=(
                    'class TreeWideGateTest {'
                    ' private fun kotlinFiles(): List<File> = commonMain.walkTopDown()'
                    ' }'
                ),
            ),
            test_class(
                'com.singularity.todo.feature.agenda.MentionTest',
                'feature.agenda',
                body='class MentionTest { val y = TaskRepositoryImpl() }',
            ),
            test_class(
                'com.singularity.todo.feature.genui.CorpusTest',
                'feature.genui',
                body='class CorpusTest { val z = UNIT }',
                source='class CorpusTest { val z = Files.walk(corpus).count() }',
            ),
        ]

    def map(self, paths):
        return impact.map_tests(paths, self.tests)

    def test_a_change_in_a_scope_selects_the_instrumentation_classes_of_that_scope(self):
        """The instrumentation tier follows the rule the JVM tier uses.

        A change in a scope selects the classes of that scope, and the tier's
        classes carry the same scopes as the JVM ones.
        """
        instrumented = test_class(
            'com.singularity.todo.core.security.AndroidSecureStorageRecoveryTest',
            'core.security',
            task=':androidApp:connectedDebugAndroidTest',
        )
        selected, _ = impact.map_tests(
            [
                'shared/src/commonMain/kotlin/com/singularity/todo/core/'
                'security/SecureStore.kt',
            ],
            [instrumented],
        )
        self.assertEqual([instrumented.qualified_name], selected)

    def test_a_change_in_the_instrumentation_harness_selects_the_classes_that_use_it(self):
        """The tier's base class is not a test — the classes that extend it are.

        A change to the harness changes what every class of the tier asserts,
        and the harness itself is not a class the runner can be given: it
        carries no test method.
        """
        changed = (
            'androidApp/src/androidTest/kotlin/com/singularity/todo/testing/'
            'InstrumentedTestBase.kt'
        )
        instrumented = test_class(
            'com.singularity.todo.ui.AppStartupTest',
            '',
            body='class AppStartupTest : InstrumentedTestBase() { }',
            task=':androidApp:connectedDebugAndroidTest',
        )
        selected, _ = impact.map_tests([changed], [instrumented])
        self.assertIn('com.singularity.todo.ui.AppStartupTest', selected)

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
        self.assertIn('com.singularity.todo.feature.agenda.MentionTest', selected)
        self.assertTrue(any('mentions it' in reason for reason in reasons))

    def test_a_tree_walk_outside_the_arch_suite_is_mapped_by_any_change(self):
        # A fixture-corpus test reads a directory without living in `arch/`.
        # The call in its FILE is what says so — not a mention of a class.
        selected, reasons = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/core/llm/X.kt'],
        )
        self.assertIn('com.singularity.todo.feature.genui.CorpusTest', selected)
        self.assertTrue(any('reads the whole tree' in reason for reason in reasons))

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

    def test_a_gate_that_reads_the_whole_tree_is_mapped_by_any_change(self):
        # The gate reads every file of the root, so it can break because of any
        # change in it — a change whose scope it belongs to neither, and whose
        # classes it does not mention.
        selected, reasons = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/X.kt'],
        )
        self.assertIn('com.singularity.todo.arch.TreeWideGateTest', selected)
        self.assertTrue(any('reads the whole tree' in reason for reason in reasons))

    def test_a_gate_that_reads_the_whole_tree_is_mapped_by_a_change_outside_its_scope(self):
        selected, _ = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/core/llm/X.kt'],
        )
        self.assertIn('com.singularity.todo.arch.TreeWideGateTest', selected)

    def test_a_gate_that_only_names_a_class_is_not_treated_as_tree_wide(self):
        # Naming a class is coupling, and the mention rule already covers it.
        # Treating every mention as a tree-wide read would put every test in
        # the map of every change.
        selected, _ = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/core/llm/X.kt'],
        )
        self.assertNotIn('com.singularity.todo.feature.agenda.MentionTest', selected)

    def test_a_non_kotlin_file_in_a_tree_a_gate_reads_maps_that_gate(self):
        # `MaestroFlowTagsTest` walks every flow and fails on one without
        # `tags:`. The Kotlin rules never see a `.yaml` change, so the gate has
        # to be selected by the tree itself.
        selected, reasons = self.map(['Maestro/flows/new-thing.yaml'])
        self.assertIn('com.singularity.todo.arch.TreeWideGateTest', selected)
        self.assertTrue(any('a tree a gate reads' in reason for reason in reasons))

    def test_a_schema_export_maps_nothing_here(self):
        # The migration tests read the exports through `applyExportedSchema(N, …)`
        # in another file and are `@Tag("slow")`, so no rule reaches them. The gap
        # is recorded in the ADR; this test keeps the omission deliberate rather
        # than letting `shared/schemas/` creep back into READ_TREES and select
        # gates that never open the file.
        selected, reasons = self.map(
            ['shared/schemas/com.singularity.todo.core.database.AppDatabase/47.json'],
        )
        self.assertEqual([], selected)
        self.assertTrue(any('no test class claims it' in reason for reason in reasons))

    def test_a_changed_test_maps_the_gates_that_read_the_whole_test_tree(self):
        # A gate that asserts every test class carries a @Tag is broken by a
        # test class that does not — and the class that broke it is the one
        # that changed, so the gate has to run too.
        selected, reasons = self.map(
            ['shared/src/jvmTest/kotlin/com/singularity/todo/arch/SomeTest.kt'],
        )
        self.assertIn('com.singularity.todo.arch.TreeWideGateTest', selected)
        self.assertTrue(any('reads the whole tree' in reason for reason in reasons))

    def test_a_doc_change_maps_no_app_test(self):
        selected, _ = self.map(['docs/decisions/2026-10-22-something.md'])
        self.assertEqual([], selected)

    def test_every_selected_class_carries_its_reason(self):
        selected, reasons = self.map(
            ['shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/X.kt'],
        )
        for name in selected:
            self.assertTrue(any(reason.startswith(name) for reason in reasons))


class SourceSetTaskTest(unittest.TestCase):
    """The task that runs a class belongs to its SOURCE SET."""

    def test_every_test_source_set_names_a_task(self):
        """No source set is without a task.

        A source set without a task is a source set whose classes are named in
        a command that cannot run them.
        """
        for source_dir in impact.TEST_SOURCE_DIRS:
            self.assertIn(source_dir, impact.SOURCE_SET_TASKS)

    def test_the_android_host_source_set_has_its_own_task(self):
        """The android host classes do not run under the JVM task.

        `shared` carries three test source sets, and each of them has its own
        task. A class of `androidHostTest` does not exist under
        `:shared:jvmTest` — the filter that names it selects nothing there,
        Gradle fails a test task that ran no test, and the class never ran.
        """
        self.assertEqual(
            ':shared:testAndroidHostTest',
            impact.SOURCE_SET_TASKS['shared/src/androidHostTest'],
        )

    def test_the_task_the_map_names_is_the_task_the_build_declares(self):
        """The map and the build cannot disagree about the task's name.

        A map that names a task the build does not have runs nothing: Gradle
        fails an unknown task, and the failure says nothing about the change.
        """
        build = (impact.ROOT / 'shared/build.gradle.kts').read_text(encoding='utf-8')
        task = impact.SOURCE_SET_TASKS['shared/src/androidHostTest']
        self.assertIn(task.rsplit(':', 1)[-1], build)

    def test_the_instrumentation_source_set_has_its_own_task(self):
        """The instrumentation classes do not run under a JVM task.

        The tier is the one that sees the real Android runtime, and its task
        is the one the device job runs. A class of that tier does not exist
        under `:shared:jvmTest` — the filter that names it selects nothing
        there, and the run that ran nothing reports green.
        """
        self.assertEqual(
            ':androidApp:connectedDebugAndroidTest',
            impact.SOURCE_SET_TASKS['androidApp/src/androidTest'],
        )

    def test_the_instrumentation_task_is_the_task_the_device_job_runs(self):
        """The map and the device job cannot disagree about the task's name.

        The task is generated by the Android plugin, so it is not written down
        in a build file — but the job that runs the tier names it, and that is
        where a reader looks for the spelling.
        """
        workflow = (
            impact.ROOT / '.github/workflows/android-device-tests.yml'
        ).read_text(encoding='utf-8')
        self.assertIn(
            impact.SOURCE_SET_TASKS['androidApp/src/androidTest'],
            workflow,
        )

    def test_an_android_host_class_is_run_by_the_android_host_task(self):
        """The command of an android host class names the task that runs it."""
        host = test_class(
            'com.singularity.todo.core.notifications.AndroidNotifierTest',
            'core.notifications',
            task=':shared:testAndroidHostTest',
        )
        commands = impact.gradle_commands([host], 'fast')
        self.assertEqual(1, len(commands))
        self.assertIn(':shared:testAndroidHostTest', commands[0])
        self.assertNotIn(':shared:jvmTest', commands[0])

    def test_two_source_sets_of_one_module_get_two_commands(self):
        """One command per task — a filter is a filter of the whole invocation.

        A `--tests` filter that names a class of another task leaves that
        task's run with nothing to run, and Gradle fails such a task.
        """
        tests = [
            test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks'),
            test_class(
                'com.singularity.todo.core.notifications.AndroidNotifierTest',
                'core.notifications',
                task=':shared:testAndroidHostTest',
            ),
        ]
        commands = impact.gradle_commands(tests, 'fast')
        self.assertEqual(2, len(commands))
        self.assertEqual(
            [':shared:jvmTest', ':shared:testAndroidHostTest'],
            sorted(command[1] for command in commands),
        )

    def test_the_android_host_classes_of_this_tree_carry_their_task(self):
        """The map is exercised against the repository's own tree.

        A promise about a tree nobody looked at is a promise about a tree that
        does not exist.
        """
        host_root = impact.ROOT / 'shared/src/androidHostTest'
        classes = [
            test
            for test in impact.test_classes()
            if test.path.is_relative_to(host_root)
        ]
        self.assertTrue(classes, 'the repository has no android host test class')
        for test in classes:
            self.assertEqual(':shared:testAndroidHostTest', test.task)

    def test_the_instrumentation_classes_of_this_tree_carry_their_task(self):
        """The map is exercised against the repository's own tree.

        The tier is the one the map could not see at all, so the promise is
        worth checking against the classes that are really there.
        """
        root = impact.ROOT / 'androidApp/src/androidTest'
        classes = [
            test
            for test in impact.test_classes()
            if test.path.is_relative_to(root)
        ]
        self.assertTrue(classes, 'the repository has no instrumentation test class')
        for test in classes:
            self.assertEqual(':androidApp:connectedDebugAndroidTest', test.task)
        names = [test.qualified_name for test in classes]
        self.assertIn('com.singularity.todo.ui.AppStartupTest', names)

    def test_the_instrumentation_harness_is_not_named(self):
        """A class with no runnable method fails the run instead of running it.

        The tier's base class is named like a test and carries no test method.
        Given to the runner it is an initialization error, so the map must not
        name it — the classes that extend it are the tests that run.
        """
        root = impact.ROOT / 'androidApp/src/androidTest'
        names = [
            test.qualified_name
            for test in impact.test_classes()
            if test.path.is_relative_to(root)
        ]
        self.assertNotIn('com.singularity.todo.testing.InstrumentedTestBase', names)
        # The tier is seen, and the classes that extend the harness are named:
        # the assertion above is about the harness, not about an empty list.
        self.assertIn('com.singularity.todo.ui.AppStartupTest', names)


class CommandTest(unittest.TestCase):
    """The commands the map prints."""

    def test_the_command_names_a_population_not_a_tag_list(self):
        """The command asks for a population the repository declares.

        A tag list is a second spelling of a thing declared in one place, and
        the two spellings drift: the map used to select the fast and the slow
        half together, so a job meant to answer in minutes measured the
        population the schedule measures.
        """
        tests = [test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks')]
        commands = impact.gradle_commands(tests, 'fast')
        self.assertEqual(1, len(commands))
        self.assertIn('-Ptest.population=fast', commands[0])
        self.assertNotIn('-Ptest.tags=fast,slow', commands[0])
        self.assertFalse(any(token.startswith('-Ptest.tags=') for token in commands[0]))
        self.assertIn(':shared:jvmTest', commands[0])
        self.assertIn('--tests', commands[0])
        self.assertIn('com.singularity.todo.feature.tasks.TaskTest', commands[0])

    def test_the_population_the_command_names_is_a_declared_one(self):
        declared = impact.populations()
        self.assertIn('fast', declared)
        self.assertEqual(['fast'], declared['fast'])
        self.assertEqual('fast', impact.default_population())

    def test_an_undeclared_population_is_refused(self):
        with self.assertRaises(impact.UnknownPopulation):
            impact.population_tags('no-such-population')

    def test_the_gradle_entry_point_is_overridable(self):
        """The local recipe reaches the private wrapper, not the shared cache."""
        tests = [test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks')]
        commands = impact.gradle_commands(tests, 'fast', gradle='./gw')
        self.assertEqual('./gw', commands[0][0])

    def test_a_missing_base_ref_is_refused(self):
        """A comparison that could not be made is not one that found nothing.

        The tool used to swallow the failed comparison and report an empty
        selection with exit code 0 — the same answer it gives for a change
        that genuinely touches no test.
        """
        with self.assertRaises(impact.UnresolvableBaseRef):
            impact.changed_paths('origin/no-such-base-ref')

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = impact.main(['--command', '--base', 'origin/no-such-base-ref'])
        self.assertNotEqual(0, code)
        self.assertIn('origin/no-such-base-ref', stderr.getvalue())

    def test_a_class_outside_the_population_is_dropped(self):
        """A filter that names a class the population excludes runs nothing.

        The dropped class is NAMED, so the report says what did not run instead
        of reporting a green run that ran nothing.
        """
        slow = test_class(
            'com.singularity.todo.feature.tasks.SlowTask',
            'feature.tasks',
            source=(
                'package com.singularity.todo.feature.tasks\n'
                '\n'
                '@Tag("slow")\n'
                'class SlowTask {\n'
                '    @Test\n'
                '    fun aTest() = Unit\n'
                '}\n'
            ),
        )
        kept, dropped = impact.select_for_population([slow], ['fast'])
        self.assertEqual([], kept)
        self.assertEqual(1, len(dropped))
        self.assertIn('com.singularity.todo.feature.tasks.SlowTask', dropped[0])
        self.assertIn(impact.DROPPED, dropped[0])

        kept_in_full, dropped_in_full = impact.select_for_population([slow], ['fast', 'slow'])
        self.assertEqual([slow], kept_in_full)
        self.assertEqual([], dropped_in_full)

    def test_an_empty_selection_says_so(self):
        """The empty selection is reported where the caller reads it.

        The caller has to tell "this change claims no test class" apart from
        "the comparison failed": the first is an answer, the second is an
        error with a non-zero exit code.
        """
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(io.StringIO()):
            code = impact.main(['--command'], paths=[])
        self.assertEqual(0, code)
        self.assertIn(impact.EMPTY_SELECTION, stdout.getvalue())

    def test_no_tests_means_no_command(self):
        self.assertEqual([], impact.gradle_commands([], 'fast'))

    def test_an_instrumentation_class_is_named_by_the_runner_argument(self):
        """The instrumentation task does not take `--tests`.

        The classes of that tier are run by the Android instrumentation runner,
        and the class is named by the runner argument the plugin forwards to
        it — the spelling the device smoke already uses. A `--tests` filter is
        not understood there: the invocation runs the whole tier, or nothing.
        """
        test = test_class(
            'com.singularity.todo.ui.AppStartupTest',
            '',
            task=':androidApp:connectedDebugAndroidTest',
        )
        commands = impact.gradle_commands([test], 'fast')
        self.assertEqual(1, len(commands))
        self.assertIn(
            '-Pandroid.testInstrumentationRunnerArguments.class='
            'com.singularity.todo.ui.AppStartupTest',
            commands[0],
        )
        self.assertNotIn('--tests', commands[0])

    def test_the_runner_argument_names_every_class_of_the_task(self):
        """One invocation, every class — the runner takes a comma-separated list.

        One command per task is the map's rule, and the runner argument is a
        filter of the whole invocation just as `--tests` is.
        """
        tests = [
            test_class(
                'com.singularity.todo.ui.AppStartupTest',
                '',
                task=':androidApp:connectedDebugAndroidTest',
            ),
            test_class(
                'com.singularity.todo.ui.NavigationSmokeTest',
                '',
                task=':androidApp:connectedDebugAndroidTest',
            ),
        ]
        commands = impact.gradle_commands(tests, 'fast')
        self.assertEqual(1, len(commands))
        self.assertIn(
            '-Pandroid.testInstrumentationRunnerArguments.class='
            'com.singularity.todo.ui.AppStartupTest,'
            'com.singularity.todo.ui.NavigationSmokeTest',
            commands[0],
        )

    def test_the_instrumentation_command_names_no_population(self):
        """The runner does not read the tag filter.

        The population does not narrow that task, so a population named on its
        command would say the opposite of what happens.
        """
        test = test_class(
            'com.singularity.todo.ui.AppStartupTest',
            '',
            task=':androidApp:connectedDebugAndroidTest',
        )
        commands = impact.gradle_commands([test], 'fast')
        self.assertNotIn('-Ptest.population=fast', commands[0])

    def test_the_instrumentation_tier_is_not_dropped_by_a_population(self):
        """The population does not narrow the instrumentation task.

        The tier's classes are untagged by design — they are JUnit4 classes on
        the Vintage engine, where a Jupiter `@Tag` is not a filter at all. A
        population that dropped them would drop a class the runner WOULD run.
        """
        test = test_class(
            'com.singularity.todo.ui.AppStartupTest',
            '',
            task=':androidApp:connectedDebugAndroidTest',
        )
        kept, dropped = impact.select_for_population([test], ['fast'])
        self.assertEqual([test.qualified_name], [t.qualified_name for t in kept])
        self.assertEqual([], dropped)

    def test_the_run_path_switches_the_coverage_agent_off(self):
        """A run of a SUBSET of the suite must not measure coverage.

        The agent multiplies the run by about 1.6 on this tree (1241 s
        instrumented against 771 s not), and a coverage number over a subset
        describes the subset rather than the app — a number nobody can act on.
        The coverage job is where the measurement belongs.

        So the flag is added on the path that RUNS the command. The path that
        PRINTS the command leaves the decision to its caller, which is why the
        CI `quick` job adds the same flag to the command it evaluates.
        """
        ran = []

        def fake_run(command, cwd, check):
            ran.append(command)
            return type('Result', (), {'returncode': 0})()

        original = impact.subprocess.run
        impact.subprocess.run = fake_run
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                # A path in THIS repository's tree, so the selection is a real
                # one: the map reads the tree, and a synthetic path names no
                # class and the run would be empty.
                impact.main(['--run'], paths=['shared/src/commonMain/kotlin/com/singularity/todo/App.kt'])
        finally:
            impact.subprocess.run = original

        self.assertTrue(ran, 'nothing was run')
        for command in ran:
            self.assertIn(impact.COVERAGE_OFF, command)

    def test_the_printed_command_leaves_the_coverage_decision_to_its_caller(self):
        """`--command` prints the selection; the caller decides what to run it with.

        The CI `quick` job evaluates the printed command with the coverage
        agent off, and a caller that WANTS the measurement can keep it on. The
        flag therefore belongs on the run path, not on the printed command.
        """
        commands = impact.gradle_commands(
            [test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks')],
            'fast',
        )
        for command in commands:
            self.assertNotIn(impact.COVERAGE_OFF, command)

    def test_each_task_gets_its_own_command(self):
        tests = [
            test_class('com.singularity.todo.feature.tasks.TaskTest', 'feature.tasks'),
            test_class(
                'com.singularity.todo.flows.DesktopTest',
                'flows',
                module='desktopApp',
                task=':desktopApp:test',
            ),
        ]
        commands = impact.gradle_commands(tests, 'fast')
        self.assertEqual(2, len(commands))
        # A `--tests` filter that names a class of another task leaves that
        # task's run with nothing to run, and Gradle fails such a task.
        for test in tests:
            carrying = [command for command in commands if command[1] == test.task]
            self.assertEqual(1, len(carrying))
            self.assertIn(test.qualified_name, carrying[0])


class TreeReadingTest(unittest.TestCase):
    """A gate that reads one tree is not selected by a change to another.

    The tree-wide rule used to be a single rule: any change to any tree
    selected every gate that reads a tree. That is safe and it is slow — 55
    classes for a change to a Maestro flow, most of which read `commonMain`
    and cannot be broken by a YAML file.

    The narrowing is only allowed in the safe direction: a gate that reads an
    unnamed tree reads everything, and a gate that names a tree is selected
    when THAT tree changes.
    """

    def _gate(self, name: str, source: str) -> impact.TestClass:
        return test_class(
            f'com.singularity.todo.arch.{name}',
            'arch',
            source=source,
            task=':shared:archTest',
        )

    def test_a_gate_that_names_no_tree_reads_everything(self):
        """The safe default: an unnamed tree is every tree."""
        gate = self._gate('UnnamedGate', 'class UnnamedGate { }')
        self.assertIsNone(impact.roots_read_by(gate))

    def test_a_gate_that_names_a_tree_reads_that_tree(self):
        gate = self._gate('FlowGate', 'val root = System.getProperty("maestro.root")')
        self.assertEqual({'maestro'}, impact.roots_read_by(gate))

    def test_a_change_to_a_flow_selects_the_gate_that_reads_the_flows(self):
        gate = self._gate('FlowGate', 'val root = System.getProperty("maestro.root")')
        selected, _ = impact.map_tests(
            ['Maestro/flows/tasks/create-task.yaml'], [gate]
        )
        self.assertIn(gate.qualified_name, selected)

    def test_a_change_to_a_flow_does_not_select_a_gate_that_reads_common_main(self):
        """A YAML file cannot break a gate that never opens it."""
        gate = self._gate(
            'MainGate',
            'val root = System.getProperty("commonMain.root")',
        )
        selected, _ = impact.map_tests(
            ['Maestro/flows/tasks/create-task.yaml'], [gate]
        )
        self.assertNotIn(gate.qualified_name, selected)

    def test_a_change_to_a_test_file_selects_the_gate_that_reads_the_tests(self):
        gate = self._gate(
            'TestTreeGate',
            'val root = System.getProperty("jvmTestSources.root")',
        )
        selected, _ = impact.map_tests(
            ['shared/src/jvmTest/kotlin/com/singularity/todo/arch/DiFacadeTest.kt'],
            [gate],
        )
        self.assertIn(gate.qualified_name, selected)

    def test_a_change_to_production_selects_every_tree_wide_gate(self):
        """The narrowing never applies to a change in the code.

        A gate can depend on production code through an IMPORT — without
        reading its source — so a change in `commonMain` selects every
        tree-wide gate, exactly as before.
        """
        gate = self._gate('MainGate', 'val root = System.getProperty("commonMain.root")')
        selected, _ = impact.map_tests(
            ['shared/src/commonMain/kotlin/com/singularity/todo/core/di/DiFacade.kt'],
            [gate],
        )
        self.assertIn(gate.qualified_name, selected)

    def test_a_gate_that_names_no_tree_is_selected_for_a_flow_change(self):
        """The narrow direction must not lose a gate that reads everything."""
        gate = self._gate('UnnamedGate', 'class UnnamedGate { }')
        selected, _ = impact.map_tests(
            ['Maestro/flows/tasks/create-task.yaml'], [gate]
        )
        self.assertIn(gate.qualified_name, selected)


class ArchTaskTest(unittest.TestCase):
    """The architecture gates run in a task of their own."""

    def test_an_arch_class_is_run_by_the_arch_task(self):
        """The split is by package, so the map has to know it.

        A command that names `:shared:jvmTest` for an arch class runs nothing:
        the class is excluded from that task, and the command reports green.
        """
        self.assertEqual(
            ':shared:archTest',
            impact._task_for('shared/src/jvmTest', 'com.singularity.todo.arch.DiFacadeTest'),
        )

    def test_a_class_outside_the_arch_package_keeps_its_source_set_task(self):
        self.assertEqual(
            ':shared:jvmTest',
            impact._task_for('shared/src/jvmTest', 'com.singularity.todo.feature.tasks.TaskTest'),
        )

    def test_the_build_declares_the_arch_task(self):
        """The map and the build cannot disagree about the task's name."""
        build = (impact.ROOT / 'shared/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('archTest', build)


class BaseContainsHeadTest(unittest.TestCase):
    """A change that is already in the base is a THIRD answer.

    After a push the branch IS the base, so there is no difference left to
    measure. The map used to answer "this change claims no test class", which
    reads like a verdict about a change that touches no test — and the caller
    that read it printed a green line about tests it never ran.
    """

    def _sandbox(self) -> pathlib.Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name)
        subprocess.run(['git', 'init', '-q'], cwd=root, check=True)
        (root / 'config').mkdir()
        shutil.copy(
            impact.ROOT / 'config' / 'test-populations.properties',
            root / 'config' / 'test-populations.properties',
        )
        (root / 'a.txt').write_text('one\n', encoding='utf-8')
        subprocess.run(['git', 'add', '-A'], cwd=root, check=True)
        subprocess.run(
            ['git', '-c', 'user.email=t@example.com', '-c', 'user.name=test',
             'commit', '-qm', 'first'],
            cwd=root, check=True,
        )
        return root

    def _map(self, root: pathlib.Path, *args: str) -> tuple[int, str]:
        old_root = impact.ROOT
        impact.ROOT = root
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                code = impact.main(['--command', '--population', 'fast', *args])
        finally:
            impact.ROOT = old_root
        return code, out.getvalue()

    def test_a_change_already_in_the_base_is_reported_as_such(self):
        root = self._sandbox()
        code, output = self._map(root, '--base', 'HEAD')
        self.assertEqual(code, 0)
        self.assertIn(
            impact.BASE_CONTAINS_HEAD,
            output,
            "a change that is already inside the base is not a change that "
            "claims no test class",
        )

    def test_a_dirty_tree_is_not_that_answer(self):
        """An uncommitted change IS a difference, and the map answers about it."""
        root = self._sandbox()
        (root / 'b.txt').write_text('two\n', encoding='utf-8')
        code, output = self._map(root, '--base', 'HEAD')
        self.assertEqual(code, 0)
        self.assertNotIn(impact.BASE_CONTAINS_HEAD, output)
        self.assertIn(impact.EMPTY_SELECTION, output)

    def test_the_two_answers_are_different_lines(self):
        """A caller reads stdout by its line, so the two must not collide."""
        self.assertNotEqual(impact.BASE_CONTAINS_HEAD, impact.EMPTY_SELECTION)
        self.assertTrue(impact.BASE_CONTAINS_HEAD.startswith('test-impact-map:'))
        self.assertTrue(impact.EMPTY_SELECTION.startswith('test-impact-map:'))


if __name__ == '__main__':
    unittest.main()
