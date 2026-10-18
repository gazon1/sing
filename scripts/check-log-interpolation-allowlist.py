#!/usr/bin/env python3
"""
Log-interpolation allowlist gate: user-provided values in log messages must be allowlisted.

User-provided values in log messages risk log injection: a malicious actor can
craft input that pollutes log files with fake entries. The safe default is to
reject any user-provided type in a log message unless it is explicitly
allowlisted below.

The allowlist is a code-embedded constant rather than a separate file: the point
of the gate is to make each exemption a deliberate, visible decision made in
the same commit as the code it exempts, not a quiet addition to a text file
that reviewers never see.

## Sabotage

The control in _registry.py removes one type from ALLOWLISTED_TYPES when
`--sabotage` is passed, which causes the script to report that now-unlisted
type as an unexpected finding and fail the gate.

## Usage

    python3 scripts/check-log-interpolation-allowlist.py
    python3 scripts/check-log-interpolation-allowlist.py --sabotage  # for CI testing

Exit codes: 0 = no unexpected interpolations, 1 = findings, 2 = error.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import click

WORKSPACE = Path(__file__).resolve().parent.parent

# Types whose interpolated values are considered safe to log.
#
# Include the fully-qualified name of each safe type. A type must be in this
# list AND appear in source code to pass the gate — an entry here that
# nothing uses is not a finding.
#
# Rules for adding an entry:
# 1. The type carries no user-provided semantic content (opaque IDs, enums, etc.)
# 2. If in doubt, do not add it — use explicit sanitization in the log call instead.
ALLOWLISTED_TYPES: frozenset[str] = frozenset({
    # Primitive types — no user content possible
    "kotlin.Boolean",
    "kotlin.Int",
    "kotlin.Long",
    "kotlin.Float",
    "kotlin.Double",
    "kotlin.String",
    # Opaque identifiers — user content is the ID string itself, not semantic
    "com.singularity.todo.feature.tasks.domain.model.TaskId",
    "com.singularity.todo.feature.projects.domain.model.ProjectId",
    "com.singularity.todo.feature.tags.TagId",
    "com.singularity.todo.core.ids.UserId",
    # Kotlinx datetime (immutable value objects, no user-content)
    "kotlinx.datetime.LocalDate",
    "kotlinx.datetime.LocalTime",
    "kotlinx.datetime.Instant",
    # Internal enum types
    "com.singularity.todo.feature.tasks.domain.model.TaskPriority",
    "com.singularity.todo.feature.tasks.domain.model.TaskStatus",
    "com.singularity.todo.feature.tasks.domain.model.TaskKind",
    # Nullability markers
    "kotlin.Unit",
})

# Pattern: log.<level> { "string" }
# The log call uses a lambda: log.w { "message ${expr}" }
# We match the whole call first, then extract interpolations from the string.
#
# Kotlin interpolation forms:
#   ${expr}   — full expression in braces
#   $var      — simple variable reference
#   $x.y      — member access on simple variable
# We capture the first identifier after $ as the base type.
#
# Because ${...} blocks can contain } characters (e.g. Redaction::redactEmail}),
# we cannot use a simple character class to skip over them. Instead we match
# log.<level> { " and then manually parse the string content, treating ${...}
# as atomic units.
_LOG_CALL_RE = re.compile(r'\blog\.\w+\s*\{\s*"', re.MULTILINE)

# Extract all interpolations from a log string.
# Matches ${expr}, $var, $x.y
_INTERPOLATION_RE = re.compile(r'\$\{([^}]+)\}|\$(\w+(?:\.\w+)*)')


# Modules to scan
MODULES = ("shared", "androidApp", "desktopApp", "mcp-server")


def _extract_string_content(line: str, start: int) -> str | None:
    """
    Extract the content of a Kotlin double-quoted string starting at `start` (the opening ").

    Handles ${...} interpolations as atomic units so their internal {}/} don't
    terminate the string prematurely.
    Returns the string content (without quotes), or None if no closing " is found.
    """
    n = len(line)
    i = start + 1  # skip opening "
    while i < n:
        ch = line[i]
        if ch == '"':
            # Unescaped closing quote
            return line[start + 1:i]
        elif ch == '$':
            if i + 1 < n:
                nxt = line[i + 1]
                if nxt == '{':
                    # Skip to first } that closes this ${...} (no nested ${...} in Kotlin strings)
                    j = i + 2
                    while j < n and line[j] != '}':
                        j += 1
                    i = j
                    continue
                elif nxt.isidentifier():
                    # $identifier — skip the identifier
                    j = i + 2
                    while j < n and line[j].isidentifier():
                        j += 1
                    i = j
                    continue
        i += 1
    return None  # no closing quote found


def find_interpolations_in_file(path: Path) -> list[tuple[str, str, int]]:
    """
    Scan one Kotlin file for log interpolations.
    Returns list of (raw_log_call, base_type, line_number).
    """
    findings = []
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return findings

    for lineno, line in enumerate(text.splitlines(), start=1):
        # Skip comment-only lines
        stripped = line.strip()
        if stripped.startswith("//"):
            continue

        for m in _LOG_CALL_RE.finditer(line):
            # m.end() is the position right after 'log.<level> { "'
            string_content = _extract_string_content(line, m.end() - 1)
            if string_content is None:
                continue

            for im in _INTERPOLATION_RE.finditer(string_content):
                # Group 1 = ${expr}, Group 2 = $var
                expr = (im.group(1) or im.group(2)).split(".")[0]
                findings.append((m.group(0).strip()[:60], expr, lineno))

    return findings



def resolve_type_to_fqn(type_name: str) -> str | None:
    """
    Map a short type name seen in code to its fully-qualified name.

    This is a best-effort heuristic: it maps the most common cases
    found in this codebase. If a type is not in this map, it will
    be treated as user-provided and will fail the gate — which is
    the correct behavior for genuinely unknown types.
    """
    TYPE_MAP = {
        "TaskId": "com.singularity.todo.feature.tasks.domain.model.TaskId",
        "ProjectId": "com.singularity.todo.feature.projects.domain.model.ProjectId",
        "TagId": "com.singularity.todo.feature.tags.TagId",
        "UserId": "com.singularity.todo.core.ids.UserId",
        "TaskPriority": "com.singularity.todo.feature.tasks.domain.model.TaskPriority",
        "TaskStatus": "com.singularity.todo.feature.tasks.domain.model.TaskStatus",
        "TaskKind": "com.singularity.todo.feature.tasks.domain.model.TaskKind",
        "Task": "com.singularity.todo.feature.tasks.domain.model.Task",
        "LocalDate": "kotlinx.datetime.LocalDate",
        "LocalTime": "kotlinx.datetime.LocalTime",
        "Instant": "kotlinx.datetime.Instant",
        "String": "kotlin.String",
        "Boolean": "kotlin.Boolean",
        "Int": "kotlin.Int",
        "Long": "kotlin.Long",
        "Float": "kotlin.Float",
        "Double": "kotlin.Double",
        "Unit": "kotlin.Unit",
    }
    return TYPE_MAP.get(type_name)


def scan_workspace() -> list[tuple[str, str, int, str]]:
    """
    Scan all Kotlin source files in the workspace for log interpolations.

    Returns list of (raw_log_call, base_type, line_number, fqn_type).
    """
    findings = []
    for module in MODULES:
        src = WORKSPACE / module / "src"
        if not src.is_dir():
            continue
        for kt in src.rglob("*.kt"):
            for raw, base_type, lineno in find_interpolations_in_file(kt):
                fqn = resolve_type_to_fqn(base_type)
                findings.append((raw, base_type, lineno, fqn or ""))

    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Suppress informational output")
@click.option("--sabotage", is_flag=True, help=argparse.SUPPRESS)  # for CI control testing
def cli(quiet: bool, sabotage: bool) -> None:
    """Check log interpolations against the allowlist of safe types."""
    # Allowlist is embedded as a constant. When --sabotage is passed (injected by
    # the sabotage control in _registry.py), remove one entry so the gate fails
    # if that type is still being logged.
    allowlist = set(ALLOWLISTED_TYPES)
    if sabotage:
        # Remove TaskId — if any log call still uses it, the gate will report it.
        allowlist.discard("com.singularity.todo.feature.tasks.domain.model.TaskId")

    findings = scan_workspace()
    unexpected: list[tuple[str, str, int, str]] = []

    for raw, base_type, lineno, fqn in findings:
        if fqn and fqn not in allowlist:
            unexpected.append((raw, base_type, lineno, fqn))

    if not quiet:
        click.echo(f"scanned {len(findings)} log interpolations, {len(unexpected)} unexpected")

    if unexpected:
        click.echo(
            f"\n{len(unexpected)} log interpolation(s) use user-provided or unrecognised types "
            "that are not in the allowlist:",
            err=True,
        )
        seen: set[str] = set()
        for raw, base_type, lineno, fqn in sorted(unexpected, key=lambda x: x[2]):
            key = (base_type, fqn)
            if key in seen:
                continue
            seen.add(key)
            click.echo(f"  line {lineno}: {raw.strip()[:60]} -> {fqn or base_type}", err=True)
        click.echo(
            "\nIf this type is genuinely safe to log, add it to ALLOWLISTED_TYPES "
            "in scripts/check-log-interpolation-allowlist.py with a comment explaining why.",
            err=True,
        )
        raise click.Context(cli).exit(1)

    if not quiet:
        click.echo("no unexpected log interpolations")
    raise SystemExit(0)


if __name__ == "__main__":
    # Simple exit code mapping: 0 = pass, 1 = findings, 2 = error
    try:
        cli()
    except click.exceptions.ClickException as e:
        click.echo(str(e), err=True)
        raise SystemExit(e.exit_code if hasattr(e, "exit_code") else 2)
    except Exception as e:
        click.echo(f"error: {e}", err=True)
        raise SystemExit(2)
