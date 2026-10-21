"""Tests for `scripts/changed-modules.py` — what a change makes worth running.

The mapper answers a question with consequences: it decides which checks a change
needs. Two ways to be wrong, and both are quiet:

* too few — a change that breaks a downstream module passes the lane and fails in
  CI, and the lane taught the developer that it is enough;
* too many — the lane costs what the full loop costs, and nobody uses it.

So the edges are pinned: `:core-base` reaches `:adr-core`, then `:shared`, then the
four modules that compile against it; `:adr-cli` depends on `:adr-core` and on
nothing else, because it was deliberately cut off from `:shared`.
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


if __name__ == '__main__':
    unittest.main()
