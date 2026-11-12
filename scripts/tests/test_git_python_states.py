"""Tests for the states a repository can be in when the git work is done.

The git work is GitPython's now, and GitPython is a library over the git
binary: it opens the repository, it asks git for a diff, it asks git for a
merge-base. Every one of those is a git command that can fail, and each of
them fails in a way a reader of a red CI run has to be able to tell apart:

  - a directory that is not a repository at all;
  - a repository that has no commits yet;
  - a detached HEAD, where there is no branch to compare against;
  - a branch that does not exist (the base was never fetched);
  - a git command that exited non-zero (a corrupt index);
  - a working tree that is dirty — the ordinary case, and the one that must
    not be confused with the others;
  - a `GIT_DIR` the hook exported, which points at somebody else's
    repository — the failure of 2026-10-23, where a fixture committed a
    12124-file deletion into the developer's own tree.

Every case here is a repository that really is in that state: the tests
build the state and then read it, because a mock proves that the code reads
a mock.
"""

from __future__ import annotations

import importlib.util
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location("git_changed_states", SCRIPTS / "git_changed.py")
git_changed = importlib.util.module_from_spec(_spec)
sys.modules["git_changed_states"] = git_changed
_spec.loader.exec_module(git_changed)

from sgate import control_scope  # noqa: E402 — the path entry above is the reason


def _git(repo: pathlib.Path, *args: str) -> str:
    """A git command that must succeed, and its output."""
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _init_repo(root: pathlib.Path) -> pathlib.Path:
    """A repository with one commit."""
    root.mkdir(parents=True, exist_ok=True)
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "t@e.com")
    _git(root, "config", "user.name", "T")
    _git(root, "commit", "--allow-empty", "-m", "base", "-q")
    return root


class NotARepositoryTest(unittest.TestCase):
    """A directory that is not a repository."""

    def test_a_directory_that_is_not_a_repository_is_refused(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            with self.assertRaises(RuntimeError) as caught:
                git_changed.changed_paths("HEAD", root=root, cwd=root)
        self.assertIn("not inside a git repository", str(caught.exception))


class EmptyRepositoryTest(unittest.TestCase):
    """A repository with no commits."""

    def test_a_repository_without_commits_is_refused(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            _git(root, "init", "-q")
            with self.assertRaises(RuntimeError) as caught:
                git_changed.changed_paths("HEAD", root=root, cwd=root, staged_only=True)
        self.assertIn("no commits", str(caught.exception))


class DetachedHeadTest(unittest.TestCase):
    """A HEAD that points at a commit and not at a branch."""

    def test_a_detached_head_still_names_the_change(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            (root / "a.md").write_text("v1")
            _git(root, "add", "a.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD")
            (root / "a.md").write_text("v2")
            _git(root, "checkout", "--detach", base)
            out = git_changed.changed_paths(base, pathspec=("*.md",), root=root, cwd=root)
        self.assertEqual(out, ["a.md"])

    def test_a_base_that_does_not_exist_names_no_merge_base(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            self.assertIsNone(control_scope._merge_base(control_scope.Repo(root), "no-such-ref"))


class MissingBranchTest(unittest.TestCase):
    """A base branch that was never fetched."""

    def test_an_unresolvable_base_names_the_ref_that_is_missing(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            with self.assertRaises(RuntimeError) as caught:
                git_changed.changed_paths("origin/no-such", root=root, cwd=root)
        message = str(caught.exception)
        self.assertIn("origin/no-such", message)
        self.assertIn("merge-base", message)


class FailingGitCommandTest(unittest.TestCase):
    """A git command that exited non-zero."""

    def test_a_corrupt_index_is_reported_as_a_failed_diff(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            (root / "a.md").write_text("v1")
            _git(root, "add", "a.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD")
            (root / ".git" / "index").write_bytes(b"not-an-index")
            with self.assertRaises(RuntimeError) as caught:
                git_changed.changed_paths(base, root=root, cwd=root)
        message = str(caught.exception)
        self.assertIn("git diff failed", message)
        # The reason git gave is empty for this command, so the exit code is
        # what is left — and a red run has to carry one of the two.
        self.assertIn("128", message)


class DirtyWorkingTreeTest(unittest.TestCase):
    """A working tree with changes in it — the ordinary case."""

    def test_an_unstaged_change_of_a_tracked_file_is_a_change(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            (root / "a.md").write_text("v1")
            _git(root, "add", "a.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD")
            (root / "a.md").write_text("v2")
            out = git_changed.changed_paths(base, pathspec=("*.md",), root=root, cwd=root)
        self.assertEqual(out, ["a.md"])

    def test_a_staged_change_of_a_tracked_file_is_a_change(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            (root / "a.md").write_text("v1")
            _git(root, "add", "a.md")
            _git(root, "commit", "-m", "add", "-q")
            base = _git(root, "rev-parse", "HEAD")
            (root / "a.md").write_text("v2")
            _git(root, "add", "a.md")
            out = git_changed.changed_paths(base, pathspec=("*.md",), root=root, cwd=root)
        self.assertEqual(out, ["a.md"])


class InheritedGitDirTest(unittest.TestCase):
    """The `GIT_DIR` a hook exports points at somebody else's repository."""

    def test_the_tree_that_is_read_is_the_one_that_was_asked_for(self):
        with tempfile.TemporaryDirectory() as td:
            base = pathlib.Path(td)
            mine = _init_repo(base / "mine")
            theirs = _init_repo(base / "theirs")
            (mine / "mine.md").write_text("m")
            (theirs / "theirs.md").write_text("t")
            saved = {
                name: os.environ.get(name)
                for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR")
            }
            os.environ["GIT_DIR"] = str(theirs / ".git")
            os.environ["GIT_WORK_TREE"] = str(theirs)
            try:
                out = git_changed.changed_paths(
                    "HEAD", pathspec=("*.md",), root=mine, cwd=mine, require_base=False
                )
            finally:
                for name, value in saved.items():
                    if value is None:
                        os.environ.pop(name, None)
                    else:
                        os.environ[name] = value
        self.assertEqual(out, ["mine.md"])

    def test_the_environment_is_given_back_after_the_call(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            os.environ["GIT_DIR"] = str(root / ".git")
            try:
                git_changed.changed_paths("HEAD", root=root, cwd=root)
                self.assertIn("GIT_DIR", os.environ)
            finally:
                os.environ.pop("GIT_DIR", None)


class ControlScopeBaseRefTest(unittest.TestCase):
    """The ref the control phase measures the change against."""

    def test_an_explicit_ref_wins_over_the_candidates(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            _git(root, "branch", "release")
            saved = os.environ.get("SGATE_BASE_REF")
            os.environ["SGATE_BASE_REF"] = "release"
            try:
                self.assertEqual(control_scope._base_ref(control_scope.Repo(root)), "release")
            finally:
                if saved is None:
                    os.environ.pop("SGATE_BASE_REF", None)
                else:
                    os.environ["SGATE_BASE_REF"] = saved

    def test_a_ref_that_does_not_exist_is_not_a_base(self):
        with tempfile.TemporaryDirectory() as td:
            root = _init_repo(pathlib.Path(td))
            saved = os.environ.get("SGATE_BASE_REF")
            os.environ["SGATE_BASE_REF"] = "no-such-ref"
            try:
                self.assertIsNone(control_scope._base_ref(control_scope.Repo(root)))
            finally:
                if saved is None:
                    os.environ.pop("SGATE_BASE_REF", None)
                else:
                    os.environ["SGATE_BASE_REF"] = saved


if __name__ == "__main__":
    unittest.main()
