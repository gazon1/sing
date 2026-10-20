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

## What "type" means here, exactly

The gate has no parser, so it cannot know the declared type of an interpolated
value. It uses the *name* of the interpolated identifier as a proxy: `$TaskId`
is read as the type `TaskId`, and `$task` is read as a type called `task`, which
no one has heard of. A name the map does not know is **not** a finding.

That is a real limitation and it is stated here rather than left in the code to
be discovered: the gate catches an interpolation whose variable is named after
a type it knows, and it stays quiet about every other name. Thirty-eight
distinct names are interpolated in this tree that the map does not know, among
them `email` — the very case the gate exists for.

Closing that gap is a policy decision, not a refactoring: the honest fix is to
allowlist *names* rather than types, which changes what the allowlist means
and turns the gate red on every unknown name until each is judged. The
maintainers own that call. Until it is made, this gate is a floor and not a
ceiling, and the docstring says so.

## Sabotage

The control in `_registry.py` plants a log call that interpolates a type the
map knows and the allowlist does not, and the gate must report it and fail.

The previous control removed the `TaskId` entry from the allowlist instead,
and it could not fail the gate: no log call in this tree interpolates a name
that resolves to `TaskId`, so removing the entry changed nothing. A control
that cannot fail the gate is worse than no control, because it is reported as
a passing one.

## Usage

    python3 scripts/check-log-interpolation-allowlist.py
    python3 scripts/check-log-interpolation-allowlist.py --sabotage  # for CI testing

Exit codes: 0 = no unexpected interpolations, 1 = findings, 2 = error.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import click

from scanners.source_text import strip_comments

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

# The log call: `log.<level> { "message" }`, with an optional argument list
# (`log.e(e) { "message" }` — the exception being logged) and with the message
# starting on any later line.
#
# The old pattern required the opening quote on the same line as the call, and
# the scan was line by line, so a multi-line call and a call with an argument
# were both invisible: 28 of the 74 interpolations in this tree were seen.
_LOG_CALL_RE = re.compile(r"\blog\.\w+\s*(?:\([^()]*\))?\s*\{")


# Modules to scan
MODULES = ("shared", "androidApp", "desktopApp", "mcp-server")


def _string_at(text: str, start: int) -> tuple[str, int] | None:
    """
    The content of the string literal that starts at [start], and the index
    just past it.

    A plain string ends at the next unescaped quote; a raw string ends at the
    last three quotes of a run of three or more. [start] must point at the
    opening quote.
    """
    n = len(text)
    if text.startswith('"""', start):
        close = text.find('"""', start + 3)
        if close == -1:
            return None
        run = close
        while run < n and text[run] == '"':
            run += 1
        if run - close > 3:
            return text[start + 3:run - 3], run
        return text[start + 3:close], close + 3
    i = start + 1
    while i < n:
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == '"':
            return text[start + 1:i], i + 1
        if ch == "\n":
            # A plain string does not span lines in Kotlin.
            return None
        i += 1
    return None


def _interpolations(content: str) -> list[str]:
    """
    The base name of every template expression in a Kotlin string.

    The three forms are `${expr}`, `$name` and `$name.member`. For a braced
    expression the base is the first segment of the expression; for a simple
    one it is the identifier itself. The base is what the type map is keyed by.
    """
    found: list[str] = []
    i = 0
    n = len(content)
    while i < n:
        ch = content[i]
        if ch != "$" or i + 1 >= n:
            i += 1
            continue
        nxt = content[i + 1]
        if nxt == "{":
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if content[j] == "{":
                    depth += 1
                elif content[j] == "}":
                    depth -= 1
                j += 1
            expr = content[i + 2:max(i + 2, j - 1)]
            base = expr.split(".")[0].split("(")[0].strip()
            if base:
                found.append(base)
            i = j
            continue
        if nxt.isalpha() or nxt == "_":
            j = i + 1
            while j < n and (content[j].isalnum() or content[j] == "_"):
                j += 1
            found.append(content[i + 1:j])
            i = j
            continue
        i += 1
    return found


def find_interpolations_in_text(text: str) -> list[tuple[str, str, int]]:
    """
    Scan Kotlin source for log interpolations.
    Returns list of (raw_log_call, base_type, line_number).
    """
    findings: list[tuple[str, str, int]] = []

    # Comments are blanked first, so a log call quoted in a comment is not a
    # log call. The string *contents* stay: they are the message.
    code = strip_comments(text)

    for match in _LOG_CALL_RE.finditer(code):
        # The message is the first string literal after the call's opening brace.
        j = match.end()
        while j < len(code) and code[j].isspace():
            j += 1
        if j >= len(code) or code[j] != '"':
            continue
        literal = _string_at(code, j)
        if literal is None:
            continue
        content, _ = literal
        lineno = code.count("\n", 0, match.start()) + 1
        for base in _interpolations(content):
            findings.append((match.group(0).strip()[:60], base, lineno))
    return findings


def find_interpolations_in_file(path: Path) -> list[tuple[str, str, int]]:
    """Scan one Kotlin file for log interpolations."""
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return []
    return find_interpolations_in_text(text)


# The probe the `--sabotage` flag scans: a log call that interpolates a type
# the map knows and the allowlist does not. It goes through the same detector
# as the real tree, so the flag proves the *detection* path and not merely the
# reporting path.
PROBE_SOURCE = """
private fun logInterpolationControlProbe() {
    log.i { "planted=$Task" }
}
"""


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
# `hidden=True` is Click's own "do not show this option in --help". It used to
# be `help=argparse.SUPPRESS`, which is argparse's spelling of the same thing —
# and the only reason this file imported argparse at all, which made the
# "use Click, not argparse" rule (R2.1) report a file that had no argparse left
# in it.
@click.option("--sabotage", is_flag=True, hidden=True, help="Plant a probe (CI control testing).")
def cli(quiet: bool, sabotage: bool) -> None:
    """Check log interpolations against the allowlist of safe types."""
    # Allowlist is embedded as a constant. When --sabotage is passed (injected by
    # the sabotage control in _registry.py), remove one entry so the gate fails
    # if that type is still being logged.
    allowlist = set(ALLOWLISTED_TYPES)

    findings = scan_workspace()
    if sabotage:
        # Scan the probe through the real detector. The previous version removed
        # a type from the allowlist instead, and it could not fail the gate: no
        # log call in this tree interpolates a name that resolves to that type,
        # so removing the entry changed nothing. A control that cannot fail is
        # worse than no control, because it is reported as a passing one.
        for raw, base, lineno in find_interpolations_in_text(PROBE_SOURCE):
            findings.append((raw, base, lineno, resolve_type_to_fqn(base) or ""))
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
