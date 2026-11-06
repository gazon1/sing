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
    def test_staged_only_uses_cached(self):
        calls = []

        def fake_git(*args, cwd=None, check=False):
            calls.append(args)
            return 0, "a.md\0b.md\0", ""

        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "a.md").write_text("x")
            (root / "b.md").write_text("x")
            with mock.patch.object(git_changed, "_git", side_effect=fake_git):
                out = git_changed.changed_paths(
                    "origin/main", pathspec=("*.md",), staged_only=True, root=root, cwd=root
                )
        self.assertIn("--cached", calls[0])
        self.assertEqual(out, ["a.md", "b.md"])

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

    def test_dedupes_and_filters_missing(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "a.md").write_text("x")

            def fake_git(*args, cwd=None):
                if args[0] == "merge-base":
                    return 0, "base\n", ""
                if args[0] == "diff":
                    return 0, "a.md\0missing.md\0a.md\0", ""
                if args[0] == "ls-files":
                    return 0, "a.md\0", ""
                return 1, "", ""

            with mock.patch.object(git_changed, "_git", side_effect=fake_git):
                out = git_changed.changed_paths("origin/main", root=root, cwd=root)
        self.assertEqual(out, ["a.md"])

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
