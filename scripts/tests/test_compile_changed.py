"""Tests for `scripts/compile-changed.py` — the compile follows the change.

The defect this exists for: the pre-commit hook compiled `:shared` and
`:desktopApp` for EVERY commit, whatever the change was. A change to a gate
script paid for a Kotlin compile that could not say anything about it, and a
change that did not touch those modules was blocked by somebody else's
breakage in them. The ADR of 2026-10-23 says the price plainly: "a breakage
that is NOT yours blocks your commits in that worktree, and the only way past
it is `--no-verify`".

So the compile set is computed from the module graph — the same graph the
local verification plan and the pre-push verdict read. These tests hold that
computation to what it claims:

  - a change in a module compiles that module, and nothing else;
  - a change to the build itself compiles everything;
  - a change that touches no module compiles nothing, and says so;
  - a module the graph does not name a compile task for is an ERROR, not an
    empty plan — a silent skip must not look like a check that passed;
  - a rename names two paths, so the module that LOST the file is compiled;
  - the Android target is CI's, and the script says when it did not compile it.

No build runs here: the plan is a pure function of the paths, and the paths
are given to it directly.
"""

import importlib.util
import pathlib
import sys
import types
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _load(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


graph = _load('changed_modules', REPO_ROOT / 'scripts' / 'changed-modules.py')
mod = _load('compile_changed', REPO_ROOT / 'scripts' / 'compile-changed.py')


class CompilePlanTest(unittest.TestCase):
    """Which modules a change reaches."""

    def plan(self, *paths: str) -> tuple[list[str], list[str], list[str], list[str]]:
        return mod.plan(graph, list(paths))

    def test_a_change_in_a_module_compiles_that_module(self):
        """The module the change touches is the module that is compiled."""
        modules, tasks, _reasons, _errors = self.plan('shared/src/commonMain/kotlin/A.kt')

        self.assertEqual(['shared'], modules)
        self.assertIn(':shared:compileKotlinJvm', tasks)
        self.assertIn(':shared:compileTestKotlinJvm', tasks)

    def test_a_change_in_one_module_does_not_compile_the_others(self):
        """`:desktopApp` does not compile against `:shared` — the edge is one way.

        A change in `:desktopApp` compiles `:desktopApp` and nothing else. The
        hardcoded pair compiled `:shared` for every commit, which is the whole
        defect: the compile of a module the change cannot break.
        """
        modules, tasks, _reasons, _errors = self.plan('desktopApp/src/main/kotlin/A.kt')

        self.assertEqual(['desktopApp'], modules)
        self.assertNotIn(':shared:compileKotlinJvm', tasks)

    def test_a_change_in_the_base_module_compiles_only_that_module(self):
        """`:core-base` is consumed by the chain below it — and CI compiles them.

        The commit hook answers "does the code I wrote compile"; the reach of
        the change is judged by the pre-push verdict and by CI. So the base
        module compiles alone, and the chain below it is not the commit's
        price to pay.
        """
        modules, tasks, _reasons, _errors = self.plan('core-base/src/commonMain/kotlin/A.kt')

        self.assertEqual(['core-base'], modules)
        self.assertIn(':core-base:compileKotlinJvm', tasks)
        self.assertNotIn(':shared:compileKotlinJvm', tasks)

    def test_a_change_to_the_build_compiles_everything(self):
        """The build decides how every module is built."""
        modules, tasks, _reasons, _errors = self.plan('settings.gradle.kts')

        self.assertIn('androidApp', modules)
        self.assertIn(':shared:compileKotlinJvm', tasks)
        self.assertIn(':androidApp:compileDebugKotlin', tasks)

    def test_a_change_to_the_convention_plugins_compiles_everything(self):
        """`build-logic/` holds the convention plugins every module uses.

        It was missing from the build areas, so a change to a convention plugin
        compiled nothing at all — and the pre-push verdict, which knows the
        same fact, judged every module.
        """
        modules, _tasks, _reasons, _errors = self.plan('build-logic/src/main/kotlin/Convention.kt')

        self.assertIn('shared', modules)
        self.assertIn('desktopApp', modules)

    def test_a_change_to_the_gates_compiles_nothing(self):
        """A change under `scripts/` is a change to the gates, not to the app."""
        modules, tasks, _reasons, _errors = self.plan('scripts/check-something.py')

        self.assertEqual([], modules)
        self.assertEqual([], tasks)

    def test_a_change_to_a_document_compiles_nothing(self):
        """A document is not code, and a compile cannot say anything about it."""
        modules, tasks, _reasons, _errors = self.plan('docs/architecture/quality.md')

        self.assertEqual([], modules)
        self.assertEqual([], tasks)

    def test_the_android_target_is_not_compiled_for_a_jvm_change(self):
        """The Android SDK is CI's, and a JVM change does not need it.

        `:androidApp` is not touched by a change in `:shared`, so it is not
        compiled — and the Android target of the change is CI's.
        """
        modules, tasks, reasons, _errors = self.plan('shared/src/commonMain/kotlin/A.kt')

        self.assertNotIn('androidApp', modules)
        self.assertNotIn(':androidApp:compileDebugKotlin', tasks)
        self.assertTrue(reasons, 'the plan says why it compiles what it compiles')

    def test_the_android_target_is_compiled_when_the_change_touches_it(self):
        """A change in the Android application is the Android application's."""
        modules, tasks, _reasons, _errors = self.plan('androidApp/src/main/kotlin/A.kt')

        self.assertIn('androidApp', modules)
        self.assertIn(':androidApp:compileDebugKotlin', tasks)


class TheGraphCannotFallBehindTest(unittest.TestCase):
    """A graph that has fallen behind the build must say so, not skip in silence."""

    def plan(self, *paths: str) -> tuple[list[str], list[str], list[str], list[str]]:
        return mod.plan(graph, list(paths))

    def test_a_module_the_graph_cannot_compile_is_an_error(self):
        """A module with no compile entry is an error, not an empty plan.

        The commit gate is the last thing that runs before a commit exists. If
        the graph stopped knowing a module, the gate would skip it without a
        word and the commit would pass having said nothing about it.
        """
        broken = types.SimpleNamespace(
            BUILD_AREAS=graph.BUILD_AREAS,
            GATE_AREAS=graph.GATE_AREAS,
            PATH_MODULES=graph.PATH_MODULES,
            COMPILE_TASKS={
                module: tasks for module, tasks in graph.COMPILE_TASKS.items()
                if module != 'shared'
            },
            NOT_COMPILED_LOCALLY=graph.NOT_COMPILED_LOCALLY,
        )

        modules, tasks, _reasons, errors = mod.plan(
            broken, ['shared/src/commonMain/kotlin/A.kt'])

        self.assertEqual([], modules)
        self.assertEqual([], tasks)
        self.assertEqual(1, len(errors), 'the module the graph forgot is an error')
        self.assertIn('shared', errors[0])

    def test_a_module_that_is_not_compiled_locally_says_why(self):
        """An empty compile list needs a reason, or the skip is silent."""
        modules, tasks, reasons, errors = self.plan('pro/src/main/kotlin/Thing.kt')

        self.assertEqual([], modules)
        self.assertEqual([], tasks)
        self.assertEqual([], errors)
        self.assertTrue(
            any('not compiled locally' in reason for reason in reasons),
            f'the plan says why it did not compile the module: {reasons}',
        )

    def test_the_android_skip_is_written_down(self):
        """A module that leaves the plan takes its reason with it.

        A check that did not run must not look like a check that passed, so the
        skip is not a silent removal from the list.
        """
        modules, tasks, skipped = mod.without_sdk(
            graph, ['androidApp', 'shared'],
            [':androidApp:compileDebugKotlin', ':shared:compileKotlinJvm'],
            sdk_present=False,
        )

        self.assertEqual(['shared'], modules)
        self.assertEqual([':shared:compileKotlinJvm'], tasks)
        self.assertEqual(1, len(skipped))
        self.assertIn('Android SDK', skipped[0])

    def test_the_android_module_stays_when_the_sdk_is_there(self):
        """With the SDK reachable there is nothing to skip."""
        modules, tasks, skipped = mod.without_sdk(
            graph, ['androidApp'],
            [':androidApp:compileDebugKotlin'],
            sdk_present=True,
        )

        self.assertEqual(['androidApp'], modules)
        self.assertEqual([':androidApp:compileDebugKotlin'], tasks)
        self.assertEqual([], skipped)


class TheBaseIsHonestTest(unittest.TestCase):
    """A base that does not resolve is a silent loss, and it says so."""

    def test_a_base_that_does_not_resolve_is_reported(self):
        """The ref the change is measured against has to exist.

        A base that does not resolve means the committed part of the change is
        not counted at all — the plan would look like a plan for the whole
        change while covering only the working tree.
        """
        self.assertFalse(mod.base_exists('origin/there-is-no-such-remote'))
        self.assertTrue(mod.base_exists('HEAD'))

    def test_a_missing_base_stops_the_gate(self):
        """The gate stops instead of compiling less than it says."""
        self.assertEqual(
            mod.PLAN_FAILED,
            mod.main(['--base', 'origin/there-is-no-such-remote', '--list']),
        )


class TheChangeIsMeasuredTest(unittest.TestCase):
    """The paths the plan is built from."""

    def test_the_graph_reads_the_working_tree_too(self):
        """A change that is not committed yet is still a change.

        The hook runs on changes that are staged but not committed, and a plan
        that only looked at the commits would compile nothing exactly when it
        is needed most.
        """
        source = (REPO_ROOT / 'scripts' / 'changed-modules.py').read_text(encoding='utf-8')

        self.assertIn('git', source)
        self.assertIn('--porcelain', source)


if __name__ == '__main__':
    unittest.main()
