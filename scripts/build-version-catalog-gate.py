#!/usr/bin/env python3
"""
Build version catalog gate.

Hardcoded `group:artifact:version` literals in *.gradle.kts are a code smell.
Every dependency must come from gradle/libs.versions.toml (the single source of
truth). A literal that bypasses the catalog drifts silently: the version falls out
of step with the catalog's alignment rules, and the next dependency audit misses it.

This gate detects that drift class — a literal that SHOULD be in the catalog
but isn't — before it lands. It does NOT flag:
  - Plugin version literals in plugins {} blocks (version refs are resolved from
    the catalog already; the plugins {} block is the canonical place for them)
  - Entries in gradle/libs.versions.toml itself (they ARE the catalog)
  - Files under /detekt-rules/ (builds its own plugin-classpath without the catalog)
  - Files under /buildSrc/ (convention plugins, same)
  - Gradle's own .gradle/ and build/ output directories

It also checks the one place a version *must* be a literal: settings.gradle.kts cannot
read the version catalog, so a settings-applied plugin carries its version inline. That
is a real drift risk with no compiler behind it, so the gate compares the literal against
gradle/libs.versions.toml and fails when they disagree. The first case is Kover, which is
applied at settings level to aggregate coverage across projects.

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
        return
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

    if not violations:
        if not quiet:
            print(f"OK: scanned {len(gradle_files)} files, 0 hardcoded version literals.")
        return 0

    print(f"Found {len(violations)} hardcoded version literal(s) in {len(gradle_files)} files:")
    for path, lit in violations:
        try:
            rel = path.relative_to(workspace)
        except ValueError:
            rel = path
        print(f"  {rel}: {lit}")
    print("\nFix: add the library to gradle/libs.versions.toml and reference via libs.*")
    return 1


if __name__ == "__main__":
    sys.exit(main())
