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
   pair is listed in `MANUAL_MIGRATIONS`. Each such manual pair is named in
   `AppDatabaseFactory.addMigrations`; this script asserts that too, so the list
   cannot drift away from the code. A version nobody migrated across is the same
   crash as a missing column.
3. **Entities and exports agree on the head.** The set of `tableName`s in the
   head `NN.json` equals the set of entity table names in `AppDatabase.kt`'s
   `entities = [...]` list. This catches an entity added to the annotation
   without a rebuild, and a table left in the export after the entity was
   deleted — both of which Room reports as an identity-hash mismatch.
4. **The head export's columns are the entities' columns.** The tables and the
   columns the head `NN.json` declares are the ones the `@Entity` classes
   declare. Room writes the export at build time, so an entity that grew a
   column and was never re-exported is invisible until a build runs and Room's
   own validation says the schema does not match — on the second minute of CI,
   after the build, and only on the branch that changed it. This rule answers
   the same question from the sources, in milliseconds, without a build.
5. **The chain has a round-trip test.** The generated
   `MigrationRoundTripTest.kt` covers every step the chain promises to run.
   The file is generated from the exported schemas (`--write`), and this rule
   fails when the committed file is not what the generator produces — so a
   migration added without a regeneration is a red build rather than a gap
   nobody notices.

## What this script does NOT do

It does not verify that a migration *produces* the schema its successor claims.
That is what the generated round-trip test does, and what `Migration35To36Test`,
`Migration31To32Test` and friends do by hand: the round-trip test opens each
exported version through Room and lets the whole chain run, and Room validates
the schema it finds afterwards. This script is a consistency check over three
committed artefacts; it is cheap, runs in milliseconds, and is the one that
would have turned a silent data-loss class into a red build. A schema-shape
verifier that runs Room belongs in the migration tests, not here — but the test
that does it is generated and kept current from here.

It does not compare the columns *across* the 34 schema files. That is a
different question, and this project has already decided those findings are
noise: a migration is supposed to change columns, so a column that differs
between version 37 and version 38 is the chain doing its job. What rule 4
compares is the head export against the entity classes — one export and one
source of truth — which is not a diff between two histories but a question with
a right answer. (The `createSql` of an export is compared with that export's
own column list, by a separate rule: an export that disagrees with itself is
broken, whatever the entities say.)

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
    python3 scripts/check-room-schema-integrity.py --json
    python3 scripts/check-room-schema-integrity.py --write

`--json` is the output contract the `sgate` adapter reads: one object with
`passed`, `blocking_errors`, and the per-rule `violations`.

`--write` regenerates the round-trip test from the exported schemas. Run it
after a migration is added; the check above fails when the committed file is
not what the generator produces.

Exit codes:
    0 — the declared schema, the exports, and the migration chain agree
    1 — a divergence, or the scan is vacuous
"""

from __future__ import annotations

import json
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import click

# The gate suite is a package of this repository, and the `sgate` the
# interpreter finds may be an editable install of *another* checkout. This
# repository's own `scripts/src` goes in front of it, so the helper the
# generator uses is this checkout's. The same block
# `scripts/check-tools-catalog.py` uses.
_SCRIPTS_SRC = str(Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import run  # noqa: E402
from sgate.gatekit.paths import generated_path  # noqa: E402
from sgate.gatekit.room import entities as room_entities  # noqa: E402
from sgate.gatekit.scenarios import (  # noqa: E402
    MAX_FUNCTIONS_PER_CLASS,
    MAX_LINE_LENGTH,
    kotlin_convention_findings,
    load_scenarios,
    render_scenario_test,
)

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
    (38, 39): "Migration38To39",
    (42, 43): "Migration42To43",
    (44, 45): "Migration44To45",
    (45, 46): "Migration45To46",
    (46, 47): "Migration46To47",
}

# The list as it was declared, captured before any self-test plants a defect.
#
# The self-test's fixture writes a class file for every manual migration, so
# that the fixture satisfies the rule "the exemption list must not outlive the
# thing it exempts". A fixture built from the live list would also write a
# class for the *phantom* entry the self-test plants to prove that rule fires
# — and the rule would then have nothing to fire on.
DECLARED_MANUAL_MIGRATIONS: dict[tuple[int, int], str] = dict(MANUAL_MIGRATIONS)

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

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2


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


def read_schema_entities(path: Path) -> list[dict] | None:
    """The entities an export declares, or `None` when it cannot be read."""
    try:
        data = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    entities = data.get("database", {}).get("entities")
    if not isinstance(entities, list):
        return None
    return [e for e in entities if isinstance(e, dict)]


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


def mod_scenarios_dir(root: Path) -> str:
    """Where the scenario files live, relative to the root."""
    return SCENARIO_DIR_RELATIVE


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


def check_columns_agree(schema_dir: Path, declared_version: int | None, root: Path) -> list[Violation]:
    """Rule 4: the head export's columns are the columns the entities declare.

    Room writes the export at build time: KSP reads the `@Entity` classes and
    writes `NN.json`. So an entity that grew a column and was never
    re-exported is invisible until a build runs and Room's own validation says
    the schema does not match — on the second minute of CI, after the build,
    and only on the branch that changed it.

    This rule answers the same question from the sources. It compares the
    table names and the column names, and nothing else: the column's type,
    its affinity and its default are Room's business, and a rule that guessed
    at them would report differences where there are none.

    A table the code declares and the export does not have is reported too.
    That is the other half of a stale export, and the existing rule only
    catches the opposite direction.
    """
    out: list[Violation] = []
    exports = exported_versions(schema_dir)
    if not exports:
        return [Violation("columns-agree", f"no exported schema files in {schema_dir}")]

    head = max(exports)
    exported_entities = read_schema_entities(exports[head])
    if exported_entities is None:
        return [Violation("columns-agree", f"{exports[head].name} is not readable Room schema JSON")]

    declared = room_entities(root / "shared/src", "commonMain")
    if not declared:
        return [
            Violation(
                "columns-agree",
                "no @Entity classes found in shared/src/commonMain — the comparison would "
                "check nothing, which reads as a pass",
            )
        ]

    exported_columns = {
        entity["tableName"]: [
            field["columnName"]
            for field in entity.get("fields", [])
            if isinstance(field, dict) and "columnName" in field
        ]
        for entity in exported_entities
        if "tableName" in entity
    }

    for table in sorted(declared):
        entity = declared[table]
        if table not in exported_columns:
            out.append(
                Violation(
                    "columns-agree",
                    f"{head}.json has no table {table!r}, which {entity.path.name} declares. "
                    f"The export is stale — re-export the schema ({table!r} and its columns "
                    f"are missing from it)",
                )
            )
            continue
        only_declared = [c for c in entity.columns if c not in exported_columns[table]]
        only_exported = [c for c in exported_columns[table] if c not in entity.columns]
        if only_declared or only_exported:
            out.append(
                Violation(
                    "columns-agree",
                    f"table {table!r}: the entity declares {list(entity.columns)}, "
                    f"{head}.json declares {exported_columns[table]}. "
                    + (
                        f"Missing from the export: {only_declared}. "
                        if only_declared
                        else ""
                    )
                    + (
                        f"Not declared by the entity: {only_exported}. "
                        if only_exported
                        else ""
                    )
                    + "The export and the code describe different tables.",
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


# ── the generated round-trip test ─────────────────────────────────────────────
#
# The gap this gate cannot close by reading files: whether a migration
# *produces* the schema its successor claims. That is a property of running
# the chain, so it belongs in a test — and a test nobody writes is a test that
# does not exist. The list of steps is derived here, so a new migration is
# covered by regenerating, and the rule below fails when nobody did.

#: The test file this gate generates: one test per migration step.
ROUND_TRIP_TEST_RELATIVE = (
    "shared/src/jvmTest/kotlin/com/singularity/todo/core/database/MigrationRoundTripTest.kt"
)

#: The hand-written helper the generated test calls.
ROUND_TRIP_SUPPORT_RELATIVE = (
    "shared/src/jvmTest/kotlin/com/singularity/todo/core/database/MigrationRoundTripSupport.kt"
)


#: The scenario files: what the migrations do to the *data*, written as data.
SCENARIO_DIR_RELATIVE = "shared/src/jvmTest/resources/migrations"

#: The test the scenarios are generated into.
SCENARIO_TEST_RELATIVE = (
    "shared/src/jvmTest/kotlin/com/singularity/todo/core/database/"
    "GeneratedMigrationScenarioTest.kt"
)

ROUND_TRIP_HEADER = '''package com.singularity.todo.core.database

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The migration chain, one version at a time.
 *
 * GENERATED FILE — do not edit it by hand. Regenerate it with:
 *
 *     python3 scripts/check-room-schema-integrity.py --write
 *
 * The gate that owns this file fails when it is not what the generator produces,
 * so a migration that nobody regenerated for is a red build rather than a gap
 * nobody notices.
 *
 * ## What one case does
 *
 * It takes the exported schema of one version, opens that database through Room
 * and lets the whole chain run. Room validates the schema it finds after the
 * chain against the entities the code declares, so the case fails when a
 * migration produces something other than the schema its successor claims —
 * the one property no gate can establish by reading files.
 *
 * The fixture carries one row in `tasks`, so "the schema came out right" and
 * "the data is still there" are a single case.
 *
 * ## Why one parameterized case and not one method per version
 *
 * A method per version is the shape that reads best and the shape detekt
 * refuses: forty methods in one class is forty functions, and the bar for a
 * class is sixteen. A parameterized case keeps the file at one function, keeps
 * one reported case per version — which is what the CI annotations want — and
 * keeps the count honest, because the list of versions is generated.
 */
@Tag("slow")
class MigrationRoundTripTest {

'''


def migration_steps(root: Path) -> list[tuple[int, int]]:
    """The migration steps the app promises to run, oldest first.

    A step is a pair of consecutive exported versions at or above the support
    floor. The floor is where the promise starts: below it the app has no
    migration to offer, and a database there cannot be upgraded at all.
    """
    schema_dir = root / "shared/schemas/com.singularity.todo.core.database.AppDatabase"
    versions = sorted(exported_versions(schema_dir))
    floor = get_floor()
    steps: list[tuple[int, int]] = []
    for version in versions:
        if version + 1 in versions and version >= floor:
            steps.append((version, version + 1))
    return steps


def render_roundtrip_test(steps: list[tuple[int, int]]) -> str:
    """The generated test file: one case per migration step.

    The rendering is a pure function of the steps, so the file the gate reads
    and the file the gate would write are the same string. That is the whole
    point: a check that compares against something else reports a stale file as
    current, and the file nobody looks at is the one that changed.
    """
    versions = "".join(f"            {from_version},\n" for from_version, _to_version in steps)
    body = (
        "    @ParameterizedTest(name = \"the upgrade from v{0} lands on the head schema\")\n"
        "    @ValueSource(\n"
        "        ints = [\n"
        f"{versions}"
        "        ],\n"
        "    )\n"
        "    fun `the upgrade lands on the head schema`(version: Int) {\n"
        "        roundTripFrom(version)\n"
        "    }\n"
    )
    return ROUND_TRIP_HEADER + body + "}\n"


def create_sql_columns(create_sql: str) -> list[str] | None:
    """The columns a `CREATE TABLE` declares, in order.

    A table constraint — `PRIMARY KEY(...)`, `FOREIGN KEY(...)`, `CHECK(...)` —
    is not a column, and neither is an FTS option (`tokenize=…`, `content=…`).
    Only a part that starts with a backticked name is a column. The parts are
    split on the commas that are not inside parentheses, so a column type with
    parentheses in it (`DECIMAL(10, 2)`) does not split in the wrong place.
    """
    start = create_sql.find("(")
    end = create_sql.rfind(")")
    if start < 0 or end <= start:
        return None
    body = create_sql[start + 1:end]
    parts: list[str] = []
    depth = 0
    current = ""
    for char in body:
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += char
    if current.strip():
        parts.append(current.strip())
    columns: list[str] = []
    for part in parts:
        match = re.match(r"^`(\w+)`\s", part)
        if match:
            columns.append(match.group(1))
    return columns


def check_exports_are_internally_consistent(schema_dir: Path, root: Path) -> list[Violation]:
    """An export must describe one schema, not two.

    Room writes both the column list and the DDL, so they cannot disagree —
    unless the file was edited by hand or merged from two versions. The v16
    export in this repository did exactly that: its DDL created six columns
    its own column list did not declare, and the migration that follows adds
    precisely those six. A database built from that DDL cannot be upgraded —
    the `ALTER TABLE` fails on a column that is already there — and the room
    schema gate could not see it, because it only compared table *names*.
    """
    out: list[Violation] = []
    for version, path in sorted(exported_versions(schema_dir).items()):
        entities = read_schema_entities(path)
        if entities is None:
            continue
        for entity in entities:
            table = entity.get("tableName")
            create_sql = entity.get("createSql")
            fields = entity.get("fields")
            if not isinstance(table, str) or not isinstance(create_sql, str):
                continue
            if not isinstance(fields, list):
                continue
            declared = [f.get("columnName") for f in fields if isinstance(f, dict)]
            columns = create_sql_columns(create_sql)
            if columns is None:
                out.append(
                    Violation(
                        "export-ddl-matches-fields",
                        f"v{version} `{table}` has a createSql that declares no columns",
                    )
                )
                continue
            if columns != declared:
                extra = [c for c in columns if c not in declared]
                missing = [c for c in declared if c not in columns]
                out.append(
                    Violation(
                        "export-ddl-matches-fields",
                        f"v{version} `{table}`: the DDL declares {extra or 'nothing extra'} "
                        f"and misses {missing or 'nothing'} — the export's own column list "
                        f"says {declared}",
                    )
                )
    return out


def check_roundtrip_test_current(root: Path) -> list[Violation]:
    """The generated test must cover every step the chain promises to run.

    A stale file is the failure this rule exists for: the test still runs, it
    just stops covering the migration that was added last. Nothing else in the
    tree notices — the schema is exported, the chain is continuous, and the
    upgrade is broken.
    """
    steps = migration_steps(root)
    if not steps:
        # Nothing to certify: a tree without a chain has no round trip to run.
        return []
    out: list[Violation] = []
    test_path = generated_path(root, ROUND_TRIP_TEST_RELATIVE)
    support_path = generated_path(root, ROUND_TRIP_SUPPORT_RELATIVE)
    if not support_path.is_file():
        out.append(
            Violation(
                "roundtrip-test-current",
                f"{support_path.name} is missing — the generated test calls the helper it holds",
            )
        )
    if not test_path.is_file():
        out.append(
            Violation(
                "roundtrip-test-current",
                f"{test_path.name} is missing — regenerate it with "
                "`python3 scripts/check-room-schema-integrity.py --write`",
            )
        )
        return out
    on_disk = test_path.read_text(encoding="utf-8")
    for finding in kotlin_convention_findings(on_disk):
        out.append(
            Violation(
                "roundtrip-test-current",
                f"{test_path.name} does not follow the conventions detekt enforces: {finding}",
            )
        )
    if on_disk != render_roundtrip_test(steps):
        out.append(
            Violation(
                "roundtrip-test-current",
                f"{test_path.name} is not what the generator produces — it does not cover "
                f"{len(steps)} migration step(s). Regenerate it with "
                "`python3 scripts/check-room-schema-integrity.py --write`",
            )
        )
    return out


def check_scenarios_current(root: Path) -> list[Violation]:
    """Rule 5: the generated scenario test is what the scenario files say.

    The round-trip test proves the schema; the scenario test proves the meaning
    of a step — that a backfilled column holds the backfilled value, that a row
    that cannot satisfy a NOT NULL constraint is gone. The scenarios are written
    as data, and the test is generated from them, so the file and the data can
    disagree — and a test file that disagrees with the data is a test of
    something else.
    """
    scenario_dir = root / SCENARIO_DIR_RELATIVE
    if not scenario_dir.is_dir():
        return []
    files = load_scenarios(scenario_dir)
    if not files:
        return []
    rendered = render_scenario_test(files)
    findings = kotlin_convention_findings(rendered)
    if findings:
        return [
            Violation(
                "migration-scenarios-current",
                "the generated scenario test does not follow the conventions detekt "
                f"enforces: {'; '.join(findings)}",
            )
        ]
    path = root / SCENARIO_TEST_RELATIVE
    if not path.is_file():
        return [
            Violation(
                "migration-scenarios-current",
                f"{SCENARIO_TEST_RELATIVE} is missing — generate it with "
                "`python3 scripts/check-room-schema-integrity.py --write`",
            )
        ]
    on_disk = path.read_text(encoding="utf-8")
    if on_disk != rendered:
        return [
            Violation(
                "migration-scenarios-current",
                f"{SCENARIO_TEST_RELATIVE} is not what the scenario files say today — "
                "regenerate it with `python3 scripts/check-room-schema-integrity.py --write`",
            )
        ]
    return []


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
    violations += check_columns_agree(schema_dir, version, root)
    violations += check_manual_list_matches_factory(manual, root)
    violations += check_exports_are_internally_consistent(schema_dir, root)
    violations += check_roundtrip_test_current(root)
    violations += check_scenarios_current(root)
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

    (ent_dir / "TaskEntity.kt").write_text(
        '@Entity(tableName = "tasks")\nclass TaskEntity(val id: String)\n'
    )

    def export(n: int) -> None:
        # The export carries a DDL and a column list that agree, because the
        # rule that compares them has to have something to compare: a fixture
        # without a DDL passes that rule for the wrong reason.
        (schema_dir / f"{n}.json").write_text(
            json.dumps(
                {
                    "database": {
                        "version": n,
                        "entities": [
                            {
                                "tableName": "tasks",
                                "createSql": (
                                    "CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` "
                                    "(`id` TEXT NOT NULL, PRIMARY KEY(`id`))"
                                ),
                                "fields": [
                                    {"columnName": "id", "affinity": "TEXT", "notNull": True},
                                ],
                            }
                        ],
                    }
                }
            )
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
    (db_dir / "Migration5To6.kt").write_text("class Migration5To6\n")
    # Every manual migration the exemption list names must exist in the tree —
    # that is one of the rules the fixture has to satisfy, and the list grows
    # with the schema. Writing the classes from the list keeps the fixture
    # honest as it grows: a hard-coded pair of names went stale silently, and
    # the self-test stopped passing for a reason that had nothing to do with
    # the rules it was proving.
    for class_name in DECLARED_MANUAL_MIGRATIONS.values():
        (db_dir / f"{class_name}.kt").write_text(f"class {class_name}\n")
    (db_dir / "AppDatabaseFactory.kt").write_text(
        "package com.singularity.todo.core.database\n"
        "object AppDatabaseFactory {\n"
        "    fun build() = Room.databaseBuilder<AppDatabase>(name = \"x\")\n"
        "        .addMigrations(Migration5To6())\n"
        "        .build()\n"
        "}\n"
    )

    # The generated round-trip test and the helper it calls. A compliant tree
    # has both, so the rule that checks them has something to check — and the
    # mutators below can take them away.
    steps = migration_steps(root)
    test_path = root / ROUND_TRIP_TEST_RELATIVE
    test_path.parent.mkdir(parents=True, exist_ok=True)
    test_path.write_text(render_roundtrip_test(steps), encoding="utf-8")
    support_path = root / ROUND_TRIP_SUPPORT_RELATIVE
    support_path.parent.mkdir(parents=True, exist_ok=True)
    support_path.write_text("// the helper the generated test calls\n", encoding="utf-8")


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

    def _stale_roundtrip_test(root: Path) -> None:
        """The generated test no longer covers the step that was added last.

        The defect this rule exists for: the file is still there, it still runs,
        and it has quietly stopped testing the newest migration.
        """
        p = root / ROUND_TRIP_TEST_RELATIVE
        p.write_text(p.read_text().replace("            5,\n", "            4,\n"), encoding="utf-8")

    def _missing_roundtrip_test(root: Path) -> None:
        """The generated test is gone entirely."""
        (root / ROUND_TRIP_TEST_RELATIVE).unlink()

    def _over_long_line_in_generated_test(root: Path) -> None:
        """The generated test holds a line detekt would reject.

        The generator wrote a `@ValueSource` on one line, and ktlint said
        `MaximumLineLength` — found by a Gradle run, eight minutes on a busy
        host. The rule has to see it without Gradle.
        """
        p = root / ROUND_TRIP_TEST_RELATIVE
        p.write_text(p.read_text() + "\n" + "// " + ("x" * MAX_LINE_LENGTH) + "\n", encoding="utf-8")

    def _column_in_entity_not_in_export(root: Path) -> None:
        """The entity grew a column and the schema was never re-exported.

        The defect this rule exists for. Room would have said so — but only
        after a build, on the second minute of CI, on the branch that made
        the change. Before the rule, the export was compared with itself.
        """
        p = root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskEntity.kt"
        p.write_text(p.read_text().replace("val id: String", "val id: String, val title: String"))

    def _table_in_entity_not_in_export(root: Path) -> None:
        """A new table was declared and the export was not re-exported."""
        p = root / "shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/GhostEntity.kt"
        p.write_text('@Entity(tableName = "ghosts")\nclass GhostEntity(val id: String)\n')

    def _stale_scenario_test(root: Path) -> None:
        """The generated scenario test no longer says what the scenarios say.

        The scenarios are data and the test is generated from them, so the two
        can disagree — and a test file that disagrees with the data is a test
        of something else. The rule that catches it is the one that matters
        here: without it, editing a scenario changes nothing until somebody
        remembers to regenerate.
        """
        scenario_dir = root / mod_scenarios_dir(root)
        scenario_dir.mkdir(parents=True, exist_ok=True)
        (scenario_dir / "31-to-32.yaml").write_text(
            "from: 31\nto: 32\nscenarios:\n  - name: a claim\n    seed: []\n"
            "    expect:\n      - sql: \"SELECT 1\"\n        equals: 1\n",
            encoding="utf-8",
        )
        test_path = root / SCENARIO_TEST_RELATIVE
        test_path.parent.mkdir(parents=True, exist_ok=True)
        test_path.write_text("// stale\n", encoding="utf-8")

    def _missing_scenario_test(root: Path) -> None:
        """The scenarios exist and the test they generate does not."""
        scenario_dir = root / mod_scenarios_dir(root)
        scenario_dir.mkdir(parents=True, exist_ok=True)
        (scenario_dir / "31-to-32.yaml").write_text(
            "from: 31\nto: 32\nscenarios:\n  - name: a claim\n    seed: []\n"
            "    expect:\n      - sql: \"SELECT 1\"\n        equals: 1\n",
            encoding="utf-8",
        )

    def _export_ddl_disagrees(root: Path) -> None:
        """The export's DDL declares a column its own column list does not.

        This is the defect the v16 export carried: a database built from that
        DDL already has the column, and the migration that adds it fails.
        """
        path = root / "shared/schemas/com.singularity.todo.core.database.AppDatabase/6.json"
        data = json.loads(path.read_text())
        entity = data["database"]["entities"][0]
        entity["createSql"] = entity["createSql"].replace(
            "(`id` TEXT NOT NULL", "(`id` TEXT NOT NULL, `extra` TEXT",
        )
        path.write_text(json.dumps(data))

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
    expect_caught("stale round-trip test", _stale_roundtrip_test, "roundtrip-test-current")
    expect_caught(
        "over-long line in the generated test",
        _over_long_line_in_generated_test,
        "roundtrip-test-current",
    )
    expect_caught("missing round-trip test", _missing_roundtrip_test, "roundtrip-test-current")
    expect_caught("export DDL disagrees with its columns", _export_ddl_disagrees, "export-ddl-matches-fields")
    expect_caught("a stale generated scenario test", _stale_scenario_test, "migration-scenarios-current")
    expect_caught("a missing generated scenario test", _missing_scenario_test, "migration-scenarios-current")
    expect_caught("a column the export does not have", _column_in_entity_not_in_export, "columns-agree")
    expect_caught("a table the export does not have", _table_in_entity_not_in_export, "columns-agree")

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
        click.echo("check-room-schema-integrity self-test FAILED", err=True)
        for f in failures:
            click.echo(f"  - {f}", err=True)
        return 1

    click.echo("check-room-schema-integrity self-test: all rules fire, compliant tree clean")
    return 0


@click.command()
@click.option("--self-test", "run_self_test", is_flag=True, help="plant each defect and assert it is caught")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.option("--write", "do_write", is_flag=True, help="Regenerate the round-trip test.")
@click.pass_context
def cli(ctx: click.Context, run_self_test: bool, emit_json: bool, do_write: bool) -> None:
    """Prove the declared schema, the exported schema, and the migration chain are the same schema."""
    # The parameter is not named `self_test`: click derives a parameter name from
    # the option name, and a parameter called `self_test` shadowed the module's
    # `self_test()` — so the flag read as "call the boolean", and the self-test
    # died with a TypeError instead of running.
    if run_self_test:
        raise ctx.exit(self_test())

    if do_write:
        steps = migration_steps(ROOT)
        path = generated_path(ROOT, ROUND_TRIP_TEST_RELATIVE)
        rendered = render_roundtrip_test(steps)
        # The generator checks its own output before writing it: a file that
        # detekt would reject is a file that costs two Gradle runs to find out.
        findings = kotlin_convention_findings(rendered)
        if findings:
            click.echo(
                "the generated Kotlin does not follow the conventions detekt enforces:",
                err=True,
            )
            for finding in findings:
                click.echo(f"  {finding}", err=True)
            raise ctx.exit(VALIDATION_FAILURE)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(rendered, encoding="utf-8")
        click.echo(f"wrote {path.relative_to(ROOT)} — {len(steps)} migration step(s)")

        # The scenario test: the semantics of the migrations, generated from the
        # scenario files. Same rule as above — the generator checks its own
        # output before writing it.
        scenario_dir = ROOT / SCENARIO_DIR_RELATIVE
        scenario_files = load_scenarios(scenario_dir)
        if scenario_files:
            scenario_path = generated_path(ROOT, SCENARIO_TEST_RELATIVE)
            scenario_rendered = render_scenario_test(scenario_files)
            scenario_findings = kotlin_convention_findings(scenario_rendered)
            if scenario_findings:
                click.echo(
                    "the generated scenario test does not follow the conventions detekt enforces:",
                    err=True,
                )
                for finding in scenario_findings:
                    click.echo(f"  {finding}", err=True)
                raise ctx.exit(VALIDATION_FAILURE)
            scenario_path.parent.mkdir(parents=True, exist_ok=True)
            scenario_path.write_text(scenario_rendered, encoding="utf-8")
            count = sum(len(f.scenarios) for f in scenario_files)
            click.echo(
                f"wrote {scenario_path.relative_to(ROOT)} — {count} scenario(s) "
                f"from {len(scenario_files)} file(s)"
            )
        raise ctx.exit(CLEAN)

    violations = run_all(ROOT)
    if violations:
        if emit_json:
            click.echo(json.dumps(
                {
                    "passed": False,
                    "blocking_errors": [str(v) for v in violations],
                    "violations": [
                        {"rule": v.rule, "message": v.message} for v in violations
                    ],
                },
                ensure_ascii=False,
            ))
            raise ctx.exit(VALIDATION_FAILURE)
        click.echo(f"Room schema integrity: {len(violations)} violation(s)", err=True)
        for v in violations:
            click.echo(f"  {v}", err=True)
        raise ctx.exit(VALIDATION_FAILURE)

    version = parse_schema_version((ROOT / str(
        "shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt"
    )).read_text())
    count = len(exported_versions(SCHEMA_DIR))
    if emit_json:
        click.echo(json.dumps(
            {
                "passed": True,
                "blocking_errors": [],
                "violations": [],
                "schema_version": version,
                "exported_schemas": count,
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(CLEAN)
    click.echo(f"Room schema integrity: SCHEMA_VERSION {version}, {count} exported schemas, chain continuous")
    raise ctx.exit(CLEAN)




if __name__ == "__main__":
    raise SystemExit(run(cli))
