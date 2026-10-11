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
    # error if body starts with '[' (i.e. "[gate-name] rule: message")
    is_error = body.startswith("[")
    tag = "error" if is_error else "warning"
    if parts:
        header = f"::{tag} {','.join(parts)}::{body}"
    else:
        header = f"::{tag}::{body}"
    # GitHub annotations always go to stdout so Actions picks them up
    sys.stdout.write(header + "\n")
    sys.stdout.flush()


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

    rule_label = rule or "violation"

    # Human output → stdout (structured, parseable)
    if file and line is not None:
        out_line = f"{file}:{line}: {rule_label}: {message}"
    elif file:
        out_line = f"{file}: {rule_label}: {message}"
    else:
        out_line = f"{rule_label}: {message}"

    sys.stdout.write(out_line + "\n")
    sys.stdout.flush()

    # CI annotation
    if is_ci():
        github_msg = f"[{gate_name}] {rule_label}: {message}"
        _github_error(file, line, github_msg)


def gate_warn(message: str, file: Optional[str] = None, line: Optional[int] = None) -> None:
    """Emit a non-blocking warning.

    In CI mode also emits a GitHub Actions ::warning annotation.
    """
    if file and line is not None:
        out_line = f"{file}:{line}: warning: {message}"
    elif file:
        out_line = f"{file}: warning: {message}"
    else:
        out_line = f"warning: {message}"

    sys.stdout.write(out_line + "\n")
    sys.stdout.flush()

    if is_ci():
        _github_error(file, line, message)
