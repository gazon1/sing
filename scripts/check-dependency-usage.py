#!/usr/bin/env python3
"""
Dependency-usage gate: a declared dependency that nothing imports (#205).

`find-unwired-surfaces.py` counts *symbols*, so it cannot see an unused
dependency: an unused dependency has no symbol until something imports it.
`material-kolor` sat in the catalog and on the `commonMain` classpath for a
release, imported by nobody — and the palette it should have been generating was
two hard-coded schemes, so the nine accent choices all rendered the same blue.

## Why the catalog cannot answer this

A Gradle coordinate does not determine an import package:

    org.jetbrains.compose.material3:material3  ->  androidx.compose.material3
    org.jetbrains.compose.ui:ui                ->  androidx.compose.ui
    io.insert-koin:koin-core                    ->  org.koin.core

So "does the module string appear in an import" is not a prefix test. Measured on
this tree: a first-two-segment heuristic over all 98 library entries reports 45
unused — and every one is used, including koin-core, compose-material3,
kotlinx-coroutines-core, coil-compose, robolectric and turbine. Do not
re-implement it and do not allowlist its output: a 45-entry list of
definitely-used libraries is indistinguishable, to the next reader, from a
45-entry list of genuinely dead ones.

## What this uses instead

Gradle already knows which files each configuration resolved and which coordinate
each file came from. `:shared:printResolvedArtifacts` prints that mapping, and this
script reads the *contents* of each jar/aar to learn the package roots it actually
contributes. Comparing those roots against the imports in the source set that
resolved them answers the question without guessing.

Usage:
    ./gradlew :shared:printResolvedArtifacts -q > /tmp/resolved.tsv
    python3 scripts/check-dependency-usage.py --resolved /tmp/resolved.tsv

Exit codes: 0 = no unused dependency, 1 = findings, 2 = could not measure.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parent.parent

# Source set -> directories it may import from. commonMain is included by
# androidMain and jvmMain, so a commonMain import counts for all three.
SOURCE_SET_DIRS = {
    "commonMain": ["commonMain"],
    "androidMain": ["commonMain", "androidMain"],
    "jvmMain": ["commonMain", "jvmMain"],
}

MODULES_WITH_ROOT = ("shared", "androidApp", "desktopApp", "mcp-server")

# Gradle files whose `libs.*` references count as a *declared* dependency.
DECLARING_FILES = tuple(f"{m}/build.gradle.kts" for m in MODULES_WITH_ROOT)

CATALOG_ENTRY_RE = re.compile(
    r"^(?P<key>[\w.-]+)\s*=\s*\{.*?\bmodule\s*=\s*\"(?P<module>[^\"]+)\"",
    re.MULTILINE,
)

BUNDLES = ("compose", "koin", "ktor", "datastore", "tracer")

IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)", re.MULTILINE)

# A dotted token in code — `a.b.C.Factory()`, `com.mikepenz.markdown.m3`. `\b`
# at both ends keeps it from matching inside a longer identifier.
_FQN_RE = re.compile(r"\b([a-z][\w]*(?:\.[a-zA-Z_]\w*){2,})\b")

_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT_RE = re.compile(r"//[^\n]*")


def _strip_comments(text: str) -> str:
    return _LINE_COMMENT_RE.sub("", _BLOCK_COMMENT_RE.sub("", text))

# A jar entry is a package-bearing class file. Metadata jars ship
# `.kotlin_metadata` and `META-INF` only, so they contribute nothing and are
# filtered out by requiring a real directory segment that is not META-INF.
#
# The package part is `(\w+/)+` rather than the obvious `[\w/$]+` because the
# single class fails on every entry: greedy `[\w/$]+` swallows the final
# `Foo.class` segment and then `\.class$` has nothing left to match. Written as
# a slash-terminated repeat, the class name is never consumed. Nested classes
# (`Foo$Bar`) work under both, which is why only the plain case catches it —
# every entry in a real jar is the plain case.
_ENTRY = re.compile(r"^(?P<pkg>(?:\w+/)+)[\w$]+\.class$")

# A KMP dependency's compiled code reaches the classpath as a `.klib`, but in this
# build the klib is packaged *inside* the metadata jar as link data, under
# `linkdata/package_<fqcn>/`. Without this second pattern every KMP library reads
# as contributing no packages at all — which is precisely the set that includes
# every wrong-answer case (compose, koin, markdown, okio). Reading the JVM `.class`
# layout alone would have made the gate green over an unexamined majority.
_LINKDATA = re.compile(r"(?:^|/)linkdata/package_(?P<pkg>[\w.]+)/")

MIN_ARTEFACTS = 50


def package_roots(path: Path) -> set[str]:
    """
    Top-level package roots an artifact contributes, read from its contents.

    Two shapes: `.jar` and `.aar` (an Android archive wrapping `classes.jar`).
    For a KMP multiplatform module the metadata jar is a sibling artifact of the
    same coordinate, and both are reported — the gate unions them, because a
    dependency is "used" if either contributes a matching root.
    """
    if not path.exists():
        return set()
    roots: set[str] = set()
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if path.suffix == ".aar":
                if "classes.jar" in names:
                    roots |= _roots_from_bytes(archive.read("classes.jar"))
                # An .aar may also declare its package in the manifest; nothing
                # else in the archive is class code.
            else:
                roots |= _roots_from_names(names)
    except (zipfile.BadZipFile, OSError):
        return set()
    return roots


def _roots_from_names(names: list[str]) -> set[str]:
    roots = {
        # The group ends on a slash (it is `(\w+/)+`), so the trailing separator
        # has to go before the result is a package name.
        match.group("pkg").rstrip("/").replace("/", ".")
        for name in names
        if (match := _ENTRY.match(name)) and not name.startswith("META-INF")
    }
    roots |= {match.group("pkg") for name in names if (match := _LINKDATA.search(name))}
    return roots


def _roots_from_bytes(data: bytes) -> set[str]:
    import io

    try:
        with zipfile.ZipFile(io.BytesIO(data)) as nested:
            return _roots_from_names(nested.namelist())
    except (zipfile.BadZipFile, OSError):
        return set()


def load_resolved(path: Path) -> list[tuple[str, str, Path]]:
    rows = []
    for line in path.read_text().splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        source_set, coordinate, file_path = parts
        if source_set not in SOURCE_SET_DIRS:
            continue
        rows.append((source_set, coordinate, Path(file_path)))
    return rows


ALLOWLIST_PATH = WORKSPACE / "scripts" / "dependency-usage-allowlist.txt"


def read_allowlist(path: Path) -> tuple[set[str], int | None]:
    """
    Accepted findings from `path`, and the ceiling on how many there may be.

    Comments and blank lines are not entries: the file is mostly prose explaining
    *why* each dependency is exempt, and a parser that counted that prose would
    report a ceiling nobody set.
    """
    if not path.exists():
        return set(), None
    entries = {
        line.strip().split("#", 1)[0].strip()
        for line in path.read_text().splitlines()
    }
    ceiling_file = path.with_suffix(".ceiling")
    ceiling = int(ceiling_file.read_text().strip()) if ceiling_file.exists() else None
    return {e for e in entries if e}, ceiling


def load_allowlist() -> tuple[set[str], int | None]:
    """
    Accepted findings, and the ceiling on how many there may be.

    The ceiling is the line count at the moment this gate landed. Lowering it is
    the point: the list is debt, and a rule that only stops new entries is a rule
    that lets the debt be extended indefinitely by paperwork. The coordinate text
    in the file is documentation; what the gate enforces is the count and the
    exact set, so an entry cannot be renamed into irrelevance.
    """
    return read_allowlist(ALLOWLIST_PATH)


def declared_coordinates() -> dict[str, str]:
    """
    Catalog keys this project declares itself → their `group:artifact` module.

    Only *declared* dependencies are the gate's business. A transitively-resolved
    library is someone else's decision — Koog alone contributes ~100 on this tree,
    and a finding about each of them would bury the one real answer. The
    transitive set is still printed as context when a finding exists, because
    "declared but unused" and "pulled in by something else" read very differently
    to whoever has to act on it.
    """
    catalog = (WORKSPACE / "gradle" / "libs.versions.toml").read_text()
    libraries = catalog.split("[libraries]", 1)[1]
    key_to_module = {
        match.group("key").replace("-", "."): match.group("module")
        for match in CATALOG_ENTRY_RE.finditer(libraries)
    }

    referenced: set[str] = set()
    for name in DECLARING_FILES:
        path = WORKSPACE / name
        if path.is_file():
            referenced.update(re.findall(r"libs\.([\w.]+)", path.read_text()))

    declared: dict[str, str] = {}
    for key in referenced:
        module = key_to_module.get(key)
        if module is not None:
            declared[key] = module
    return declared


def imports_for(source_set: str, module: str = "shared") -> set[str]:
    """Every reference to an external package in the source set, across the modules.

Two things that look like "unused" but are not, both found by running this on the
real tree rather than on a fixture:

  * `androidApp` and `desktopApp` are Android/JVM *applications*, so their sources
    live in `src/main/kotlin`, not `src/androidMain/kotlin`. Scanning only the
    library layout reported `koin-android` as unused while `MainActivity` imports
    `org.koin.android.ext.android.inject`.
  * A fully-qualified reference needs no import. `ai.koog.http.client.okhttp.
    OkHttpKoogHttpClient.Factory()` in a DI module is a use of that package with
    zero import statements to match against.
"""
    found: set[str] = set()
    for name in SOURCE_SET_DIRS[source_set]:
        for mod in MODULES_WITH_ROOT:
            for base in (WORKSPACE / mod / "src" / name / "kotlin",
                         WORKSPACE / mod / "src" / "main" / "kotlin"):
                if not base.is_dir():
                    continue
                for kt in base.rglob("*.kt"):
                    found.update(IMPORT_RE.findall(kt.read_text(encoding="utf-8", errors="ignore")))
    return found


def _qualified_references() -> set[str]:
    """
    Every dotted identifier that looks like an external package, anywhere in the
    workspace's own Kotlin sources.

    Comments are stripped first: prose naming a package ("the JVM-only
    `okio.IOException`") is not a use of it, and the gate has to survive being
    described in a KDoc that mentions the very package under discussion.
    """
    prefixes: set[str] = set()
    for mod in MODULES_WITH_ROOT:
        src = WORKSPACE / mod / "src"
        if not src.is_dir():
            continue
        for kt in src.rglob("*.kt"):
            code = _strip_comments(kt.read_text(encoding="utf-8", errors="ignore"))
            for token in _FQN_RE.findall(code):
                # `a.b.C.Factory` is a use of `a.b`; keep every dotted prefix.
                parts = token.split(".")
                for i in range(2, len(parts)):
                    prefixes.add(".".join(parts[:i]))
    return prefixes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--resolved",
        type=Path,
        help="TSV from :shared:printResolvedArtifacts. Omit to invoke Gradle.",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if args.resolved:
        rows = load_resolved(args.resolved)
    else:
        result = subprocess.run(
            ["./gradlew", ":shared:printResolvedArtifacts", "-q"],
            cwd=WORKSPACE,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            print("could not measure: printResolvedArtifacts failed", file=sys.stderr)
            return 2
        tmp = Path("/tmp/_resolved.tsv")
        tmp.write_text(result.stdout)
        rows = load_resolved(tmp)

    # Coordinate -> package roots, and coordinate -> the source sets that resolved it.
    roots_by_coordinate: dict[str, set[str]] = defaultdict(set)
    sets_by_coordinate: dict[str, set[str]] = defaultdict(set)
    for source_set, coordinate, file_path in rows:
        roots_by_coordinate[coordinate] |= package_roots(file_path)
        sets_by_coordinate[coordinate].add(source_set)

    # Positive control: a gate that scanned nothing reports "no findings", which
    # is indistinguishable from a clean tree.
    if len(rows) < MIN_ARTEFACTS:
        print(
            f"could not measure: only {len(rows)} artifacts resolved "
            f"(floor {MIN_ARTEFACTS}) — treating this as a scan failure, not a pass",
            file=sys.stderr,
        )
        return 2

    imports_by_set = {s: imports_for(s) for s in SOURCE_SET_DIRS}

    # A fully-qualified use needs no import statement. Collect the *prefixes* of
    # every FQN-looking token in the code so `a.b.C.Factory()` counts as a use of
    # package `a.b` — otherwise a DI module wiring a Koog factory reads as unused.
    # Only runs against the module's own sources, which is cheap enough to redo.
    qualified = _qualified_references()

    declared = declared_coordinates()
    declared_modules = set(declared.values())

    findings = []
    skipped_unmeasured = []
    for coordinate, roots in sorted(roots_by_coordinate.items()):
        if not roots:
            # A metadata-only artifact (no classes, no link data) contributes no
            # package; it cannot be matched and is not evidence of anything.
            continue
        if coordinate not in declared_modules:
            continue
        used = False
        for source_set in sets_by_coordinate[coordinate]:
            imports = imports_by_set.get(source_set, set())
            if any(imp.startswith(root + ".") or imp == root for root in roots for imp in imports):
                used = True
                break
        # A package named in code without an import is still a use — the DI
        # modules are full of `ai.koog.http.client.okhttp.OkHttpKoogHttpClient`,
        # and matching imports alone reported every one of them unused.
        if not used and any(root in qualified for root in roots):
            used = True
        if not used:
            findings.append((coordinate, sorted(roots)[:3], sorted(sets_by_coordinate[coordinate])))

    # A declared dependency whose artifact never resolved at all is a different
    # failure (typo'd coordinate, or a configuration that does not cover it) and
    # must not be read as "used".
    for key, module in sorted(declared.items()):
        if module not in roots_by_coordinate:
            skipped_unmeasured.append((key, module))

    if not args.quiet:
        print(
            f"scanned {len(rows)} resolved artifacts across {len(roots_by_coordinate)} coordinates; "
            f"{len(declared_modules)} of them declared by this project"
        )
        if skipped_unmeasured:
            # Not a failure, and not a finding: the task prints only the three
            # *production* source sets, so every test-only, detekt-only and
            # desktopApp-only dependency lands here. Reporting it as a finding
            # would train the reader to ignore the list, which is what the real
            # findings below are for.
            print(
                f"{len(skipped_unmeasured)} declared dependencies resolve in other source sets "
                f"only (tests, detekt, other modules) — not examined here"
            )

    if findings:
        found_coordinates = {coordinate for coordinate, _, _ in findings}
        allowlist, ceiling = load_allowlist()
        unexpected = sorted(found_coordinates - allowlist)
        stale = sorted(allowlist - found_coordinates)

        if unexpected:
            print(
                f"\n{len(unexpected)} declared dependencies contribute packages nothing "
                f"imports and are not in the allowlist:\n",
                file=sys.stderr,
            )
            for coordinate in unexpected:
                print(f"  {coordinate}", file=sys.stderr)
            print(
                "\nRemove the dependency, or — if it is genuinely used through a "
                "reflection lookup, a service loader, or an annotation processor — add it "
                "to scripts/dependency-usage-allowlist.txt with the reason it cannot be "
                "seen by an import.",
                file=sys.stderr,
            )
            return 1

        if ceiling is not None and len(allowlist) > ceiling:
            print(
                f"\nthe allowlist grew from {ceiling} to {len(allowlist)} entries. "
                f"Exempting a dependency is not the same as removing it: either delete "
                f"the dependency (and its line here), or state the exemption and raise "
                f"scripts/dependency-usage-allowlist.ceiling in the same commit — with a "
                f"reason, not a number.",
                file=sys.stderr,
            )
            return 1

        if stale:
            # Not a failure: a dependency that became used again, or a typo'd
            # coordinate, should not hold the gate red. Worth saying out loud,
            # because a stale line is indistinguishable from a not-yet-checked one.
            print(
                f"\nnote: {len(stale)} allowlist entries no longer match any finding — "
                f"the dependency is now used, or the coordinate is wrong: "
                f"{', '.join(stale)}",
                file=sys.stderr,
            )

        if not args.quiet:
            print(
                f"no unexpected unused dependencies "
                f"({len(findings)} known, allowlisted, tracked in #211)"
            )
        return 0

    if not args.quiet:
        print("no unused dependencies")
    return 0


if __name__ == "__main__":
    sys.exit(main())