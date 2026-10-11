"""sgate.commands.git_hooks_path_is_per_worktree — shim for: git hooks path is per-worktree."""
from __future__ import annotations

import json
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)
KIWI_REQUIRED: ClassVar[bool] = False


@click.command("git_hooks_path_is_per_worktree")
@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def git_hooks_path_is_per_worktree(check: bool, emit_json: bool) -> None:
    """Run the "git hooks path is per-worktree" gate.

    Mode: advisory

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (gate detected an issue)
      2 = I/O error (script not found)
      3 = config error
      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)
      5 = internal error (unexpected exception)

    KIWI_REQUIRED: False
    """
    repo_root = sgate.repo.ROOT
    gate_name = 'git_hooks_path_is_per_worktree'

    script = repo_root / 'scripts/check-hooks-path.py'
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    import sys
    py_cmd = [sys.executable, str(script)]
    py_cmd.extend(['--quiet'])
    try:
        if emit_json:
            result = run_capture(py_cmd, gate_name)
            out = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
            data = {"gate": gate_name, "passed": result.returncode == 0, "exit_code": result.returncode, "output": out}
            if result.returncode != 0:
                gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            print(json.dumps(data))
            raise SystemExit(result.returncode)
        run(py_cmd, gate_name)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)

register(git_hooks_path_is_per_worktree)

