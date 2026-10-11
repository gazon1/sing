"""sgate.output — structured output for gates.

- gate_fail() — emit a violation (human + GitHub Actions annotation in CI)
- gate_warn()  — emit a warning (CI::warning annotation)
- is_ci()      — detect GitHub Actions environment

In CI mode (CI=true) raw tracebacks are never printed.
"""
from __future__ import annotations

import os
import sys
from typing import Optional

# ── helpers ──────────────────────────────────────────────────────────────────

def is_ci() -> bool:
    """Return True when running in GitHub Actions."""
    return bool(os.environ.get("CI"))


def _github_error(file: Optional[str], line: Optional[int], body: str) -> None:
    """Emit a GitHub Actions error/warning annotation.

    ::error file=<path>,line=<n>::<message>
    ::warning file=<path>,line=<n>::<message>
    """
    parts = []
    if file:
        parts.append(f"file={file}")
    if line is not None:
        parts.append(f"line={line}")
    if parts:
        header = f"::{'error' if body.startswith('[') else 'warning'} {','.join(parts)}::"
    else:
        header = f"::{'error' if body.startswith('[') else 'warning'}::"
    print(header + body, flush=True)


def _write_violation(
    file: Optional[str],
    line: Optional[int],
    rule: Optional[str],
    message: str,
) -> None:
    """Write one violation line to stdout (human format)."""
    if file and line is not None:
        print(f"{file}:{line}: {rule or 'violation'}: {message}", flush=True)
    elif file:
        print(f"{file}: {rule or 'violation'}: {message}", flush=True)
    else:
        print(f"{rule or 'violation'}: {message}", flush=True)


# ── public API ───────────────────────────────────────────────────────────────

def gate_fail(
    gate_name: str,
    file: Optional[str],
    line: Optional[int],
    expected: Optional[str],
    actual: Optional[str],
    rule: Optional[str],
    message: Optional[str] = None,
) -> None:
    """Emit one gate violation.

    In CI mode also emits a GitHub Actions ::error annotation.
    message is constructed from expected/actual if not provided.
    """
    if message is None:
        parts = []
        if expected:
            parts.append(f"expected {expected}")
        if actual:
            parts.append(f"got {actual}")
        message = ", ".join(parts) if parts else "violation"

    # Human output → stdout
    _write_violation(file, line, rule, message)

    # CI annotation
    if is_ci():
        github_msg = f"[{gate_name}] {rule or 'violation'}: {message}"
        _github_error(file, line, github_msg)


def gate_warn(message: str, file: Optional[str] = None, line: Optional[int] = None) -> None:
    """Emit a non-blocking warning.

    In CI mode also emits a GitHub Actions ::warning annotation.
    """
    if file and line is not None:
        print(f"{file}:{line}: warning: {message}", flush=True)
    elif file:
        print(f"{file}: warning: {message}", flush=True)
    else:
        print(f"warning: {message}", flush=True)

    if is_ci():
        _github_error(file, line, message)
