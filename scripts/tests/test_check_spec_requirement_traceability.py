"""Tests for scripts/check-spec-requirement-traceability.py.

The gate makes two claims about a specification: every requirement it declares is
traced, and every check it promises is a file that exists. Both claims are about
prose, and prose fails silently — so the tests build specifications that break
each claim and assert that the gate says so.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts/check-spec-requirement-traceability.py"

SPEC_WITH_TABLE = """
# Feature

- **FR-001**: The first thing MUST happen.
- **FR-002**: The second thing MUST happen.

## Requirement traceability

| Requirements | Exercised by |
|---|---|
| FR-001 – FR-002 | User Story 1, scenario 1 |
"""

SPEC_WITH_UNTRACED = """
# Feature

- **FR-001**: The first thing MUST happen.
- **FR-002**: The second thing MUST happen.

## Requirement traceability

| Requirements | Exercised by |
|---|---|
| FR-001 | User Story 1, scenario 1 |
"""

SPEC_PROMISING_A_MISSING_CHECK = """
# Feature

- **FR-001**: The first thing MUST happen.

## Requirement traceability

| Requirements | Exercised by |
|---|---|
| FR-001 | Automated check: `NoSuchCheckTest` |
"""


def run_gate(root: pathlib.Path) -> subprocess.CompletedProcess:
    """The gate, pointed at a tree."""
    return subprocess.run(
        [sys.executable, str(MODULE_PATH), "--check", "--root", str(root)],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )


class GateSmokeTest(unittest.TestCase):
    def test_the_gate_script_exists(self):
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_gate_passes_on_a_clean_tree(self):
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class RequirementTraceabilityTest(unittest.TestCase):
    """The gate's verdict on specifications written for the purpose."""

    def _tree(self, spec: str) -> pathlib.Path:
        """A tree with one feature whose specification is the given text."""
        root = pathlib.Path(tempfile.mkdtemp(prefix="spec-traceability-"))
        feature = root / "specs" / "999-example-feature"
        feature.mkdir(parents=True)
        (feature / "spec.md").write_text(spec, encoding="utf-8")
        return root

    def test_a_fully_traced_spec_passes(self):
        root = self._tree(SPEC_WITH_TABLE)

        result = run_gate(root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_an_untraced_requirement_fails(self):
        root = self._tree(SPEC_WITH_UNTRACED)

        result = run_gate(root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FR-002", result.stdout + result.stderr)

    def test_a_promised_check_that_does_not_exist_fails(self):
        root = self._tree(SPEC_PROMISING_A_MISSING_CHECK)

        result = run_gate(root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("NoSuchCheckTest", result.stdout + result.stderr)

    def test_a_spec_without_a_table_is_skipped_not_failed(self):
        root = self._tree("# Feature\n\n- **FR-001**: Something MUST happen.\n")

        result = run_gate(root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
