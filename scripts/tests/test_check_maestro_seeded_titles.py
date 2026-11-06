"""Tests for scripts/check-maestro-seeded-titles.py.

The rule it enforces — a seeded title carries no run id — is what makes a
seeded fixture the same row on the next run. The pattern it forbids is the one
that survived until the eighth flow grew it, so these tests ask the three
questions that keep a gate honest: does it exist, is it green on the tree it
lives in, and does it say so when a flow seeds a title it can never replace.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts/check-maestro-seeded-titles.py"


def run_gate(*args: str) -> subprocess.CompletedProcess:
    """Run the gate in this checkout."""
    return subprocess.run(
        [sys.executable, str(MODULE_PATH), *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )


def write_flow(directory: pathlib.Path, body: str, name: str = "flow.yaml") -> pathlib.Path:
    """Write a flow file into `directory` and return its path."""
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_text(body, encoding="utf-8")
    return path


def seed_flow(query: str) -> str:
    """A flow whose one seed call carries `query`."""
    return (
        "appId: com.singularity.todo\n"
        "name: probe\n"
        "---\n"
        "- runFlow:\n"
        "    file: ../helpers/seed.yaml\n"
        "    env:\n"
        f'      QUERY: "{query}"\n'
    )


class GateSmokeTest(unittest.TestCase):
    def test_the_gate_script_exists(self):
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_gate_passes_on_the_tree_it_lives_in(self):
        """A gate that is red on a clean tree says nothing about a red tree."""
        result = run_gate()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK", result.stdout)


class TheSeededTitleIsCheckedTest(unittest.TestCase):
    def test_a_run_scoped_seeded_title_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(flows, seed_flow("task=${RUN_ID}_PinnedTask2026"))
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("flow.yaml:7", result.stdout)
            self.assertIn("task=${RUN_ID}_PinnedTask2026", result.stdout)

    def test_any_template_in_a_seeded_title_is_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(flows, seed_flow("note=${DAY}_Standup"))
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("note=${DAY}_Standup", result.stdout)

    def test_a_template_in_a_key_that_is_not_a_title_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(flows, seed_flow("task=Standup&due=-${DAYS}d&id=${ID}"))
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_fixed_seeded_title_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(flows, seed_flow("task=PinnedTask2026&due=today"))
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_seed_with_no_title_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(flows, seed_flow("session=anonymous"))
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class TheTypedTitleIsNotCheckedTest(unittest.TestCase):
    def test_a_typed_run_scoped_title_is_clean(self):
        """The row a flow types is its own: the seed is not its business."""
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(
                flows,
                'appId: com.singularity.todo\nname: probe\n---\n'
                '- tapOn:\n    id: notes_quick_add_input\n'
                '- inputText: "${RUN_ID} Meeting notes"\n',
            )
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_seed_named_in_a_comment_is_not_a_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            flows = pathlib.Path(tmp)
            write_flow(flows, "# QUERY: \"task=${RUN_ID}_Probe\" is the pattern this gate forbids\n")
            result = run_gate("--flows", str(flows))
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class TheGateCannotPassVacuouslyTest(unittest.TestCase):
    def test_a_missing_flows_directory_is_an_io_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = run_gate("--flows", str(pathlib.Path(tmp) / "absent"))
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_an_empty_flows_directory_is_an_io_error(self):
        """A rule that finds nothing to check has proved nothing."""
        with tempfile.TemporaryDirectory() as tmp:
            result = run_gate("--flows", tmp)
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)

    def test_the_json_contract_is_one_object(self):
        result = run_gate("--json")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip()[0], "{")
        self.assertIn('"findings"', result.stdout)


if __name__ == "__main__":
    unittest.main()
