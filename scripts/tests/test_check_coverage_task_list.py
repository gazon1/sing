"""Tests for check-coverage-task-list.py.

The gate exists because the list of test tasks that feed the aggregated
coverage report is written by hand, in the root build script. A module added
to the build is not added to that list, so its tests never run in the coverage
job — and a module whose classes ARE in the denominator is then scored as 0%
covered, which reads like a coverage problem and is a measurement one.

So the cases that matter are the ones a clean tree never shows:

  - a project the app pulls in, that has tests, and that the list does not
    name is a finding: the report says nothing about it while the reader
    assumes it does;
  - a module with tests and NO JVM `Test` task of its own is the same
    finding: `:androidApp` declares no `Test` task, so a scan of the build
    scripts for one never sees it, and the module whose classes are in the
    denominator goes unmeasured while every gate stays green;
  - a project the coverage job does not build is NOT a finding: it
    contributes no classes to the report, and asking about its tests would
    be asking about a configuration this gate does not describe;
  - a project excluded from the coverage scope is not a finding: that is the
    other half of the rule, and it must be said out loud, not inferred;
  - a project in the list that the build does not contain is a finding: a
    typo there is a task that does not run, and a module that drops out of
    the measurement looks exactly like a module with no tests;
  - a project that is included only behind a flag (`:pro`) is out of scope:
    the free configuration does not contain it, so its classes are not in the
    denominator either.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-coverage-task-list.py'
spec = importlib.util.spec_from_file_location('check_coverage_task_list', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_coverage_task_list'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


SETTINGS = """\
include(":shared")
include(":adr-core")
include(":adr-cli")
"""

BUILD = """\
tasks.register("koverReport") {
    dependsOn(
        ":shared:jvmTest",
        ":adr-core:jvmTest",
        ":koverXmlReport",
    )
}
"""

#: A module with a JVM test task…
TEST_TASK = "tasks.withType<Test>().configureEach {\n    useJUnitPlatform {\n    }\n}\n"

#: …that the app pulls in: the dependency is what puts its classes in the
#: denominator, exactly as `shared` pulls in `:adr-core` in the repository.
SHARED = TEST_TASK + '\ndependencies {\n    implementation(project(":adr-core"))\n}\n'
ADR_CORE = TEST_TASK
ADR_CLI = TEST_TASK

#: The Android application module: no JVM `Test` task anywhere in its build
#: script, and a suite on disk all the same.
ANDROID_APP = "plugins {\n    alias(libs.plugins.androidApplication)\n}\n"
ANDROID_APP_TEST = "package com.singularity.todo\n\nclass DeviceTest\n"


class CoverageTaskListTest(unittest.TestCase):
    """Every project the coverage job builds is measured, or excluded."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self._original_root = mod.ROOT
        mod.ROOT = self.root
        (self.root / 'settings.gradle.kts').write_text(SETTINGS, encoding='utf-8')
        (self.root / 'build.gradle.kts').write_text(BUILD, encoding='utf-8')
        self.write('shared/build.gradle.kts', SHARED)
        self.write('adr-core/build.gradle.kts', ADR_CORE)
        self.write('adr-cli/build.gradle.kts', ADR_CLI)

    def tearDown(self) -> None:
        mod.ROOT = self._original_root
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def findings(self) -> list[str]:
        return mod.check()

    def test_a_clean_tree_has_no_findings(self) -> None:
        self.assertEqual(self.findings(), [])

    def test_a_project_the_app_pulls_in_that_is_not_measured_is_a_finding(self) -> None:
        # `:adr-core` is a dependency of `:shared`, so the coverage job builds
        # it and its classes are in the denominator — and its tests never ran.
        self.write(
            'build.gradle.kts',
            BUILD.replace('        ":adr-core:jvmTest",\n', ''),
        )
        findings = self.findings()
        self.assertTrue(
            any(':adr-core' in f for f in findings),
            f'expected the unmeasured project to be reported: {findings}',
        )

    def test_a_module_without_a_jvm_test_task_that_is_not_measured_is_a_finding(self) -> None:
        # `:androidApp` declares no `Test` task at all: the suite is on disk,
        # and the build-script scan never sees it.
        self.write(
            'settings.gradle.kts',
            SETTINGS + 'include(":androidApp")\n',
        )
        self.write('androidApp/build.gradle.kts', ANDROID_APP)
        self.write(
            'androidApp/src/androidTest/kotlin/com/singularity/todo/DeviceTest.kt',
            ANDROID_APP_TEST,
        )
        findings = self.findings()
        self.assertTrue(
            any(':androidApp' in f for f in findings),
            f'expected the unmeasured module to be reported: {findings}',
        )

    def test_the_same_module_in_the_list_is_not_a_finding(self) -> None:
        self.write(
            'settings.gradle.kts',
            SETTINGS + 'include(":androidApp")\n',
        )
        self.write('androidApp/build.gradle.kts', ANDROID_APP)
        self.write(
            'androidApp/src/androidTest/kotlin/com/singularity/todo/DeviceTest.kt',
            ANDROID_APP_TEST,
        )
        self.write(
            'build.gradle.kts',
            BUILD.replace('        ":koverXmlReport",', '        ":androidApp:test",\n        ":koverXmlReport",'),
        )
        self.assertEqual(self.findings(), [])

    def test_a_project_the_coverage_job_does_not_build_is_not_a_finding(self) -> None:
        # `:adr-cli` has tests, is in the build, and nothing the coverage job
        # builds depends on it: its classes are not in the report, so there is
        # nothing for the list to measure.
        self.assertEqual(self.findings(), [])

    def test_a_project_excluded_from_the_coverage_scope_is_not_a_finding(self) -> None:
        self.write(
            'build.gradle.kts',
            BUILD.replace('        ":adr-core:jvmTest",\n', ''),
        )
        self.write(
            'settings.gradle.kts',
            SETTINGS + '\nkover {\n    skipProjects(":adr-core")\n}\n',
        )
        self.assertEqual(self.findings(), [])

    def test_a_measured_project_the_build_does_not_contain_is_a_finding(self) -> None:
        self.write(
            'build.gradle.kts',
            BUILD.replace('":adr-core:jvmTest",', '":ghost-module:jvmTest",'),
        )
        findings = self.findings()
        self.assertTrue(
            any(':ghost-module' in f for f in findings),
            f'expected the unknown project to be reported: {findings}',
        )

    def test_a_project_included_only_behind_a_flag_is_out_of_scope(self) -> None:
        self.write('pro/build.gradle.kts', TEST_TASK)
        self.write(
            'settings.gradle.kts',
            SETTINGS + '\nif (withPro) {\n    include(":pro")\n}\n',
        )
        self.assertEqual(self.findings(), [])


if __name__ == '__main__':
    unittest.main()
