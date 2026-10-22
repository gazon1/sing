#!/usr/bin/env python3
"""check-di-fixture-sync.py — the DI fixture table must match the Koin definitions.

The defect this catches
-----------------------
A ViewModel that takes its arguments at runtime has a Koin definition of the shape
`viewModel { (route: TasksRoute.Detail) -> TaskDetailCoordinator(...) }`. The
`DomainGraphResolutionTest` fixture table says what to hand that definition when
the graph is built. The two live in different modules — the definition in
`shared/.../core/di/`, the fixture in `desktopApp/.../jvmTest/` — and nothing
keeps them in step.

The cost of a step is a crash at graph construction, which is exactly what
happened on 2026-10-22: `TaskDetailCoordinator` was given a route instead of a
`TaskId`, the definition was updated, and the fixture was not. The graph could
not be created, and the only test that says so lives in the full desktop suite —
an hour of wall clock away on a loaded host, at the very end of the work.

Why this is a text check and not a runtime check
------------------------------------------------
The runtime check already exists and is good: `DomainGraphResolutionTest` builds
every definition in the graph and fails on the first one that cannot be built.
What it cannot do is fail *early*. This gate reads the same two facts from the
sources — the parameter types a definition destructures, and the values the
fixture hands it — and compares them without a JVM, so it runs in the static
gates and in the pre-push hook, in seconds.

What it compares
----------------
For every definition that takes runtime parameters:

  * the fixture table has a row for that ViewModel;
  * the row has one value per parameter;
  * each value can satisfy the parameter's type.

"Cannot satisfy" is decided from the text, so it is a comparison and not a type
checker: a value must mention the parameter's simple type name (`Detail` for
`com.singularity.todo.feature.nav.TasksRoute.Detail`), and a literal must be a
literal of that type (`"section-1"` satisfies `String`, `2026` satisfies `Int`).
A value built by a lower-case factory function (`sampleAgendaDefinition()`)
cannot be read from the text, so it is left alone — the runtime test is what
judges it. The consequence is deliberate: this gate catches a definition and a
fixture that disagree about *which* type they are talking about, and it stays
silent about a value that merely looks unusual.

Usage:
    scripts/check-di-fixture-sync.py            # human-readable report
    scripts/check-di-fixture-sync.py --quiet    # findings only, for CI
    scripts/check-di-fixture-sync.py --check    # fail when anything is found
    scripts/check-di-fixture-sync.py --json     # JSON instead of text

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

#: The repository root — the tree to check. The pre-push hook runs this script
#: against a throwaway checkout of the tree being pushed, while the script
#: itself comes from the repository the hook runs in, so the root is an argument
#: and not a derivation.
DEFAULT_ROOT = Path(__file__).resolve().parent.parent

#: Where the Koin definitions live. The whole source tree is scanned, not just
#: `core/di`: a platform module may define a ViewModel with runtime parameters
#: too, and the fixture table is the one place that says how to build it.
SOURCES_RELATIVE = Path("shared/src")

#: The fixture table — the values the graph test hands to each definition.
FIXTURE_TABLE_RELATIVE = Path(
    "desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt",
)

#: `viewModel { (a: T, b: U) ->` followed by the ViewModel being built.
VIEW_MODEL_WITH_PARAMS = re.compile(
    r"viewModel\s*\{\s*\(([^)]*)\)\s*->\s*\n\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(",
)

#: One row of the fixture table: `X::class to listOf(...),`
FIXTURE_ROW = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)::class\s+to\s+listOf\((.*)\),\s*$")

#: A parameter of a destructuring lambda: `name: Type`.
PARAM = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_.]*)")

#: A string literal, a number, a boolean — the types a literal can have.
STRING_LITERAL = re.compile(r'^"')
INT_LITERAL = re.compile(r"^-?\d+$")
LONG_LITERAL = re.compile(r"^-?\d+[lL]$")
BOOLEAN_LITERAL = re.compile(r"^(true|false)$")


def split_top_level(text: str) -> list[str]:
    """Split a list of values on the commas that are not inside brackets or quotes."""
    values: list[str] = []
    depth = 0
    in_string = False
    current: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if in_string:
            current.append(char)
            if char == "\\":
                index += 1
                if index < len(text):
                    current.append(text[index])
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            current.append(char)
        elif char in "([{":
            depth += 1
            current.append(char)
        elif char in ")]}":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            values.append("".join(current).strip())
            current = []
        else:
            current.append(char)
        index += 1
    tail = "".join(current).strip()
    if tail:
        values.append(tail)
    return values


def simple_name(type_text: str) -> str:
    """The type's simple name — `com.x.y.TasksRoute.Detail` → `Detail`."""
    return type_text.rstrip("?").split(".")[-1]


def value_can_satisfy(value: str, expected: str) -> bool | None:
    """Whether a fixture value can stand for a parameter of the expected type.

    `None` means the text does not say — a value built by a factory function
    whose name starts lower-case is one the text cannot judge, and the runtime
    test is what judges it.
    """
    value = value.strip()
    if STRING_LITERAL.match(value):
        return expected == "String"
    if BOOLEAN_LITERAL.match(value):
        return expected == "Boolean"
    if INT_LITERAL.match(value):
        return expected in ("Int", "Long", "Number")
    if LONG_LITERAL.match(value):
        return expected in ("Long", "Number")
    if expected in value:
        return True
    callee = value.split("(", 1)[0].strip()
    if callee and callee[0].islower():
        # A factory function: the text does not name the type it returns.
        return None
    return False


def definitions(root: Path) -> list[tuple[str, str, list[str]]]:
    """Every Koin definition that takes runtime parameters.

    Returns `(module, view model, parameter types)`.
    """
    found: list[tuple[str, str, list[str]]] = []
    for path in sorted((root / SOURCES_RELATIVE).rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        for match in VIEW_MODEL_WITH_PARAMS.finditer(source):
            params = [simple_name(param.group(2)) for param in PARAM.finditer(match.group(1))]
            found.append((path.relative_to(root).as_posix(), match.group(2), params))
    return found


def fixture_rows(root: Path) -> dict[str, list[str]]:
    """The fixture table — ViewModel → the values the graph test hands it."""
    rows: dict[str, list[str]] = {}
    fixture = root / FIXTURE_TABLE_RELATIVE
    if not fixture.exists():
        return rows
    for line in fixture.read_text(encoding="utf-8").splitlines():
        match = FIXTURE_ROW.match(line)
        if match:
            rows[match.group(1)] = split_top_level(match.group(2))
    return rows


def mismatches(root: Path) -> list[str]:
    """The differences between the definitions and the fixture table."""
    findings: list[str] = []
    rows = fixture_rows(root)
    fixture_relative = FIXTURE_TABLE_RELATIVE.as_posix()
    seen: set[str] = set()

    for module, view_model, params in definitions(root):
        seen.add(view_model)
        if view_model not in rows:
            findings.append(
                f"{module}: `{view_model}` takes {len(params)} runtime parameter(s) "
                f"({', '.join(params)}) but the fixture table in "
                f"{fixture_relative} has no row for it",
            )
            continue
        values = rows[view_model]
        if len(values) != len(params):
            findings.append(
                f"{module}: `{view_model}` takes {len(params)} parameter(s) "
                f"({', '.join(params)}) but the fixture table supplies {len(values)} "
                f"value(s) ({', '.join(values)})",
            )
            continue
        for value, expected in zip(values, params):
            verdict = value_can_satisfy(value, expected)
            if verdict is False:
                findings.append(
                    f"{module}: `{view_model}` takes a `{expected}` but the fixture "
                    f"table supplies `{value}`, which is not one",
                )

    for view_model in sorted(set(rows) - seen):
        findings.append(
            f"{fixture_relative}: the fixture table has a row "
            f"for `{view_model}`, which no Koin definition gives runtime parameters",
        )

    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Findings only, for CI.")
@click.option("--check", is_flag=True, help="Fail when anything is found.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
@click.option(
    "--root",
    type=click.Path(file_okay=False, path_type=Path),
    default=DEFAULT_ROOT,
    help="The tree to check. Defaults to the repository the script lives in.",
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, check: bool, as_json: bool, root: Path) -> None:
    """Report the definitions and the fixture table that disagree."""
    findings = mismatches(root)
    if as_json:
        click.echo(json.dumps({"findings": findings, "count": len(findings)}, indent=2))
    else:
        if not quiet:
            click.echo("check-di-fixture-sync: Koin definitions and the DI fixture table")
        for finding in findings:
            click.echo(f"  [fixture-drift] {finding}")
        if not quiet:
            click.echo()
            click.echo(f"{len(findings)} difference(s) between the definitions and the fixture table.")
            click.echo(
                "The fixture table lives in "
                f"{FIXTURE_TABLE_RELATIVE.as_posix()} and says what to hand "
                "each definition when the graph is built. A definition and a fixture that "
                "disagree crash the graph on the first screen that uses it.",
            )
    if findings and check:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
