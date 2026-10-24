#!/usr/bin/env python3
"""check-compose-stability-config.py — the stability contract must name real types, and be wired.

The defect this catches
-----------------------
`config/compose/stability_config.conf` tells the Compose compiler which types are
stable. A name in that file that does not resolve to a type is not an error: the
compiler matches what it can and ignores the rest. The contract then says less
than it appears to say, and the only symptom is that list rows keep recomposing —
a performance fact with no error message attached to it.

The second failure is worse, because it is silent in a different way: the file
exists and names real types, but no module reads it. The compiler is then never
told anything, and every "stable" claim in the repository's documentation is a
claim about a file nothing loads.

So this gate checks seven things:

  1. every entry is a syntactically valid fully-qualified name;
  2. every entry resolves to a type that exists — a declared type in the shared
     sources for a project namespace, an imported type for a library one;
  3. every entry names something the compiler can use — a **class**, or an
     **interface**, which the compiler honours and applies to the
     implementations as well. An `object` is judged stable without the
     contract, and a `typealias` resolves to no class at all: both are
     reported, because both say less than they appear to;
  4. the file is actually wired into the modules that compile composables;
  5. every type the contract names **from this project's namespace** keeps the
     promise it makes for it — no `var`, no collection that can change after
     construction. The promise covers the types below it too, because an
     instance of a contracted type may be one of them. A library type is not
     checked: its declarations are not in these sources;
  6. every site the register of debt names is still declared in the sources;
  7. every parameter the register of debt names is still declared by some
     declaration of that site, and the register has not grown past the size
     recorded in `config/compose/stability_debt_baseline.conf`.

The last three are a different failure from the first four. The
contract names *types*, and the compiler ignores a name it cannot resolve — a
contract entry that rots is a contract that says less than it appears to. The
register of debt (`ComposeCollectionExemptions.kt`) names *composables* and the
raw collection parameters they still declare, and a register entry whose
composable was deleted is worse than a dead contract entry: the detekt rule
exempts by name, so a stale entry exempts nothing and the debt it was
accounting for is invisible in both directions.

The rule catches the entries that are stale in the *type* — a parameter that
was retyped. It cannot catch the other two: a parameter that was renamed or
removed has no declared type to compare, and a composable that is gone is not
visited at all. That is what this gate is for; it reads the sources, not a
build artifact.

What it does NOT check
----------------------
It does not run the compiler, and it does not read the compiler's stability
report. The report is a build artifact: it exists only when a compilation ran,
and it is not a thing a gate may depend on. The contract is checked where it
lives — in a source file.

It does not check the declarations of a library type: they are not in these
sources. Such an entry is checked for existence only — that the codebase
imports the type it names — and the promise for it is taken on trust.

An entry may not carry a wildcard in the middle of the name. The compiler's
matcher accepts `com.example.*.Foo`, and this gate does not: the entry is
reported as a name that is not fully qualified. The contract uses no such
entry, and a wildcard at the end of a name is what it is for.

Usage:
    python3 scripts/check-compose-stability-config.py

Exit code is 1 when anything is wrong, so it can gate a check.
"""
from __future__ import annotations

import pathlib
import re
import sys
from dataclasses import dataclass, field

import click

# `scripts/src` on sys.path so `sgate.commands.base` resolves. When this file is
# loaded by a test harness (importlib), sys.path[0] is `scripts/tests` and the
# import would fail without this.
_scripts_src = pathlib.Path(__file__).resolve().parent / "src"
if str(_scripts_src) not in sys.path:
    sys.path.insert(0, str(_scripts_src))

from sgate.commands.base import CLEAN, VALIDATION_FAILURE, run  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent

CONF = ROOT / "config" / "compose" / "stability_config.conf"

#: Where the contract must be wired. These are the modules that compile
#: composables; a module without them has no composables to stabilise.
WIRED_MODULES = (
    "shared/build.gradle.kts",
    "desktopApp/build.gradle.kts",
)

#: The namespace this repository owns. Entries under it must be declared in the
#: shared sources; entries outside it are library types and must be imported.
PROJECT_NAMESPACE = "com.singularity.todo."

#: The package a source declares. The line may be preceded by a file-level
#: annotation or a comment, so the pattern is anchored per line and not to the
#: start of the file.
_PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
_TYPEALIAS = re.compile(r"^[ \t]*typealias\s+([A-Za-z_]\w*)", re.M)
_IMPORT = re.compile(r"^import\s+([\w.]+(?:\.\*)?)", re.M)

#: A declaration with the kind of thing it declares, and where it starts.
#:
#: The kinds differ in what the compiler does with them, and the gate has to
#: tell them apart rather than lump them together:
#:
#:  * a **class** is what the compiler's stability file lists, and an entry
#:    for it is exactly what the contract is for;
#:  * an **interface** is honoured too — the compiler matches the entry
#:    against the supertypes of a class as well, so an entry for an interface
#:    reaches the implementations that carry it. Measured on 2026-10-24: an
#:    entry for `UiNode` made `UiNode.Checkbox` and `UiNode.TextField` stable,
#:    and the marker appeared in their class files;
#:  * an **object** the compiler judges stable on its own — an object is a
#:    singleton, so the compiler infers stability for it whatever it holds.
#:    The stability report shows them as `stable class` with no entry in the
#:    contract at all;
#:  * a **typealias** is not a class: the compiler matches class names, so an
#:    entry that names an alias resolves to nothing.
#:
#: The declaration may be nested, so the pattern allows the indentation a
#: nested declaration carries.
_DECLARATION_WITH_KIND = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+|internal\s+|private\s+|protected\s+|abstract\s+|sealed\s+|open\s+"
    r"|data\s+|value\s+|enum\s+|annotation\s+|expect\s+|actual\s+|inline\s+|fun\s+"
    r"|inner\s+)*"
    r"(class|interface|object)\s+([A-Za-z_]\w*)",
    re.M,
)

#: What the sources declare about one name.
#:
#: The kind decides what the compiler does with an entry for the name, so the
#: kind is part of what the gate knows. The properties are what the promise
#: is checked against, and the supertypes are what says who implements the
#: name — an entry for an interface reaches the implementations, so the
#: promise covers them too.
@dataclass
class Declaration:
    kind: str
    properties: list[tuple[str, str, str | None, str | None]] = field(default_factory=list)
    supertypes: list[str] = field(default_factory=list)
    package: str = ""
    imports: frozenset[str] = frozenset()


@dataclass
class _Site:
    """One declaration as it sits in a source file."""

    kind: str
    name: str
    position: int
    body_range: tuple[int, int] | None
    constructor: str
    body: str
    supertypes: list[str]


#: A property declaration: `val` or `var`, and its name. The type is read
#: separately, because a type may be generic, inferred, or followed by an
#: accessor — a pattern that one regex cannot tell apart.
#:
#: The modifiers are the ones Kotlin allows in front of a property, and the
#: annotations may carry a use-site target (`@get:JvmName("x")`) — a property
#: that the pattern does not match is a property the check does not see, and a
#: check that does not see a property is not a check.
_PROPERTY = re.compile(
    r"^\s*"
    r"(?:@[\w.]+(?::[\w.]+)?(?:\([^)]*\))?[ \t]*\n?[ \t]*)*"
    r"(?:(?:private|public|internal|protected|override|lateinit|open|final|abstract|"
    r"const|expect|actual)[ \t]+)*"
    r"(val|var)[ \t]+([A-Za-z_]\w*)",
)

#: The factories that build a collection that can change. A property whose type
#: is inferred is invisible to the declared type — but the initializer is not,
#: and a `mutableListOf()` says the same thing as a `MutableList`.
_MUTABLE_FACTORIES = (
    "mutableListOf",
    "mutableSetOf",
    "mutableMapOf",
    "arrayListOf",
    "hashSetOf",
    "hashMapOf",
)

#: The collection types that can change after they are built. A contract type
#: that holds one of these breaks the promise the contract makes: the compiler
#: compares the instance by `equals`, and `equals` of a collection that changed
#: is a lie about "the same content".
_MUTABLE_COLLECTIONS = (
    "MutableList",
    "MutableSet",
    "MutableMap",
    "ArrayList",
    "HashSet",
    "HashMap",
)

#: A fully-qualified name: dot-separated Java identifiers. The last segment may
#: be a wildcard — `*` for one package level, `**` for the level and everything
#: under it.
_ENTRY = re.compile(r"^[A-Za-z_]\w*(\.[A-Za-z_]\w*)*(\.\*{1,2})?$")

#: The register of debt: the composables that still take a raw collection.
DEBT_REGISTER = (
    ROOT / "detekt-rules" / "src" / "main" / "kotlin" / "com" / "singularity" / "todo"
    / "detekt" / "ComposeCollectionExemptions.kt"
)

#: The recorded size of the register of debt — the ratchet. It may go down,
#: and it may not go up without somebody deciding to raise it.
DEBT_BASELINE = ROOT / "config" / "compose" / "stability_debt_baseline.conf"

#: The modules whose sources may declare a registered composable.
#:
#: Every module detekt scans, because the rule and the gate must see the same
#: tree: a site the gate cannot find would be reported as deleted, while the
#: rule still sees it and demands an exemption the gate cannot register. The
#: modules without composables today are listed anyway — the list is a promise
#: about where a composable may appear, not a measurement of where one does.
REGISTER_MODULES = (
    "shared",
    "desktopApp",
    "androidApp",
    "core-base",
    "adr-cli",
    "adr-core",
    "mcp-server",
    "pro",
)

#: The body of the register's map — the places a site may be named. The map
#: runs to the end of the file: the entries are read one by one, and a body
#: that stopped at the first closing parenthesis would depend on how the map
#: happens to be indented.
_REGISTER_BLOCK = re.compile(r"val KNOWN[^=]*=\s*mapOf\((?P<body>.*)", re.DOTALL)

#: How a source declares a name. A composable is a function; the class it is
#: nested in is a class. A function may carry a type parameter list and a
#: receiver — `fun <T> Nav3State.toDecoratedEntries(`.
_DECLARATION_SHAPES = (
    r"\b(?:class|object|interface)\s+{name}\b",
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?{name}\s*\(",
)


def read_entries(conf: pathlib.Path) -> list[tuple[int, str]]:
    """The (line number, entry) pairs of the contract, comments removed."""
    entries: list[tuple[int, str]] = []
    for number, raw in enumerate(conf.read_text().splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        entries.append((number, line))
    return entries


def kotlin_sources(source_root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for p in source_root.rglob("*.kt") if p.is_file())


def declared_declarations(sources: list[pathlib.Path]) -> dict[str, Declaration]:
    """Every declaration the sources declare, by its fully-qualified name.

    The name is the one the Compose compiler prints: the enclosing
    declarations come first and the declaration itself last. A nested class
    therefore reads as `Outer.Nested`, and two declarations of the same name in
    different packages stay apart — which is what makes the checks below able
    to say anything about a *particular* type.

    A typealias is a declaration too, and it is kept apart from the classes:
    the compiler matches class names, so an alias is a name it cannot use.
    """
    declarations: dict[str, Declaration] = {}
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        package = _package_of(text)
        sites: list[_Site] = []
        for match in _DECLARATION_WITH_KIND.finditer(text):
            constructor, body, body_range, supertypes = _class_parts(text, match.end())
            sites.append(
                _Site(
                    kind=match.group(1),
                    name=match.group(2),
                    position=match.start(),
                    body_range=body_range,
                    constructor=constructor,
                    body=body,
                    supertypes=supertypes,
                )
            )
        names = _qualified_names(package, sites)
        imports = frozenset(
            match.group(1) for match in _IMPORT.finditer(text)
        )
        for index, site in enumerate(sites):
            declarations[names[index]] = Declaration(
                kind=site.kind,
                properties=_properties_of(site.constructor, site.body),
                supertypes=site.supertypes,
                package=package,
                imports=imports,
            )
        for match in _TYPEALIAS.finditer(text):
            fqn = f"{package}.{match.group(1)}" if package else match.group(1)
            declarations.setdefault(fqn, Declaration(kind="typealias"))
    return declarations


def _qualified_names(package: str, sites: list[_Site]) -> list[str]:
    """The fully-qualified name of every declaration, in the order they sit."""
    names: list[str] = [""] * len(sites)
    for index, site in enumerate(sites):
        # A declaration inside another declaration's body is nested: its name
        # carries the enclosing one, the way the compiler prints it.
        parent = _enclosing_declaration(index, sites)
        base = names[parent] if parent is not None else package
        names[index] = f"{base}.{site.name}" if base else site.name
    return names


def imported_types(sources: list[pathlib.Path]) -> set[str]:
    """Every fully-qualified name imported by the given sources."""
    imports: set[str] = set()
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in _IMPORT.finditer(text):
            imports.add(match.group(1))
    return imports


#: What may follow a class header that a line break ended. The header
#: continues when the next line is part of it; when it is not, the class is
#: over and the next declaration is somebody else's.
#:
#: The modifiers are in the list because a header may break before them:
#: `class Task` on one line and `private constructor(...)` on the next is the
#: same header.
_HEADER_CONTINUES = (
    ":",
    "{",
    "<",
    ",",
    "where",
    ")",
    "private",
    "public",
    "internal",
    "protected",
    "constructor",
    "expect",
    "actual",
)


def _continues_header(text: str, position: int) -> bool:
    """True when the text at `position` continues a class header."""
    return text.startswith(_HEADER_CONTINUES, position)


def _indentation_of(text: str, position: int) -> int:
    """How far the line holding `position` is indented."""
    start = text.rfind("\n", 0, position) + 1
    return len(text[start:position]) - len(text[start:position].lstrip())


def _next_meaningful_line(text: str, position: int) -> tuple[int, int] | None:
    """The position and the indentation of the next line that carries code.

    Blank lines are skipped: they say nothing about where the next
    declaration is.
    """
    index = position
    while index < len(text):
        end = text.find("\n", index)
        if end == -1:
            end = len(text)
        line = text[index:end]
        stripped = line.strip()
        if stripped:
            return index, len(line) - len(line.lstrip())
        index = end + 1
    return None


def _body_start(text: str, position: int, indent: int) -> int | None:
    """The index of the brace that opens a class body, or None when there is none.

    The brace is looked for at bracket depth zero, so a `{` inside a default
    value or a lambda in the header does not pass for the body.

    A class without a body has no body. The search must not run into the next
    class and take *its* body, or the properties of one class are attributed
    to another — and a class that promises to be immutable is failed for a
    `var` it does not have. The end of the class is where the next
    declaration starts: a line indented no further than the class itself.
    """
    stack: list[str] = []
    index = position
    while index < len(text):
        char = text[index]
        if char == "\n" and not stack:
            following = _next_meaningful_line(text, index + 1)
            if following is None:
                return None
            line_start, line_indent = following
            if line_indent <= indent and not _continues_header(text, line_start):
                return None
        if _opens(char, text, index):
            if char == "{" and not stack:
                return index
            stack.append(_closer_of(char))
        elif _closes(char) and stack and stack[-1] == char:
            stack.pop()
        index += 1
    return None


def _skip_space(text: str, position: int) -> tuple[int, bool]:
    """Skip the whitespace, and say whether a line break was crossed."""
    crossed = False
    while position < len(text) and text[position].isspace():
        if text[position] == "\n":
            crossed = True
        position += 1
    return position, crossed


def _class_parts(
    text: str,
    after_name: int,
) -> tuple[str, str, tuple[int, int] | None, list[str]]:
    """The constructor parameters, the body, the body's range and the supertypes.

    The header is walked rather than pattern-matched: a class may declare a
    secondary constructor — with a visibility modifier, as `private
    constructor(...)` — a supertype, or both, and the body is the first brace
    at depth zero after all of that.

    A class without a body has no body. The search for one must not run into
    the next class and take *its* body, or the properties of one class are
    attributed to another — and a class that promises to be immutable is
    failed for a `var` it does not have. The header ends where the next
    declaration starts: at a line indented no further than the class itself.

    The supertypes come out of the same walk: the list after the colon. They
    are what says who implements the name, and an entry for an interface
    reaches the implementations that carry it.
    """
    position = after_name
    constructor = ""
    supertypes: list[str] = []
    # The space between the name and what follows decides whether the header
    # is over: a header that ends on the line break is over, and what follows
    # is another declaration. The whitespace has to be skipped *with* that
    # knowledge — skipping it blindly loses the line break, and a declaration
    # without a parameter list then takes the body of the next one.
    position, crossed = _skip_space(text, position)
    if crossed and not _continues_header(text, position):
        return constructor, "", None, supertypes
    visibility = re.compile(r"(?:private|public|internal|protected|expect|actual)[ \t]+")
    matched = visibility.match(text, position)
    if matched is not None:
        position = matched.end()
    if text[position:position + 11] == "constructor":
        position += 11
        while position < len(text) and text[position].isspace():
            position += 1
    if position < len(text) and text[position] == "(":
        close = _matching_paren(text, position)
        if close is None:
            return "", "", None, []
        constructor = text[position + 1:close]
        position = close + 1
    indent = _indentation_of(text, after_name)
    position, crossed = _skip_space(text, position)
    if crossed and not _continues_header(text, position):
        # The header ended on the line break: what follows is another
        # declaration, not the body of this one.
        return constructor, "", None, supertypes
    if position < len(text) and text[position] == ":":
        # The supertype list, up to the body.
        start = _body_start(text, position, indent)
        if start is None:
            # No body: the supertype list ends with the line it is on.
            end = text.find("\n", position)
            end = len(text) if end == -1 else end
            return constructor, "", None, _supertype_names(text[position + 1:end])
        supertypes = _supertype_names(text[position + 1:start])
        position = start
    body_start = _body_start(text, position, indent)
    if body_start is None:
        return constructor, "", None, supertypes
    body_end = _matching_paren(text, body_start)
    if body_end is None:
        return constructor, "", None, supertypes
    return constructor, text[body_start + 1:body_end], (body_start, body_end), supertypes


def _supertype_names(text: str) -> list[str]:
    """The names of the supertypes a declaration lists.

    The names are the ones as they are written, dots and all: a supertype
    written `com.other.pkg.Thing` is that exact type, and a supertype written
    `Outer.Nested` is a nested type of `Outer` — and which `Outer` it is
    depends on the imports of the file. Cutting the name down to its last
    segment would throw both away, and the resolution would guess.
    """
    names: list[str] = []
    for part in _split_parameters(text):
        name = part.strip()
        if not name:
            continue
        name = name.split("<", 1)[0].strip()
        name = name.split("(", 1)[0].strip()
        if not name:
            continue
        names.append(name)
    return names


def _top_level_statements(body: str) -> list[str]:
    """The statements of a class body that are not inside a nested block.

    A property declared in a function body is not a property of the class, and
    a property of a nested class belongs to that class.
    """
    statements: list[str] = []
    buffer: list[str] = []
    depth = 0
    for line in body.splitlines():
        buffer.append(line)
        depth += line.count("{") - line.count("}")
        if depth <= 0:
            depth = 0
            statements.append("\n".join(buffer))
            buffer = []
    if buffer:
        statements.append("\n".join(buffer))
    return statements


def _declared_type(text: str) -> str | None:
    """The type a property declares, or None when the source infers it.

    An inferred type cannot be read from the text — and an inferred type is
    not necessarily a collection, so nothing may be concluded from it.
    """
    declared, _ = _split_type_and_initializer(text)
    return declared


def _split_type_and_initializer(text: str) -> tuple[str | None, str | None]:
    """The declared type and the initializer of a property declaration.

    The type ends where the accessor or the default value begins; the
    initializer is what follows the `=`. A property that declares no type has
    no declared type, and its initializer is the whole remainder.
    """
    if ":" not in text:
        return None, _initializer_of(text)
    rest = text.split(":", 1)[1]
    depth = 0
    index = 0
    type_end = len(rest)
    while index < len(rest):
        char = rest[index]
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        elif depth == 0 and (
            char == "="
            or rest.startswith(" get", index)
            or rest.startswith(" set", index)
            or rest.startswith(" by", index)
        ):
            type_end = index
            break
        index += 1
    declared = rest[:type_end].strip().rstrip(",").strip()
    return (declared or None), _initializer_of(rest[type_end:])


def _initializer_of(text: str) -> str | None:
    """The initializer of a property, or None when it has none."""
    depth = 0
    for index, char in enumerate(text):
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        elif char == "=" and depth == 0:
            initializer = text[index + 1:].strip()
            return initializer or None
    return None


def _parse_property(text: str) -> tuple[str, str, str | None, str | None] | None:
    """The property a declaration declares: kind, name, type and initializer."""
    match = _PROPERTY.match(text)
    if match is None:
        return None
    kind, name = match.group(1), match.group(2)
    declared, initializer = _split_type_and_initializer(text[match.end():])
    return kind, name, declared, initializer


def _properties_of(
    constructor: str,
    body: str,
) -> list[tuple[str, str, str | None, str | None]]:
    """The properties a class declares: kind, name, type and initializer.

    The constructor parameters that carry `val` or `var` are properties of the
    class, and so are the ones declared in its body.
    """
    properties: list[tuple[str, str, str | None, str | None]] = []
    for part in _split_parameters(constructor):
        parsed = _parse_property(part)
        if parsed is not None:
            properties.append(parsed)
    for statement in _top_level_statements(body):
        parsed = _parse_property(statement)
        if parsed is not None:
            properties.append(parsed)
    return properties


def _initializer_factory(text: str | None) -> str | None:
    """The mutable collection factory an initializer calls, or None.

    A property whose type is inferred is invisible to the declared type — but
    its initializer is not, and `mutableListOf()` says exactly what a
    `MutableList` says.
    """
    if text is None:
        return None
    for factory in _MUTABLE_FACTORIES:
        # The call may carry type arguments: `mutableListOf<String>()`.
        if re.search(rf"\b{re.escape(factory)}\s*(?:<[^>]*>)?\s*\(", text):
            return factory
    return None


def _enclosing_declaration(
    index: int,
    sites: list[_Site],
) -> int | None:
    """The declaration the one at `index` is declared inside, if any.

    The innermost one: a declaration inside a declaration inside a declaration
    is inside both, and the name the compiler prints carries them in that
    order.
    """
    position = sites[index].position
    best: int | None = None
    best_size: int | None = None
    for other, site in enumerate(sites):
        if other == index or site.body_range is None:
            continue
        if not site.body_range[0] <= position <= site.body_range[1]:
            continue
        size = site.body_range[1] - site.body_range[0]
        if best_size is None or size < best_size:
            best, best_size = other, size
    return best


def _is_mutable_collection(type_text: str) -> bool:
    """True when the declared type is a collection that can change."""
    text = type_text.strip().rstrip("?").strip()
    name = text.split("<", 1)[0].strip().split(".")[-1]
    return name in _MUTABLE_COLLECTIONS


def check_contract_honesty(
    entries: list[tuple[int, str]],
    declarations: dict[str, Declaration],
) -> list[str]:
    """The findings for the types the contract names.

    The contract is a promise the compiler cannot check: it says the type is
    stable, and the compiler compares every instance of it by `equals`. A type
    that declares a `var` breaks that promise — the value has a second
    version, and `equals` compares the wrong one. A type that holds a
    mutable collection breaks it too: the collection can change after
    construction, and `equals` of a collection that changed is a lie about
    "the same content".

    A read-only collection is allowed, and the contract relies on it: `Task`
    holds a `List<TagId>`, and the promise there is that the list is rebuilt
    on every read and never mutated afterwards. That the list is never
    mutated is not a thing this check can see — it needs to know every write,
    and the compiler does not tell it. The mutable spellings are what it can
    see, and those it rejects.

    An entry is a promise about the instances of the type it names, and an
    instance may be of any type below it: the compiler matches the entry
    against the supertypes of a class as well, so the subclasses of a
    contracted class and the implementations of a contracted interface are
    covered by the promise too. A `var` in one of them breaks it.
    """
    findings: list[str] = []
    ancestors = _ancestors(declarations)
    for number, entry in entries:
        if not entry.startswith(PROJECT_NAMESPACE):
            continue
        for qualified in _covered_types(entry, declarations):
            declaration = declarations.get(qualified)
            if declaration is None:
                # The entry check reports a name that resolves to nothing.
                continue
            if declaration.kind in ("object", "typealias"):
                # An object is stable without the contract, and a typealias is
                # not a class the compiler can match: the entry check reports
                # both, and there is no promise of ours to keep here.
                continue
            targets = [qualified] + _implementations_of(
                qualified,
                declarations,
                ancestors,
            )
            for target in targets:
                reached = "" if target == qualified else (
                    f" — {qualified} is the type the contract vouches for, and this "
                    f"declaration is one of the types below it, so an instance of it may "
                    f"be compared by equals"
                )
                for kind, name, type_text, initializer in declarations[target].properties:
                    if kind == "var":
                        findings.append(
                            f"{number}: {target} declares a mutable property '{name}' — the "
                            "contract tells the compiler the type is stable, and the compiler "
                            "compares it by equals: a var means there is a second version of "
                            f"the value, and the comparison is of the wrong one{reached}",
                        )
                        continue
                    if type_text and _is_mutable_collection(type_text):
                        findings.append(
                            f"{number}: {target} holds '{name}: {type_text.strip()}', and "
                            "that collection can change after the instance is built — the "
                            "contract tells the compiler the type is stable, and the compiler "
                            "compares it by equals, so a collection that changed compares equal "
                            f"to its old contents{reached}",
                        )
                        continue
                    if type_text is None:
                        # The type is inferred, so the declared type says nothing. The
                        # initializer does: `mutableListOf()` is a `MutableList`.
                        factory = _initializer_factory(initializer)
                        if factory is not None:
                            findings.append(
                                f"{number}: {target} holds '{name}', built by "
                                f"'{factory}(...)' — the type is inferred, so the declared "
                                "type says nothing, but the collection can change after the "
                                "instance is built, and the contract tells the compiler the "
                                f"type is stable{reached}",
                            )
    return findings


def _ancestors(declarations: dict[str, Declaration]) -> dict[str, set[str]]:
    """For every declaration, the declarations it carries as supertypes.

    A supertype is written the way Kotlin resolves it, and the gate has to
    resolve it the same way:

      1. a name written in full is that exact type;
      2. an explicit import shadows a declaration of the same package, so the
         import is looked at first — `import q.Thing` wins over a `Thing` of
         the file's own package;
      3. otherwise the name is relative to the package of the file, which is
         where a declaration of the same package lives — including a nested
         type written `Outer.Nested`;
      4. and last the wildcard import, which Kotlin ranks below the package
         of the file.

    A name that resolves to nothing belongs to nothing the gate can see: a
    namesake in another package that nobody imported is not our type, and
    treating it as one would turn an unrelated class into an implementation.
    """
    def resolve(owner: str, path: str) -> str | None:
        if path in declarations:
            # The name is written in full.
            return path
        first, _, rest = path.partition(".")
        suffix = f".{rest}" if rest else ""
        # An explicit import shadows a declaration of the same package.
        for imported in sorted(declarations[owner].imports):
            if imported.endswith(".*"):
                continue
            if imported == first or imported.endswith(f".{first}"):
                candidate = imported + suffix
                if candidate in declarations:
                    return candidate
        # Then the package of the file — including a nested type written
        # `Outer.Nested`.
        package = declarations[owner].package
        if package:
            candidate = f"{package}.{path}"
            if candidate in declarations:
                return candidate
        # And last the wildcard import, which Kotlin ranks below the package
        # of the file.
        for imported in sorted(declarations[owner].imports):
            if not imported.endswith(".*"):
                continue
            candidate = imported[:-1] + path
            if candidate in declarations:
                return candidate
        return None

    ancestors: dict[str, set[str]] = {}

    def collect(fqn: str, seen: frozenset[str]) -> set[str]:
        if fqn in ancestors:
            return ancestors[fqn]
        if fqn in seen:
            # A cycle in the sources; the names it carries are enough.
            return set()
        result: set[str] = set()
        for path in declarations[fqn].supertypes:
            parent = resolve(fqn, path)
            if parent is None:
                continue
            result.add(parent)
            result |= collect(parent, seen | {fqn})
        ancestors[fqn] = result
        return result

    for fqn in declarations:
        collect(fqn, frozenset())
    return ancestors


def _implementations_of(
    interface: str,
    declarations: dict[str, Declaration],
    ancestors: dict[str, set[str]],
) -> list[str]:
    """The declarations that carry the interface as a supertype.

    The compiler matches the entry for an interface against the supertypes of
    a class as well, so the implementations are covered by the promise the
    contract makes for the interface.
    """
    return sorted(fqn for fqn, parents in ancestors.items() if interface in parents)


def _covered_types(entry: str, declarations: dict[str, Declaration]) -> list[str]:
    """The types a contract entry speaks about.

    A wildcard entry speaks about every type under it — and a check that skips
    a wildcard skips the whole subtree under it, which is exactly where a
    `var` would hide.
    """
    if "*" not in entry:
        return [entry]
    if entry.endswith("**"):
        prefix = entry[:-2]
        return [name for name in declarations if name.startswith(prefix)]
    # A single star reaches one package level only, the way the compiler's
    # matcher reads it.
    prefix = entry[:-1]
    return [
        name
        for name in declarations
        if name.startswith(prefix) and "." not in name[len(prefix):]
    ]


def read_debt_baseline(path: pathlib.Path) -> dict[str, int]:
    """The recorded size of the register of debt."""
    baseline: dict[str, int] = {}
    if not path.is_file():
        return baseline
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        text = line.split("#", 1)[0].strip()
        if not text or "=" not in text:
            continue
        key, value = text.split("=", 1)
        key = key.strip()
        try:
            baseline[key] = int(value.strip())
        except ValueError:
            continue
    return baseline


def check_debt_size(
    debt: dict[str, dict[str, str]],
    baseline: dict[str, int],
) -> list[str]:
    """The findings for the size of the register of debt.

    The register is a ratchet: it may shrink, and every shrink has to be
    recorded, but it may not grow without someone deciding to raise the bar.
    The recorded size and the real size must agree — otherwise the number in
    the file is a number nobody reads.
    """
    findings: list[str] = []
    measured = {
        "sites": len(debt),
        "parameters": sum(len(parameters) for parameters in debt.values()),
    }
    for key, actual in measured.items():
        recorded = baseline.get(key)
        if recorded is None:
            findings.append(
                f"the register of debt records no '{key}' — the size of the debt is "
                "the number everyone quotes, and it must be written down",
            )
            continue
        if actual == recorded:
            continue
        if actual > recorded:
            findings.append(
                f"the register of debt grew: {key} is {actual}, and the recorded size "
                f"is {recorded} — the debt may not grow; convert a parameter instead, "
                "or record the new size and say why in the commit",
            )
        else:
            findings.append(
                f"the register of debt shrank: {key} is {actual}, and the recorded size "
                f"is {recorded} — the debt was paid; lower the recorded size to {actual}",
            )
    return findings


def check_entries(
    entries: list[tuple[int, str]],
    declarations: dict[str, Declaration],
    imported: set[str],
) -> list[str]:
    """The findings for the contract's entries. Empty means the contract holds.

    The kinds are told apart, because they do different things:

      * a **class** is what the compiler's stability file lists, and an entry
        for it is exactly what the contract is for;
      * an **interface** the compiler honours as well — it matches the entry
        against the supertypes of a class, so the entry reaches the
        implementations that carry the interface;
      * an **object** the compiler judges stable on its own, so an entry for
        it says nothing that was not already said;
      * a **typealias** is not a class at all, and the compiler matches class
        names, so an entry for it resolves to nothing.
    """
    findings: list[str] = []
    for number, entry in entries:
        if not _ENTRY.match(entry):
            findings.append(
                f"{number}: {entry!r} is not a fully-qualified class name "
                "(dotted identifiers, optionally ending in * or **)",
            )
            continue

        if entry.startswith(PROJECT_NAMESPACE):
            declaration = declarations.get(entry)
            if declaration is not None:
                if declaration.kind == "object":
                    findings.append(
                        f"{number}: {entry} is an object, and the compiler judges an object "
                        "stable on its own — the entry says nothing that was not already "
                        "said; delete it, or name the class that carries the data",
                    )
                elif declaration.kind == "typealias":
                    findings.append(
                        f"{number}: {entry} is a typealias, and the compiler matches class "
                        "names — a typealias is not a class, so the entry resolves to "
                        "nothing",
                    )
                continue
            # A wildcard under our own namespace names a package, not a type.
            if "*" in entry and _covered_types(entry, declarations):
                continue
            findings.append(
                f"{number}: {entry} names no type declared in the shared sources — "
                "the contract would silently do nothing for it",
            )
            continue

        # A library type: it must be a type this codebase actually uses.
        if entry in imported:
            continue
        findings.append(
            f"{number}: {entry} is neither a type declared in the shared sources "
            "nor a type imported anywhere in them — check the name",
        )
    return findings


def check_wiring(root: pathlib.Path) -> list[str]:
    """The findings for the modules that must read the contract."""
    findings: list[str] = []
    for relative in WIRED_MODULES:
        path = root / relative
        if not path.is_file():
            findings.append(f"{relative}: not found")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "stability_config.conf" not in text:
            findings.append(
                f"{relative}: does not read config/compose/stability_config.conf — "
                "the contract exists but this module never applies it",
            )
        if "stabilityConfigurationFiles" not in text:
            findings.append(
                f"{relative}: does not use stabilityConfigurationFiles — "
                "the contract must be handed to the compiler, not merely named",
            )
    return findings


def read_register(register: pathlib.Path) -> dict[str, dict[str, str]]:
    """The register of debt: a qualified name, and the parameters it names.

    The register is a Kotlin source file, so it is read as one: the map's body
    is located, and each entry is a qualified name and the `mapOf` of its
    parameters. Reading the whole file instead would pick up the qualified
    names quoted in the register's own prose.
    """
    if not register.is_file():
        return {}
    text = register.read_text(encoding="utf-8", errors="replace")
    block = _REGISTER_BLOCK.search(text)
    if block is None:
        return {}
    body = block.group("body")
    result: dict[str, dict[str, str]] = {}
    for entry in re.finditer(r'"(?P<fqn>com\.singularity\.[\w.]+)"\s*to\s*mapOf\(', body):
        fqn = entry.group("fqn")
        open_paren = entry.end() - 1
        close = _matching_paren(body, open_paren)
        if close is None:
            continue
        parameters: dict[str, str] = {}
        for pair in re.finditer(r'"(?P<name>[^"]+)"\s*to\s*"(?P<type>[^"]*)"', body[open_paren + 1:close]):
            parameters[pair.group("name")] = pair.group("type")
        result[fqn] = parameters
    return result


def _matching_paren(text: str, open_paren: int) -> int | None:
    """The index of the parenthesis that closes the one at `open_paren`."""
    stack: list[str] = []
    for index in range(open_paren, len(text)):
        char = text[index]
        if _opens(char, text, index):
            stack.append(_closer_of(char))
        elif _closes(char):
            if stack and stack[-1] == char:
                stack.pop()
                if not stack:
                    return index
    return None


def _opens(char: str, text: str, index: int) -> bool:
    """True when the character opens a bracket.

    A `<` opens a generic argument list only after an identifier — `List<…>` —
    and never after a `-`, which is what makes `-> Unit` a function type and
    not a closing bracket.
    """
    if char in "([{":
        return True
    if char == "<":
        previous = text[index - 1] if index else ""
        return previous.isalnum() or previous == "_"
    return False


def _closes(char: str) -> bool:
    return char in ")]}>"


def _closer_of(char: str) -> str:
    """The bracket that closes the one `char` opens."""
    return {"(": ")", "[": "]", "{": "}", "<": ">"}[char]


def read_register_sites(register: pathlib.Path) -> list[str]:
    """The qualified names the register of debt names, in the order it names them."""
    return list(read_register(register))


def source_roots(root: pathlib.Path) -> list[pathlib.Path]:
    """The Kotlin source roots of the modules that may declare a composable."""
    roots: list[pathlib.Path] = []
    for module in REGISTER_MODULES:
        base = root / module / "src"
        if not base.is_dir():
            continue
        roots.extend(path for path in base.glob("*/kotlin") if path.is_dir())
    return roots


def _declares(text: str, name: str) -> bool:
    """True when the source text declares a name of that spelling."""
    escaped = re.escape(name)
    return any(
        re.search(shape.format(name=escaped), text) for shape in _DECLARATION_SHAPES
    )


def _package_of(text: str) -> str:
    """The package the source declares, or an empty string when it declares none."""
    match = _PACKAGE.search(text)
    return match.group(1) if match else ""


def declaring_files(site: str, roots: list[pathlib.Path]) -> list[pathlib.Path]:
    """The files that declare the composable the register names.

    The qualified name does not say where the package ends and the
    declarations begin, so every split is tried: the longest package first,
    because a package that does not exist as a directory cannot hold the
    declaration.

    The search stays inside the package. A name that also exists in another
    package — and in this repository several do — must not stand in for the
    declaration that was deleted, or the gate would pass a tree where the
    registered composable is gone.

    "Inside the package" means the package the file *declares*, not the
    directory the file sits in. A directory holds the sub-packages as well,
    and a namesake in a sub-package would mask a deleted site exactly as a
    namesake in another package would — the package declaration is what says
    which package a name belongs to.
    """
    parts = site.split(".")
    for split in range(len(parts) - 1, 0, -1):
        package = ".".join(parts[:split])
        names = parts[split:]
        relative = pathlib.Path(*package.split("."))
        for root in roots:
            directory = root / relative
            if not directory.is_dir():
                continue
            texts = {
                path: path.read_text(encoding="utf-8", errors="replace")
                for path in directory.rglob("*.kt")
            }
            # The file must belong to the package being searched — a file in a
            # sub-package directory that declares another package is not a
            # namesake of this one.
            texts = {
                path: text for path, text in texts.items() if _package_of(text) == package
            }
            # The file must declare the function and the classes it is nested
            # in — the names of one site belong to one declaration.
            matched = [
                path
                for path, text in texts.items()
                if _declares(text, names[-1])
                and all(_declares(text, name) for name in names[:-1])
            ]
            if matched:
                return matched
    return []


def site_exists(site: str, roots: list[pathlib.Path]) -> bool:
    """True when some source declares the composable the register names."""
    return bool(declaring_files(site, roots))


def declared_parameters(site: str, roots: list[pathlib.Path]) -> tuple[bool, set[str]]:
    """Whether a declaration of the composable was found, and the names it declares.

    Every overload of the composable counts: `Nav3State.toDecoratedEntries` is
    declared twice, and the register may describe either of them.

    The flag matters: "the declaration has no parameters" and "the declaration
    was not found" are different facts, and a gate that cannot tell them apart
    skips the site in both cases — which is how a registered parameter that was
    deleted along with the last other parameter of the composable survives.
    """
    names: set[str] = set()
    function_name = site.split(".")[-1]
    found = False
    for path in declaring_files(site, roots):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in re.finditer(
            rf"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?{re.escape(function_name)}\s*\(",
            text,
        ):
            found = True
            names |= _parameter_names(text, match.end() - 1)
    return found, names


def _parameter_names(text: str, open_paren: int) -> set[str]:
    """The parameter names of the parameter list that starts at `open_paren`."""
    end = _matching_paren(text, open_paren)
    if end is None:
        return set()
    body = text[open_paren + 1:end]
    names: set[str] = set()
    for part in _split_parameters(body):
        head = part.split("=", 1)[0].strip()
        name = head.split(":", 1)[0].strip()
        if re.fullmatch(r"[A-Za-z_]\w*", name):
            names.add(name)
    return names


def _split_parameters(body: str) -> list[str]:
    """The parameters of a parameter list, split on the commas between them.

    A comma inside a generic argument list or a function type belongs to the
    type, not to the parameter list.
    """
    parts: list[str] = []
    current: list[str] = []
    stack: list[str] = []
    for index, char in enumerate(body):
        if _opens(char, body, index):
            stack.append(_closer_of(char))
        elif _closes(char) and stack and stack[-1] == char:
            stack.pop()
        if char == "," and not stack:
            parts.append("".join(current))
            current = []
            continue
        current.append(char)
    parts.append("".join(current))
    return parts


def check_register_sites(sites: list[str], roots: list[pathlib.Path]) -> list[str]:
    """The findings for the register of debt. Empty means every site still exists."""
    findings: list[str] = []
    for site in sites:
        if site_exists(site, roots):
            continue
        findings.append(
            f"the register of debt names '{site}', and no source declares it — "
            "the entry accounts for a composable that no longer exists; delete it",
        )
    return findings


def check_register_parameters(
    sites: list[str],
    register: dict[str, dict[str, str]],
    roots: list[pathlib.Path],
) -> list[str]:
    """The findings for the parameters the register names.

    The rule compares the code that is there with the register, but it cannot
    see a parameter that was removed: a parameter that is gone has no declared
    type to compare, and a composable may have overloads, so "this parameter is
    missing here" is not the same as "this parameter is gone". The gate reads
    the whole repository, so it can answer the question the rule cannot.
    """
    findings: list[str] = []
    for site in sites:
        parameters = register.get(site) or {}
        if not parameters:
            continue
        declared, names = declared_parameters(site, roots)
        if not declared:
            # No declaration of the composable at all — the site check reports
            # that, and a missing declaration has no parameters to compare.
            continue
        for name in parameters:
            if name in names:
                continue
            findings.append(
                f"the register of debt names a parameter '{name}' of {site}, and no "
                "declaration of it has such a parameter — the debt it names has been "
                "paid; delete the entry",
            )
    return findings


@click.command()
@click.option(
    "--conf",
    type=click.Path(path_type=pathlib.Path),
    default=CONF,
    show_default=True,
    help="the stability contract to check",
)
@click.option(
    "--source-root",
    type=click.Path(path_type=pathlib.Path),
    default=ROOT / "shared" / "src" / "commonMain" / "kotlin",
    show_default=True,
    help="the Kotlin sources the project-namespace entries are resolved against",
)
@click.option(
    "--root",
    type=click.Path(path_type=pathlib.Path),
    default=ROOT,
    show_default=True,
    help="the repository root, for the wiring check",
)
@click.option(
    "--register",
    type=click.Path(path_type=pathlib.Path),
    default=DEBT_REGISTER,
    show_default=True,
    help="the register of debt whose sites must still exist",
)
@click.option(
    "--debt-baseline",
    type=click.Path(path_type=pathlib.Path),
    default=DEBT_BASELINE,
    show_default=True,
    help="the recorded size of the register of debt",
)
@click.pass_context
def cli(
    ctx: click.Context,
    conf: pathlib.Path,
    source_root: pathlib.Path,
    root: pathlib.Path,
    register: pathlib.Path,
    debt_baseline: pathlib.Path,
) -> None:
    """Check that the Compose stability contract names real types and is wired."""
    if not conf.is_file():
        click.echo(f"[compose-stability] the contract is missing: {conf}")
        raise ctx.exit(VALIDATION_FAILURE)

    entries = read_entries(conf)
    if not entries:
        click.echo(
            "[compose-stability] the contract names no types — an empty contract "
            "declares everything unstable, which is the state before it existed",
        )
        raise ctx.exit(VALIDATION_FAILURE)

    sources = kotlin_sources(source_root)
    declarations = declared_declarations(sources)
    findings = check_entries(entries, declarations, imported_types(sources))
    findings += check_contract_honesty(entries, declarations)
    findings += check_wiring(root)

    debt = read_register(register)
    sites = list(debt)
    if sites:
        roots = source_roots(root)
        findings += check_register_sites(sites, roots)
        findings += check_register_parameters(sites, debt, roots)
    if not register.is_file():
        # A register that was not read is not a register that shrank: the debt
        # is not measured at all, and a ratchet that measures nothing lets the
        # debt grow unnoticed.
        findings.append(
            f"the register of debt was not read: {register} is missing — the debt "
            "cannot be measured, and the ratchet below cannot hold it",
        )
    else:
        findings += check_debt_size(debt, read_debt_baseline(debt_baseline))

    for finding in findings:
        click.echo(f"[compose-stability] {finding}")

    if findings:
        click.echo()
        click.echo(
            f"{len(findings)} problem(s) in the Compose stability contract. "
            "A name that resolves to nothing makes the compiler ignore it; "
            "a contract no module reads is not a contract."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"[compose-stability] {len(entries)} entries resolve to real types, "
        f"the contract is wired into {len(WIRED_MODULES)} module(s), "
        f"and all {len(sites)} registered site(s) still exist."
    )
    raise ctx.exit(CLEAN)


def main(argv: list[str] | None = None) -> int:
    return run(cli.main, argv)


if __name__ == "__main__":
    raise SystemExit(main())
