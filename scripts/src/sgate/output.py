"""sgate.output — structured output for gates.

- gate_fail()       — emit a violation (human + GitHub Actions annotation in CI)
- gate_warn()       — emit a warning (CI::warning annotation)
- is_ci()           — detect GitHub Actions environment
- contract_stdout() — declare that stdout carries a machine-readable contract

In CI mode (CI=true) raw tracebacks are never printed.
"""
from __future__ import annotations

import contextlib
import os
import sys
import threading
from typing import Iterator, Optional

# ── helpers ──────────────────────────────────────────────────────────────────

#: Whether the current thread is inside a ``contract_stdout()`` block.
#:
#: Thread-local because a gate runner may execute gates in parallel threads,
#: and a contract belongs to one gate's stdout, not to the process.
_CONTRACT = threading.local()


def is_ci() -> bool:
    """Return True when running in GitHub Actions."""
    return bool(os.environ.get("CI"))


def _contract_active() -> bool:
    """True while stdout is claimed by a contract."""
    return bool(getattr(_CONTRACT, "active", False))


@contextlib.contextmanager
def contract_stdout() -> Iterator[None]:
    """Declare that stdout carries a machine-readable contract.

    The GitHub Actions annotation is written to **stdout** — that is where
    Actions reads workflow commands from. A gate that emits JSON on stdout
    therefore has two claimants for one channel, and in CI the annotation wins:
    the document a consumer is about to parse is preceded by a line that is
    not JSON.

    Inside this context the annotation is written to stderr instead. It stays
    visible in the CI log; it simply stops being a workflow command, which is
    the right trade when stdout belongs to a contract. The exit code is what
    fails the build either way.

    A flag on every call site cannot express this: a new adapter that forgets
    it repeats the defect, and nothing in the code says which one owns stdout.
    A block says it once, where the contract is emitted.
    """
    previous = _contract_active()
    _CONTRACT.active = True
    try:
        yield
    finally:
        _CONTRACT.active = previous


def _github_error(
    file: Optional[str], line: Optional[int], body: str, *, level: str = "error"
) -> None:
    """Emit a GitHub Actions error/warning annotation.

    level must be ``"error"`` or ``"warning"``.
    """
    tag = "error" if level == "error" else "warning"
    parts = []
    if file:
        parts.append(f"file={file}")
    if line is not None:
        parts.append(f"line={line}")
    if parts:
        header = f"::{tag} {','.join(parts)}::{body}"
    else:
        header = f"::{tag}::{body}"
    # GitHub annotations always go to stdout so Actions picks them up — unless
    # stdout carries a contract, in which case they go to stderr and stay
    # visible in the log without being parsed as a workflow command.
    stream = sys.stderr if _contract_active() else sys.stdout
    stream.write(header + "\n")
    stream.flush()


# ── public API ───────────────────────────────────────────────────────────────

def gate_fail(
    gate_name: str,
    file: Optional[str],
    line: Optional[int],
    expected: Optional[str],
    actual: Optional[str],
    rule: Optional[str],
    message: Optional[str] = None,
    *,
    to_stderr: bool = False,
) -> None:
    """Emit one gate violation.

    In CI mode also emits a GitHub Actions ::error annotation.
    message is constructed from expected/actual if not provided.

    Args:
        to_stderr: If True, write human output to stderr instead of stdout.
                   Use in --json mode to keep stdout clean for JSON output.

    The annotation follows the channel rule: inside :func:`contract_stdout` it
    goes to stderr, because stdout belongs to the contract there.
    """
    if message is None:
        parts = []
        if expected:
            parts.append(f"expected {expected}")
        if actual:
            parts.append(f"got {actual}")
        message = ", ".join(parts) if parts else "violation"

    rule_label = rule or "violation"

    # Human output → stderr (in --json mode) or stdout
    if file and line is not None:
        out_line = f"{file}:{line}: {rule_label}: {message}"
    elif file:
        out_line = f"{file}: {rule_label}: {message}"
    else:
        out_line = f"{rule_label}: {message}"

    stream = sys.stderr if to_stderr else sys.stdout
    stream.write(out_line + "\n")
    stream.flush()

    # CI annotation
    if is_ci():
        github_msg = f"[{gate_name}] {rule_label}: {message}"
        _github_error(file, line, github_msg, level="error")


def gate_warn(
    message: str,
    file: Optional[str] = None,
    line: Optional[int] = None,
    *,
    to_stderr: bool = False,
) -> None:
    """Emit a non-blocking warning.

    In CI mode also emits a GitHub Actions ::warning annotation, on the same
    channel rule as :func:`gate_fail`.
    """
    if file and line is not None:
        out_line = f"{file}:{line}: warning: {message}"
    elif file:
        out_line = f"{file}: warning: {message}"
    else:
        out_line = f"warning: {message}"

    stream = sys.stderr if to_stderr else sys.stdout
    stream.write(out_line + "\n")
    stream.flush()

    if is_ci():
        _github_error(file, line, message, level="warning")
