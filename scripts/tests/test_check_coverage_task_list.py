"""Tests for check-coverage-task-list.py.

The gate exists because the list of test tasks that feed the aggregated
coverage report is written by hand, in the root build script. A module added
to the build is not added to that list, so its tests never run in the coverage
job — and a module whose classes ARE in the denominator is then scored as 0%
covered, which reads like a coverage problem and is a measurement one.

So the cases that matter are the ones a clean tree never shows:

  - a project with tests that is in NEITHER the list nor the excluded set is
    a finding: the report says nothing about it while the reader assumes it
    does;
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

SHARED = "tasks.withType<Test>().configureEach {\n    useJUnitPlatform {\n    }\n}\n"
ADR_CORE = "tasks.withType<Test>().configureEach {\n    useJUnitPlatform {\n    }\n}\n"


class CoverageTaskListTest(unittest.TestCase):
    """Every project with tests is measured, or excluded from the scope."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self._original_root = mod.ROOT
        mod.ROOT = self.root
        (self.root / 'settings.gradle.kts').write_text(SETTINGS, encoding='utf-8')
        (self.root / 'build.gradle.kts').write_text(BUILD, encoding='utf-8')
        self.write('shared/build.gradle.kts', SHARED)
        self.write('adr-core/build.gradle.kts', ADR_CORE)

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

    def test_a_project_with_tests_that_is_not_measured_is_a_finding(self) -> None:
        self.write('adr-cli/build.gradle.kts', ADR_CORE)
        findings = self.findings()
        self.assertTrue(
            any(':adr-cli' in f for f in findings),
            f'expected the unmeasured project to be reported: {findings}',
        )

    def test_a_project_excluded_from_the_coverage_scope_is_not_a_finding(self) -> None:
        self.write('adr-cli/build.gradle.kts', ADR_CORE)
        self.write(
            'settings.gradle.kts',
            SETTINGS + '\nkover {\n    skipProjects(":adr-cli")\n}\n',
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
        self.write('pro/build.gradle.kts', ADR_CORE)
        self.write(
            'settings.gradle.kts',
            SETTINGS + '\nif (withPro) {\n    include(":pro")\n}\n',
        )
        self.assertEqual(self.findings(), [])


if __name__ == '__main__':
    unittest.main()
