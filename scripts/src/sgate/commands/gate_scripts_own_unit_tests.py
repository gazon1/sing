"""sgate.commands.gate_scripts_own_unit_tests — shim for: gate scripts' own unit tests."""
from __future__ import annotations

import json
import subprocess
import sys
from typing import ClassVar

import click
from sgate.log import logger

import sgate
from sgate.output import gate_fail
from sgate.commands.base import INTERNAL_ERROR, register

KIWI_REQUIRED: ClassVar[bool] = False


@click.command("gate_scripts_own_unit_tests")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def gate_scripts_own_unit_tests(emit_json: bool) -> None:
    """Run the "gate scripts' own unit tests" gate.

    Mode: blocking

    Exit codes:
      0 = clean (gate passed)
      1 = validation failure (unittest found test failures)
      2 = I/O error (scripts/tests not found)
      5 = internal error (unexpected exception)
    """
    repo_root = sgate.repo.ROOT
    gate_name = "gate_scripts_own_unit_tests"
    test_dir = repo_root / "scripts" / "tests"

    if not test_dir.exists():
        logger.error(f"{test_dir} not found")
        gate_fail(gate_name, None, None, None, None, "scripts/tests not found")
        raise SystemExit(2)

    cmd = [sys.executable, "-m", "unittest", "discover", "-s", str(test_dir), "-v"]

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=False,
            cwd=str(repo_root),
        )
        # Append the sentinel so callers can extract the unittest rc from stdout
        sentinel = f"__unittest_rc={result.returncode}\n"
        output = (result.stdout or b"") + sentinel.encode("utf-8")

        if emit_json:
            data = {
                "gate": gate_name,
                "passed": result.returncode == 0,
                "exit_code": result.returncode,
                "output": output.decode("utf-8", errors="replace"),
            }
            if result.returncode != 0:
                gate_fail(gate_name, None, None, None, None,
                          f"exit {result.returncode}", to_stderr=True)
            print(json.dumps(data))
            raise SystemExit(result.returncode)

        # Human-readable: print unittest output
        if result.stdout:
            sys.stdout.write(result.stdout.decode("utf-8", errors="replace"))
            sys.stdout.flush()
        if result.stderr:
            sys.stderr.write(result.stderr.decode("utf-8", errors="replace"))
            sys.stderr.flush()
        print(sentinel, end="")
        raise SystemExit(result.returncode)

    except SystemExit:
        raise
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


register(gate_scripts_own_unit_tests)
