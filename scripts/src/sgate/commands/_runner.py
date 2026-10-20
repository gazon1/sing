"""sgate.commands._runner — shared gate execution logic.

All 45+ shims use this to run their underlying command, so the runner
is the single place where:
  - capture_output=True + stdout forwarding
  - gate_fail() on non-zero exit
  - FileNotFoundError / Exception handling
are implemented.
"""
from __future__ import annotations

import json
import subprocess
import sys

from sgate.commands.base import INTERNAL_ERROR, IO_ERROR
from sgate.log import logger
from sgate.models import GatePayload
from sgate.output import gate_fail, gate_warn

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
        # Forward the child's stderr instead of dropping it.
        #
        # `check-gate-liveness.py` decides "died instead of reported" by looking
        # for a traceback on stderr. Every registry gate runs through this runner,
        # so while stderr was captured and discarded, no gate could ever show one:
        # a gate that raised was reported as `exit 1: violation` and judged alive.
        # The liveness gate was not weak on one gate — it was blind on all of them,
        # which is why its own sabotage control kept passing after the subject it
        # aimed at had been retired.
        #
        # A finding is unaffected: gates report findings on stdout (`ERROR:` lines)
        # and exit non-zero, and this changes nothing about their exit status.
        if result.stderr:
            sys.stderr.write(result.stderr.decode())
            sys.stderr.flush()
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


def run_gate_command(cmd: list[str], gate_name: str, *, check: bool = False) -> None:
    """Run a gate command for the human-readable path.

    Without ``check`` this is :func:`run`: the command's output is streamed and
    its exit code is the gate's exit code.

    With ``check`` the output is still shown, but a non-zero exit is reported
    as a warning and the gate exits 0 — the dry run that ``--check`` promises in
    its help text. The flag used to be accepted and ignored, so the promise was
    a lie and a caller asking for a dry run got a hard failure instead.

    Args:
        cmd: Command list to execute.
        gate_name: Display name used in the warning.
        check: Dry-run mode: report, do not fail.
    """
    if not check:
        run(cmd, gate_name)
        return

    result = run_capture(cmd, gate_name)
    if result.stdout:
        sys.stdout.write(result.stdout.decode("utf-8", errors="replace"))
        sys.stdout.flush()
    if result.stderr:
        sys.stderr.write(result.stderr.decode("utf-8", errors="replace"))
        sys.stderr.flush()
    if result.returncode != 0:
        gate_warn(
            gate_name, None, None, None, None,
            f"exit {result.returncode} (--check: reported, not failed)",
            to_stderr=True,
        )


def run_json_contract(
    cmd: list[str],
    gate_name: str,
    *,
    payload_class: type[GatePayload] = GatePayload,
) -> tuple[GatePayload, int]:
    """Run a producer that speaks the JSON contract and return ``(payload, returncode)``.

    The contract is one JSON object on stdout. An adapter that rebuilds the
    verdict from the prose of the producer's output is parsing a human's
    wording; the producer already knows the answer and can say it in a form a
    machine can read.

    A stdout that is not JSON is a broken contract, not a passing gate. The
    caller receives a payload that says the gate did not pass, and an exit
    code of INTERNAL_ERROR, because a producer that stopped speaking the
    contract has not passed anything.

    Args:
        cmd: Command list, expected to include the producer's ``--json`` flag.
        gate_name: Display name used in gate_fail() annotations.
        payload_class: Typed payload to build; defaults to the base GatePayload.

    Returns:
        The parsed payload and the producer's exit code. A broken contract
        yields a payload that failed and the exit code INTERNAL_ERROR.
    """
    result = run_capture(cmd, gate_name)
    raw = result.stdout.decode("utf-8", errors="replace") if result.stdout else ""
    # The producer's stderr is diagnostics, and the contract owns stdout only.
    # Forwarding it keeps a traceback visible: the liveness gate reads stderr to
    # tell "the gate died" from "the gate reported a finding", and a swallowed
    # traceback makes a crash look like a quiet failure.
    if result.stderr:
        sys.stderr.write(result.stderr.decode("utf-8", errors="replace"))
        sys.stderr.flush()
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return _broken_contract(
            gate_name, f"producer did not emit the JSON contract: {raw[:200]!r}"
        )
    if not isinstance(data, dict):
        return _broken_contract(
            gate_name, f"JSON contract is not an object: {raw[:200]!r}"
        )
    try:
        payload = payload_class(gate=gate_name, **data)
    except Exception as exc:  # noqa: BLE001 - any validation error is the same defect
        return _broken_contract(
            gate_name, f"JSON contract does not fit the payload: {exc}"
        )
    return payload, result.returncode


def _broken_contract(gate_name: str, diagnostic: str) -> tuple[GatePayload, int]:
    """A producer that broke the contract still owes the caller a verdict.

    The contract channel is stdout, so the diagnostic goes to stderr — and the
    GitHub annotation is turned off, because that one is written to stdout as
    well and would corrupt the JSON a consumer is about to read.

    What the caller receives is a payload that says the gate did not pass, and
    an exit code that says the contract broke. A consumer that only reads the
    exit code still sees the failure; a consumer that reads the JSON sees a
    well-formed report instead of a broken one.
    """
    gate_fail(
        gate_name, None, None, None, None, diagnostic,
        to_stderr=True, annotate=False,
    )
    payload = GatePayload(
        gate=gate_name, passed=False, blocking_errors=[diagnostic]
    )
    return payload, INTERNAL_ERROR


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
