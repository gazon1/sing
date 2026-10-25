#!/usr/bin/env python3
"""check-screen-state-collections.py — a screen state does not hold a raw collection.

The defect this catches
-----------------------
A screen state is what a screen reads. When it holds a raw `List` / `Set` /
`Map`, two things go wrong at once:

- the promise the state makes about its own contents is not enforceable — the
  collection is an interface, so the state cannot promise that the instance it
  holds will not change;
- the collection is rebuilt, or rebuilt *into* the state, on the wrong side of
  the boundary. The rule of this repository is that **the state that owns the
  value holds the persistent kind** (`ImmutableList` / `ImmutableSet` /
  `ImmutableMap`), and the screen receives that instance unchanged.

The sibling rule — `NoUnstableCollectionParam` — asks about the *parameter* of
a composable: a raw collection parameter is a finding. It cannot see inside
the state. A screen that receives `ArchiveUiState` passes the rule while the
state itself carries `List<Task>`, and nothing else in the build says so.

So this gate reads the sources and asks the other half of the question:
**what does the state hold?**

What counts as a screen state
-----------------------------
A declaration is a screen state when it is a state type of a feature:

- its name ends with `UiState` or `State` — the sealed hierarchy a screen
  reads (`AgendaUiState`, `TagsUiState`, `TaskChildrenState`), or
- it is declared **inside** such a type — the members of the hierarchy
  (`ArchiveUiState.Content`, `TagGroupsUiState.Content`).

The states of the core (`SyncState`, `EphemeralState`) are infrastructure
rather than screens: they are the state of a repository and of a settings
bundle, not of a screen that renders them, and they are deliberately out of
scope. The scope is stated here rather than implied, because a rule whose
scope can be argued is a rule that gets argued with.

The register of debt
--------------------
`config/compose/screen_state_collection_debt.conf` names the screen states
that still hold a raw collection, one entry per property:

    com.singularity.todo.feature.archive.ArchiveUiState.Content.tasks : List<Task>

The file also records the **size** of the register — `properties = N`. The
number is the ratchet: the debt may not grow without somebody deciding to
raise it, and a shrink has to be recorded. Every change to the register comes
with a change to the number, in the same commit, so the diff shows the
movement of the debt in the commit that moved it.

The gate checks the register from both sides, the way the companion gate
checks the register of raw collection parameters:

- a raw collection a screen state holds and the register does **not** name is a
  finding — that is a new debt;
- a registered entry that no longer matches a property — the property is gone,
  or its type changed — is a finding too: that is what happens when the debt is
  paid, and the record has to move with it.

Usage:
    python3 scripts/check-screen-state-collections.py [--quiet]

Exit code is 1 when anything is wrong, so it can gate a check.
"""
from __future__ import annotations

import pathlib
import re
import sys
from dataclasses import dataclass, field

import click

# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import CLEAN, VALIDATION_FAILURE, run  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent

REGISTER = ROOT / "config" / "compose" / "screen_state_collection_debt.conf"

#: The roots the states are declared in. A screen state lives in the shared
#: module; the app modules are scanned too, so a state that moves out of
#: `shared` does not silently escape the gate.
SOURCE_ROOTS = (
    "shared/src/commonMain/kotlin",
    "shared/src/androidMain/kotlin",
    "shared/src/jvmMain/kotlin",
    "androidApp/src/main/kotlin",
    "desktopApp/src/main/kotlin",
)

#: The names of the raw collection types, as the source spells them.
#:
#: The same list the detekt rules use (`RawCollectionTypes` in
#: `:detekt-rules`): two rules that ask "is this a raw collection?" must not
#: end up disagreeing about it.
RAW_COLLECTIONS = frozenset(
    {
        "List",
        "MutableList",
        "ArrayList",
        "Set",
        "MutableSet",
        "HashSet",
        "Map",
        "MutableMap",
        "HashMap",
        "Collection",
        "Array",
    }
)

#: The suffixes of a state type. A state is named after what it is: the state
#: of a screen (`AgendaUiState`) or the state of a slot on a screen
#: (`TaskChildrenState`).
STATE_SUFFIXES = ("UiState", "State")

#: The package prefix of a feature. A state of the core — `SyncState`,
#: `EphemeralState` — is infrastructure, not a screen.
FEATURE_PACKAGE = "com.singularity.todo.feature."

_DECLARATION = re.compile(
    r"^(?P<indent>\s*)"
    r"(?:(?:public|internal|private|protected|expect|actual|sealed|data|abstract|open|value|enum|"
    r"annotation|inner|inline|external)\s+)*"
    r"(?P<kind>class|interface|object)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)
_PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
_PROPERTY = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|override|open|const)\s+)*"
    r"(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?P<type>.+)$"
)
_CONSTRUCTOR_VAL = re.compile(
    r"\b(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?P<type>.+)$"
)


@dataclass
class Declaration:
    """One declaration, with the properties it declares."""

    kind: str
    name: str
    qualified_name: str
    package: str
    properties: list[tuple[str, str]] = field(default_factory=list)


def _strip_comments(text: str) -> str:
    """The text without comments; the code it holds unchanged."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", " ", text)


def _header(lines: list[str], start: int) -> tuple[str, int]:
    """The declaration header at `start`, with its parameter list balanced."""
    text = lines[start]
    index = start
    while text.count("(") > text.count(")") and index + 1 < len(lines):
        index += 1
        text += " " + lines[index].strip()
    return text, index


def _split_top_level(text: str) -> list[str]:
    """The comma-separated parts of a parameter list.

    A comma inside a generic argument list — `Map<String, List<X>>` — does not
    separate two parameters, so the split counts the brackets.
    """
    parts: list[str] = []
    depth = 0
    current = ""
    for char in text:
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
            continue
        current += char
    if current.strip():
        parts.append(current)
    return parts


def _declared_type(text: str) -> str:
    """The declared type of a property or a parameter.

    The default value is not part of the type: `val x: List<T> = emptyList()`
    declares a `List<T>`, and the initializer says nothing about the type the
    state holds.
    """
    depth = 0
    for index, char in enumerate(text):
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        elif char == "=" and depth == 0:
            return text[:index].strip()
    return text.strip()


def _body(lines: list[str], start: int, end: int, indent: int) -> list[str]:
    """The lines of the declaration's body: the ones indented past it."""
    body: list[str] = []
    for line in lines[start + 1:end]:
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        body.append(line)
    return body


def _direct_body(
    lines: list[str],
    header_end: int,
    indent: int,
    nested_at: int | None,
) -> list[str]:
    """The lines the declaration itself declares.

    A nested declaration's own lines are not the outer declaration's: the
    parameters of `Content` inside `AttachmentAnnotationUiState` belong to
    `Content`, and a scan that read them as the interface's properties would
    report the same collection twice under two names.
    """
    end = len(lines)
    if nested_at is not None:
        end = min(end, nested_at)
    for index in range(header_end + 1, end):
        line = lines[index]
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= indent:
            end = index
            break
    return lines[header_end + 1:end]


def _raw_collection(type_text: str) -> bool:
    """True when the declared type is a raw collection."""
    name = type_text.strip().rstrip("?").strip()
    name = name.split("<", 1)[0].strip()
    name = name.split(".")[-1]
    return name in RAW_COLLECTIONS


def declarations_of(path: pathlib.Path) -> list[Declaration]:
    """Every declaration the file declares, with its properties."""
    text = _strip_comments(path.read_text(encoding="utf-8", errors="replace"))
    lines = text.splitlines()
    package_match = _PACKAGE.search(text)
    package = package_match.group(1) if package_match else ""

    # First the sites: where each declaration starts, how deep it sits, and
    # where its header ends. The bodies come second, because a body ends
    # where the next declaration starts — and that is only known once every
    # declaration has been seen.
    sites: list[tuple[int, int, str, str, int]] = []
    index = 0
    while index < len(lines):
        match = _DECLARATION.match(lines[index])
        if match is None:
            index += 1
            continue
        indent = len(match.group("indent"))
        header, last = _header(lines, index)
        sites.append((index, indent, match.group("kind"), match.group("name"), last))
        index = last + 1

    found: list[Declaration] = []
    stack: list[tuple[int, str]] = []
    for position, (start, indent, kind, name, header_end) in enumerate(sites):
        while stack and stack[-1][0] >= indent:
            stack.pop()
        header = " ".join(line.strip() for line in lines[start:header_end + 1])
        nested_at = next(
            (
                other_start
                for other_start, other_indent, _, _, _ in sites[position + 1:]
                if other_indent > indent
            ),
            None,
        )
        body = _direct_body(lines, header_end, indent, nested_at)

        properties: list[tuple[str, str]] = []
        parameters = header[header.find("(") + 1:] if "(" in header else ""
        parameters = parameters.rsplit(")", 1)[0] if ")" in parameters else parameters
        for part in _split_top_level(parameters):
            param = _CONSTRUCTOR_VAL.search(part)
            if param is not None:
                properties.append(
                    (param.group("name"), _declared_type(param.group("type")))
                )
        for line in body:
            prop = _PROPERTY.match(line)
            if prop is not None:
                properties.append(
                    (prop.group("name"), _declared_type(prop.group("type")))
                )

        chain = [outer for _, outer in stack] + [name]
        qualified = ".".join(part for part in (package, *chain) if part)
        found.append(
            Declaration(
                kind=kind,
                name=name,
                qualified_name=qualified,
                package=package,
                properties=properties,
            )
        )
        stack.append((indent, name))
    return found


def _is_state(declaration: Declaration, states: set[str]) -> bool:
    """True when the declaration is a screen state of a feature."""
    if not declaration.package.startswith(FEATURE_PACKAGE):
        return False
    simple = declaration.qualified_name.rsplit(".", 1)[-1]
    if simple.endswith(STATE_SUFFIXES):
        return True
    # A member of a state hierarchy is a state: `ArchiveUiState.Content`.
    parent = declaration.qualified_name.rsplit(".", 1)[0]
    return parent in states


def screen_states(sources: list[pathlib.Path]) -> tuple[list[tuple[Declaration, str, str]], int]:
    """Every raw collection a screen state holds, and how many states there are.

    The second number is the count of the screen states the scan found. It is
    returned because a scan that finds nothing must not look like a scan that
    found nothing wrong.
    """
    declarations: list[Declaration] = []
    for path in sources:
        declarations.extend(declarations_of(path))
    state_names = {
        d.qualified_name
        for d in declarations
        if d.qualified_name.rsplit(".", 1)[-1].endswith(STATE_SUFFIXES)
    }
    holdings: list[tuple[Declaration, str, str]] = []
    states = 0
    for declaration in declarations:
        if not _is_state(declaration, state_names):
            continue
        states += 1
        for name, type_text in declaration.properties:
            if _raw_collection(type_text):
                holdings.append((declaration, name, type_text))
    # The count is of the state-shaped declarations the scan found, whatever
    # package they live in: a scan that finds no state at all is a scan that
    # read nothing, and that must not look like a clean tree.
    return holdings, len(state_names)


def read_register(path: pathlib.Path) -> tuple[dict[str, str], int | None]:
    """The register: the entries by key, and the recorded size."""
    entries: dict[str, str] = {}
    recorded: int | None = None
    if not path.is_file():
        return entries, recorded
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("properties"):
            _, _, value = line.partition("=")
            recorded = int(value.strip())
            continue
        key, _, declared = line.partition(":")
        entries[key.strip()] = declared.strip()
    return entries, recorded


@click.command()
@click.option("--quiet", is_flag=True, help="findings only, for CI")
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    sources = [
        path
        for root in SOURCE_ROOTS
        for path in sorted((ROOT / root).rglob("*.kt"))
        if path.is_file()
    ]
    if not sources:
        click.echo(
            "check-screen-state-collections: no Kotlin sources found — refusing to pass silently",
            err=True,
        )
        raise ctx.exit(VALIDATION_FAILURE)

    holdings, states = screen_states(sources)
    if states == 0:
        click.echo(
            "check-screen-state-collections: found zero screen states — refusing to pass "
            "silently; a gate that reads nothing measures nothing",
            err=True,
        )
        raise ctx.exit(VALIDATION_FAILURE)

    entries, recorded = read_register(REGISTER)
    findings: list[str] = []
    seen: set[str] = set()

    for declaration, name, type_text in holdings:
        key = f"{declaration.qualified_name}.{name}"
        seen.add(key)
        registered = entries.get(key)
        if registered is None:
            findings.append(
                f"{key} : {type_text} — a screen state holds a raw collection; "
                f"the state that owns the value must hold the persistent kind"
            )
        elif registered != type_text:
            findings.append(
                f"{key} : {type_text} — the register says {registered!r}; "
                f"the declared type and the record disagree"
            )

    for key, declared in sorted(entries.items()):
        if key in seen:
            continue
        findings.append(
            f"{key} : {declared} — the register names a debt the code no longer has; "
            f"the debt was paid and the record has to move with it"
        )

    if recorded is not None and recorded != len(entries):
        findings.append(
            f"the register records {recorded} propertie(s) and holds {len(entries)}; "
            f"the recorded size is the ratchet and it has to match the register"
        )
    if recorded is None:
        findings.append(
            f"{REGISTER.relative_to(ROOT)} records no size; the debt may not grow "
            f"without somebody deciding to raise the bar"
        )

    if not quiet:
        click.echo(
            f"check-screen-state-collections: {len(holdings)} raw collection(s) held by "
            f"screen states, {len(entries)} registered, {len(entries)} recorded"
        )
    for finding in findings:
        click.echo(f"  [screen-state-collection] {finding}")

    if findings:
        if not quiet:
            click.echo()
            click.echo(f"{len(findings)} finding(s).")
            click.echo(
                "Hold the persistent kind (ImmutableList / ImmutableSet / ImmutableMap) in the "
                "state that owns the value, drop the entry from the register and lower the "
                "recorded size."
            )
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
