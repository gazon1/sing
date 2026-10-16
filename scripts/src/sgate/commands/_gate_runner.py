"""sgate.commands._gate_runner — decorators for gate command execution.

Provides two decorators:
  @gate_runner(cmd, *, repo_root_str=None)
      Calls run() or run_capture() inside a try/except that:
        - Re-raises SystemExit unchanged (preserves IO_ERROR, INTERNAL_ERROR, etc.)
        - Catches genuine Exception, logs, and raises SystemExit(INTERNAL_ERROR)

  @kiwi_required
      Marks a gate as Kiwi-TCMS-dependent. Checks command availability first;
      exits KIWI_UNAVAILABLE if the command is not found (indicates Kiwi not set up).

Usage in a custom gate:
    @click.command("my_kiwi_gate")
    @click.option("--json", "emit_json", is_flag=True)
    @gate_runner(["traceability", "validate"])
    def my_kiwi_gate(emit_json: bool) -> None:
        ...

    my_kiwi_gate(IKI_REQUIRED = True)  # declared separately
"""
from __future__ import annotations

import functools
import json as _json
import sys
from typing import Callable, ParamSpec, TypeVar

import click

from sgate.commands._runner import run, run_capture
from sgate.commands.base import INTERNAL_ERROR
from sgate.log import logger
from sgate.output import gate_fail

P = ParamSpec("P")
R = TypeVar("R")


def gate_runner(
    cmd: list[str],
    *,
    repo_root_str: str | None = None,
    kiwi: bool = False,
) -> Callable[[Callable[P, R]], Callable[P, R]]:
    """Decorator that wraps gate command execution.

    Handles two modes:
      emit_json=False  → calls run(cmd); SystemExit propagates, Exception→INTERNAL_ERROR
      emit_json=True   → calls run_capture(cmd); returns parsed JSON; SystemExit propagates

    For KIWI_REQUIRED gates (kiwi=True): FileNotFoundError → KIWI_UNAVAILABLE (4).

    Args:
        cmd: Command list to execute.
        repo_root_str: Optional repo root override.
        kiwi: If True, FileNotFoundError is treated as KIWI_UNAVAILABLE, not IO_ERROR.
    """

    def decorator(fn: Callable[P, R]) -> Callable[P, R]:
        @functools.wraps(fn)
        def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
            gate_name = getattr(fn, "_gate_name", None) or fn.__name__

            # Detect --json / --check from kwargs or click context
            emit_json = kwargs.get("emit_json", False)
            check_mode = kwargs.get("check", False)

            try:
                if emit_json:
                    result = run_capture(cmd, gate_name)
                    out = (
                        result.stdout.decode("utf-8", errors="replace")
                        if result.stdout
                        else ""
                    )
                    data = {
                        "gate": gate_name,
                        "passed": result.returncode == 0,
                        "exit_code": result.returncode,
                        "output": out,
                    }
                    if result.returncode != 0:
                        gate_fail(
                            gate_name, None, None, None, None,
                            f"exit {result.returncode}",
                        )
                    print(_json.dumps(data))
                    raise SystemExit(result.returncode)
                else:
                    run(cmd, gate_name, repo_root_str=repo_root_str)

            except SystemExit:
                # Deliberate exits from run()/run_capture() — preserve them.
                raise
            except FileNotFoundError:
                from sgate.commands.base import IO_ERROR, KIWI_UNAVAILABLE

                code = KIWI_UNAVAILABLE if kiwi else IO_ERROR
                cmd_str = cmd[0] if cmd else "<unknown>"
                logger.error(f"{cmd_str} not found")
                gate_fail(
                    gate_name, None, None, None, None,
                    f"{cmd_str} not found (kiwi={'unavailable' if kiwi else 'error'})",
                )
                raise SystemExit(code)
            except Exception:
                logger.exception("unexpected error")
                gate_fail(
                    gate_name, None, None, None, None, "unexpected error"
                )
                raise SystemExit(INTERNAL_ERROR)

            # Clean run (emit_json=False only reaches here)
            return None  # type: ignore[return-value]

        return wrapper

    return decorator


def kiwi_required(fn: Callable[P, R]) -> Callable[P, R]:
    """Decorator marking a gate as Kiwi-TCMS-dependent.

    This is a class-level marker only; the actual availability check is
    performed by @gate_runner when it raises FileNotFoundError for the
    Kiwi command (traceability, singularity-adr, etc.).

    Usage:
        @kiwi_required
        def my_kiwi_gate(...) -> None:
            ...

        # Also declare at class level for info command:
        KIWI_REQUIRED = True
    """
    fn._kiwi_required = True  # type: ignore[attr-defined]
    return fn
