"""Tests for `scripts/changed-modules.py` — what a change makes worth running.

The mapper answers a question with consequences: it decides which checks a change
needs. Two ways to be wrong, and both are quiet:

* too few — a change that breaks a downstream module passes the lane and fails in
  CI, and the lane taught the developer that it is enough;
* too many — the lane costs what the full loop costs, and nobody uses it.

So the edges are pinned: `:core-base` reaches `:adr-core`, then `:shared`, then the
four modules that compile against it; `:adr-cli` depends on `:adr-core` and on
nothing else, because it was deliberately cut off from `:shared`.

The module LIST is read out of `settings.gradle.kts`, and that is the part that
was wrong: the list used to be a table in the script, the table knew eight
modules, and the build declares ten. The two it did not know were the two
nobody judged — `:tools:unwritten-properties` (nested, two directory segments
deep) and `:pro` (included only behind `-PwithPro`). A module the graph does
not know is a module nothing runs, and nothing says so.
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


changed = _load('changed_modules', 'changed-modules.py')


class PlanTest(unittest.TestCase):
    """What the plan says for a change."""

    def test_a_change_in_shared_reaches_the_modules_that_compile_against_it(self):
        tasks, _reasons = changed.plan(['shared/src/commonMain/kotlin/Foo.kt'])

        self.assertIn(':shared:jvmTest', tasks)
        self.assertIn(':androidApp:assembleDebug', tasks)
        self.assertIn(':desktopApp:test', tasks)
        self.assertIn(':mcp-server:test', tasks)

    def test_a_change_in_shared_does_not_reach_adr_cli(self):
        """:adr-cli depends on :adr-core, not on :shared — it was cut off on purpose."""
        tasks, _reasons = changed.plan(['shared/src/commonMain/kotlin/Foo.kt'])

        self.assertNotIn(':adr-cli:test', tasks)
        self.assertNotIn(':adr-cli:detekt', tasks)

    def test_a_change_in_core_base_reaches_everything_downstream(self):
        tasks, _reasons = changed.plan(['core-base/src/commonMain/kotlin/Foo.kt'])

        for task in (
            ':core-base:detekt',
            ':adr-core:jvmTest',
            ':adr-cli:test',
            ':shared:jvmTest',
            ':mcp-server:test',
        ):
            self.assertIn(task, tasks)

    def test_a_change_in_the_rules_repeats_the_detekt_of_their_consumers(self):
        """:detekt-rules changes what the other modules report, not what they do."""
        tasks, _reasons = changed.plan(['detekt-rules/src/main/kotlin/Foo.kt'])

        self.assertIn(':detekt-rules:test', tasks)
        self.assertIn(':shared:detekt', tasks)
        self.assertIn(':adr-cli:detekt', tasks)
        self.assertNotIn(
            ':shared:jvmTest',
            tasks,
            "the shared tests do not read the detekt rules, so they are not the answer",
        )

    def test_a_change_in_the_gates_runs_the_gate_suite(self):
        tasks, _reasons = changed.plan(['scripts/check-gate-wiring.py'])

        self.assertIn('scripts/ci/static-gates.sh', tasks)

    def test_a_change_under_the_gate_tests_runs_them(self):
        tasks, _reasons = changed.plan(['scripts/tests/test_check_gate_wiring.py'])

        self.assertIn('scripts/tests', tasks)

    def test_a_change_in_the_build_runs_everything(self):
        tasks, _reasons = changed.plan(['gradle.properties'])

        self.assertIn(':shared:jvmTest', tasks)
        self.assertIn(':mcp-server:test', tasks)
        self.assertIn(':adr-core:jvmTest', tasks)
        self.assertIn('scripts/ci/static-gates.sh', tasks)

    def test_a_change_to_the_convention_plugins_runs_everything(self):
        """`build-logic/` holds the convention plugins every module uses.

        A change to one of them changes how every module is built, so the plan
        has to be the whole tree. The area was missing, and the plan for such a
        change was "nothing runs" — while the pre-push verdict, which knows
        the same fact, judged every module.
        """
        tasks, _reasons = changed.plan(['build-logic/src/main/kotlin/Convention.kt'])

        self.assertIn(':shared:jvmTest', tasks)
        self.assertIn(':desktopApp:test', tasks)
        self.assertIn(':detekt-rules:test', tasks)
        self.assertIn('scripts/ci/static-gates.sh', tasks)

    def test_a_path_no_module_owns_runs_nothing(self):
        tasks, reasons = changed.plan(['README.md'])

        self.assertEqual([], tasks)
        self.assertTrue(reasons, 'the reason it ran nothing has to be given')

    def test_the_same_task_is_never_listed_twice(self):
        tasks, _reasons = changed.plan([
            'shared/src/commonMain/kotlin/Foo.kt',
            'core-base/src/commonMain/kotlin/Bar.kt',
            'gradle.properties',
        ])

        self.assertEqual(len(tasks), len(set(tasks)))


class SettingsParsingTest(unittest.TestCase):
    """The module list is read out of the build file, not written here.

    The reader is a scanner, not a Kotlin parser, and a scanner has two ways to
    be wrong quietly: it can read a string as code (the settings file holds a
    Maven URL, and a `//` inside that string is not a comment), and it can read
    a function whose name starts with `include` as an include call.
    """

    def test_the_graph_knows_every_module_the_build_declares(self):
        modules = changed.load_modules(changed.SETTINGS)

        for name in (
            'shared',
            'androidApp',
            'desktopApp',
            'mcp-server',
            'core-base',
            'adr-core',
            'adr-cli',
            'detekt-rules',
            'tools:unwritten-properties',
        ):
            self.assertIn(name, modules, f'the build declares :{name}')

    def test_a_nested_module_owns_the_directory_gradle_maps_it_to(self):
        """`:tools:unwritten-properties` lives in `tools/unwritten-properties`.

        Two segments, which is the whole reason the CI annotation glob
        `*/build/test-results/**/*.xml` skipped its JUnit XML.
        """
        module = changed.load_modules(changed.SETTINGS)['tools:unwritten-properties']

        self.assertEqual('tools/unwritten-properties', module.directory)
        self.assertEqual(':tools:unwritten-properties', module.gradle_path)

    def test_a_conditional_include_is_known_and_marked_as_conditional(self):
        """:pro is included only behind `-PwithPro` — known, and marked as such."""
        modules = changed.load_modules(changed.SETTINGS)

        self.assertIn('pro', modules)
        self.assertTrue(modules['pro'].conditional)

    def test_an_unconditional_include_is_not_marked_as_conditional(self):
        modules = changed.load_modules(changed.SETTINGS)

        self.assertFalse(modules['shared'].conditional)

    def test_an_include_inside_a_condition_is_conditional(self):
        modules = changed.parse_modules(
            'if (withPro) {\n'
            '    include(":pro")\n'
            '}\n'
            'include(":free")\n',
        )

        self.assertTrue(modules[0].conditional)
        self.assertFalse(modules[1].conditional)

    def test_a_url_in_a_string_is_not_a_comment(self):
        """The `//` in a Maven URL must not cut the line in half."""
        modules = changed.parse_modules(
            'maven { url = uri("https://example.com/maven") }\n'
            'include(":after")\n',
        )

        self.assertEqual(['after'], [module.name for module in modules])
        self.assertFalse(any(module.conditional for module in modules))

    def test_a_brace_in_a_string_is_not_a_block(self):
        modules = changed.parse_modules(
            'val label = "a } b"\n'
            'include(":after")\n',
        )

        self.assertEqual(['after'], [module.name for module in modules])

    def test_a_function_whose_name_starts_with_include_is_not_a_module(self):
        """`includeGroupAndSubgroups("androidx")` is not `include(":androidx")`."""
        modules = changed.parse_modules(
            'mavenContent { includeGroupAndSubgroups("androidx") }\n'
            'include(":real")\n',
        )

        self.assertEqual(['real'], [module.name for module in modules])

    def test_an_include_with_a_name_that_is_not_a_literal_is_not_recorded(self):
        """A module the graph cannot name is a module it cannot judge."""
        modules = changed.parse_modules('include(someVariable)\n')

        self.assertEqual([], modules)


class GraphConsistencyTest(unittest.TestCase):
    """The graph and the build have to agree.

    A module the build declares and the graph does not judge is a module nobody
    runs — the defect that left `:tools:unwritten-properties` without a verdict.
    A module the graph judges and the build does not declare is a module that
    cannot be built at all. Both are said out loud instead of absorbed.
    """

    def test_the_graph_and_the_build_agree(self):
        self.assertEqual([], changed.consistency_errors())

    def test_a_module_the_build_declares_and_the_graph_ignores_is_reported(self):
        errors = changed.consistency_errors(
            changed.load_modules(changed.SETTINGS),
            changed.ModuleChecks({'shared': [':shared:detekt']}),
        )

        self.assertTrue(
            any('tools:unwritten-properties' in error for error in errors),
            errors,
        )

    def test_a_conditional_module_is_not_a_module_nobody_judges(self):
        """:pro is judged by the configuration that has it — that is the decision."""
        errors = changed.consistency_errors(
            changed.load_modules(changed.SETTINGS),
            changed.ModuleChecks({name: [f':{name}:detekt'] for name in changed.TASKS.all_modules()}),
        )

        self.assertEqual([], errors)

    def test_a_module_the_build_does_not_declare_is_reported(self):
        errors = changed.consistency_errors(
            {'shared': changed.Module(name='shared', gradle_path=':shared', directory='shared', conditional=False)},
            changed.TASKS,
        )

        self.assertTrue(any('does not declare it' in error for error in errors), errors)


class NestedModulePlanTest(unittest.TestCase):
    """A nested module is judged, and it is a leaf.

    `:tools:unwritten-properties` is a KSP processor that no module compiles
    against, so a change in it breaks it and nothing else. The depth of the
    directory makes no difference to that rule: what a change reaches is a fact
    about the dependency edges, not about how deep the module sits.
    """

    def test_a_change_in_the_nested_module_runs_its_tests(self):
        tasks, _reasons = changed.plan(['tools/unwritten-properties/src/main/kotlin/Foo.kt'])

        self.assertEqual([':tools:unwritten-properties:test'], tasks)

    def test_a_change_in_the_nested_module_reaches_nothing_else(self):
        tasks, _reasons = changed.plan(['tools/unwritten-properties/src/main/kotlin/Foo.kt'])

        self.assertNotIn(':shared:jvmTest', tasks)
        self.assertNotIn(':shared:detekt', tasks)

    def test_a_build_wide_change_judges_the_nested_module_too(self):
        tasks, _reasons = changed.plan(['gradle.properties'])

        self.assertIn(':tools:unwritten-properties:test', tasks)

    def test_the_nested_module_has_no_detekt_task_to_invent(self):
        """The pre-push verdict turns module names into detekt tasks.

        `:tools:unwritten-properties` applies the plain `kotlin("jvm")` plugin,
        so it has no detekt task — and a graph that invented one would hand the
        hook a task the build cannot resolve.
        """
        self.assertNotIn('tools:unwritten-properties', list(changed.TASKS))
        self.assertIn('tools:unwritten-properties', changed.TASKS.all_modules())


class ConditionalModulePlanTest(unittest.TestCase):
    """A conditional module is known, and the decision is written down.

    `:pro` exists only in the `-PwithPro` build, so its checks cannot be named
    by a plan that runs in the free build. The module is still recognized — a
    change under `pro/` is a change in a module, not a path nobody owns — and
    the plan says why it schedules nothing.
    """

    def test_a_change_in_a_conditional_module_is_recognized_as_a_module(self):
        tasks, reasons = changed.plan(['pro/src/main/kotlin/Foo.kt'])

        self.assertEqual([], tasks)
        self.assertFalse(
            any('no module owns it' in reason for reason in reasons),
            reasons,
        )

    def test_a_change_in_a_conditional_module_says_why_it_runs_nothing(self):
        _tasks, reasons = changed.plan(['pro/src/main/kotlin/Foo.kt'])

        self.assertTrue(any('pro' in reason for reason in reasons), reasons)


class FallbackTest(unittest.TestCase):
    """A tree without a build file still has to be judgeable.

    The hook fixture in `scripts/tests/test_pre_push_hook.py` copies this
    script into an empty repository, and the verdict reads the graph from
    there. There is no `settings.gradle.kts` in that tree, and a graph that
    gave up would judge nothing — which reads as a clean push.
    """

    def test_a_tree_without_a_build_file_knows_the_judged_modules(self):
        modules = changed.load_modules(pathlib.Path('/nonexistent/settings.gradle.kts'))

        self.assertIn('shared', modules)
        self.assertIn('tools:unwritten-properties', modules)

    def test_a_tree_without_a_build_file_still_maps_a_path_to_its_module(self):
        modules = changed.load_modules(pathlib.Path('/nonexistent/settings.gradle.kts'))
        prefixes = changed.path_modules(modules)

        self.assertEqual('shared', prefixes['shared/'])


if __name__ == '__main__':
    unittest.main()
