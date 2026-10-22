#!/usr/bin/env python3
"""Find text surfaces that are neither selectable nor classified.

The defect this catches
-----------------------
Text a person reads should be selectable — they should be able to copy a line of an
error message, a backup id, a project name. The app has two widgets for that
(`SelectableText`, `LinkifiedText`) and a registry that says which surface uses
which, in `.agents/skills/singularity-todo-selectable-text/SKILL.md`.

A surface that renders prose with a bare `Text(...)` is a surface a person cannot
copy from. The registry test (`SelectableTextConventionTest`) catches the case
where a surface *uses a widget* and is not listed; this scan catches the case
where a surface uses *no widget at all* and nobody wrote it down.

Why this scan and the registry test are different gates
-------------------------------------------------------
The registry test compares two exact facts — a file's imports and the table's row —
and can be wrong in only one way, so it blocks. This scan has to guess what "prose"
means from the text of a call, so it can be wrong in many ways: a literal that looks
like a sentence may be a test tag, a format string, a key.

It was therefore advisory while the inventory was open. The inventory is closed
now — every prose surface is either rendered with a selectable widget or written
down in the table as an exclusion — so the remaining findings are decisions rather
than gaps, and the scan blocks like every other gate. `--check` is what turns the
report into a failing gate, and the gate registry passes it.

What counts as prose
--------------------
A string literal passed to `Text(...)` that has at least three words, or that is
long enough to be a sentence. Short literals — "Today", "Save", "AI Suggestions" —
are labels, and a label that cannot be selected is not a defect.

Usage:
    scripts/check-selectable-text-coverage.py            # human-readable report
    scripts/check-selectable-text-coverage.py --quiet    # findings only, for CI
    scripts/check-selectable-text-coverage.py --check    # fail when anything is found

Exit code is 0 by default and 1 under `--check` when anything is found.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import click

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = Path(__file__).resolve().parent.parent

#: Where the app's Kotlin sources live, relative to the repository root.
SOURCES = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo"

#: The skill that publishes the registry.
SKILL = ROOT / ".agents/skills/singularity-todo-selectable-text/SKILL.md"

#: The header of the registry table — the table's identity.
TABLE_HEADER = ("File", "Treatment", "Reason")

#: A `Text(...)` call whose first string literal is the text.
TEXT_CALL = re.compile(r'\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"')

#: A string literal that reads as a sentence rather than as a label.
PROSE = re.compile(r"^(?:\S+\s+){2,}\S+$")


def strip_comment_lines(text: str) -> str:
    """Drop the lines that are comments, so a literal in a comment is not a call."""
    kept = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        kept.append(line)
    return "\n".join(kept)


def looks_like_prose(literal: str) -> bool:
    """Whether a string literal reads as prose rather than as a label."""
    if "{" in literal or "}" in literal:
        return False
    words = literal.split()
    if len(words) < 3 and len(literal) < 24:
        return False
    return bool(PROSE.match(literal))


def registry_paths() -> set[str]:
    """The files the skill's registry table classifies."""
    if not SKILL.exists():
        return set()
    lines = SKILL.read_text().splitlines()
    header = [cell.strip() for cell in TABLE_HEADER]
    index = next(
        (i for i, line in enumerate(lines) if cells(line) == header),
        -1,
    )
    if index < 0:
        return set()
    paths = set()
    for line in lines[index + 1 :]:
        if not line.strip().startswith("|"):
            break
        row = cells(line)
        if len(row) != len(header):
            continue
        path = row[0].strip("`").strip()
        if path.endswith(".kt"):
            paths.add(path)
    return paths


def cells(line: str) -> list[str]:
    """The cells of a Markdown table row; an empty list for anything else."""
    if not line.strip().startswith("|"):
        return []
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def uncovered_surfaces() -> list[tuple[str, str]]:
    """The prose texts rendered by a file the registry does not classify."""
    classified = registry_paths()
    found: list[tuple[str, str]] = []
    for path in sorted(SOURCES.rglob("*.kt")):
        relative = path.relative_to(SOURCES).as_posix()
        if relative in classified:
            continue
        source = strip_comment_lines(path.read_text())
        for match in TEXT_CALL.finditer(source):
            literal = match.group(1)
            if looks_like_prose(literal):
                found.append((relative, literal))
                break
    return found


@click.command()
@click.option("--quiet", is_flag=True, help="Findings only, for CI.")
@click.option("--check", is_flag=True, help="Fail when anything is found.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
@click.pass_context
def cli(ctx: click.Context, quiet: bool, check: bool, as_json: bool) -> None:
    """Report the prose texts that no registry row covers."""
    findings = uncovered_surfaces()
    if as_json:
        click.echo(
            json.dumps(
                {
                    "findings": [{"file": file, "text": text} for file, text in findings],
                    "count": len(findings),
                },
                indent=2,
            )
        )
    else:
        if not quiet:
            click.echo("check-selectable-text-coverage: prose texts in unclassified files")
        for file, text in findings:
            click.echo(f"  [unclassified-prose] {file}: {text!r}")
        if not quiet:
            click.echo()
            click.echo(f"{len(findings)} surface(s) render prose and are not in the registry.")
            click.echo(
                "Either render them with SelectableText / LinkifiedText, or add the file to "
                "the inventory table of .agents/skills/singularity-todo-selectable-text/SKILL.md."
            )
    if findings and check:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
