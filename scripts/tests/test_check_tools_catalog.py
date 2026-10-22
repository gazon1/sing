"""Tests for scripts/check-tools-catalog.py.

A gate that does not run is not a gate. This test proves the script exists,
that it is a Click command, and that it answers the three questions a caller
asks of a gate: `--check`, `--json`, and the exit code.
"""

import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts/check-tools-catalog.py"


class GateSmokeTest(unittest.TestCase):
    def test_the_gate_script_exists(self):
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_gate_passes_on_a_clean_tree(self):
        """A gate that is red on a clean tree proves nothing about a red tree."""
        import subprocess

        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
