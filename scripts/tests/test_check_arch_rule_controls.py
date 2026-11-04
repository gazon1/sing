#!/usr/bin/env python3
"""Tests for check-arch-rule-controls.py — the gate that keeps every arch rule able to fail.

The gate's own failure mode is the one it hunts. A derivation that stops
matching returns no rules, and no rules means nothing to report — a green gate
measuring nothing, which is exactly the shape of `repository read methods are
user-scoped` (issue #788). So the tests here are mostly about proving the gate
still *sees*: a planted rule with no row must be named, a rule the derivation no
longer finds must be named, and a control that has stopped proving anything must
be named.

The last test is the one that matters most: it runs the real derivation over
this checkout's rule sources and the real registry, so the registry cannot drift
from the sources without a test failing first.
"""

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

from click.testing import CliRunner

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
REPOSITORY = SCRIPTS.parent

_spec = importlib.util.spec_from_file_location(
    "check_arch_rule_controls", SCRIPTS / "check-arch-rule-controls.py"
)
arch = importlib.util.module_from_spec(_spec)
# The module is not imported by name, so it has to be in `sys.modules` before
# its dataclasses are created (Python looks the defining module up by name).
sys.modules[_spec.name] = arch
_spec.loader.exec_module(arch)


ARCH_DIR = arch.ARCH_SOURCES
BACKLOG = arch.BACKLOG

#: One rule, two controls — one that proves a finding, one that only asserts
#: emptiness. The second is the shape the gate must reject.
SOURCE = """
package com.singularity.todo.arch

class SampleArchTest {
    @Test
    fun `a rule`() {
        val offenders = scope.files.filter { it.name == "x" }
        assertNoOffenders(offenders, "no x") { it.path }
    }

    @Test
    fun controlThatFindsSomething() {
        val findings = scan("broken")
        assertTrue(findings.isNotEmpty(), "the scan found nothing")
    }

    @Test
    fun controlThatAssertsOnlyEmptiness() {
        val findings = scan("fine")
        assertTrue(findings.isEmpty(), "the scan reported something")
    }

    @Test
    fun `a second rule with no control`() {
        val offenders = scope.interfaces().filter { it.name == "y" }
        fail("x")
    }
}
"""

RULE = "SampleArchTest.a rule"
SECOND_RULE = "SampleArchTest.a second rule with no control"


class Tree:
    """A throwaway tree the gate can measure: sources, debt record, registry."""

    def __init__(self, ceiling: int = 0, source: str = SOURCE) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        sources = self.root / ARCH_DIR
        sources.mkdir(parents=True)
        if source:
            (sources / "SampleArchTest.kt").write_text(source, encoding="utf-8")
        backlog = self.root / BACKLOG
        backlog.parent.mkdir(parents=True, exist_ok=True)
        backlog.write_text("# Rule control audit\n", encoding="utf-8")
        self.rows: list[tuple[str, str, str, str]] = []
        self.ceiling = ceiling

    def row(self, rule_id: str, kind: str, ref: str, expects: str = "") -> "Tree":
        self.rows.append((rule_id, kind, ref, expects))
        return self

    def file(self, relative: str, content: str) -> "Tree":
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return self

    def findings(self) -> list[str]:
        self.write_registry()
        return arch.findings(self.root)

    def write_registry(self) -> pathlib.Path:
        lines = []
        if self.ceiling is not None:
            lines.append(f"# ceiling: {self.ceiling}")
        lines.extend("\t".join(row) for row in self.rows)
        path = self.root / arch.CONTROLS
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def close(self) -> None:
        self._tmp.cleanup()


class DerivationTest(unittest.TestCase):
    """The inventory is what the sources say, not what a list says."""

    def test_sees_the_rule_whose_corpus_could_not_hold_an_offender(self):
        """Issue #788's rule is in the inventory this gate derives."""
        rules = arch.derived_rules(REPOSITORY)
        self.assertIn("ArchitectureTest.repository read methods are user-scoped", rules)

    def test_ignores_a_test_that_asserts_emptiness_without_a_scope_query(self):
        tree = Tree()
        self.addCleanup(tree.close)
        rules = arch.derived_rules(tree.root)
        self.assertNotIn("SampleArchTest.controlThatAssertsOnlyEmptiness", rules)
        self.assertNotIn("SampleArchTest.controlThatFindsSomething", rules)

    def test_a_kdoc_mentioning_the_tokens_is_not_a_rule(self):
        source = SOURCE.replace(
            "    @Test\n    fun controlThatFindsSomething",
            "    /**\n"
            "     * scope.files, and then assertNoOffenders(offenders) — prose, not code.\n"
            "     */\n"
            "    @Test\n"
            "    fun controlThatFindsSomething",
        )
        tree = Tree(source=source)
        self.addCleanup(tree.close)
        rules = arch.derived_rules(tree.root)
        self.assertEqual({RULE, SECOND_RULE}, set(rules))

    def test_a_missing_source_directory_is_unreadable(self):
        tree = Tree(source="")
        self.addCleanup(tree.close)
        (tree.root / ARCH_DIR).rmdir()
        with self.assertRaises(arch.Unreadable):
            arch.findings(tree.root)

    def test_an_empty_tree_is_a_problem_not_a_pass(self):
        tree = Tree(source="")
        self.addCleanup(tree.close)
        problems = tree.findings()
        self.assertTrue(
            any("the derivation found no rules" in problem for problem in problems),
            problems,
        )


class RegistryTest(unittest.TestCase):
    """Every rule has a row, and every row still protects something."""

    def setUp(self):
        self.tree = Tree()
        self.addCleanup(self.tree.close)

    def test_a_clean_registry_passes(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        self.assertEqual([], self.tree.findings())

    def test_a_rule_with_no_row_is_named(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        problems = self.tree.findings()
        self.assertTrue(
            any(SECOND_RULE in problem and "has no row" in problem for problem in problems),
            problems,
        )

    def test_a_row_for_a_rule_that_is_gone_is_named(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.row("SampleArchTest.a rule that was deleted", "none", BACKLOG)
        self.tree.ceiling = 2
        problems = self.tree.findings()
        self.assertTrue(
            any("a rule that was deleted" in problem for problem in problems), problems
        )

    def test_two_rows_for_one_rule_are_rejected(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        self.tree.row(RULE, "none", BACKLOG)
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 2
        problems = self.tree.findings()
        self.assertTrue(any("already has a row" in problem for problem in problems), problems)

    def test_a_synthetic_control_that_does_not_exist_is_rejected(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.noSuchControl")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("is not a @Test method" in problem for problem in problems), problems)

    def test_a_synthetic_control_that_only_asserts_emptiness_is_rejected(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatAssertsOnlyEmptiness")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("asserts no finding" in problem for problem in problems), problems)

    def test_a_synthetic_control_that_finds_something_is_accepted(self):
        self.tree.ceiling = 1
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.assertEqual([], self.tree.findings())

    def test_a_synthetic_ref_without_a_method_is_rejected(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("<TestClass>.<method>" in problem for problem in problems), problems)

    def test_a_fixture_that_does_not_exist_is_rejected(self):
        self.tree.row(RULE, "fixture", "fixtures/arch/Missing.kt", "java.io.File")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("does not exist" in problem for problem in problems), problems)

    def test_a_fixture_without_an_expects_shape_is_rejected(self):
        self.tree.file("fixtures/arch/Sample.kt", "import java.io.File\n")
        self.tree.row(RULE, "fixture", "fixtures/arch/Sample.kt")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("names no `expects` shape" in problem for problem in problems), problems)

    def test_a_fixture_that_lost_its_shape_is_rejected(self):
        self.tree.file("fixtures/arch/Sample.kt", "// the import went away while fixing it\n")
        self.tree.row(RULE, "fixture", "fixtures/arch/Sample.kt", "java.io.File")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("no longer contains" in problem for problem in problems), problems)

    def test_a_fixture_that_still_has_its_shape_passes(self):
        self.tree.file("fixtures/arch/Sample.kt", "import java.io.File\n")
        self.tree.row(RULE, "fixture", "fixtures/arch/Sample.kt", "java.io.File")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        self.assertEqual([], self.tree.findings())

    def test_a_new_uncontrolled_rule_over_the_ceiling_is_rejected(self):
        self.tree.row(RULE, "none", BACKLOG)
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("ceiling is 1" in problem for problem in problems), problems)

    def test_a_ceiling_that_is_no_longer_reached_is_rejected(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        self.tree.row(SECOND_RULE, "none", BACKLOG)
        self.tree.ceiling = 5
        problems = self.tree.findings()
        self.assertTrue(any("Lower it to 1" in problem for problem in problems), problems)

    def test_a_debt_row_pointing_at_a_missing_document_is_rejected(self):
        self.tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        self.tree.row(SECOND_RULE, "none", "docs/does-not-exist.md")
        self.tree.ceiling = 1
        problems = self.tree.findings()
        self.assertTrue(any("debt record" in problem for problem in problems), problems)


class RegistrySyntaxTest(unittest.TestCase):
    """A malformed registry is unreadable, not a pass with zero rows."""

    def test_a_row_with_the_wrong_number_of_fields_is_unreadable(self):
        with self.assertRaises(arch.Unreadable):
            arch.parse_registry("# ceiling: 0\nSampleArchTest.a rule\tsynthetic\n")

    def test_an_unknown_control_kind_is_unreadable(self):
        with self.assertRaises(arch.Unreadable):
            arch.parse_registry("# ceiling: 0\nSampleArchTest.a rule\texempt\tref\t\n")

    def test_a_registry_without_a_ceiling_is_unreadable(self):
        with self.assertRaises(arch.Unreadable):
            arch.parse_registry(
                "SampleArchTest.a rule\tsynthetic\tSampleArchTest.controlThatFindsSomething\t\n"
            )

    def test_an_empty_fourth_field_survives_the_parse(self):
        rows, ceiling = arch.parse_registry(
            "# ceiling: 0\nSampleArchTest.a rule\tsynthetic\tSampleArchTest.controlThatFindsSomething\t\n"
        )
        self.assertEqual((0, ""), (ceiling, rows[0].expects))


class CommandTest(unittest.TestCase):
    """The gate's exit codes are what a CI step reads.

    Driven through `click.testing.CliRunner` rather than `run()`: the runner is
    the thing that turns `ctx.exit(1)` into an exit code, and a helper that
    caught the exception by hand would pin the helper instead of the gate.
    """

    def run_gate(self, *args: str):
        return CliRunner().invoke(arch.cli, list(args))

    def test_a_clean_tree_exits_zero(self):
        tree = Tree(ceiling=1)
        self.addCleanup(tree.close)
        tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        tree.row(SECOND_RULE, "none", BACKLOG)
        tree.write_registry()
        result = self.run_gate("--check", "--root", str(tree.root))
        self.assertEqual(0, result.exit_code, result.output)

    def test_a_missing_row_exits_one_and_names_the_rule(self):
        tree = Tree(ceiling=0)
        self.addCleanup(tree.close)
        tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        tree.write_registry()
        result = self.run_gate("--check", "--root", str(tree.root))
        self.assertEqual(1, result.exit_code)
        self.assertIn(SECOND_RULE, result.output)

    def test_json_is_one_object_and_nothing_else(self):
        tree = Tree(ceiling=1)
        self.addCleanup(tree.close)
        tree.row(RULE, "synthetic", "SampleArchTest.controlThatFindsSomething")
        tree.row(SECOND_RULE, "none", BACKLOG)
        tree.write_registry()
        result = self.run_gate("--json", "--root", str(tree.root))
        self.assertEqual(0, result.exit_code)
        self.assertEqual([], json.loads(result.output)["problems"])

    def test_an_unreadable_tree_exits_two(self):
        tree = Tree(source="")
        self.addCleanup(tree.close)
        (tree.root / ARCH_DIR).rmdir()
        result = self.run_gate("--check", "--root", str(tree.root))
        self.assertEqual(2, result.exit_code)


class ThisRepositoryTest(unittest.TestCase):
    """The registry this change lands with covers every rule the derivation finds."""

    def test_the_registry_covers_every_derived_rule(self):
        problems = arch.findings(REPOSITORY)
        self.assertEqual([], problems, "\n".join(problems))


if __name__ == "__main__":
    unittest.main()
