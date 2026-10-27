"""Tests for scripts/check-maestro-hidekeyboard.py.

A gate that does not run is not a gate, and a gate that cannot fail is worse:
it turns "not checked" into "checked and green". These tests ask the script
the three questions a caller asks of it — does it exist, is it green on a
clean tree, and does it say so when a flow dismisses the keyboard before it
types.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts/check-maestro-hidekeyboard.py"


def run_gate(*args: str) -> subprocess.CompletedProcess:
    """Run the gate in this checkout."""
    return subprocess.run(
        [sys.executable, str(MODULE_PATH), *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )


def write_flow(directory: pathlib.Path, body: str) -> pathlib.Path:
    """Write a flow file into `directory` and return its path."""
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "flow.yaml"
    path.write_text(body, encoding="utf-8")
    return path


class GateSmokeTest(unittest.TestCase):
    def test_the_gate_script_exists(self):
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_gate_passes_on_a_clean_tree(self):
        """A gate that is red on a clean tree proves nothing about a red tree."""
        result = run_gate("--check")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class TheDismissalIsCheckedTest(unittest.TestCase):
    def test_a_dismissal_that_precedes_the_input_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(
                flows,
                "- hideKeyboard\n- inputText: \"Focus\"\n",
            )
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("hideKeyboard", result.stdout)

    def test_a_dismissal_that_follows_the_input_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(
                flows,
                "- inputText: \"Focus\"\n- hideKeyboard\n",
            )
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_dismissal_in_a_comment_is_not_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(
                flows,
                "# hideKeyboard here is prose, not a step\n- inputText: \"Focus\"\n",
            )
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
