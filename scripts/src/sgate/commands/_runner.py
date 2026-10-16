"""sgate.commands._runner — shared gate execution logic.

All 45+ shims use this to run their underlying command, so the runner
is the single place where:
  - capture_output=True + stdout forwarding
  - gate_fail() on non-zero exit
  - FileNotFoundError / Exception handling
are implemented.
"""
from __future__ import annotations

import subprocess
import sys

from sgate.commands.base import INTERNAL_ERROR, IO_ERROR
from sgate.log import logger
from sgate.output import gate_fail

# Re-export CompletedProcess for callers who want to capture output
Result = subprocess.CompletedProcess


def run(
    cmd: list[str],
    gate_name: str,
    *,
    repo_root_str: str | None = None,
) -> None:
    """Run a gate command, forwarding stdout and calling gate_fail on failure.

    Args:
        cmd: Command list to execute (e.g. [sys.executable, "script.py"] or
            ["bash", "script.sh"] or ["traceability", "validate"])
        gate_name: Display name used in gate_fail() annotation.
        repo_root_str: Optional repo root override (for bash -c commands that
            need to set ROOT before sourcing static-gates.sh).

    Raises:
        SystemExit: IO_ERROR (cmd/file not found), or the command's own exit code
            on validation failure.
    """
    try:
        result = subprocess.run(cmd, capture_output=True)
        if result.stdout:
            sys.stdout.write(result.stdout.decode())
            sys.stdout.flush()
        if result.returncode != 0:
            gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}")
            raise SystemExit(result.returncode)
    except FileNotFoundError:
        # Narrow exception: only catches the exec call, not the python3 above.
        cmd_str = cmd[0] if cmd else "<unknown>"
        logger.error(f"{cmd_str} not found")
        gate_fail(gate_name, None, None, None, None, f"{cmd_str} not found")
        raise SystemExit(IO_ERROR)
    except OSError as exc:
        # e.g. bash script not executable, python3 not found (different from
        # FileNotFoundError which catches the exec call specifically).
        cmd_str = cmd[0] if cmd else "<unknown>"
        logger.error(f"OS error running {cmd_str}: {exc}")
        gate_fail(gate_name, None, None, None, None, f"OS error: {exc}")
        raise SystemExit(IO_ERROR)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


def run_capture(cmd: list[str], gate_name: str) -> subprocess.CompletedProcess:
    """Run a gate command and return the result without forwarding output.

    Used by --json mode to capture output for structured parsing.

    FileNotFoundError is returned as CompletedProcess with returncode=127 so
    callers in --json mode can emit structured JSON even when the command
    is missing.

    Raises:
        SystemExit: INTERNAL_ERROR only (unexpected exceptions).
    """
    try:
        return subprocess.run(cmd, capture_output=True)
    except FileNotFoundError:
        cmd_str = cmd[0] if cmd else "<unknown>"
        logger.error(f"{cmd_str} not found")
        # Return a CompletedProcess with exit 127 instead of raising,
        # so --json callers can emit structured output.
        # Mark it so callers (especially KIWI_REQUIRED gates) can distinguish
        # "command not found" from "command ran and failed".
        result = subprocess.CompletedProcess(cmd, 127, b"", f"{cmd_str}: not found".encode())
        result._cmd_not_found = True  # type: ignore[attr-defined]
        return result
    except OSError as exc:
        cmd_str = cmd[0] if cmd else "<unknown>"
        logger.error(f"OS error running {cmd_str}: {exc}")
        gate_fail(gate_name, None, None, None, None, f"OS error: {exc}")
        raise SystemExit(IO_ERROR)
    except Exception:
        logger.exception("unexpected error")
        gate_fail(gate_name, None, None, None, None, "unexpected error")
        raise SystemExit(INTERNAL_ERROR)


def run_python_script(
    script_path: str,
    gate_name: str,
    *,
    repo_root: str,
    extra_args: list[str] | None = None,
) -> None:
    """Convenience: run a Python script gate."""
    from pathlib import Path

    script = Path(script_path)
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    py_cmd = [sys.executable, str(script)]
    if extra_args:
        py_cmd.extend(extra_args)

    run(py_cmd, gate_name)


def run_bash_script(
    script_path: str,
    gate_name: str,
    *,
    repo_root: str,
    extra_args: list[str] | None = None,
) -> None:
    """Convenience: run a bash script gate."""
    from pathlib import Path

    script = Path(script_path)
    if not script.exists():
        logger.error(f"{script} not found")
        gate_fail(gate_name, None, None, None, None, "script not found")
        raise SystemExit(IO_ERROR)

    bash_cmd = [str(script)]
    if extra_args:
        bash_cmd.extend(extra_args)

    run(["bash"] + bash_cmd, gate_name)


# ── handle_result (extracted from generated shims) ───────────────────────────────


def handle_result(result: subprocess.CompletedProcess[bytes], gate_name: str) -> None:
    """Handle run_capture result for non-Kiwi gates.

    Emits JSON to stdout and raises SystemExit with the command's return code.
    This replaces the 45+ identical _handle_result implementations that were
    generated by _generate_shims.py (now deleted).
    """
    import json

    out = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
    data = {
        "gate": gate_name,
        "passed": result.returncode == 0,
        "exit_code": result.returncode,
        "output": out,
    }
    if result.returncode != 0:
        gate_fail(gate_name, None, None, None, None, f"exit {result.returncode}", to_stderr=True)
    print(json.dumps(data))
    raise SystemExit(result.returncode)
