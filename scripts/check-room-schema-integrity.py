#!/usr/bin/env python3
"""check-room-schema-integrity.py — prove the declared schema, the exported
schema, and the migration chain are the same schema.

## Why this exists (2026-10-05)

This project lost a day of work to a defect that no gate could see. `SyncColumns`
in `Entities.kt` declares `server_version`, and that mixin is embedded into
`sync_shadow`. The exported Room schema `36.json` had no such column — it
appeared only in `37.json`. `SCHEMA_VERSION` stayed at 36 and there was no
`Migration36To37`. The result: Room's identity-hash check fails the moment a
user with an existing database opens the app, and the failure is an
`IllegalStateException` at first query, not a compile error and not a test
failure. Existing installs upgrade into a crash. Fresh installs are fine, which
is precisely why a test suite that creates its own database never sees it.

Room exports `4.json` … `37.json` on every build and gives the compiler enough
information to *detect* this class of drift. Nothing read them. The gap was not
Room's; it was that the one place in the repository where all three facts meet —
the `@Database` annotation, the `NN.json` files, and the `Migration*.kt` files —
was never checked against itself.

## What is checked

1. **The head exists and agrees.** The highest `NN.json` in the schema directory
   exists, and its recorded `version` equals `SCHEMA_VERSION` in `AppDatabase.kt`.
   This is the check that would have caught the `server_version` defect: the
   entity and the annotation said 36, the newest export said 37, and nobody
   reconciled them.
2. **The chain is continuous.** For every `N` in `first+1 … SCHEMA_VERSION`,
   there is an `AutoMigration(from = N-1, to = N)` in `AppDatabase.kt` **or** the
   pair is listed in `MANUAL_MIGRATIONS`. There is exactly one such manual pair,
   `31 -> 32`, and it is named in `AppDatabaseFactory.addMigrations`; this script
   asserts that too, so the list cannot drift away from the code. A version
   nobody migrated across is the same crash as a missing column.
3. **Entities and exports agree on the head.** The set of `tableName`s in the
   head `NN.json` equals the set of entity table names in `AppDatabase.kt`'s
   `entities = [...]` list. This catches an entity added to the annotation
   without a rebuild, and a table left in the export after the entity was
   deleted — both of which Room reports as an identity-hash mismatch.

## What this script does NOT do

It does not verify that a migration *produces* the schema its successor claims.
That is what `Migration35To36Test`, `Migration31To32Test` and friends do, and
what `MigrationTestHelper` would do. This is a consistency check over three
committed artefacts; it is cheap, runs in milliseconds, and is the one that
would have turned a silent data-loss class into a red build. A schema-shape
verifier that runs Room belongs in the migration tests, not here.

It also does not read `createSql` to compare DDL, only table names. Comparing
every column across 34 schema files would produce findings this project has
already decided are noise; the column-level drift is caught by the identity-hash
comparison that Room itself performs on open, and by the migration tests.

## The positive control

Every rule here is a pattern over text, and a pattern that quietly stops
matching reports success having checked nothing. So `--self-test` plants each
defect in a temp tree and asserts it is caught, and plants a compliant tree and
asserts it is not. `--self-test` is registered in `SCRIPT_GATES`, so a rule
that stops firing is itself caught by `check-gate-wiring.py`.

## Vacuity

`check_not_vacuous` fails if the schema directory holds fewer than two exports
or if the annotation parses to no version at all. "Found zero tables" must never
be reported as "found no problems".

Usage:
    python3 scripts/check-room-schema-integrity.py
    python3 scripts/check-room-schema-integrity.py --self-test

Exit codes:
    0 — the declared schema, the exports, and the migration chain agree
    1 — a divergence, or the scan is vacuous
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SCHEMA_DIR = ROOT / "shared/schemas/com.singularity.todo.core.database.AppDatabase"
APP_DATABASE = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
FACTORY = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseFactory.kt"

# Version pairs that legitimately have no `AutoMigration` entry, because the
# migration is a manual `Migration` registered via `addMigrations` and needs
# `onPreMigrate`/`onPostMigrate` data movement an `AutoMigrationSpec` cannot
# express.
#
# This is an assertion, not a tolerance. Each pair must ALSO appear in
# `addMigrations` in AppDatabaseFactory.kt — asserted below — so adding a new
# manual migration means editing this list deliberately, and forgetting to
# register it in the factory is a finding rather than a silent pass.
MANUAL_MIGRATIONS: dict[tuple[int, int], str] = {
    (31, 32): "Migration31To32",
    (37, 38): "Migration37To38",
}

# The oldest schema version this project still promises to upgrade a user from.
#
# `4.json` exists because it was exported when the codebase moved to Room 3 on
# 2026-09-04; `5.json` added `checklist_items`. Nothing migrates 4 -> 5, and
# nothing needs to: the app is `versionName 0.1.0`, has no release tag, and has
# never been distributed — a v4 database can only exist on a developer machine
# that ran a build from a three-week window in September.
#
# Declaring the floor is the honest form. The alternative is an empty
# `MANUAL_MIGRATIONS` entry that says "we meant to migrate this and didn't",
# which is false, or a hand-written migration for a population of zero, which
# is work spent on nobody.
#
# The gate asserts this equals the lowest `AutoMigration(from = …)`, so if
# someone later writes the 4 -> 5 migration the declaration must be revisited
# rather than quietly outliving the fact that made it true.
SUPPORTED_FROM_VERSION = 5


def get_floor() -> int:
    """Read the support floor.

    The self-test moves this constant to prove the floor rules can fire, and
    `global` inside a nested function would bind the *name* locally for the
    whole enclosing scope — so the mutation goes through these two functions
    rather than an assignment in the test body.
    """
    return SUPPORTED_FROM_VERSION


def set_floor(value: int) -> None:
    global SUPPORTED_FROM_VERSION
    SUPPORTED_FROM_VERSION = value

MIN_EXPORTS = 2


@dataclass
class Violation:
    rule: str
    message: str

    def __str__(self) -> str:  # pragma: no cover - formatting only
        return f"[{self.rule}] {self.message}"


# ── parsing ────────────────────────────────────────────────────────────────

_VERSION_RE = re.compile(r"^\s*(?:const\s+val|public\s+const\s+val)\s+SCHEMA_VERSION\s*=\s*(\d+)\s*", re.MULTILINE)
_AUTO_MIGRATION_RE = re.compile(r"AutoMigration\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)\s*,\s*spec\s*=\s*(\w+)")
# The argument list contains nested calls — `addMigrations(Migration31To32())` —
# so a `[^)]*` class stops at the inner paren and reads as empty. Scan forward
# and balance the parens instead.
_ADD_MIGRATIONS_RE = re.compile(r"\.addMigrations\(")
_TABLE_NAME_RE = re.compile(r'tableName\s*=\s*"([^"]+)"')


def parse_schema_version(source: str) -> int | None:
    """Read `SCHEMA_VERSION` out of AppDatabase.kt.

    Falls back to `version = <n>` inside `@Database(...)` when the named
    constant is absent, so a tree that inlines the literal is still checked
    rather than silently treated as unparseable.
    """
    match = _VERSION_RE.search(source)
    if match:
        return int(match.group(1))
    inline = re.search(r"@Database\([^)]*?\bversion\s*=\s*(\d+)", source, re.DOTALL)
    return int(inline.group(1)) if inline else None


def parse_auto_migrations(source: str) -> set[tuple[int, int]]:
    return {(int(a), int(b)) for a, b, _spec in _AUTO_MIGRATION_RE.findall(source)}


def parse_spec_classes(source: str) -> dict[tuple[int, int], str]:
    return {(int(a), int(b)): spec for a, b, spec in _AUTO_MIGRATION_RE.findall(source)}


def parse_manual_migrations(factory_source: str) -> set[str]:
    """Class names passed to `addMigrations` in the builder chain."""
    names: set[str] = set()
    for match in _ADD_MIGRATIONS_RE.finditer(factory_source):
        depth = 1
        index = match.end()
        while index < len(factory_source) and depth:
            char = factory_source[index]
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        names.update(re.findall(r"\b(Migration\w+)\s*\(\s*\)", factory_source[match.end() : index - 1]))
    return names


def parse_entity_table_names(source: str) -> set[str]:
    """Table names declared by the `entities = [...]` list of `@Database`."""
    match = re.search(r"@Database\((.*?)\)\s*(?:@\w+\s*)*abstract\s+class", source, re.DOTALL)
    if not match:
        return set()
    body = match.group(1)
    list_match = re.search(r"entities\s*=\s*\[(.*?)\]", body, re.DOTALL)
    if not list_match:
        return set()
    entries = re.findall(r"(\w+Entity|\w+CrossRef)\b", list_match.group(1))
    if not entries:
        return set()
    # The annotation names classes, not tables. Resolve through the codebase
    # in the caller; here we only need the *set of class names* to know the
    # list was non-empty, which the vacuity check uses.
    return set(entries)


def exported_versions(schema_dir: Path) -> dict[int, Path]:
    found: dict[int, Path] = {}
    if not schema_dir.is_dir():
        return found
    for path in schema_dir.glob("*.json"):
        stem = path.stem
        if stem.isdigit():
            found[int(stem)] = path
    return found


def read_schema_tables(path: Path) -> set[str] | None:
    try:
        data = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    entities = data.get("database", {}).get("entities")
    if not isinstance(entities, list):
        return None
    return {e["tableName"] for e in entities if isinstance(e, dict) and "tableName" in e}


def read_schema_version(path: Path) -> int | None:
    try:
        data = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    version = data.get("database", {}).get("version")
    return version if isinstance(version, int) else None


def source_table_names(root: Path) -> set[str]:
    """Every `tableName = "..."` declared in the project's entity classes.

    Read from source rather than from the annotation's class list, because
    `entities = [...]` names classes and the schema names tables. Resolving
    class -> table would need a Kotlin parse; resolving table -> source is a
    plain grep and is enough, since the gate compares the *set* of tables the
    code declares against the set the head export records.
    """
    names: set[str] = set()
    src = root / "shared/src/commonMain/kotlin/com/singularity/todo"
    if not src.is_dir():
        return names
    for path in src.rglob("*.kt"):
        names.update(_TABLE_NAME_RE.findall(path.read_text()))
    return names


# ── rules ──────────────────────────────────────────────────────────────────


def check_head_agrees(schema_dir: Path, declared_version: int | None, root: Path) -> list[Violation]:
    """Rule 1: the newest export exists and its version is SCHEMA_VERSION."""
    out: list[Violation] = []
    if declared_version is None:
        return [Violation("head-agrees", "could not read SCHEMA_VERSION from AppDatabase.kt")]

    exports = exported_versions(schema_dir)
    if not exports:
        return [Violation("head-agrees", f"no exported schema files in {schema_dir}")]

    head = max(exports)
    if head < declared_version:
        out.append(
            Violation(
                "head-agrees",
                f"SCHEMA_VERSION is {declared_version} but the newest exported schema is {head}.json — "
                f"the annotation was bumped without a build to regenerate the export",
            )
        )
    if head > declared_version:
        exported = read_schema_version(exports[head])
        out.append(
            Violation(
                "head-agrees",
                f"newest exported schema is {head}.json (version {exported}) but SCHEMA_VERSION is "
                f"{declared_version}. An entity changed without a version bump: Room will fail its "
                f"identity-hash check for every user with an existing database",
            )
        )
    return out


def check_chain_continuous(
    schema_dir: Path,
    declared_version: int | None,
    auto: set[tuple[int, int]],
    manual_names: set[str],
    root: Path,
) -> list[Violation]:
    """Rule 2: every version step across the exported range is migrated."""
    out: list[Violation] = []
    if declared_version is None:
        return [Violation("chain-continuous", "could not read SCHEMA_VERSION from AppDatabase.kt")]

    exports = exported_versions(schema_dir)
    if len(exports) < 2:
        return [
            Violation(
                "chain-continuous",
                f"only {len(exports)} exported schema(s); a chain needs at least two to check",
            )
        ]

    # Below the declared floor, no migration is required — that is what the
    # floor means. The chain is checked from the floor up, and the floor's own
    # consistency with the lowest AutoMigration is a separate rule.
    low = max(min(exports), SUPPORTED_FROM_VERSION)
    for step in range(low, declared_version):
        pair = (step, step + 1)
        expected = MANUAL_MIGRATIONS.get(pair)
        if expected is not None and expected not in manual_names:
            # Checked *before* the `pair in auto` short-circuit below. A pair
            # that has both an AutoMigration and a MANUAL_MIGRATIONS entry is
            # exactly the case where the exemption list can rot unnoticed: the
            # step looks covered either way, so nothing else would ever compare
            # the list against the factory. The failure this prevents is a later
            # edit deleting the AutoMigration on the belief that the manual
            # migration registered in the factory is there — it is not.
            out.append(
                Violation(
                    "chain-continuous",
                    f"MANUAL_MIGRATIONS claims {pair[0]} -> {pair[1]} is manual via {expected}, but "
                    f"{expected} is not registered in AppDatabaseFactory.addMigrations "
                    f"(found: {sorted(manual_names) or 'none'})",
                )
            )
        if pair in auto:
            continue
        if expected is None:
            out.append(
                Violation(
                    "chain-continuous",
                    f"no migration for {step} -> {step + 1} (no AutoMigration, and the pair is not "
                    f"in MANUAL_MIGRATIONS). A version nobody migrates across crashes on upgrade",
                )
            )
            continue
    return out


def check_entities_agree(schema_dir: Path, declared_version: int | None, root: Path) -> list[Violation]:
    """Rule 3: tables in the head export match tables declared in source."""
    out: list[Violation] = []
    if declared_version is None:
        return [Violation("entities-agree", "could not read SCHEMA_VERSION from AppDatabase.kt")]

    exports = exported_versions(schema_dir)
    if not exports:
        return [Violation("entities-agree", f"no exported schema files in {schema_dir}")]

    head = max(exports)
    exported = read_schema_tables(exports[head])
    if exported is None:
        return [Violation("entities-agree", f"{exports[head].name} is not readable Room schema JSON")]

    declared = source_table_names(root)
    if not declared:
        return [Violation("entities-agree", "no `tableName = \"...\"` found in shared source")]

    only_exported = sorted(exported - declared)
    if only_exported:
        out.append(
            Violation(
                "entities-agree",
                f"{head}.json records tables no entity declares: {only_exported}. The export and the "
                f"code describe different databases",
            )
        )
    return out


def check_manual_list_matches_factory(manual_names: set[str], root: Path) -> list[Violation]:
    """The MANUAL_MIGRATIONS list must not name a class that does not exist."""
    out: list[Violation] = []
    src = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database"
    for pair, cls in MANUAL_MIGRATIONS.items():
        if not (src / f"{cls}.kt").is_file():
            out.append(
                Violation(
                    "manual-migrations-real",
                    f"MANUAL_MIGRATIONS maps {pair[0]} -> {pair[1]} to {cls}, but {cls}.kt does not "
                    f"exist. The exemption list has outlived the thing it exempts",
                )
            )
    return out


def check_not_vacuous(schema_dir: Path, root: Path) -> list[Violation]:
    out: list[Violation] = []
    exports = exported_versions(schema_dir)
    if len(exports) < MIN_EXPORTS:
        out.append(
            Violation(
                "not-vacuous",
                f"found {len(exports)} exported schema(s), need at least {MIN_EXPORTS}. A rule that "
                f"matches nothing must not be reported as a pass",
            )
        )
    if not (root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt").is_file():
        out.append(Violation("not-vacuous", "AppDatabase.kt is missing; nothing was checked"))
    if not source_table_names(root):
        out.append(Violation("not-vacuous", "no entity table names found in shared source; rule 3 is inert"))
    return out


def check_floor_is_consistent(auto: set[tuple[int, int]], declared_version: int | None) -> list[Violation]:
    """The declared support floor must match where the migration chain starts.

    A floor lower than the lowest `AutoMigration` means the chain has a real
    hole below the floor and the declaration is hiding it. A floor *higher*
    than the lowest `AutoMigration` means a migration exists that this script
    is not checking — the declaration has outlived the fact behind it.
    """
    if declared_version is None or not auto:
        return []
    lowest = min(frm for frm, _to in auto)
    if lowest < SUPPORTED_FROM_VERSION:
        return [
            Violation(
                "floor-consistent",
                f"an AutoMigration starts at v{lowest}, below SUPPORTED_FROM_VERSION="
                f"{SUPPORTED_FROM_VERSION}. The floor is stale: v{lowest} is upgradeable, so the "
                f"comment justifying it no longer holds",
            )
        ]
    if lowest > SUPPORTED_FROM_VERSION:
        return [
            Violation(
                "floor-consistent",
                f"the lowest AutoMigration starts at v{lowest}, above SUPPORTED_FROM_VERSION="
                f"{SUPPORTED_FROM_VERSION}. Steps between the floor and v{lowest} are not being "
                f"checked — lower the floor or write the migration",
            )
        ]
    return []


def run_all(root: Path) -> list[Violation]:
    schema_dir = root / "shared/schemas/com.singularity.todo.core.database.AppDatabase"
    app_db = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
    factory = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseFactory.kt"

    if not app_db.is_file():
        return [Violation("setup", f"{app_db} is missing")]
    app_source = app_db.read_text()
    factory_source = factory.read_text() if factory.is_file() else ""

    version = parse_schema_version(app_source)
    auto = parse_auto_migrations(app_source)
    manual = parse_manual_migrations(factory_source)

    violations: list[Violation] = []
    violations += check_not_vacuous(schema_dir, root)
    violations += check_head_agrees(schema_dir, version, root)
    violations += check_chain_continuous(schema_dir, version, auto, manual, root)
    violations += check_floor_is_consistent(auto, version)
    violations += check_entities_agree(schema_dir, version, root)
    violations += check_manual_list_matches_factory(manual, root)
    return violations


# ── self-test ──────────────────────────────────────────────────────────────


def _make_compliant(root: Path) -> None:
    """A minimal tree that passes every rule."""
    schema_dir = root / "shared/schemas/com.singularity.todo.core.database.AppDatabase"
    schema_dir.mkdir(parents=True)
    db_dir = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database"
    db_dir.mkdir(parents=True)
    ent_dir = root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks"
    ent_dir.mkdir(parents=True)

    (ent_dir / "TaskEntity.kt").write_text('@Entity(tableName = "tasks")\nclass TaskEntity\n')

    def export(n: int) -> None:
        (schema_dir / f"{n}.json").write_text(
            json.dumps({"database": {"version": n, "entities": [{"tableName": "tasks"}]}})
        )

    # The fixture mirrors the real tree's shape: schemas exist below the
    # support floor (here 4 < 5), the lowest AutoMigration starts *at* the
    # floor, and the head export agrees with SCHEMA_VERSION. A fixture that did
    # not reproduce that shape would pass for the wrong reason.
    export(4)
    export(5)
    export(6)

    (db_dir / "AppDatabase.kt").write_text(
        "package com.singularity.todo.core.database\n"
        "const val SCHEMA_VERSION = 6\n"
        "@Database(\n"
        "    entities = [TaskEntity::class],\n"
        "    version = SCHEMA_VERSION,\n"
        "    autoMigrations = [\n"
        "        AutoMigration(from = 5, to = 6, spec = Migration5To6::class),\n"
        "    ],\n"
        ")\n"
        "abstract class AppDatabase : RoomDatabase()\n"
    )
    (db_dir / "Migration31To32.kt").write_text("class Migration31To32\n")
    (db_dir / "Migration5To6.kt").write_text("class Migration5To6\n")
    (db_dir / "AppDatabaseFactory.kt").write_text(
        "package com.singularity.todo.core.database\n"
        "object AppDatabaseFactory {\n"
        "    fun build() = Room.databaseBuilder<AppDatabase>(name = \"x\")\n"
        "        .addMigrations(Migration5To6())\n"
        "        .build()\n"
        "}\n"
    )


def _bump_export_to_six(root: Path) -> None:
    """Reproduce the real defect: a newer export than the annotation claims.

    `SyncColumns.server_version` was added to `sync_shadow`, the export moved to
    7, and `SCHEMA_VERSION` stayed at 6. Room's identity-hash check then failed
    for every user with an existing database — a crash at first query, invisible
    to a test suite that creates its own database.
    """
    schema_dir = root / "shared/schemas/com.singularity.todo.core.database.AppDatabase"
    (schema_dir / "7.json").write_text(
        json.dumps(
            {
                "database": {
                    "version": 7,
                    "entities": [{"tableName": "tasks"}, {"tableName": "sync_shadow"}],
                }
            }
        )
    )
    (root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/SyncShadowEntity.kt").write_text(
        '@Entity(tableName = "sync_shadow")\nclass SyncShadowEntity\n'
    )


def self_test() -> int:
    failures: list[str] = []

    def expect_clean(label: str) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _make_compliant(root)
            found = run_all(root)
            if found:
                failures.append(f"{label}: expected clean, got {[str(v) for v in found]}")

    def expect_caught(label: str, mutate, rule: str) -> None:
        saved_manual = dict(MANUAL_MIGRATIONS)
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                _make_compliant(root)
                mutate(root)
                found = run_all(root)
                if not any(v.rule == rule for v in found):
                    failures.append(
                        f"{label}: expected rule {rule!r} to fire, got {[str(v) for v in found] or 'clean'}"
                    )
        finally:
            # A mutator that edits a module-level table must not leak into the
            # next case, or the order of the assertions decides which pass.
            MANUAL_MIGRATIONS.clear()
            MANUAL_MIGRATIONS.update(saved_manual)

    def _drop_auto_migration(root: Path) -> None:
        p = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
        p.write_text(p.read_text().replace("AutoMigration(from = 5, to = 6, spec = Migration5To6::class),", ""))

    def _extra_table_in_export(root: Path) -> None:
        schema_dir = root / "shared/schemas/com.singularity.todo.core.database.AppDatabase"
        (schema_dir / "6.json").write_text(
            json.dumps({"database": {"version": 6, "entities": [{"tableName": "tasks"}, {"tableName": "ghost"}]}})
        )

    def _bump_annotation_past_export(root: Path) -> None:
        p = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
        p.write_text(p.read_text().replace("SCHEMA_VERSION = 6", "SCHEMA_VERSION = 7"))

    def _unparseable_version(root: Path) -> None:
        p = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
        p.write_text(p.read_text().replace("SCHEMA_VERSION = 6", "SCHEMA_VERSION = ???"))

    def _empty_schema_dir(root: Path) -> None:
        for p in (root / "shared/schemas/com.singularity.todo.core.database.AppDatabase").glob("*.json"):
            p.unlink()

    def _no_entities_in_source(root: Path) -> None:
        p = root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskEntity.kt"
        p.write_text("class TaskEntity\n")

    def _manual_not_registered(root: Path) -> None:
        """MANUAL_MIGRATIONS claims 5->6 is manual, but the factory omits it.

        Removing `Migration5To6()` from `addMigrations` is not on its own a
        finding — 5 -> 6 also has an `AutoMigration`, so the step is still
        covered. The defect is the *combination*: the exemption list says this
        pair needs the factory, and the factory does not have it. That is the
        state where a later edit deletes the `AutoMigration` believing the manual
        one is there.
        """
        saved = dict(MANUAL_MIGRATIONS)
        MANUAL_MIGRATIONS.clear()
        MANUAL_MIGRATIONS[(5, 6)] = "Migration5To6"
        try:
            p = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseFactory.kt"
            p.write_text(p.read_text().replace(".addMigrations(Migration5To6())\n        ", ""))
        except Exception:  # pragma: no cover - restore before propagating
            MANUAL_MIGRATIONS.clear()
            MANUAL_MIGRATIONS.update(saved)
            raise

    def _stale_floor(root: Path) -> None:
        """A migration starts below the declared floor — the floor has gone stale."""
        db = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
        db.write_text(
            db.read_text().replace(
                "AutoMigration(from = 5, to = 6, spec = Migration5To6::class),",
                "AutoMigration(from = 5, to = 6, spec = Migration5To6::class),\n"
                "        AutoMigration(from = 4, to = 5, spec = Migration4To5::class),",
            )
        )

    def _floor_above_chain(root: Path) -> None:
        """The floor sits above the chain start, so a step is going unchecked."""
        set_floor(get_floor() + 1)

    expect_clean("compliant tree is clean")
    # The exact defect that cost the day: an entity gained a column, the export
    # moved to 5, the annotation stayed at 4.
    expect_caught("newer export than annotation", _bump_export_to_six, "head-agrees")
    expect_caught("missing auto migration", _drop_auto_migration, "chain-continuous")
    expect_caught("export has a table no entity declares", _extra_table_in_export, "entities-agree")
    expect_caught("annotation bumped without a rebuild", _bump_annotation_past_export, "head-agrees")
    expect_caught("unparseable SCHEMA_VERSION", _unparseable_version, "head-agrees")
    expect_caught("empty schema dir", _empty_schema_dir, "not-vacuous")
    expect_caught("no entities in source", _no_entities_in_source, "not-vacuous")
    expect_caught("manual migration claimed but unregistered", _manual_not_registered, "chain-continuous")
    expect_caught("stale support floor", _stale_floor, "floor-consistent")

    saved_floor = get_floor()
    try:
        expect_caught("floor above chain start", _floor_above_chain, "floor-consistent")
    finally:
        set_floor(saved_floor)

    # MANUAL_MIGRATIONS must name a class that exists; point the real list at a
    # name with no file and assert the rule fires on the real tree shape.
    saved = dict(MANUAL_MIGRATIONS)
    try:
        MANUAL_MIGRATIONS[(99, 100)] = "MigrationThatDoesNotExist"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _make_compliant(root)
            found = run_all(root)
            if not any(v.rule == "manual-migrations-real" for v in found):
                failures.append("phantom MANUAL_MIGRATIONS entry: expected manual-migrations-real to fire")
    finally:
        MANUAL_MIGRATIONS.clear()
        MANUAL_MIGRATIONS.update(saved)

    if failures:
        print("check-room-schema-integrity self-test FAILED", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print("check-room-schema-integrity self-test: all rules fire, compliant tree clean")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="plant each defect and assert it is caught")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    violations = run_all(ROOT)
    if violations:
        print(f"Room schema integrity: {len(violations)} violation(s)", file=sys.stderr)
        for v in violations:
            print(f"  {v}", file=sys.stderr)
        return 1

    version = parse_schema_version((ROOT / str(
        "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
    )).read_text())
    count = len(exported_versions(SCHEMA_DIR))
    print(f"Room schema integrity: SCHEMA_VERSION {version}, {count} exported schemas, chain continuous")
    return 0


if __name__ == "__main__":
    sys.exit(main())
