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

So this gate checks four things:

  1. every entry is a syntactically valid fully-qualified name;
  2. every entry resolves to a type that exists — a declared type in the shared
     sources for a project namespace, an imported type for a library one;
  3. the file is actually wired into the modules that compile composables;
  4. every site the register of debt names is still declared in the sources.

The fourth one is a different failure from the first three. The contract names
*types*, and the compiler ignores a name it cannot resolve — a contract entry
that rots is a contract that says less than it appears to. The register of debt
(`ComposeCollectionExemptions.kt`) names *composables*, and a register entry
whose composable was deleted is worse than a dead contract entry: the detekt
rule exempts by name, so a stale entry exempts nothing and the debt it was
accounting for is invisible in both directions.

The rule itself catches the entries that are stale in the *parameters* — a
parameter that was renamed, removed or retyped. It cannot catch a composable
that is gone: the rule visits the code that exists. That is what this gate is
for; it reads the sources, not a build artifact.

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

_PACKAGE = re.compile(r"^package\s+([\w.]+)")
_DECLARATION = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+|internal\s+|private\s+|protected\s+|abstract\s+|sealed\s+|open\s+"
    r"|data\s+|value\s+|enum\s+|annotation\s+|expect\s+|actual\s+|inline\s+)*"
    r"(?:class|interface|object)\s+([A-Za-z_]\w*)",
    re.M,
)
_TYPEALIAS = re.compile(r"^typealias\s+([A-Za-z_]\w*)", re.M)
_IMPORT = re.compile(r"^import\s+([\w.]+)", re.M)

#: A fully-qualified name: dot-separated Java identifiers. The last segment may
#: be a wildcard — `*` for one package level, `**` for the level and everything
#: under it.
_ENTRY = re.compile(r"^[A-Za-z_]\w*(\.[A-Za-z_]\w*)*(\.\*{1,2})?$")

#: The register of debt: the composables that still take a raw collection.
DEBT_REGISTER = (
    ROOT / "detekt-rules" / "src" / "main" / "kotlin" / "com" / "singularity" / "todo"
    / "detekt" / "ComposeCollectionExemptions.kt"
)

#: The modules whose sources may declare a registered composable.
REGISTER_MODULES = ("shared", "desktopApp", "androidApp")

#: A qualified name in the register — the keys of the exemption map.
_REGISTER_SITE = re.compile(r'"(com\.singularity\.[\w.]+)"')

#: How a source declares a name. Three shapes, because the register names
#: classes, functions and properties alike. A function may carry a type
#: parameter list and a receiver — `fun <T> Nav3State.toDecoratedEntries(`.
_DECLARATION_SHAPES = (
    r"\b(?:class|object|interface)\s+{name}\b",
    r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?{name}\s*\(",
    r"\b(?:val|var)\s+(?:[\w.]+\.)?{name}\b",
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


def read_register_sites(register: pathlib.Path) -> list[str]:
    """The qualified names the register of debt names, in the order it names them."""
    if not register.is_file():
        return []
    text = register.read_text(encoding="utf-8", errors="replace")
    sites: list[str] = []
    for match in _REGISTER_SITE.finditer(text):
        site = match.group(1)
        if site not in sites:
            sites.append(site)
    return sites


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


def site_exists(site: str, roots: list[pathlib.Path]) -> bool:
    """True when some source declares the composable the register names.

    The qualified name does not say where the package ends and the
    declarations begin, so every split is tried: the longest package first,
    because a package that does not exist as a directory cannot hold the
    declaration.

    A source root that does not lay its files out by package path cannot be
    searched by path, so the name is looked for in the whole root as well —
    a gate that reports sixty false positives because a module moved its
    sources is a gate that gets switched off.
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
            texts = [
                path.read_text(encoding="utf-8", errors="replace")
                for path in directory.rglob("*.kt")
            ]
            if texts and all(any(_declares(text, name) for text in texts) for name in names):
                return True
    return _declared_anywhere(site.split(".")[-1], roots)


def _declared_anywhere(name: str, roots: list[pathlib.Path]) -> bool:
    """True when some source anywhere declares a name of that spelling."""
    escaped = re.escape(name)
    patterns = [shape.format(name=escaped) for shape in _DECLARATION_SHAPES]
    for root in roots:
        for path in root.rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            if any(re.search(pattern, text) for pattern in patterns):
                return True
    return False


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
@click.pass_context
def cli(
    ctx: click.Context,
    conf: pathlib.Path,
    source_root: pathlib.Path,
    root: pathlib.Path,
    register: pathlib.Path,
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
    findings += check_wiring(root)

    sites = read_register_sites(register)
    if sites:
        findings += check_register_sites(sites, source_roots(root))

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
