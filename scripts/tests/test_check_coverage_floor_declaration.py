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
  - a rule that BINDS something other than the declared constant is a
    finding: `minValue.set(1)` enforces a floor of one percent, and a gate
    that only looked for the constant's NAME would call that tree correct —
    the floor would be neutered with every gate green;
  - a COMMENT that talks about `kover {` is not a block. Prose about the
    forbidden place is exactly what the declaration site carries, and a gate
    that cannot tell prose from code would fail on the documentation of its
    own defect;
  - a module-level verify block with its own name IS allowed: the repository
    has one (`detekt-rules` measures its own rules), and a rule that forbids
    it would be wrong.

Since 2026-10-23 the floors are declared in ONE place — settings.gradle.kts —
so the cases that matter grew with the rule:

  - a floor constant declared in a MODULE's build file is a finding: it is a
    second home for one number, and the drift between the homes is what the
    gate exists to catch;
  - a module that BINDS the constant declared in the settings file — it reads
    it from `gradle.extra` — is allowed: that is the arrangement the policy
    asks for, not an exception to it;
  - a module rule that binds a LITERAL is a finding, and so is a rule that
    binds a name the settings file never declares: in both cases the floor
    the task checks is not the floor this repository declares;
  - a floor without a `require(...)` minimum is a finding: a floor that can be
    lowered without a visible change is a floor that cannot fail;
  - a minimum of ZERO is a finding: `require(FLOOR >= 0)` is written like a
    minimum and lets the floor go to zero, and a gate that looks for the
    PRESENCE of a minimum cannot tell the difference;
  - a minimum below the one the wiring gate holds every kover verify rule to
    is a finding: the two gates must not disagree about what a usable floor
    is;
  - a bound with ARITHMETIC behind the name is a finding: `FLOOR - 40` is a
    different number from the floor, and the name in front of it satisfies
    every other check of the gate;
  - a module that binds a floor and declares NO verify rule is a finding: a
    floor that is bound and never checked is a floor that cannot fail, and
    the whole block can go without leaving a rule name behind to look for.
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
val FULL_BRANCH_COVERAGE_FLOOR = 23
val FULL_LINE_COVERAGE_FLOOR = 31
val FAST_INSTRUCTION_COVERAGE_FLOOR = 27
val FAST_BRANCH_COVERAGE_FLOOR = 23
val FAST_LINE_COVERAGE_FLOOR = 31
val DETEKT_RULE_BRANCH_FLOOR = 60

require(FULL_INSTRUCTION_COVERAGE_FLOOR >= 27) { }
require(FULL_BRANCH_COVERAGE_FLOOR >= 23) { }
require(FULL_LINE_COVERAGE_FLOOR >= 31) { }
require(FAST_INSTRUCTION_COVERAGE_FLOOR >= 27) { }
require(FAST_BRANCH_COVERAGE_FLOOR >= 23) { }
require(FAST_LINE_COVERAGE_FLOOR >= 31) { }
require(DETEKT_RULE_BRANCH_FLOOR >= 60) { }

val appInstructionCoverageFloor =
    if (measuredFullPopulation) FULL_INSTRUCTION_COVERAGE_FLOOR else FAST_INSTRUCTION_COVERAGE_FLOOR
val appBranchCoverageFloor =
    if (measuredFullPopulation) FULL_BRANCH_COVERAGE_FLOOR else FAST_BRANCH_COVERAGE_FLOOR
val appLineCoverageFloor =
    if (measuredFullPopulation) FULL_LINE_COVERAGE_FLOOR else FAST_LINE_COVERAGE_FLOOR

kover {
    reports {
        verify {
            rule("app instruction coverage") {
                bound {
                    minValue.set(appInstructionCoverageFloor)
                }
            }
            rule("app branch coverage") {
                bound {
                    minValue.set(appBranchCoverageFloor)
                }
            }
            rule("app line coverage") {
                bound {
                    minValue.set(appLineCoverageFloor)
                }
            }
        }
    }
}
"""

#: The module as it stands since 2026-10-23: the floor is DECLARED in the
#: settings file, and the module only BINDS it.
MODULE_BINDS_THE_SETTINGS_FLOOR = '''\
val DETEKT_RULE_BRANCH_FLOOR: Int =
    (gradle.extra["kover.detektRulesBranchCoverageFloor"] as? Int)
        ?: error("settings.gradle.kts must declare the detekt-rules branch coverage floor")

kover {
    reports {
        total {
            verify {
                rule("detekt-rules branch coverage") {
                    bound {
                        minValue = DETEKT_RULE_BRANCH_FLOOR
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
            }
        }
    }
}
'''

#: The module as it stood before: the floor declared in the module itself —
#: the second home for one number the policy ended.
MODULE_DECLARES_THE_FLOOR = '''\
val DETEKT_RULE_BRANCH_FLOOR = 60

kover {
    reports {
        total {
            verify {
                rule("detekt-rules branch coverage") {
                    bound {
                        minValue = DETEKT_RULE_BRANCH_FLOOR
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
            }
        }
    }
}
'''


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

    def test_the_wider_populations_floor_is_not_below_the_narrower_one(self) -> None:
        """The full population's floor is not below the fast population's.

        A floor taken from a wider run is unreachable by a narrower one, and
        the two floors are compared by whoever reads them: a reader who sees
        the full population held to LESS than the fast one concludes that the
        full population is held to a weaker standard — the opposite of what a
        wider population means.

        The comparison is per counter. The three counters are measured
        independently, so an instruction floor that clears and a branch floor
        that does not is not "the floor".
        """
        self.assertEqual(self.findings(), [])

        for counter, current, lowered in (
            ('BRANCH', '23', '22'),
            ('LINE', '31', '30'),
            ('INSTRUCTION', '27', '26'),
        ):
            with self.subTest(counter=counter):
                declaration = f'val FULL_{counter}_COVERAGE_FLOOR = {current}'
                self.write(
                    'settings.gradle.kts',
                    SETTINGS.replace(
                        declaration, f'val FULL_{counter}_COVERAGE_FLOOR = {lowered}'
                    ),
                )
                findings = self.findings()
                self.assertTrue(
                    any(
                        'wider population' in f and counter.lower() in f
                        for f in findings
                    ),
                    f'expected the {counter.lower()} floor to be reported: {findings}',
                )
                # The clean tree is clean again — the finding is about the
                # ordering, not about the tree being broken.
                self.write('settings.gradle.kts', SETTINGS)
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
        # The repository has one: detekt-rules measures its own rules. The rule
        # binds the constant the settings file declares — the floor is declared
        # in one place and checked where Kover reads the rule.
        self.write(
            'detekt-rules/build.gradle.kts',
            MODULE_BINDS_THE_SETTINGS_FLOOR,
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
            SETTINGS.replace(
                'rule("app branch coverage") {\n'
                '                bound {\n'
                '                    minValue.set(appBranchCoverageFloor)\n'
                '                }\n'
                '            }\n',
                '',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('app branch coverage' in f for f in findings),
            f'expected the missing rule to be reported: {findings}',
        )

    # ── The binding: the rule must check the DECLARED floor ───────────────

    def test_a_rule_that_binds_a_literal_is_a_finding(self) -> None:
        # The floor would be 1 % — and every other rule of this gate is green.
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace(
                'minValue.set(appInstructionCoverageFloor)',
                'minValue.set(1)',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('app instruction coverage' in f for f in findings),
            f'expected the literal binding to be reported: {findings}',
        )
        self.assertTrue(
            any('binds the literal 1' in f for f in findings),
            f'expected the finding to name the literal it found: {findings}',
        )

    def test_a_rule_that_binds_an_undeclared_name_is_a_finding(self) -> None:
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace(
                'minValue.set(appInstructionCoverageFloor)',
                'minValue.set(someFloorNobodyDeclared)',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('someFloorNobodyDeclared' in f for f in findings),
            f'expected the undeclared binding to be reported: {findings}',
        )

    def test_a_bound_value_that_is_not_the_floor_constant_is_a_finding(self) -> None:
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace(
                'if (measuredFullPopulation) FULL_INSTRUCTION_COVERAGE_FLOOR '
                'else FAST_INSTRUCTION_COVERAGE_FLOOR',
                '27',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any(
                'FULL_INSTRUCTION_COVERAGE_FLOOR' in f and 'FAST_INSTRUCTION_COVERAGE_FLOOR' in f
                for f in findings
            ),
            f'expected the unbound floor to be reported: {findings}',
        )

    def test_a_bound_value_that_reads_only_one_population_is_a_finding(self) -> None:
        # The fast population's floor is a floor of its own: a rule that reads
        # only the full one enforces the wrong number on a fast run.
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace(
                'if (measuredFullPopulation) FULL_INSTRUCTION_COVERAGE_FLOOR '
                'else FAST_INSTRUCTION_COVERAGE_FLOOR',
                'FULL_INSTRUCTION_COVERAGE_FLOOR',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('FAST_INSTRUCTION_COVERAGE_FLOOR' in f for f in findings),
            f'expected the missing fast floor to be reported: {findings}',
        )

    # ── One home for every floor ──────────────────────────────────────────

    def test_a_floor_declared_in_a_module_is_a_finding(self) -> None:
        """The second home for one number — the defect the policy ended.

        The module may check the floor; it may not declare it.
        """
        self.write('detekt-rules/build.gradle.kts', MODULE_DECLARES_THE_FLOOR)
        findings = self.findings()
        self.assertTrue(
            any(
                'detekt-rules/build.gradle.kts' in f and 'DETEKT_RULE_BRANCH_FLOOR' in f
                for f in findings
            ),
            f'expected the module to be reported: {findings}',
        )
        self.assertTrue(
            any('declares the coverage floor' in f for f in findings),
            f'expected the finding to say what is wrong: {findings}',
        )

    def test_a_module_that_binds_the_settings_floor_is_allowed(self) -> None:
        """The arrangement the policy asks for, not an exception to it.

        The module reads the constant the settings file declares and binds it
        in its own rule, because that is where Kover reads the rule.
        """
        self.write('detekt-rules/build.gradle.kts', MODULE_BINDS_THE_SETTINGS_FLOOR)
        self.assertEqual(self.findings(), [])

    def test_a_module_rule_that_binds_a_literal_is_a_finding(self) -> None:
        """A literal is a floor of that literal, and not the declared one."""
        self.write(
            'detekt-rules/build.gradle.kts',
            MODULE_BINDS_THE_SETTINGS_FLOOR.replace(
                'minValue = DETEKT_RULE_BRANCH_FLOOR', 'minValue = 1'
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('binds the literal 1' in f for f in findings),
            f'expected the literal binding to be reported: {findings}',
        )

    def test_a_module_rule_that_binds_a_constant_settings_does_not_declare_is_a_finding(self) -> None:
        """The floor the task checks must be the floor this repository declares."""
        self.write(
            'detekt-rules/build.gradle.kts',
            MODULE_BINDS_THE_SETTINGS_FLOOR.replace(
                'minValue = DETEKT_RULE_BRANCH_FLOOR',
                'minValue = SOME_FLOOR_NOBODY_DECLARED',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('SOME_FLOOR_NOBODY_DECLARED' in f for f in findings),
            f'expected the undeclared binding to be reported: {findings}',
        )

    def test_a_floor_without_a_require_minimum_is_a_finding(self) -> None:
        """A floor that can be lowered silently is a floor that cannot fail."""
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace('require(DETEKT_RULE_BRANCH_FLOOR >= 60) { }\n', ''),
        )
        findings = self.findings()
        self.assertTrue(
            any('require' in f and 'DETEKT_RULE_BRANCH_FLOOR' in f for f in findings),
            f'expected the missing minimum to be reported: {findings}',
        )

    def test_a_require_minimum_of_zero_is_a_finding(self) -> None:
        """A minimum of zero is written like a minimum and is not one.

        `require(FLOOR >= 0)` lets the floor go to zero, and a gate that only
        looks for the PRESENCE of a minimum cannot tell the difference.
        """
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace(
                'require(DETEKT_RULE_BRANCH_FLOOR >= 60)', 'require(DETEKT_RULE_BRANCH_FLOOR >= 0)'
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('minimum of zero' in f for f in findings),
            f'expected the zero minimum to be reported: {findings}',
        )

    def test_a_require_minimum_below_the_gate_minimum_is_a_finding(self) -> None:
        """A module's floor may not have a smaller minimum than the gate.

        The wiring gate holds every kover verify rule to `_KOVER_MIN_FLOOR`;
        a floor whose own minimum is below that could be lowered past what
        the gate watches, and the two would disagree about what a usable
        floor is.
        """
        self.write(
            'settings.gradle.kts',
            SETTINGS.replace(
                'require(DETEKT_RULE_BRANCH_FLOOR >= 60)', 'require(DETEKT_RULE_BRANCH_FLOOR >= 5)'
            ),
        )
        self.write('detekt-rules/build.gradle.kts', MODULE_BINDS_THE_SETTINGS_FLOOR)
        findings = self.findings()
        self.assertTrue(
            any('below the' in f and '_KOVER' not in f and '60' in f for f in findings),
            f'expected the weak minimum to be reported: {findings}',
        )

    def test_a_bound_value_with_arithmetic_on_it_is_a_finding(self) -> None:
        """`FLOOR - 40` is a different number from the floor.

        The name in front of it satisfies every other check of the gate, so
        the arithmetic behind the name has to be caught on its own.
        """
        self.write(
            'detekt-rules/build.gradle.kts',
            MODULE_BINDS_THE_SETTINGS_FLOOR.replace(
                'minValue = DETEKT_RULE_BRANCH_FLOOR',
                'minValue = DETEKT_RULE_BRANCH_FLOOR - 40',
            ),
        )
        findings = self.findings()
        self.assertTrue(
            any('arithmetic' in f for f in findings),
            f'expected the arithmetic to be reported: {findings}',
        )

    def test_a_module_that_binds_a_floor_and_declares_no_verify_rule_is_a_finding(self) -> None:
        """A floor that is bound and never checked is a floor that cannot fail.

        Deleting the whole `verify { … }` block leaves a rule NAME to look
        for, and there is none — the module reads the number and binds it to
        nothing.
        """
        without_verify = MODULE_BINDS_THE_SETTINGS_FLOOR
        start = without_verify.find('            verify {')
        end = without_verify.find('\n', without_verify.find('}', without_verify.find('minValue')))
        end = without_verify.find('\n', end) + 1
        self.write('detekt-rules/build.gradle.kts', without_verify[:start] + without_verify[end:])
        findings = self.findings()
        self.assertTrue(
            any('no `verify' in f for f in findings),
            f'expected the missing rule to be reported: {findings}',
        )


if __name__ == '__main__':
    unittest.main()
