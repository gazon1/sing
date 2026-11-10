#!/usr/bin/env python3
"""
Build version catalog gate.

Hardcoded `group:artifact:version` literals in *.gradle.kts are a code smell.
Every dependency must come from gradle/libs.versions.toml (the single source of
truth). A literal that bypasses the catalog drifts silently: the version falls out
of step with the catalog's alignment rules, and the next dependency audit misses it.

This gate detects that drift class — a literal that SHOULD be in the catalog
but isn't — before it lands. It does NOT flag:
  - Plugin version literals in plugins {} blocks that use `alias(libs.plugins.xxx)`
    (version refs are resolved from the catalog; the plugins {} block is canonical)
  - Entries in gradle/libs.versions.toml itself (they ARE the catalog)
  - Files under /detekt-rules/ (builds its own plugin-classpath without the catalog)
  - Files under /buildSrc/ (convention plugins, same)
  - Gradle's own .gradle/ and build/ output directories

It also checks the one place a version *must* be a literal: settings.gradle.kts cannot
read the version catalog, so a settings-applied plugin carries its version inline. That
is a real drift risk with no compiler behind it, so the gate compares the literal against
gradle/libs.versions.toml and fails when they disagree. The first case is Kover, which is
applied at settings level to aggregate coverage across projects.

B2 extension (2026-10-08): also checks gradle.properties version keys against the
catalog, and checks plugin literals in root build.gradle.kts plugins {} against the
catalog — the two remaining places where version literals can silently drift.

2026-11-10: gradle.properties no longer holds any version — the catalog owns them
and the build reads them through VersionCatalogsExtension. The check became a
duplication check (the property must not exist), and a detekt↔Kotlin compatibility
table was added: detekt refuses to run against any Kotlin it was not compiled
with, so a bump the table does not cover is rejected here with a legible message
instead of failing deep inside the detekt task.

Pattern: mirror scripts/find-unwired-surfaces.py — --quiet for CI, positive
control that the scan covered ≥MIN_SCAN files (vacuous green is worse than red).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

EXCLUDE_PREFIXES = (
    "detekt-rules/",
    "buildSrc/",
    ".gradle/",
    "build/",
)
# group:artifact:version where version starts with a digit (filters out plugin refs)
LITERAL_RE = re.compile(r"\b[\w.-]+\:[\w-]+\:\d[\w.-]*")
MIN_SCAN = 5

# Plugin id -> catalog version key, for plugins applied in settings.gradle.kts.
# A settings script has no access to the version catalog, so these literals are
# unavoidable; the gate's job is to stop them drifting from the catalog.
SETTINGS_PLUGIN_CATALOG_KEYS = {
    "org.jetbrains.kotlinx.kover.aggregation": "kover",
}
SETTINGS_PLUGIN_RE = re.compile(
    r'id\("(?P<id>[\w.\-]+)"\)\s+version\s+"(?P<version>[^"]+)"'
)
# B2: plugin literals in root build.gradle.kts plugins {} that are NOT alias().
# Key = plugin id (exact string), value = catalog version key.
ROOT_PLUGINS_CATALOG_KEYS = {
    "io.insert-koin.compiler.plugin": "koin-compiler-plugin",
}
ROOT_PLUGINS_RE = re.compile(
    r'id\("(?P<id>[\w.\-]+)"\)\s+version\s+"(?P<version>[^"]+)"'
)
# B2: gradle.properties version keys that the version catalog owns.
# They used to live in BOTH places. A dependabot bump moves the catalog and leaves
# the property behind, and the two then disagree — that is the mismatch that
# red-lined main. The catalog is the single source of truth, so the property is
# not a second source, it is a duplicate one: it must not exist.
#
# The rule is the PREFIX, not a list of keys: a list would only catch the three
# versions that were there when the rule was written, and the next one to be
# added would slip through. Every `version.*` property is a version the catalog
# owns, and the ones this repository still uses are named here so that the
# message can say which catalog entry the property duplicates.
GRADLE_PROPS_FORBIDDEN_PREFIX = "version."
GRADLE_PROPS_CATALOG_KEYS = {
    "version.kotlin": "kotlin",
    "version.kotlinSerialization": "kotlin-serialization",
    "version.kotlinxCollectionsImmutable": "kotlinxCollectionsImmutable",
}
# The Kotlin version each detekt build was compiled with. detekt refuses to run
# against any other one ("detekt was compiled with Kotlin X but is currently
# running with Y"), so a Kotlin bump that this table does not cover cannot be
# merged as it stands.
#
# A detekt version that is missing here fails the gate on purpose: bumping detekt
# is a decision that has to name the Kotlin it was built with, not a silent
# consequence of a version range.
DETEKT_KOTLIN_COMPATIBILITY = {
    "2.0.0-alpha.6": "2.4.10",
}
GRADLE_PROPS_VERSION_RE = re.compile(
    r"^(?P<key>[^#\s][^\s=]*)\s*=\s*[\"\']?(?P<value>[\w.\-]+)[\"\']?"
)
VERSION_ENTRY_RE = re.compile(r"^(?P<key>\S+)\s*=\s*\"(?P<value>[^\"]+)\"")



def _catalog_versions(workspace: Path) -> dict[str, str]:
    """version key -> value, from the [versions] table of the catalog."""
    catalog = workspace / "gradle" / "libs.versions.toml"
    versions: dict[str, str] = {}
    if not catalog.is_file():
        return versions
    for line in catalog.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("[") or "=" not in line:
            continue
        m = VERSION_ENTRY_RE.match(line)
        if m:
            versions[m.group("key")] = m.group("value")
    return versions


def _settings_plugin_drift(workspace: Path):
    """settings-applied plugin versions must equal their catalog entry."""
    settings = workspace / "settings.gradle.kts"
    if not settings.is_file():
        return []
    versions = _catalog_versions(workspace)
    if not versions:
        print(
            "ERROR: gradle/libs.versions.toml not found or has no [versions] entries — "
            "the settings-plugin drift check would pass vacuously.",
            file=sys.stderr,
        )
        return [("settings.gradle.kts", "no catalog versions to compare against")]

    out: list[tuple[Path, str]] = []
    for m in SETTINGS_PLUGIN_RE.finditer(settings.read_text(encoding="utf-8")):
        plugin_id = m.group("id")
        key = SETTINGS_PLUGIN_CATALOG_KEYS.get(plugin_id)
        if key is None:
            continue
        expected = versions.get(key)
        if expected is None:
            out.append((settings, f"{plugin_id}: no `{key}` entry in the catalog"))
        elif expected != m.group("version"):
            out.append((
                settings,
                f"{plugin_id} version {m.group('version')} != libs.versions.toml "
                f"{key} = \"{expected}\"",
            ))
    return out


def _root_plugins_drift(workspace: Path):
    """B2: root build.gradle.kts plugin literals must match catalog entries."""
    root_gradle = workspace / "build.gradle.kts"
    if not root_gradle.is_file():
        return []
    versions = _catalog_versions(workspace)
    if not versions:
        return [("build.gradle.kts", "no catalog versions to compare against")]

    # Extract the plugins {} block
    content = root_gradle.read_text(encoding="utf-8")
    block_m = re.search(r"^plugins\s*\{(.*?)\n\}", content, re.MULTILINE | re.DOTALL)
    if not block_m:
        return []
    block = block_m.group(1)

    out: list[tuple[Path, str]] = []
    for m in ROOT_PLUGINS_RE.finditer(block):
        plugin_id = m.group("id")
        key = ROOT_PLUGINS_CATALOG_KEYS.get(plugin_id)
        if key is None:
            continue
        expected = versions.get(key)
        if expected is None:
            out.append((root_gradle, f"{plugin_id}: no `{key}` entry in the catalog"))
        elif expected != m.group("version"):
            out.append((
                root_gradle,
                f"{plugin_id} version {m.group('version')} != libs.versions.toml "
                f"{key} = \"{expected}\"",
            ))
    return out


def _gradle_properties_duplication(workspace: Path):
    """The version catalog owns these versions; gradle.properties must not repeat them."""
    props = workspace / "gradle.properties"
    if not props.is_file():
        return []

    content = props.read_text(encoding="utf-8")
    out: list[tuple[Path, str]] = []
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = GRADLE_PROPS_VERSION_RE.match(line)
        if not m:
            continue
        key = m.group("key")
        if not key.startswith(GRADLE_PROPS_FORBIDDEN_PREFIX):
            continue
        catalog_key = GRADLE_PROPS_CATALOG_KEYS.get(key)
        catalog = f"libs.versions.toml {catalog_key}" if catalog_key else "libs.versions.toml"
        out.append((
            props,
            f"{key}={m.group('value')} duplicates {catalog} — "
            "the catalog is the single source of truth; delete the property",
        ))
    return out


def _detekt_kotlin_compatibility(workspace: Path):
    """The catalog's Kotlin version must be one the catalog's detekt was built with."""
    versions = _catalog_versions(workspace)
    if not versions:
        return [("gradle/libs.versions.toml", "no catalog versions to compare against")]

    detekt = versions.get("detekt")
    kotlin = versions.get("kotlin")
    if detekt is None or kotlin is None:
        return []

    expected = DETEKT_KOTLIN_COMPATIBILITY.get(detekt)
    if expected is None:
        return [(
            workspace / "gradle" / "libs.versions.toml",
            f"detekt {detekt} is not in DETEKT_KOTLIN_COMPATIBILITY — find the Kotlin "
            "version it was compiled with and add the entry",
        )]
    if expected != kotlin:
        return [(
            workspace / "gradle" / "libs.versions.toml",
            f"kotlin = \"{kotlin}\", but detekt {detekt} was compiled with Kotlin "
            f"{expected} and refuses any other version — bump detekt to a build "
            f"compiled with {kotlin} first, or keep Kotlin at {expected}",
        )]
    return []


def main() -> int:
    # Parse: flags anywhere, path is the first non-flag argument.
    args = sys.argv[1:]
    quiet = "--quiet" in args
    positional = [a for a in args if not a.startswith("--")]
    workspace = Path(positional[0] if positional else ".")

    gradle_files = [
        f
        for f in workspace.rglob("*.gradle.kts")
        if not any(p in str(f) for p in EXCLUDE_PREFIXES)
    ]

    if len(gradle_files) < MIN_SCAN:
        print(
            f"ERROR: only {len(gradle_files)} *.gradle.kts files scanned — "
            f"path resolution is broken (expected ≥{MIN_SCAN}). "
            "The gate would pass on zero files.",
            file=sys.stderr,
        )
        return 2

    violations: list[tuple[Path, str]] = []
    for f in gradle_files:
        for m in LITERAL_RE.finditer(f.read_text()):
            violations.append((f, m.group(0)))

    violations.extend(_settings_plugin_drift(workspace))
    violations.extend(_root_plugins_drift(workspace))
    violations.extend(_gradle_properties_duplication(workspace))
    violations.extend(_detekt_kotlin_compatibility(workspace))

    if not violations:
        if not quiet:
            print(
                f"OK: scanned {len(gradle_files)} files, 0 hardcoded version literals, "
                "no duplicated version properties, detekt and Kotlin agree."
            )
        return 0

    print(f"Found {len(violations)} version problem(s) in {len(gradle_files)} scanned files:")
    for path, lit in violations:
        try:
            rel = path.relative_to(workspace)
        except ValueError:
            rel = path
        print(f"  {rel}: {lit}")
    print(
        "\nFix: add the library to gradle/libs.versions.toml and reference it via "
        "libs.*, delete the version property from gradle.properties, and — if the "
        "message is about detekt and Kotlin — make the two versions agree."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
