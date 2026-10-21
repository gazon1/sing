"""Tests for check-hooks-path.py — `core.hooksPath` must not point at another checkout.

The defect (#519): `core.hooksPath` was set to an **absolute** path — the main
clone's `.githooks` — while the work was happening in a worktree whose
`.githooks/` was a different directory. Git keeps `config` in the shared `.git/`,
so every worktree inherited the value and every worktree ran hooks out of a
checkout it was not working in. Editing a hook in the worktree did nothing
until the change landed on main, and until then every agent believed it had a
`pre-push` gate.

The rule the gate enforces is therefore narrow and mechanical: the value must be
relative (git resolves a relative value against each working tree's own top
level, so one setting is correct in every worktree), it must name the directory
the repository actually tracks (`.githooks`), and that directory must contain
*tracked* files — hooks that exist in one clone but were never committed are
hooks no worktree and no CI run can rely on, while the configuration looks
completely healthy.

The assertions plant each of those wrong values on a temporary git repository
(`git init` plus a local `core.hooksPath`) and require the gate to report them,
and require the correct value to pass. The repository is temporary and the
global git configuration is neutralised for the duration, so the result does not
depend on the machine the test runs on.
"""

import contextlib
import importlib.util
import io
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

_spec = importlib.util.spec_from_file_location(
    "check_hooks_path", SCRIPTS / "check-hooks-path.py"
)
hooks = importlib.util.module_from_spec(_spec)
sys.modules["check_hooks_path"] = hooks
_spec.loader.exec_module(hooks)

REPO_ROOT = SCRIPTS.parent


class HooksRepoTest(unittest.TestCase):
    """A temporary git repository with a `core.hooksPath` under the gate's eye."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.git("init", "-q", str(self.root))
        # Neutralise the machine's git configuration: a global core.hooksPath
        # would otherwise decide the outcome of every case below.
        empty_config = self.root / "empty-gitconfig"
        empty_config.write_text("", encoding="utf-8")
        patcher = unittest.mock.patch.dict(
            os.environ, {"GIT_CONFIG_GLOBAL": str(empty_config)}
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def git(self, *args):
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            capture_output=True,
            text=True,
            check=True,
        )

    def set_hooks_path(self, value):
        self.git("config", "core.hooksPath", value)

    def make_hooks_dir(self, tracked=True):
        hooks_dir = self.root / ".githooks"
        hooks_dir.mkdir(exist_ok=True)
        (hooks_dir / "pre-commit").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        if tracked:
            self.git("add", ".githooks/pre-commit")

    def run_gate(self, *args):
        buffer = io.StringIO()
        with unittest.mock.patch.object(hooks, "ROOT", self.root):
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                code = hooks.run(hooks.cli.main, list(args))
        return code, buffer.getvalue()


class UnsetTest(HooksRepoTest):
    """An unset value is not an error — git falls back to `.git/hooks`."""

    def test_an_unset_hooks_path_passes(self):
        code, output = self.run_gate()
        self.assertEqual(0, code, output)

    def test_an_unset_hooks_path_says_how_to_install_the_tracked_hooks(self):
        """The report is the install instruction; the tracked hooks are dormant."""
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("core.hooksPath is unset", output)
        self.assertIn("git config core.hooksPath .githooks", output)

    def test_the_quiet_mode_prints_nothing(self):
        code, output = self.run_gate("--quiet")
        self.assertEqual(0, code, output)
        self.assertEqual("", output.strip())


class CorrectValueTest(HooksRepoTest):
    """A relative value naming the tracked directory passes."""

    def test_a_relative_hooks_path_with_tracked_hooks_passes(self):
        self.make_hooks_dir()
        self.set_hooks_path(".githooks")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("OK", output)

    def test_a_relative_hooks_path_is_reported_as_relative(self):
        """The report says the value is relative, which is the whole rule."""
        self.make_hooks_dir()
        self.set_hooks_path(".githooks")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)
        self.assertIn("(relative)", output)


class AbsolutePathTest(HooksRepoTest):
    """The defect: an absolute path is correct in exactly one checkout."""

    def test_an_absolute_hooks_path_is_reported(self):
        self.make_hooks_dir()
        self.set_hooks_path("/somewhere/else/.githooks")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("is the absolute path", output)

    def test_the_report_names_the_offending_value(self):
        self.make_hooks_dir()
        self.set_hooks_path("/somewhere/else/.githooks")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("/somewhere/else/.githooks", output)

    def test_an_absolute_path_that_happens_to_exist_is_still_reported(self):
        """Existence is not correctness: the path is still wrong for every other tree."""
        elsewhere = self.root.parent / "elsewhere-hooks"
        elsewhere.mkdir(exist_ok=True)
        self.set_hooks_path(str(elsewhere))
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("is the absolute path", output)


class WrongNameTest(HooksRepoTest):
    """A relative value must name the directory the repository tracks."""

    def test_a_relative_path_with_the_wrong_name_is_reported(self):
        self.make_hooks_dir()
        self.set_hooks_path("hooks")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("the name has to match the directory that is committed", output)

    def test_a_relative_path_that_does_not_exist_is_reported(self):
        self.set_hooks_path(".githooks")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("does not exist in this checkout", output)


class UntrackedHooksTest(HooksRepoTest):
    """Hooks that exist in one clone but were never committed are worth nothing."""

    def test_untracked_hooks_are_reported(self):
        self.make_hooks_dir(tracked=False)
        self.set_hooks_path(".githooks")
        code, output = self.run_gate()
        self.assertEqual(1, code, output)
        self.assertIn("contains no tracked files", output)

    def test_a_directory_with_one_tracked_hook_passes(self):
        """Tracked-ness is per file, not per directory."""
        self.make_hooks_dir()
        self.set_hooks_path(".githooks")
        code, output = self.run_gate()
        self.assertEqual(0, code, output)


class RealRepositoryTest(unittest.TestCase):
    """This repository's own `core.hooksPath`."""

    def test_the_real_repository_passes(self):
        """The clone this gate was written for has a relative value and tracked hooks."""
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            code = hooks.run(hooks.cli.main, [])
        self.assertEqual(0, code, buffer.getvalue())


if __name__ == "__main__":
    unittest.main()
