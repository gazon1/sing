"""Unit tests for rumdl-changed helper behavior (no rumdl binary required)."""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))
sys.path.insert(0, str(SCRIPTS / "src"))


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


git_changed = _load("git_changed", SCRIPTS / "git_changed.py")
rumdl_changed = _load("rumdl_changed", SCRIPTS / "rumdl-changed.py")


class RumdlChangedTest(unittest.TestCase):
    def test_files_from_filters_md_and_missing(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "a.md").write_text("x")
            (root / "b.kt").write_text("x")
            f = root / "list.txt"
            f.write_text("a.md\nb.kt\nmissing.md\n\n")
            self.assertEqual(rumdl_changed.files_from(str(f), root), ["a.md"])

    def test_skip_when_rumdl_missing(self):
        with mock.patch("shutil.which", return_value=None):
            rc = rumdl_changed.main(["--list", "--", "README.md"])
        self.assertEqual(rc, 0)

    def test_require_fails_when_rumdl_missing(self):
        with mock.patch("shutil.which", return_value=None):
            rc = rumdl_changed.main(["--require", "--list", "--", "README.md"])
        self.assertEqual(rc, 1)

    def test_advisory_returns_zero_on_findings(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "x.md").write_text("x")
            with mock.patch.object(rumdl_changed, "ROOT", root), mock.patch(
                "shutil.which", return_value="/bin/rumdl"
            ), mock.patch(
                "subprocess.run", return_value=subprocess.CompletedProcess([], 1)
            ):
                rc = rumdl_changed.main(["--advisory", "x.md"])
        self.assertEqual(rc, 0)

    def test_changed_paths_staged_uses_cached(self):
        calls = []

        def fake_git(*args, cwd=None):
            calls.append(args)
            return 0, ["a.md", "b.md"]

        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "a.md").write_text("x")
            (root / "b.md").write_text("x")
            with mock.patch.object(git_changed, "git_paths", side_effect=fake_git):
                out = git_changed.changed_paths(
                    "origin/main", pathspec=("*.md",), staged_only=True, root=root
                )
        self.assertIn("--cached", calls[0])
        self.assertEqual(out, ["a.md", "b.md"])


if __name__ == "__main__":
    unittest.main()

class PrePushRumdlWiringTest(unittest.TestCase):
    def test_rumdl_gate_uses_explicit_touched_files(self):
        pre_push = _load(
            "sgate_gatekit_pre_push",
            ROOT / "scripts" / "src" / "sgate" / "gatekit" / "pre_push.py",
        )
        rumdl = [s for s in pre_push.PRE_PUSH_STEPS if s.label == "rumdl gate"][0]
        self.assertEqual(rumdl.args, ("--files-from", "@TOUCHED_MD@", "--advisory"))
        self.assertIn(r"if-touched:\.md$", rumdl.when)

    def test_files_from_checks_only_listed_md(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "a.md").write_text("x")
            (root / "b.md").write_text("x")
            (root / "c.kt").write_text("x")
            lst = root / "list.txt"
            lst.write_text("a.md\n\nb.md\nc.kt\n")
            self.assertEqual(rumdl_changed.files_from(str(lst), root), ["a.md", "b.md"])
