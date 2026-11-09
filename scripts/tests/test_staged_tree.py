"""Tests for staged-tree.py — the working tree, held at the index.

The helper holds the unstaged work aside while the commit is checked, and puts
it back afterwards. Both halves matter, and the second one matters more: the
held bytes are somebody's uncommitted work, and a hook that keeps them is a
hook that loses work.

The tests run against a sandbox repository — the helper's own `ROOT` points at
the repository it lives in, so the tests move that pointer at the sandbox and
put it back afterwards.
"""

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

from git_sandbox import git as _git, sandbox_env  # noqa: E402

SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "staged-tree.py"

_spec = importlib.util.spec_from_file_location("staged_tree", SCRIPT)
staged_tree = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(staged_tree)


class HeldTreeTest(unittest.TestCase):
    """The working tree carries the index while the check runs."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.repo = pathlib.Path(self._tmp.name) / "repo"
        self.repo.mkdir()
        self.env = sandbox_env(self.repo)
        _git("init", "-b", "main", cwd=self.repo, env=self.env)
        self.plan = self.repo / "plan.json"

        self._original_root = staged_tree.ROOT
        staged_tree.ROOT = self.repo
        self.addCleanup(self._restore_root)

    def _restore_root(self) -> None:
        staged_tree.ROOT = self._original_root

    def _commit(self, message: str) -> None:
        _git("add", "-A", cwd=self.repo, env=self.env)
        _git("commit", "-m", message, cwd=self.repo, env=self.env)

    def _write(self, name: str, content: str) -> pathlib.Path:
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_the_held_tree_carries_the_index_not_the_working_tree(self):
        tracked = self._write("Tracked.kt", "committed\n")
        self._commit("the first version")

        # A staged change, and more on top of it in the working tree.
        tracked.write_text("staged\n", encoding="utf-8")
        _git("add", "Tracked.kt", cwd=self.repo, env=self.env)
        tracked.write_text("staged and then some\n", encoding="utf-8")

        self.assertEqual(0, staged_tree.hold(self.plan))
        self.assertEqual("staged\n", tracked.read_text(encoding="utf-8"))

        self.assertEqual(0, staged_tree.restore(self.plan))
        self.assertEqual("staged and then some\n", tracked.read_text(encoding="utf-8"))

    def test_an_untracked_file_is_not_part_of_the_checked_tree(self):
        self._write("Tracked.kt", "committed\n")
        self._commit("the first version")
        untracked = self._write("Untracked.kt", "not in the commit\n")

        self.assertEqual(0, staged_tree.hold(self.plan))
        self.assertFalse(untracked.exists())

        self.assertEqual(0, staged_tree.restore(self.plan))
        self.assertTrue(untracked.exists())
        self.assertEqual("not in the commit\n", untracked.read_text(encoding="utf-8"))

    def test_a_file_deleted_in_the_working_tree_comes_back_for_the_check(self):
        tracked = self._write("Tracked.kt", "committed\n")
        self._commit("the first version")
        tracked.unlink()

        self.assertEqual(0, staged_tree.hold(self.plan))
        self.assertTrue(tracked.exists())

        self.assertEqual(0, staged_tree.restore(self.plan))
        self.assertFalse(tracked.exists())

    def test_a_staged_new_file_is_in_the_checked_tree(self):
        self._write("Tracked.kt", "committed\n")
        self._commit("the first version")
        added = self._write("Added.kt", "in the commit\n")
        _git("add", "Added.kt", cwd=self.repo, env=self.env)

        self.assertEqual(0, staged_tree.hold(self.plan))
        self.assertTrue(added.exists())
        self.assertEqual("in the commit\n", added.read_text(encoding="utf-8"))

        self.assertEqual(0, staged_tree.restore(self.plan))
        self.assertTrue(added.exists())

    def test_a_gitignored_file_is_left_alone(self):
        self._write("Tracked.kt", "committed\n")
        self._write(".gitignore", "build/\n")
        self._commit("the first version")
        ignored = self._write("build/Output.kt", "generated\n")

        self.assertEqual(0, staged_tree.hold(self.plan))
        self.assertTrue(ignored.exists())

        self.assertEqual(0, staged_tree.restore(self.plan))
        self.assertTrue(ignored.exists())

    def test_the_restore_removes_the_plan(self):
        self._write("Tracked.kt", "committed\n")
        self._commit("the first version")

        self.assertEqual(0, staged_tree.hold(self.plan))
        self.assertTrue(self.plan.is_file())

        self.assertEqual(0, staged_tree.restore(self.plan))
        self.assertFalse(self.plan.exists())
        self.assertFalse(pathlib.Path(str(self.plan) + ".d").exists())

    def test_a_restore_without_a_plan_is_a_failure_not_a_crash(self):
        self.assertEqual(1, staged_tree.restore(self.repo / "nothing.json"))

    def test_a_copy_outside_the_tree_can_restore_what_it_held(self):
        """The helper may itself be untracked.

        The first commit that adds the helper is exactly that case: the helper
        is an untracked file, the hold moves it aside, and a restore that
        needs the helper cannot run — the tree was left held, with the
        developer's work inside it and nothing able to bring it back.

        The copy outside the tree is what makes the restore possible, and this
        is the test that says so: it failed once, in this repository, with the
        hook's own helper moved out from under it.
        """
        self._write("Tracked.kt", "committed\n")
        self._commit("the first version")
        untracked = self._write("Untracked.kt", "not in the commit\n")

        with tempfile.TemporaryDirectory() as outside:
            copy = pathlib.Path(outside) / "staged-tree.py"
            copy.write_bytes(SCRIPT.read_bytes())
            plan = pathlib.Path(outside) / "plan.json"

            hold = subprocess.run(
                [
                    sys.executable,
                    str(copy),
                    "hold",
                    "--root",
                    str(self.repo),
                    "--plan",
                    str(plan),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, hold.returncode, hold.stdout + hold.stderr)
            self.assertFalse(untracked.exists())

            restore = subprocess.run(
                [
                    sys.executable,
                    str(copy),
                    "restore",
                    "--root",
                    str(self.repo),
                    "--plan",
                    str(plan),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, restore.returncode, restore.stdout + restore.stderr)
            self.assertTrue(untracked.exists())
            self.assertEqual("not in the commit\n", untracked.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
