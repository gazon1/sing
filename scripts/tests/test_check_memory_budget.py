"""Tests for check-memory-budget.py.

The gate exists because the heap of a test fork and the heap of the Gradle
daemon were literal strings in four build scripts and in `gradle.properties`,
and the measurement behind them lived in an ADR that no build read and no gate
checked. The record is now a file — `config/docs/gradle-memory-budget.properties`
— and the gate checks the tree against it.

So the cases that matter are the ones a clean tree never shows:

  - a module that hardcodes a heap other than the recorded one is a finding,
    and the finding names the file, the line and BOTH numbers;
  - a module that declares a test task and has no budget in the record at all
    is a finding, not a silence;
  - a module that declares no heap of its own takes the recorded default, and
    the root build script has to apply that default — otherwise the module runs
    with Gradle's own default, which is not a budget;
  - a module that reads ANOTHER module's budget is a finding;
  - the daemon's heap in `gradle.properties` has to be the recorded one —
    `gradle.properties` cannot interpolate a value from another file, so the
    number is written twice and the gate is what keeps the copies in step;
  - a coverage floor has to be declared where its Kover reads it: a module
    floor in that module's own build script, and never in the root build
    script, where the root project has no Kover extension of its own;
  - and a tree that satisfies every rule produces no findings at all.
"""

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

from click.testing import CliRunner

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-memory-budget.py'
spec = importlib.util.spec_from_file_location('check_memory_budget', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_memory_budget'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


SETTINGS = """\
rootProject.name = "singularity-todo"
include(":shared")
include(":desktopApp")
include(":adr-core")
include(":detekt-rules")
"""

ROOT_BUILD = """\
subprojects {
    val memoryBudget = java.util.Properties().apply {
        rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
    }
    val defaultTestHeap = memoryBudget.getProperty("test.heap.default")
    tasks.withType<Test>().configureEach {
        maxHeapSize = defaultTestHeap
    }
}
"""

SHARED_BUILD = """\
val memoryBudget = java.util.Properties().apply {
    rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
}
val testTaskHeap = memoryBudget.getProperty("test.heap.module.shared")
    ?: throw GradleException("config/docs/gradle-memory-budget.properties declares no budget")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = testTaskHeap
}
"""

DESKTOP_BUILD = """\
val memoryBudget = java.util.Properties().apply {
    rootProject.file("config/docs/gradle-memory-budget.properties").inputStream().use { load(it) }
}
val testTaskHeap = memoryBudget.getProperty("test.heap.module.desktopApp")
    ?: throw GradleException("config/docs/gradle-memory-budget.properties declares no budget")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = testTaskHeap
}
"""

#: A module that declares no heap of its own: it takes the recorded default.
ADR_CORE_BUILD = """\
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
"""

#: A module with a floor of its own — read by that module's Kover extension.
DETEKT_RULES_BUILD = """\
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "3g"
}

val DETEKT_RULE_BRANCH_FLOOR = 60

kover {
    reports {
        total {
            verify {
                rule("detekt-rules branch coverage") {
                    bound {
                        minValue = DETEKT_RULE_BRANCH_FLOOR
                    }
                }
            }
        }
    }
}
"""

#: Another module's floor, declared in the wrong module's build script: the
#: name is the claim the reader takes away, and the script is what measures.
FOREIGN_FLOOR = """\
val DETEKT_RULE_BRANCH_FLOOR = 60

kover {
    reports {
        total {
            verify {
                rule("detekt-rules branch coverage") {
                    bound {
                        minValue = DETEKT_RULE_BRANCH_FLOOR
                    }
                }
            }
        }
    }
}
"""

RECORD = """\
# The measured memory budget of one Gradle worktree.
test.heap.default=1536m
test.heap.module.shared=1536m
test.heap.module.desktopApp=1536m
test.heap.module.adr-core=1536m
test.heap.module.detekt-rules=3g
gradle.daemon.heap=6144M
measuredAt=2026-10-22
machine=16 vCPU, 62 GiB RAM, 8 GiB swap
"""

GRADLE_PROPERTIES = """\
org.gradle.jvmargs=-Xmx6144M -Dfile.encoding=UTF-8
org.gradle.daemon.idletimeout=600000
"""


class MemoryBudgetTest(unittest.TestCase):
    """Every test task runs with the heap the record declares."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.write('settings.gradle.kts', SETTINGS)
        self.write('build.gradle.kts', ROOT_BUILD)
        self.write('shared/build.gradle.kts', SHARED_BUILD)
        self.write('desktopApp/build.gradle.kts', DESKTOP_BUILD)
        self.write('adr-core/build.gradle.kts', ADR_CORE_BUILD)
        self.write('detekt-rules/build.gradle.kts', DETEKT_RULES_BUILD)
        self.write('config/docs/gradle-memory-budget.properties', RECORD)
        self.write('gradle.properties', GRADLE_PROPERTIES)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def findings(self) -> list[str]:
        return mod.check(self.root)

    # ── the clean tree ──────────────────────────────────────────────────────

    def test_a_tree_that_satisfies_the_rule_has_no_findings(self) -> None:
        self.assertEqual(self.findings(), [])

    def test_a_module_with_no_heap_of_its_own_takes_the_default(self) -> None:
        """`:adr-core` declares no `maxHeapSize` — the root applies the default.

        The alternative is a module that runs with Gradle's own default heap,
        which is not a budget at all, and nobody would notice.
        """
        self.assertNotIn('maxHeapSize', (self.root / 'adr-core/build.gradle.kts').read_text())
        self.assertEqual(self.findings(), [])

    # ── the heap of a test task ─────────────────────────────────────────────

    def test_a_module_with_a_different_heap_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the module hardcodes a heap of its own.

        The finding has to name the file, the line and BOTH numbers — the one
        the module runs with and the one the record declares.
        """
        self.write(
            'shared/build.gradle.kts',
            SHARED_BUILD.replace('maxHeapSize = testTaskHeap', 'maxHeapSize = "2g"'),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        finding = findings[0]
        self.assertIn('shared/build.gradle.kts:', finding)
        self.assertIn('2g', finding)
        self.assertIn('1536m', finding)
        self.assertIn('test.heap.module.shared', finding)

    def test_a_module_with_no_budget_in_the_record_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the module runs tests and the record says nothing.

        This is the "a module that declares a test task and has no budget at
        all" case: the budget is not wrong, it is absent.
        """
        self.write(
            'config/docs/gradle-memory-budget.properties',
            RECORD.replace('test.heap.module.adr-core=1536m\n', ''),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('adr-core/build.gradle.kts', findings[0])
        self.assertIn('no budget for it', findings[0])
        self.assertIn('test.heap.module.adr-core', findings[0])

    def test_a_module_that_reads_another_modules_budget_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the module reads the wrong line of the record."""
        self.write(
            'shared/build.gradle.kts',
            SHARED_BUILD.replace('test.heap.module.shared', 'test.heap.module.desktopApp'),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('test.heap.module.desktopApp', findings[0])
        self.assertIn('test.heap.module.shared', findings[0])

    def test_a_module_that_writes_its_heap_outside_the_record_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the heap is read from somewhere that is not the record."""
        self.write(
            'shared/build.gradle.kts',
            SHARED_BUILD.replace('maxHeapSize = testTaskHeap', 'maxHeapSize = "1536m" * 2'),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('not from the record', findings[0])

    def test_a_root_that_does_not_apply_the_default_is_a_finding(self) -> None:
        """POSITIVE CONTROL: a module with no heap of its own has no budget.

        The root build script is what applies the recorded default to the
        modules that name no budget of their own. Without it, those modules run
        with Gradle's own default heap.
        """
        self.write(
            'build.gradle.kts',
            ROOT_BUILD.replace('    val defaultTestHeap = memoryBudget.getProperty("test.heap.default")\n', '')
            .replace('        maxHeapSize = defaultTestHeap\n', ''),
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('does not apply the recorded default', findings[0])
        self.assertIn('adr-core', findings[0])

    # ── the daemon's heap ───────────────────────────────────────────────────

    def test_a_daemon_heap_that_is_not_the_recorded_one_is_a_finding(self) -> None:
        """POSITIVE CONTROL: `gradle.properties` carries another heap.

        `gradle.properties` cannot interpolate a value from another file —
        Gradle reads it before it reads anything else — so the number is
        written there as well, and the gate is what keeps the copies in step.
        """
        self.write(
            'gradle.properties',
            GRADLE_PROPERTIES.replace('-Xmx6144M', '-Xmx4096M'),
        )
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('gradle.properties:', findings[0])
        self.assertIn('4096M', findings[0])
        self.assertIn('6144M', findings[0])

    def test_a_daemon_heap_that_is_missing_is_a_finding(self) -> None:
        self.write('gradle.properties', 'org.gradle.daemon.idletimeout=600000\n')
        findings = self.findings()
        self.assertEqual(len(findings), 1, findings)
        self.assertIn('no `org.gradle.jvmargs`', findings[0])

    # ── where the floors are declared ───────────────────────────────────────

    def test_a_module_floor_moved_to_the_root_build_script_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the floor moves to a place its Kover does not read.

        The root project has no Kover extension of its own to configure: the
        aggregation plugin owns the aggregate and reads the app floor from
        `settings.gradle.kts`, and a module floor is read from that module's
        own build script. A floor declared in the root build script is
        evaluated by no task.
        """
        root = self.root / 'build.gradle.kts'
        root.write_text(
            ROOT_BUILD
            + "\nval DETEKT_RULE_BRANCH_FLOOR = 60\n",
            encoding='utf-8',
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('build.gradle.kts:', findings[0])
        self.assertIn('DETEKT_RULE_BRANCH_FLOOR', findings[0])

    def test_a_module_floor_bound_to_a_constant_of_another_file_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the rule stays, the constant moves away."""
        self.write(
            'detekt-rules/build.gradle.kts',
            DETEKT_RULES_BUILD.replace(
                'val DETEKT_RULE_BRANCH_FLOOR = 60\n\n', '',
            ),
        )
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('DETEKT_RULE_BRANCH_FLOOR', findings[0])
        self.assertIn('detekt-rules/build.gradle.kts', findings[0])

    def test_a_module_floor_in_its_own_build_script_is_not_a_finding(self) -> None:
        """The floor that works: the module declares it where Kover reads it."""
        findings = self.findings()
        self.assertEqual([f for f in findings if 'FLOOR' in f or 'floor' in f], [])

    def test_a_module_floor_declared_in_another_module_is_a_finding(self) -> None:
        """POSITIVE CONTROL: the rule names one module and sits in another's script.

        The rule's name is the claim the reader takes away — and the script it
        is declared in is what the build measures. A rule that names
        `:detekt-rules` inside `:shared`'s build script measures `:shared`.
        """
        self.write('shared/build.gradle.kts', SHARED_BUILD + FOREIGN_FLOOR)
        findings = self.findings()
        self.assertTrue(findings)
        self.assertIn('detekt-rules', findings[0])
        self.assertIn('shared/build.gradle.kts', findings[0])

    # ── the record itself ───────────────────────────────────────────────────

    def test_a_missing_record_is_a_configuration_error(self) -> None:
        (self.root / 'config/docs/gradle-memory-budget.properties').unlink()
        with self.assertRaises(FileNotFoundError):
            self.findings()

    def test_an_incomplete_record_is_a_configuration_error(self) -> None:
        self.write(
            'config/docs/gradle-memory-budget.properties',
            RECORD.replace('gradle.daemon.heap=6144M\n', ''),
        )
        with self.assertRaises(FileNotFoundError):
            self.findings()

    # ── the command line ────────────────────────────────────────────────────

    def test_the_root_option_points_the_gate_at_another_tree(self) -> None:
        """The `--root` flag is how the unit tests aim the gate at a tree."""
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 0, result.output)
        self.assertIn('OK', result.output)

        self.write(
            'shared/build.gradle.kts',
            SHARED_BUILD.replace('maxHeapSize = testTaskHeap', 'maxHeapSize = "2g"'),
        )
        result = runner.invoke(mod.cli, ['--root', str(self.root)])
        self.assertEqual(result.exit_code, 1, result.output)
        self.assertIn('2g', result.output)

    def test_a_root_that_is_not_a_directory_is_a_configuration_error(self) -> None:
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root / 'nowhere')])
        self.assertEqual(result.exit_code, 3, result.output)

    def test_the_json_verdict_carries_the_contract(self) -> None:
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root), '--json'])
        self.assertEqual(result.exit_code, 0, result.output)
        verdict = json.loads(result.output)
        self.assertTrue(verdict['passed'])
        self.assertEqual(verdict['blocking_errors'], [])
        self.assertEqual(verdict['warnings'], [])

        self.write(
            'shared/build.gradle.kts',
            SHARED_BUILD.replace('maxHeapSize = testTaskHeap', 'maxHeapSize = "2g"'),
        )
        result = runner.invoke(mod.cli, ['--root', str(self.root), '--json'])
        self.assertEqual(result.exit_code, 1, result.output)
        verdict = json.loads(result.output)
        self.assertFalse(verdict['passed'])
        self.assertTrue(verdict['blocking_errors'])

    def test_the_quiet_flag_prints_nothing_on_a_clean_tree(self) -> None:
        runner = CliRunner()
        result = runner.invoke(mod.cli, ['--root', str(self.root), '--quiet'])
        self.assertEqual(result.exit_code, 0, result.output)
        self.assertEqual(result.output.strip(), '')


if __name__ == '__main__':
    unittest.main()
