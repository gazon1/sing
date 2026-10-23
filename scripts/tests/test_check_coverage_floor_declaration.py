"""Tests for check-coverage-floor-declaration.py.

The gate exists because a floor that is declared where nothing reads it is a
floor nobody enforces. The app coverage floors lived in the root build script
for months, inside a `plugins.withId("…kover")` block that never executed —
the root project has no Kover extension of its own to configure — and the CI
step that reported them was green no matter what the coverage was.

So the cases that matter are the ones a clean tree never shows:

  - a verify block in the ROOT build script is a finding: that is where the
    floors were, and nothing read them;
  - a rule named `app …` outside settings.gradle.kts is a finding: the app
    floor is the aggregate one, and a module-level rule under that name
    measures the module and answers a different question;
  - a floor constant that is not declared is a finding: a floor nobody
    declares is a floor nobody enforces;
  - a verify rule that is not declared is a finding, for the same reason;
  - a COMMENT that talks about `kover {` is not a block. Prose about the
    forbidden place is exactly what the declaration site carries, and a gate
    that cannot tell prose from code would fail on the documentation of its
    own defect;
  - a module-level verify block with its own name IS allowed: the repository
    has one (`detekt-rules` measures its own rules), and a rule that forbids
    it would be wrong.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / 'check-coverage-floor-declaration.py'
)
spec = importlib.util.spec_from_file_location('check_coverage_floor_declaration', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_coverage_floor_declaration'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


SETTINGS = """\
val FULL_INSTRUCTION_COVERAGE_FLOOR = 27
val FULL_BRANCH_COVERAGE_FLOOR = 20
val FULL_LINE_COVERAGE_FLOOR = 27
val FAST_INSTRUCTION_COVERAGE_FLOOR = 27
val FAST_BRANCH_COVERAGE_FLOOR = 23
val FAST_LINE_COVERAGE_FLOOR = 31

kover {
    reports {
        verify {
            rule("app instruction coverage") { }
            rule("app branch coverage") { }
            rule("app line coverage") { }
        }
    }
}
"""


class CoverageFloorDeclarationTest(unittest.TestCase):
    """The floor is declared in settings.gradle.kts, and nowhere else."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self._original_root = mod.ROOT
        mod.ROOT = self.root
        (self.root / 'settings.gradle.kts').write_text(SETTINGS, encoding='utf-8')

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

    def test_a_verify_block_in_the_root_build_script_is_a_finding(self) -> None:
        self.write(
            'build.gradle.kts',
            'kover {\n    reports {\n        verify {\n            rule("x") { }\n        }\n    }\n}\n',
        )
        findings = self.findings()
        self.assertTrue(
            any('build.gradle.kts' in f and 'verify' in f for f in findings),
            f'expected the root build script to be reported: {findings}',
        )

    def test_an_app_rule_in_a_module_is_a_finding(self) -> None:
        self.write(
            'shared/build.gradle.kts',
            'kover {\n    reports {\n        verify {\n'
            '            rule("app instruction coverage") { }\n        }\n    }\n}\n',
        )
        findings = self.findings()
        self.assertTrue(
            any('shared/build.gradle.kts' in f for f in findings),
            f'expected the module to be reported: {findings}',
        )

    def test_a_module_rule_with_its_own_name_is_allowed(self) -> None:
        # The repository has one: detekt-rules measures its own rules.
        self.write(
            'detekt-rules/build.gradle.kts',
            'kover {\n    reports {\n        verify {\n'
            '            rule("detekt-rules branch coverage") { }\n        }\n    }\n}\n',
        )
        self.assertEqual(self.findings(), [])

    def test_a_comment_that_mentions_the_block_is_not_a_block(self) -> None:
        self.write(
            'build.gradle.kts',
            '// kover { reports { verify { } } } — prose about the forbidden place.\n'
            '/* kover { verify { } } */\n',
        )
        self.assertEqual(self.findings(), [])

    def test_a_missing_floor_constant_is_a_finding(self) -> None:
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace('val FAST_LINE_COVERAGE_FLOOR = 31\n', ''),
        )
        findings = self.findings()
        self.assertTrue(
            any('FAST_LINE_COVERAGE_FLOOR' in f for f in findings),
            f'expected the missing constant to be reported: {findings}',
        )

    def test_a_missing_verify_rule_is_a_finding(self) -> None:
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace('rule("app branch coverage") { }', ''),
        )
        findings = self.findings()
        self.assertTrue(
            any('app branch coverage' in f for f in findings),
            f'expected the missing rule to be reported: {findings}',
        )


if __name__ == '__main__':
    unittest.main()
