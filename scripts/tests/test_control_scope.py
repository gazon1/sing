"""Tests for the scope of the control phase.

The control phase proves that each gate can fail. Which controls have to run
again after a change is a question with two answers: "all of them" (safe, slow)
and "the ones this change can have broken" (fast, and wrong the moment the
scope is computed wrongly). These tests pin the boundary between the two.
"""
from __future__ import annotations

import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "src"))

from sgate import control_scope  # noqa: E402

GATE_FILES = {
    "doc-dead-refs": {"scripts/check-doc-dead-refs.py", "docs/decisions/2026-09-01-a.md"},
    "coverage-timeout": {"scripts/check-coverage-timeout.py", ".github/workflows/ci.yml"},
    "tag-entity-normalized": {"scripts/ci/check-tag-entity-normalized.py", "shared/src/Tags.kt"},
}


class AffectedGatesTest(unittest.TestCase):
    def test_a_changed_gate_script_affects_that_gate_only(self) -> None:
        scope = control_scope.affected_gates({"scripts/check-doc-dead-refs.py"}, GATE_FILES)
        self.assertEqual(list(scope), ["doc-dead-refs"])

    def test_a_changed_control_target_affects_its_gate(self) -> None:
        scope = control_scope.affected_gates({".github/workflows/ci.yml"}, GATE_FILES)
        self.assertEqual(list(scope), ["coverage-timeout"])

    def test_a_change_to_the_gate_scripts_own_tests_affects_no_gate(self) -> None:
        scope = control_scope.affected_gates({"scripts/tests/test_check_doc_dead_refs.py"}, GATE_FILES)
        self.assertEqual(scope, {})

    def test_another_script_under_scripts_affects_every_gate(self) -> None:
        # A gate script may import it, and the scope does not resolve the
        # import graph — so every gate is in scope.
        scope = control_scope.affected_gates({"scripts/adr_corpus.py"}, GATE_FILES)
        self.assertEqual(sorted(scope), sorted(GATE_FILES))

    def test_a_change_outside_scripts_affects_only_the_gate_that_owns_it(self) -> None:
        scope = control_scope.affected_gates({"shared/src/Tags.kt"}, GATE_FILES)
        self.assertEqual(list(scope), ["tag-entity-normalized"])

    def test_a_change_nobody_owns_affects_no_gate(self) -> None:
        scope = control_scope.affected_gates({"shared/src/Foo.kt"}, GATE_FILES)
        self.assertEqual(scope, {})

    def test_a_directory_target_covers_the_files_under_it(self) -> None:
        files = {"gate": {"scripts/check-gate.py", "docs/decisions"}}
        scope = control_scope.affected_gates({"docs/decisions/2026-09-01-a.md"}, files)
        self.assertEqual(list(scope), ["gate"])

    def test_a_script_shared_by_two_gates_puts_both_in_scope(self) -> None:
        files = {
            "one": {"scripts/check-doc-dead-refs.py"},
            "two": {"scripts/check-doc-dead-refs.py"},
            "three": {"scripts/check-other.py"},
        }
        scope = control_scope.affected_gates({"scripts/check-doc-dead-refs.py"}, files)
        self.assertEqual(sorted(scope), ["one", "two"])


class ChangedFilesTest(unittest.TestCase):
    def test_an_unresolvable_base_means_no_scope(self) -> None:
        # None means "prove everything". A scope nobody can read is not a scope.
        original = control_scope.BASE_CANDIDATES
        control_scope.BASE_CANDIDATES = ("refs/heads/no-such-branch",)
        try:
            self.assertIsNone(control_scope.changed_files())
        finally:
            control_scope.BASE_CANDIDATES = original


if __name__ == "__main__":
    unittest.main()
