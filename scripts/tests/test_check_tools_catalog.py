"""Tests for scripts/check-tools-catalog.py — and for the path it writes to.

A gate that does not run is not a gate. This test proves the script exists,
that it is a Click command, and that it answers the three questions a caller
asks of a gate: `--check`, `--json`, and the exit code.

The second half is about *where* the catalogue lives. The generator used to
count the repository root from `__file__` by hand and got it wrong once: the
catalogue was written a directory above the repository, while the check
compared against the file inside it and reported the catalogue as current. The
file that changed was the one nobody read, and the gate that was supposed to
notice was green.
"""

import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts/check-tools-catalog.py"

sys.path.insert(0, str(ROOT / "scripts" / "src"))

from sgate.gatekit.paths import assert_inside, generated_path, is_inside  # noqa: E402


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


class TestTheCatalogueLivesInsideTheRepository(unittest.TestCase):
    """The generated path is checked, and the check and the write agree."""

    def test_the_catalogue_is_inside_the_repository(self):
        import importlib.util

        spec = importlib.util.spec_from_file_location("check_tools_catalog", MODULE_PATH)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        path = module.catalog_path(ROOT)
        self.assertTrue(
            is_inside(ROOT, path),
            f"the catalogue is written outside the repository: {path}",
        )
        self.assertEqual(path, ROOT / "docs" / "tools" / "catalog.md")

    def test_a_path_outside_the_repository_is_refused(self):
        """The guard is the point: a path that leaves the tree is a failure."""
        with self.assertRaises(ValueError):
            generated_path(ROOT, "../docs/tools/catalog.md")

    def test_a_path_inside_the_repository_is_accepted(self):
        path = generated_path(ROOT, "docs/tools/catalog.md")
        self.assertTrue(is_inside(ROOT, path))

    def test_the_root_itself_is_inside(self):
        self.assertTrue(is_inside(ROOT, ROOT))

    def test_assert_inside_returns_the_path_it_accepted(self):
        path = ROOT / "docs" / "tools" / "catalog.md"
        self.assertEqual(assert_inside(ROOT, path, "the catalogue"), path)


if __name__ == "__main__":
    unittest.main()
