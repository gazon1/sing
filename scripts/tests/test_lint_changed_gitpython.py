"""Tests for the GitPython bootstrap of `scripts/lint-changed.py`.

The scoped lint runs from the local hook as `python3 scripts/lint-changed.py` —
with the python of the shell. GitPython is a dependency of the tooling venv,
not of the system python, so on a machine where the venv is not on `PATH` the
import of `git` fails before the lint starts: the hook reports a crash, not a
finding.

The bootstrap answers that: the script re-runs itself with the interpreter of
the checkout's own venv, and says how to install that venv when there is none.
These tests pin both answers — and the case that must not change anything.
"""

import importlib.util
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
MODULE_PATH = SCRIPTS / "lint-changed.py"

spec = importlib.util.spec_from_file_location("lint_changed", MODULE_PATH)
lint_changed = importlib.util.module_from_spec(spec)
sys.modules["lint_changed"] = lint_changed
spec.loader.exec_module(lint_changed)


class ToolingVenvTest(unittest.TestCase):
    """Where the venv that has GitPython is looked for."""

    def test_the_venv_is_named_after_the_checkout(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("SINGULARITY_TOOLS_VENV", None)
            with mock.patch.object(pathlib.Path, "home", return_value=pathlib.Path("/home/dev")):
                venv = lint_changed.tooling_venv()
        self.assertEqual(
            venv,
            pathlib.Path("/home/dev/.venvs") / f"singularity-todo-{lint_changed.ROOT.name}",
        )

    def test_the_place_of_the_venv_can_be_overridden(self):
        with mock.patch.dict(
            os.environ, {"SINGULARITY_TOOLS_VENV": "/tmp/other-venv"}, clear=False
        ):
            self.assertEqual(lint_changed.tooling_venv(), pathlib.Path("/tmp/other-venv"))


class EnsureGitPythonTest(unittest.TestCase):
    """What happens when the interpreter has no GitPython."""

    def _venv_with_a_python(self, tmp: str) -> pathlib.Path:
        venv = pathlib.Path(tmp)
        (venv / "bin").mkdir(parents=True)
        (venv / "bin" / "python").write_text("", encoding="utf-8")
        return venv

    def test_an_interpreter_with_gitpython_is_left_alone(self):
        with mock.patch.object(lint_changed, "gitpython_is_importable", return_value=True):
            with mock.patch.object(lint_changed.os, "execv") as execv:
                lint_changed.ensure_gitpython()
        execv.assert_not_called()

    def test_a_missing_gitpython_runs_the_script_with_the_venv_python(self):
        recorded = {}

        def fake_execv(path, args):
            recorded["path"] = path
            recorded["args"] = args
            raise SystemExit(0)  # execv never returns

        with tempfile.TemporaryDirectory() as tmp:
            venv = self._venv_with_a_python(tmp)
            with mock.patch.object(lint_changed, "gitpython_is_importable", return_value=False):
                with mock.patch.object(lint_changed, "tooling_venv", return_value=venv):
                    with mock.patch.object(lint_changed.os, "execv", fake_execv):
                        with self.assertRaises(SystemExit):
                            lint_changed.ensure_gitpython()
        self.assertEqual(recorded["path"], str(venv / "bin" / "python"))
        self.assertEqual(recorded["args"][0], str(venv / "bin" / "python"))
        self.assertEqual(recorded["args"][1], str(MODULE_PATH))

    def test_without_a_venv_the_script_says_how_to_install_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            venv = pathlib.Path(tmp) / "not-installed"
            with mock.patch.object(lint_changed, "gitpython_is_importable", return_value=False):
                with mock.patch.object(lint_changed, "tooling_venv", return_value=venv):
                    with self.assertRaises(SystemExit) as failure:
                        lint_changed.ensure_gitpython()
        message = str(failure.exception)
        self.assertIn("setup-local-tools.sh", message)
        self.assertIn(str(venv / "bin"), message)


if __name__ == "__main__":
    unittest.main()
