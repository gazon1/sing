"""Smoke tests for scripts/documentation/check.py (Phase 1a spike)."""
import importlib.util
import pathlib
import subprocess
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "documentation" / "check.py"


def _load():
    spec = importlib.util.spec_from_file_location("check_documentation", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Smoke(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_body_state_changes_on_body_edit(self):
        import tempfile, os
        # use existing pilot concept
        p = REPO_ROOT / "knowledge" / "architecture" / "sync.md"
        fm, body, _ = self.mod.parse_concept(p)
        s1 = self.mod.body_state(fm, body)
        s2 = self.mod.body_state(fm, body + "\nextra")
        self.assertNotEqual(s1, s2)

    def test_status_verified_after_stamp(self):
        r = subprocess.run(["python3", str(SCRIPT), "status", "sync"],
                           capture_output=True, text=True, cwd=REPO_ROOT)
        self.assertEqual(r.stdout.strip(), "verified")

    def test_verify_ok(self):
        r = subprocess.run(["python3", str(SCRIPT), "verify"],
                           capture_output=True, text=True, cwd=REPO_ROOT)
        self.assertEqual(r.returncode, 0)

    def test_stamp_verified_requires_by(self):
        r = subprocess.run(["python3", str(SCRIPT), "stamp", "sync", "--verified"],
                           capture_output=True, text=True, cwd=REPO_ROOT)
        self.assertNotEqual(r.returncode, 0)

    def test_body_state_deterministic(self):
        p = REPO_ROOT / "knowledge" / "architecture" / "sync.md"
        fm, body, _ = self.mod.parse_concept(p)
        self.assertEqual(self.mod.body_state(fm, body), self.mod.body_state(fm, body))

    def test_affected_relative_path(self):
        r = subprocess.run(
            ["python3", str(SCRIPT), "affected", "./shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt"],
            capture_output=True, text=True, cwd=REPO_ROOT,
        )
        self.assertIn("sync.md", r.stdout)

    def test_dirty_source_blocks_stamp(self):
        src = REPO_ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt"
        original = src.read_text()
        src.write_text(original + "\\n// dirty-marker\\n")
        try:
            r = subprocess.run(["python3", str(SCRIPT), "stamp", "sync"],
                               capture_output=True, text=True, cwd=REPO_ROOT)
            self.assertNotEqual(r.returncode, 0)
        finally:
            src.write_text(original)


if __name__ == "__main__":
    unittest.main()