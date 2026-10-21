#!/usr/bin/env python3
"""check-skill-drift.py — the declared toolbox must be the implemented toolbox (#526).

Why
---
The class of defect this closes is not "a wrong sentence in a doc". It is: **a hand-written
description of code that nobody diffs against it.** The description is edited rarely, the code
is edited daily, so the description is always the stale one, and it is always plausible — it
reads like documentation, not like a bug.

Found in a single session:

| Claim in a skill                    | Reality                                   |
|-------------------------------------|-------------------------------------------|
| `write_adr` "create **or update**"  | it only creates; the update half was gone |
| ADR tools are a `desktopApp` subcommand | a separate `:adr-cli` module since #522 |
| "all four tools"                    | five, since `update_adr` landed in #511   |
| no mention of `adr validate`        | it existed, and it *was* the gate          |

Each one is fixed by hand and true again for a while. That is the wrong fix: the next
subcommand lands and the document is wrong again, and nobody notices for months.

The same class of defect was found in the toolbox itself, one level down. The catalogue
carried a second copy of every tool description, and the two copies drifted apart in 32 of 39
tools — including five where the catalogue described a *different capability* than the tool
implements, and four where it promised a permanent delete while the tool creates a proposal
that needs confirmation. The catalogue no longer holds descriptions at all: the tool class
owns that text, because the server builds its schema from it.

What this gate does
-------------------
Three checks over the same tool surface, one of them the reason this script exists:

1. The "ADR tools" section of the two ADR documents is **generated** from the tool surface
   (`AdrSurface.kt` for the CLI, the tool classes for the MCP tools) and this gate re-renders
   it and fails on any difference. Hand-written prose outside the markers is never touched —
   that is the author's, not the generator's.
2. The **catalogue** and the **tool classes** declare the same set of tools, and the class
   each entry points at is the class that declares that name. A tool in one and not the other
   is a tool the client cannot call, or one nobody checked.
3. Every `adr`-domain tool has a **CLI command** mapped to it, and every mapping points at a
   tool that exists. The CLI and the MCP server are two faces of the same corpus; a tool that
   lost its terminal face keeps working, which is exactly why nobody noticed.

    python3 scripts/check-skill-drift.py           # check
    python3 scripts/render-adr-tools-section.py    # regenerate

The renderer is a separate script rather than a function here on purpose: writing the
documents and checking them must be the *same* code path, or the second one becomes a
second implementation of the first — which is the failure this whole toolchain exists
to prevent (see `2026-10-10-adr-file-local-rules-live-in-the-writer`).

The positive control
--------------------
`sabotage` is the generated block with one entry removed; the gate must report drift
when the document holds it. Proved by `scripts/tests/test_render_adr_tools_section.py`,
which asserts that a hand-edited document fails and a regenerated one passes. The
toolbox-parity checks have their own cases in the same file.
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import re

import click
from sgate.commands.base import run

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The tool surface, exported from the compiled classes by `:shared:exportToolSurface`.
#
# The checks below read the surface out of the Kotlin *sources*, because a gate that
# runs a compiler is a gate nobody runs. This file is the other half: the same surface,
# as the compiler saw it. Comparing the two says that the parser read the code
# faithfully — a regex that misreads a class cannot pass unnoticed, because the export
# says what the class really declares.
#
# The file is generated, not written by hand: a test in `:shared` says it is current.
TOOL_SURFACE_JSON = ROOT / 'config/docs/tool-surface.json'

# The renderer is loaded by path, not by name: its filename carries hyphens to match
# every other script in `scripts/`, which `import` will not accept. The module *is*
# still the single implementation — `importlib` loads the same file `python3
# scripts/render-adr-tools-section.py` executes.
_RENDERER_PATH = pathlib.Path(__file__).resolve().parent / 'render-adr-tools-section.py'
_spec = importlib.util.spec_from_file_location('render_adr_tools_section', _RENDERER_PATH)
assert _spec and _spec.loader
renderer = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(renderer)


def exported_surface() -> dict:
    """The tool surface as the compiled classes declare it.

    Returns an empty mapping when the export is missing or unreadable — the caller
    reports that as a problem rather than pretending the surface is empty.
    """
    if not TOOL_SURFACE_JSON.is_file():
        return {}
    try:
        data = json.loads(TOOL_SURFACE_JSON.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {}
    tools = data.get('tools')
    if not isinstance(tools, list):
        return {}
    return {tool.get('name'): tool for tool in tools if isinstance(tool, dict)}


def flag_problems() -> list[str]:
    """The flag keys the documentation names, checked against the declared space.

    A remote configuration switches a tool off by naming it. The name space is the
    tools' own names, declared in the export. A document that shows a flag naming a
    tool that does not exist teaches an operator to write a flag that matches nothing —
    and a flag that matches nothing is silent: the tool stays exposed, and the operator
    believes it is off.

    So every flag key the documentation shows is checked against the declared space.
    """
    problems: list[str] = []
    exported = exported_surface()
    if not exported:
        return problems

    declared = set(exported_flag_keys())
    if not declared:
        problems.append('the export declares no flag keys — the space is empty')
        return problems

    for path, key in documented_flag_keys():
        if key not in declared:
            where = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
            problems.append(
                f'{where} names the flag {key!r}, and no tool has that name — '
                f'the flag would match nothing, and the tool it was meant to switch off '
                f'stays exposed'
            )
    return problems


def exported_flag_keys() -> list[str]:
    """The flag key space, as the export declares it."""
    if not TOOL_SURFACE_JSON.is_file():
        return []
    try:
        data = json.loads(TOOL_SURFACE_JSON.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return []
    keys = data.get('flagKeys')
    if not isinstance(keys, list):
        return []
    return [key for key in keys if isinstance(key, str)]


def documented_flag_keys() -> list[tuple[pathlib.Path, str]]:
    """Every flag key the documentation shows, with the file it came from.

    The pattern is a `mcpToolFlags` assignment and the object that follows it — a
    documented configuration, not prose. A flag key mentioned in passing is not a
    configuration, and a gate that reported those would be reported as noise.
    """
    found: list[tuple[pathlib.Path, str]] = []
    for path in sorted(DOCUMENTED_FLAG_SOURCES):
        if not path.is_file():
            continue
        text = path.read_text(encoding='utf-8')
        for block in re.finditer(r'mcpToolFlags\s*=\s*\{([^}]*)\}', text):
            for key in re.findall(r'"([^"]+)"\s*:', block.group(1)):
                found.append((path, key))
    return found


# The documents that show a configuration. The specification of the change that
# introduced the switch, and the architecture document that describes it.
DOCUMENTED_FLAG_SOURCES = (
    ROOT / 'openspec/changes/agent-toolbox-surface/specs/agent-toolbox-surface/spec.md',
    ROOT / 'docs/architecture/mcp-server.md',
)


def surface_problems() -> list[str]:
    """Where what the gate read differs from what the compiler saw.

    The three facts that have to line up: the code declares the surface, the export is
    that surface written down, and the gate's parser is that surface read back out of
    the sources. This is the check on the third link.
    """
    problems: list[str] = []
    exported = exported_surface()
    if not exported:
        problems.append(
            'the tool surface has not been exported — run ./gradlew :shared:exportToolSurface'
        )
        return problems

    entries = renderer.catalog_entries()
    classes = renderer.tool_classes()
    parsed: dict[str, str] = {}
    for name, tool_class, _domain in entries:
        parsed[name] = classes.get(tool_class, (name, ''))[1]

    for name in sorted(set(exported) - set(parsed)):
        problems.append(
            f'the export declares the tool {name!r}, and the sources do not — the export is '
            f'out of date, or the sources are'
        )
    for name in sorted(set(parsed) - set(exported)):
        problems.append(
            f'the sources declare the tool {name!r}, and the export does not — the export is '
            f'out of date'
        )
    for name in sorted(set(parsed) & set(exported)):
        if parsed[name] != exported[name].get('description', ''):
            problems.append(
                f'the sources read the description of {name!r} as {parsed[name]!r}, and the '
                f'export says {exported[name].get("description", "")!r} — the gate is reading '
                f'the source wrongly'
            )
    return problems


def toolbox_problems() -> list[str]:
    """Where the declared toolbox and the implemented toolbox disagree.

    Every problem is one an agent would hit: a tool the client cannot call, a tool
    nobody can reach from a terminal, a name that points at the wrong class.
    """
    problems: list[str] = []
    entries = renderer.catalog_entries()
    classes = renderer.tool_classes()

    catalogue_names = [name for name, _, _ in entries]
    for name in sorted({n for n in catalogue_names if catalogue_names.count(n) > 1}):
        problems.append(f'the catalogue declares {name!r} more than once')

    class_names = {name for name, _ in classes.values()}
    for name, tool_class, domain in entries:
        if name not in class_names:
            problems.append(
                f'the catalogue declares {name!r} ({domain}) but no tool class declares that NAME'
            )
        elif tool_class not in classes:
            problems.append(f'the catalogue points {name!r} at {tool_class}, which is not a tool class')
        elif classes[tool_class][0] != name:
            problems.append(
                f'the catalogue says {name!r} is {tool_class}, but that class declares '
                f'{classes[tool_class][0]!r}'
            )

    for tool_class, (name, _) in classes.items():
        if name not in catalogue_names:
            problems.append(f'{tool_class} declares the tool {name!r}, which the catalogue omits')

    adr_tools = {name for name, _, domain in entries if domain == 'adr'}
    commands = {name for name, _, _ in renderer.commands()}
    mapped: dict[str, str] = {}
    for command, tool in renderer.mcp_tool_mapping():
        if command not in commands:
            problems.append(f'the CLI mapping names {command!r}, which is not a declared command')
        if tool not in adr_tools:
            problems.append(f'the CLI mapping points {command!r} at {tool!r}, which is not an adr-domain tool')
        if tool in mapped:
            problems.append(f'both {mapped[tool]!r} and {command!r} map to the tool {tool!r}')
        mapped[tool] = command
    for tool in sorted(adr_tools - set(mapped)):
        problems.append(f'the adr-domain tool {tool!r} has no CLI command mapped to it')

    problems.extend(surface_problems())
    problems.extend(flag_problems())

    return problems


@click.command()
@click.pass_context
def cli(ctx: click.Context) -> None:
    try:
        expected = renderer.render()
        problems = toolbox_problems()
    except renderer.ParseError as exc:
        click.echo(f'check-skill-drift: {exc}', err=True)
        click.echo('check-skill-drift: the renderer could not read the tool surface, so nothing '
              'can be compared. A gate that cannot read its own input is not a green gate.',
              err=True)
        raise ctx.exit(1)

    drifted: list[pathlib.Path] = []
    missing: list[pathlib.Path] = []

    for skill in renderer.SKILLS:
        if not skill.is_file():
            missing.append(skill)
            continue
        current = renderer.current_block(skill)
        if current != expected:
            drifted.append(skill)

    # The flag key space: a second generated block, with the same guarantee as the
    # first. An operator writes a configuration against these names, so a list that
    # has drifted from the catalogue is a list that teaches the wrong keys.
    flag_expected = renderer.render_flag_block()
    flag_drifted: list[pathlib.Path] = []
    flag_missing: list[pathlib.Path] = []
    for path in renderer.FLAG_DOCUMENTS:
        if not path.is_file():
            flag_missing.append(path)
            continue
        if renderer.current_flag_block(path) != flag_expected:
            flag_drifted.append(path)

    for path in missing:
        click.echo(f'ERROR: {path.relative_to(ROOT)} does not exist — the generated section has '
              f'nowhere to live')

    for path in drifted:
        rel = path.relative_to(ROOT)
        current = renderer.current_block(path)
        if current is None:
            click.echo(f'ERROR: {rel} has no "{renderer.BEGIN}" block. The ADR tool surface is '
                  f'generated, not maintained by hand; restore it with:')
        else:
            click.echo(f'ERROR: {rel} — the generated "ADR tools" section does not match the code.')
            click.echo(f'  the skill says: {_first_difference(current, expected)}')
        click.echo(f'  fix with: python3 scripts/render-adr-tools-section.py')

    for path in flag_missing:
        click.echo(f'ERROR: {path.relative_to(ROOT)} does not exist — the flag key space has '
              f'nowhere to live')

    for path in flag_drifted:
        rel = path.relative_to(ROOT)
        current = renderer.current_flag_block(path)
        if current is None:
            click.echo(f'ERROR: {rel} has no "{renderer.FLAG_BEGIN}" block. The flag key space is '
                  f'generated, not maintained by hand; restore it with:')
        else:
            click.echo(f'ERROR: {rel} — the generated flag key space does not match the catalogue.')
            click.echo(f'  the document says: {_first_difference(current, flag_expected)}')
        click.echo(f'  fix with: python3 scripts/render-adr-tools-section.py')

    for problem in problems:
        click.echo(f'ERROR: {problem}')

    if drifted or flag_drifted or missing or flag_missing or problems:
        click.echo('')
        click.echo('A skill that lists tools the code no longer has is worse than no skill: an')
        click.echo('agent reads it and calls something that does not exist, or trusts a promise')
        click.echo('about a tool whose behaviour has changed.')
        if problems:
            click.echo('A catalogue that names tools the code does not implement is the same')
            click.echo('failure one level down: the client asks for a tool and gets an error, or')
            click.echo('the documentation promises a tool nobody wired up.')
        raise ctx.exit(1)

    click.echo(f'check-skill-drift: OK — {len(renderer.SKILLS)} document(s) match the ADR tool surface '
          f'({len(renderer.commands())} command(s), {len(renderer.mcp_adr_tools())} MCP tool(s)), '
          f'{len(renderer.catalog_entries())} catalogue entr(y/ies) match the tool classes, '
          f'{len(renderer.flag_keys())} flag key(s) declared')
    raise ctx.exit(0)


def _first_difference(actual: str, expected: str) -> str:
    """The first line where the skill and the code disagree, for the error message."""
    actual_lines = actual.splitlines()
    expected_lines = expected.splitlines()
    for i, (a, e) in enumerate(zip(actual_lines, expected_lines), 1):
        if a != e:
            return f'line {i}: skill has {a.strip()!r}, code says {e.strip()!r}'
    if len(actual_lines) != len(expected_lines):
        return (f'the skill has {len(actual_lines)} line(s) in the block, the code produces '
                f'{len(expected_lines)}')
    return 'identical text but byte-different'


if __name__ == '__main__':
    raise SystemExit(run(cli))