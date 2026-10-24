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

So this gate checks five things:

  1. every entry is a syntactically valid fully-qualified name;
  2. every entry resolves to a type that exists — a declared type in the shared
     sources for a project namespace, an imported type for a library one;
  3. the file is actually wired into the modules that compile composables;
  4. every site the register of debt names is still declared in the sources;
  5. every parameter the register of debt names is still declared by some
     declaration of that site.

The fourth and the fifth are a different failure from the first three. The
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

Usage:
    python3 scripts/check-compose-stability-config.py

Exit code is 1 when anything is wrong, so it can gate a check.
"""
from __future__ import annotations

import pathlib
import re
import sys

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
_DECLARATION = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+|internal\s+|private\s+|protected\s+|abstract\s+|sealed\s+|open\s+"
    r"|data\s+|value\s+|enum\s+|annotation\s+|expect\s+|actual\s+|inline\s+)*"
    r"(?:class|interface|object)\s+([A-Za-z_]\w*)",
    re.M,
)
_TYPEALIAS = re.compile(r"^typealias\s+([A-Za-z_]\w*)", re.M)
_IMPORT = re.compile(r"^import\s+([\w.]+)", re.M)

#: A class declaration, with the position where its header ends. The property
#: check needs the constructor and the body, not only the name.
_CLASS = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*\n?[ \t]*)*"
    r"(?:public |internal |private |protected |abstract |sealed |open |data |value |"
    r"enum |annotation |expect |actual |inline )*"
    r"class[ \t]+([A-Za-z_]\w*)",
    re.M,
)

#: A property declaration: `val` or `var`, and its name. The type is read
#: separately, because a type may be generic, inferred, or followed by an
#: accessor — a pattern that one regex cannot tell apart.
_PROPERTY = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:private |public |internal |protected )?(val|var)[ \t]+([A-Za-z_]\w*)",
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


def declared_types(sources: list[pathlib.Path]) -> set[str]:
    """Every fully-qualified type name declared in the given sources."""
    types: set[str] = set()
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        package_match = _PACKAGE.search(text)
        package = package_match.group(1) if package_match else ""
        for match in _DECLARATION.finditer(text):
            types.add(f"{package}.{match.group(1)}" if package else match.group(1))
        for match in _TYPEALIAS.finditer(text):
            types.add(f"{package}.{match.group(1)}" if package else match.group(1))
    return types


def imported_types(sources: list[pathlib.Path]) -> set[str]:
    """Every fully-qualified name imported by the given sources."""
    imports: set[str] = set()
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in _IMPORT.finditer(text):
            imports.add(match.group(1))
    return imports


def _body_start(text: str, position: int) -> int | None:
    """The index of the brace that opens a class body, or None when there is none.

    The brace is looked for at bracket depth zero, so a `{` inside a default
    value or a lambda in the header does not pass for the body.
    """
    stack: list[str] = []
    for index in range(position, len(text)):
        char = text[index]
        if _opens(char, text, index):
            if char == "{" and not stack:
                return index
            stack.append(_closer_of(char))
        elif _closes(char) and stack and stack[-1] == char:
            stack.pop()
    return None


def _class_parts(text: str, after_name: int) -> tuple[str, str, tuple[int, int] | None]:
    """The constructor parameters, the body, and the range of the body.

    The header is walked rather than pattern-matched: a class may declare a
    secondary constructor, a supertype, or both, and the body is the first
    brace at depth zero after all of that.
    """
    position = after_name
    constructor = ""
    while position < len(text) and text[position].isspace():
        position += 1
    if text[position:position + 11] == "constructor":
        position += 11
        while position < len(text) and text[position].isspace():
            position += 1
    if position < len(text) and text[position] == "(":
        close = _matching_paren(text, position)
        if close is None:
            return "", "", None
        constructor = text[position + 1:close]
        position = close + 1
    while position < len(text) and text[position].isspace():
        position += 1
    if position < len(text) and text[position] == ":":
        # The supertype list, up to the body.
        start = _body_start(text, position)
        if start is None:
            return constructor, "", None
        position = start
    body_start = _body_start(text, position)
    if body_start is None:
        return constructor, "", None
    body_end = _matching_paren(text, body_start)
    if body_end is None:
        return constructor, "", None
    return constructor, text[body_start + 1:body_end], (body_start, body_end)


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
    if ":" not in text:
        return None
    rest = text.split(":", 1)[1]
    depth = 0
    end = len(rest)
    index = 0
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
            end = index
            break
        index += 1
    declared = rest[:end].strip().rstrip(",").strip()
    return declared or None


def _parse_property(text: str) -> tuple[str, str, str | None] | None:
    """The property a declaration declares: kind, name and declared type."""
    match = _PROPERTY.match(text)
    if match is None:
        return None
    kind, name = match.group(1), match.group(2)
    return kind, name, _declared_type(text[match.end():])


def _properties_of(constructor: str, body: str) -> list[tuple[str, str, str | None]]:
    """The properties a class declares: kind, name and declared type.

    The constructor parameters that carry `val` or `var` are properties of the
    class, and so are the ones declared in its body.
    """
    properties: list[tuple[str, str, str | None]] = []
    for part in _split_parameters(constructor):
        parsed = _parse_property(part)
        if parsed is not None:
            properties.append(parsed)
    for statement in _top_level_statements(body):
        parsed = _parse_property(statement)
        if parsed is not None:
            properties.append(parsed)
    return properties


def declared_classes(
    sources: list[pathlib.Path],
) -> dict[str, list[tuple[str, str, str | None]]]:
    """Every class the sources declare, by its fully-qualified name.

    The name is the one the Compose compiler prints: the enclosing classes
    come first and the class itself last. A nested class therefore reads as
    `Outer.Nested`, and two classes of the same name in different packages
    stay apart — which is what makes the check below able to say anything
    about a *particular* type.
    """
    classes: dict[str, list[tuple[str, str, str | None]]] = {}
    for path in sources:
        text = path.read_text(encoding="utf-8", errors="replace")
        package = _package_of(text)
        found: list[tuple[str, int, tuple[int, int] | None, str, str]] = []
        for match in _CLASS.finditer(text):
            name = match.group(1)
            constructor, body, body_range = _class_parts(text, match.end())
            found.append((name, match.start(), body_range, constructor, body))
        for index, (name, position, body_range, constructor, body) in enumerate(found):
            qualified = f"{package}.{name}" if package else name
            # A class declared inside another class's body is nested: its name
            # carries the enclosing class, the way the compiler prints it.
            for other_name, other_position, other_range, _, _ in found:
                if other_range is None or other_position >= position:
                    continue
                if other_range[0] <= position <= other_range[1]:
                    qualified = f"{qualified}.{other_name}"
                    break
            classes.setdefault(qualified, _properties_of(constructor, body))
    return classes


def _is_mutable_collection(type_text: str) -> bool:
    """True when the declared type is a collection that can change."""
    text = type_text.strip().rstrip("?").strip()
    name = text.split("<", 1)[0].strip().split(".")[-1]
    return name in _MUTABLE_COLLECTIONS


def check_contract_honesty(
    entries: list[tuple[int, str]],
    classes: dict[str, list[tuple[str, str, str | None]]],
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
    """
    findings: list[str] = []
    for number, entry in entries:
        if not entry.startswith(PROJECT_NAMESPACE) or "*" in entry:
            continue
        properties = classes.get(entry)
        if properties is None:
            # The entry check reports a name that resolves to nothing.
            continue
        for kind, name, type_text in properties:
            if kind == "var":
                findings.append(
                    f"{number}: {entry} declares a mutable property '{name}' — the "
                    "contract tells the compiler the type is stable, and the compiler "
                    "compares it by equals: a var means there is a second version of "
                    "the value, and the comparison is of the wrong one",
                )
            elif type_text and _is_mutable_collection(type_text):
                findings.append(
                    f"{number}: {entry} holds '{name}: {type_text.strip()}', and that "
                    "collection can change after the instance is built — the contract "
                    "tells the compiler the type is stable, and the compiler compares "
                    "it by equals, so a collection that changed compares equal to its "
                    "old contents",
                )
    return findings


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
    declared: set[str],
    imported: set[str],
) -> list[str]:
    """The findings for the contract's entries. Empty means the contract holds."""
    findings: list[str] = []
    for number, entry in entries:
        if not _ENTRY.match(entry):
            findings.append(
                f"{number}: {entry!r} is not a fully-qualified class name "
                "(dotted identifiers, optionally ending in * or **)",
            )
            continue

        if entry.startswith(PROJECT_NAMESPACE):
            if entry in declared:
                continue
            # A wildcard under our own namespace names a package, not a type.
            if entry.endswith("**"):
                prefix = entry[:-2]
                if any(name.startswith(prefix) for name in declared):
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
    findings = check_entries(entries, declared_types(sources), imported_types(sources))
    findings += check_contract_honesty(entries, declared_classes(sources))
    findings += check_wiring(root)

    debt = read_register(register)
    sites = list(debt)
    if sites:
        roots = source_roots(root)
        findings += check_register_sites(sites, roots)
        findings += check_register_parameters(sites, debt, roots)
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
