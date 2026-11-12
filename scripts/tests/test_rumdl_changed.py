"""Tests for rumdl advisory wiring and git_changed helper (Phase 1+2)."""
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


git_changed = _load("git_changed_phase12", SCRIPTS / "git_changed.py")
rumdl_advisory = _load("rumdl_advisory_files", SCRIPTS / "rumdl-advisory-files.py")


def _git(repo, *args):
    return subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True)


def _init_repo(td):
    root = pathlib.Path(td)
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "t@e.com")
    _git(root, "config", "user.name", "T")
    _git(root, "commit", "--allow-empty", "-m", "base", "-q")
    return root


class GitChangedTest(unittest.TestCase):
    def test_staged_only_reads_the_index_and_not_the_working_tree(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            (root / "staged.md").write_text("x")
            _git(root, "add", "staged.md")
            (root / "untracked.md").write_text("u")
            out = git_changed.changed_paths(
                "origin/main", pathspec=("*.md",), staged_only=True, root=root, cwd=root
            )
        self.assertEqual(out, ["staged.md"])

    def test_branchwide_uses_merge_base_and_untracked(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            (root / "a.md").write_text("v1")
            _git(root, "add", "a.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD").stdout.strip()
            (root / "a.md").write_text("v2")  # tracked working tree change
            (root / "b.md").write_text("u")  # untracked
            out = git_changed.changed_paths(base, pathspec=("*.md",), root=root, cwd=root)
        self.assertEqual(sorted(out), ["a.md", "b.md"])

    def test_a_path_that_is_no_longer_on_disk_is_filtered_out(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            (root / "a.md").write_text("x")
            _git(root, "add", "a.md")
            (root / "a.md").unlink()  # staged, but the file is gone from the tree
            out = git_changed.changed_paths(
                "origin/main", staged_only=True, root=root, cwd=root
            )
        self.assertEqual(out, [])

    def test_a_rename_is_reported_under_its_new_name(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            (root / "old.md").write_text("x")
            _git(root, "add", "old.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD").stdout.strip()
            _git(root, "mv", "old.md", "new.md")
            out = git_changed.changed_paths(base, pathspec=("*.md",), root=root, cwd=root)
        self.assertEqual(out, ["new.md"])

    def test_a_deleted_file_is_not_a_change_to_lint(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            (root / "gone.md").write_text("x")
            _git(root, "add", "gone.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD").stdout.strip()
            (root / "gone.md").unlink()
            out = git_changed.changed_paths(base, pathspec=("*.md",), root=root, cwd=root)
        self.assertEqual(out, [])

    def test_unavailable_base_fails_by_default(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            with self.assertRaises(RuntimeError) as ctx:
                git_changed.changed_paths("origin/no-such-base", root=root, cwd=root)
        self.assertIn("merge-base", str(ctx.exception))

    def test_unavailable_base_fallback_is_explicit(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            (root / "a.md").write_text("u")
            with self.assertRaises(RuntimeError):
                # default require_base=True
                git_changed.changed_paths("origin/no-such-base", root=root, cwd=root)
            # explicit fallback: checks working tree only, never claims full scope
            out = git_changed.changed_paths(
                "origin/no-such-base", pathspec=("*.md",), root=root, cwd=root, require_base=False
            )
            self.assertEqual(out, ["a.md"])

    def test_unicode_and_space_paths(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(td)
            name = "docs/Ünï code.md"
            (root / "docs").mkdir()
            (root / name).write_text("x")
            out = git_changed.changed_paths(
                "origin/nope", pathspec=("*.md",), root=root, cwd=root, require_base=False
            )
        self.assertEqual(out, [name])


class RumdlAdvisoryTest(unittest.TestCase):
    def test_skip_when_rumdl_missing(self):
        with mock.patch("shutil.which", return_value=None):
            rc = rumdl_advisory.main(["--files-from", "/nope", "--advisory"])
        self.assertEqual(rc, 0)

    def test_advisory_returns_zero_on_findings(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "x.md").write_text("x")
            lst = root / "list.txt"
            lst.write_text("x.md\n")
            old = pathlib.Path.cwd()
            try:
                import os
                os.chdir(root)
                with mock.patch("shutil.which", return_value="/bin/rumdl"), mock.patch(
                    "subprocess.run", return_value=subprocess.CompletedProcess([], 1)
                ):
                    rc = rumdl_advisory.main(["--files-from", str(lst), "--advisory"])
            finally:
                os.chdir(old)
        self.assertEqual(rc, 0)


class PrePushRumdlWiringTest(unittest.TestCase):
    def test_rumdl_gate_uses_advisory_files_script(self):
        pre_push = _load(
            "sgate_gatekit_pre_push_p12",
            ROOT / "scripts" / "src" / "sgate" / "gatekit" / "pre_push.py",
        )
        rumdl = [s for s in pre_push.PRE_PUSH_STEPS if s.label == "rumdl gate"][0]
        self.assertEqual(rumdl.script, "scripts/rumdl-advisory-files.py")
        self.assertEqual(rumdl.args, ("--files-from", "@TOUCHED_MD@", "--advisory"))
        self.assertIn(r"if-touched:\.md$", rumdl.when)


if __name__ == "__main__":
    unittest.main()
