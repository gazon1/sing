#!/usr/bin/env python3
"""The environment is a way to select what runs — and it has to actually select.

Run with: python3 -m unittest discover -s scripts/tests

The `just` recipes cannot pass a flag through to the runner: they set
variables instead (`SERIAL=… FLOW=… TAGS=… run-maestro`). Until the runner
read `FLOW` and `TAGS`, it read only `SERIAL`, and the two ignored variables
were dropped on the floor:

    just gm agenda            → ran all 94 flows, not the 8 agenda ones
    just ui-maestro <flow>    → ran all 94 flows, not the one that was asked for

Both looked like they did what they were told. A run that ignores the
selection is worse than a run that cannot select: the report is about a
different set of flows than the person believes it is about, and nothing in
the output says so.

The environment is therefore part of the contract, and this file pins it:
the variables select, an explicit flag still wins over them, and a variable
that names nothing is not silently the whole suite.

Why a subprocess
----------------
The runner reports through kermit, which writes to the stream it was given at
import time. `CliRunner` replaces `sys.stdout` for the duration of the call
and the log lines never reach it, so an in-process test would assert on an
empty string and pass. A child process is what a caller actually observes.
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SRC = REPO_ROOT / "scripts" / "src"

# `main` is a Click command, so the child process is the module's entry point
# invoked with the arguments the test wants.
_ENTRY = "from emulator.maestro._cli import main; main()"


def _dry_run(env: dict[str, str], args: list[str] | None = None) -> str:
    """Run the CLI in dry-run mode with `env` applied; return its output."""
    child_env = dict(os.environ)
    child_env["PYTHONPATH"] = str(SRC)
    child_env.update(env)
    result = subprocess.run(
        [sys.executable, "-c", _ENTRY, "--dry-run", *(args or [])],
        env=child_env,
        capture_output=True,
        text=True,
        timeout=120,
        cwd=REPO_ROOT,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return result.stdout + result.stderr


class EnvironmentSelectsTheRunTest(unittest.TestCase):
    """`FLOW` and `TAGS` narrow the run; they do not decorate it."""

    def test_flow_env_selects_the_named_flow(self):
        out = _dry_run({"FLOW": "Maestro/flows/tasks/04-delete.yaml"})
        self.assertIn("Total discovered: 1", out)
        self.assertIn("04-delete", out)

    def test_flow_env_selects_a_directory(self):
        out = _dry_run({"FLOW": "Maestro/flows/tasks"})
        self.assertIn("Total discovered: 20", out)

    def test_tags_env_selects_by_tag(self):
        out = _dry_run({"TAGS": "agenda"})
        self.assertIn("After tag filter (['agenda'])", out)
        self.assertNotIn("After no tag filter: 94", out)

    def test_explicit_flag_wins_over_the_environment(self):
        """A flag is a decision; an inherited variable is a default."""
        out = _dry_run(
            {"FLOW": "Maestro/flows/tasks/04-delete.yaml"},
            ["--flow", "Maestro/flows/tasks"],
        )
        self.assertIn("Flow target  : Maestro/flows/tasks", out)
        self.assertNotIn("Flow target  : Maestro/flows/tasks/04-delete.yaml", out)

    def test_dry_run_does_not_need_a_device(self):
        """The selection is observable without a device — that is the point."""
        out = _dry_run({"FLOW": "Maestro/flows/tasks/04-delete.yaml"})
        self.assertIn("Dry run", out)


if __name__ == "__main__":
    unittest.main()
