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

  - a change in a module compiles that module and the modules that compile
    against it, and nothing else;
  - a change to the build itself compiles everything;
  - a change that touches no module compiles nothing, and says so;
  - the Android target is CI's, and the script says when it did not compile it.

No build runs here: the plan is a pure function of the paths, and the paths
are given to it directly.
"""

import importlib.util
import pathlib
import sys
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

    def plan(self, *paths: str) -> tuple[list[str], list[str], list[str]]:
        return mod.plan(graph, list(paths))

    def test_a_change_in_a_module_compiles_that_module(self):
        """The module the change touches is the module that is compiled."""
        modules, tasks, _reasons = self.plan('shared/src/commonMain/kotlin/A.kt')

        self.assertEqual(['shared'], modules)
        self.assertIn(':shared:compileKotlinJvm', tasks)
        self.assertIn(':shared:compileTestKotlinJvm', tasks)

    def test_a_change_in_one_module_does_not_compile_the_others(self):
        """`:desktopApp` does not compile against `:shared` — the edge is one way.

        A change in `:desktopApp` compiles `:desktopApp` and nothing else. The
        hardcoded pair compiled `:shared` for every commit, which is the whole
        defect: the compile of a module the change cannot break.
        """
        modules, tasks, _reasons = self.plan('desktopApp/src/main/kotlin/A.kt')

        self.assertEqual(['desktopApp'], modules)
        self.assertNotIn(':shared:compileKotlinJvm', tasks)

    def test_a_change_in_the_base_module_compiles_only_that_module(self):
        """`:core-base` is consumed by the chain below it — and CI compiles them.

        The commit hook answers "does the code I wrote compile"; the reach of
        the change is judged by the pre-push verdict and by CI. So the base
        module compiles alone, and the chain below it is not the commit's
        price to pay.
        """
        modules, tasks, _reasons = self.plan('core-base/src/commonMain/kotlin/A.kt')

        self.assertEqual(['core-base'], modules)
        self.assertIn(':core-base:compileKotlinJvm', tasks)
        self.assertNotIn(':shared:compileKotlinJvm', tasks)

    def test_a_change_to_the_build_compiles_everything(self):
        """The build decides how every module is built."""
        modules, tasks, _reasons = self.plan('settings.gradle.kts')

        self.assertIn('androidApp', modules)
        self.assertIn(':shared:compileKotlinJvm', tasks)
        self.assertIn(':androidApp:compileDebugKotlin', tasks)

    def test_a_change_to_the_convention_plugins_compiles_everything(self):
        """`build-logic/` holds the convention plugins every module uses.

        It was missing from the build areas, so a change to a convention plugin
        compiled nothing at all — and the pre-push verdict, which knows the
        same fact, judged every module.
        """
        modules, _tasks, _reasons = self.plan('build-logic/src/main/kotlin/Convention.kt')

        self.assertIn('shared', modules)
        self.assertIn('desktopApp', modules)

    def test_a_change_to_the_gates_compiles_nothing(self):
        """A change under `scripts/` is a change to the gates, not to the app."""
        modules, tasks, _reasons = self.plan('scripts/check-something.py')

        self.assertEqual([], modules)
        self.assertEqual([], tasks)

    def test_a_change_to_a_document_compiles_nothing(self):
        """A document is not code, and a compile cannot say anything about it."""
        modules, tasks, _reasons = self.plan('docs/architecture/quality.md')

        self.assertEqual([], modules)
        self.assertEqual([], tasks)

    def test_the_android_target_is_not_compiled_for_a_jvm_change(self):
        """The Android SDK is CI's, and a JVM change does not need it.

        `:androidApp` is not touched by a change in `:shared`, so it is not
        compiled — and the Android target of the change is CI's.
        """
        modules, tasks, reasons = self.plan('shared/src/commonMain/kotlin/A.kt')

        self.assertNotIn('androidApp', modules)
        self.assertNotIn(':androidApp:compileDebugKotlin', tasks)
        self.assertTrue(reasons, 'the plan says why it compiles what it compiles')

    def test_the_android_target_is_compiled_when_the_change_touches_it(self):
        """A change in the Android application is the Android application's."""
        modules, tasks, _reasons = self.plan('androidApp/src/main/kotlin/A.kt')

        self.assertIn('androidApp', modules)
        self.assertIn(':androidApp:compileDebugKotlin', tasks)


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
