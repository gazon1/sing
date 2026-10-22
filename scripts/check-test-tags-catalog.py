#!/usr/bin/env python3
"""Compare `TestTags.kt` with the generated section of `Maestro/TAGS.md`.

The defect this catches
-----------------------
`Maestro/TAGS.md` is a *golden file*: the catalogue of the ids a Maestro flow
may address, generated from `TestTags.kt`. The JVM test
(`TestTagsCatalogJvmTest`) compares the two and fails when they disagree — but
that test lives in the JVM suite, which takes forty minutes to run. So a tag
that was added an hour ago and a catalogue that was not rewritten are both
"green" for the rest of the session, and the person who finds out is the one
whose flow fails on a device.

This gate is the same comparison without the JVM: it parses the source of
truth and the generated file and says in a second whether they still agree.

What it compares
----------------
The **static constants** — every `const val` in `TestTags.kt`, qualified by the
object that holds it (`Pomodoro.CYCLE_LABEL`), and the row of the same name and
value in the generated section of `Maestro/TAGS.md`.

The dynamic functions are *not* compared: they are templates, not values, and
the JVM test owns their shape. The `Where` column is not compared either: it is
documentation, and an empty cell is not a defect.

What it does not do
-------------------
It does not rewrite the catalogue. Regeneration needs the JVM:

    ./gradlew :shared:jvmTest -PupdateGoldens=true

This gate tells you *that* the file is stale and *where* it disagrees; the
gradle command above is what fixes it.

Usage:
    scripts/check-test-tags-catalog.py            # human-readable report
    scripts/check-test-tags-catalog.py --quiet    # findings only, for CI
    scripts/check-test-tags-catalog.py --check    # fail when anything is found

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

#: The source of truth — the object every tag is declared in.
TEST_TAGS = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"

#: The golden file — the catalogue a Maestro flow may address.
TAGS_MD = ROOT / "Maestro/TAGS.md"

#: The markers around the generated region of the catalogue.
GENERATED_BEGIN = "<!-- GENERATED:BEGIN -->"
GENERATED_END = "<!-- GENERATED:END -->"

#: The heading of the table this gate reads. The dynamic functions live under
#: another heading, and their rows are templates, not values.
STATIC_HEADING = "## Static constants"

#: `object Name {` — the scope a constant is declared in.
OBJECT_OPEN = re.compile(r"^\s*(?:public\s+|internal\s+|private\s+)?object\s+(\w+)\s*\{")

#: `const val NAME = …` — the start of a constant's declaration.
CONST_START = re.compile(r"^\s*const\s+val\s+(\w+)\s*=\s*(.*)$")

#: A string literal. A declaration may hold several, concatenated.
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')

#: A row of the generated table: `| `NAME` | `value` | … |`.
TABLE_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*`([^`]*)`\s*\|")


def strip_comments(text: str) -> str:
    """Drop the comment lines, so a literal in a comment is not a declaration."""
    kept = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        kept.append(line)
    return "\n".join(kept)


def declared_constants(source: str) -> dict[str, str]:
    """The static constants of the source, as `NAME` → value.

    A constant nested in an object is qualified by that object, the way the
    catalogue writes it (`Pomodoro.CYCLE_LABEL`); a constant of the top-level
    object is not.
    """
    lines = strip_comments(source).splitlines()
    constants: dict[str, str] = {}
    scope: list[str] = []

    index = 0
    while index < len(lines):
        line = lines[index]

        opened = OBJECT_OPEN.match(line)
        if opened:
            scope.append(opened.group(1))
            index += 1
            continue

        declaration = CONST_START.match(line)
        if declaration:
            name = declaration.group(1)
            parts = [declaration.group(2)]
            # A declaration may continue on the next lines with `+`; the value
            # is the concatenation of its string literals.
            while parts[-1].rstrip().endswith("+") and index + 1 < len(lines):
                index += 1
                parts.append(lines[index])
            value = "".join(STRING_LITERAL.findall(" ".join(parts)))
            qualifier = ".".join(scope[1:]) if len(scope) > 1 else ""
            constants[f"{qualifier}.{name}" if qualifier else name] = value
            index += 1
            continue

        # A closing brace ends the innermost scope. Braces inside a string
        # literal are not braces: a tag built by interpolation
        # (`"multi_date_picker_day_${…}"`) would otherwise close a scope that
        # is still open.
        code = STRING_LITERAL.sub('""', line)
        if "}" in code:
            for _ in range(code.count("}")):
                if scope:
                    scope.pop()
        index += 1

    return constants


def catalog_rows(text: str) -> dict[str, str]:
    """The rows of the generated static-constants table, as `NAME` → value."""
    lines = text.splitlines()
    try:
        start = lines.index(GENERATED_BEGIN)
        end = lines.index(GENERATED_END)
    except ValueError:
        return {}

    rows: dict[str, str] = {}
    in_static = False
    for line in lines[start + 1 : end]:
        if line.strip().startswith("## "):
            if in_static:
                break
            in_static = line.strip() == STATIC_HEADING
            continue
        if not in_static:
            continue
        row = TABLE_ROW.match(line.strip())
        if row:
            rows[row.group(1)] = row.group(2)
    return rows


def differences() -> dict[str, list[str]]:
    """The ways the catalogue and the source can disagree."""
    if not TEST_TAGS.exists():
        return {"missing source": [str(TEST_TAGS)]}
    if not TAGS_MD.exists():
        return {"missing catalogue": [str(TAGS_MD)]}

    declared = declared_constants(TEST_TAGS.read_text(encoding="utf-8"))
    catalogued = catalog_rows(TAGS_MD.read_text(encoding="utf-8"))

    missing = sorted(name for name in declared if name not in catalogued)
    stale = sorted(name for name in catalogued if name not in declared)
    changed = sorted(
        name for name in declared if name in catalogued and catalogued[name] != declared[name]
    )
    differences = {
        "missing from the catalogue": missing,
        "not in the source any more": stale,
        "a different value": changed,
    }
    # A way of disagreeing that did not happen is not a difference: an empty
    # report is what "they agree" looks like.
    return {reason: names for reason, names in differences.items() if names}


@click.command()
@click.option("--quiet", is_flag=True, help="Findings only, for CI.")
@click.option("--check", is_flag=True, help="Fail when anything is found.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
@click.pass_context
def cli(ctx: click.Context, quiet: bool, check: bool, as_json: bool) -> None:
    """Report the catalogue rows that no longer match the source."""
    found = differences()
    total = sum(len(names) for names in found.values())

    if as_json:
        click.echo(json.dumps({"differences": found, "count": total}, indent=2))
    else:
        if not quiet:
            click.echo("check-test-tags-catalog: TestTags.kt against Maestro/TAGS.md")
        for reason, names in found.items():
            for name in names:
                click.echo(f"  [{reason}] {name}")
        if not quiet:
            click.echo()
            if total:
                click.echo(f"{total} difference(s) between the tags and the catalogue.")
                click.echo(
                    "Rewrite the catalogue with:\n"
                    "    ./gradlew :shared:jvmTest -PupdateGoldens=true"
                )
            else:
                click.echo("The catalogue agrees with the tags.")
    if total and check:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
