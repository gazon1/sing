---
date: 2026-10-23
status: accepted
---

# The migration chain has a generated round-trip test

## Context

`scripts/check-room-schema-integrity.py` proves three things about the Room
schema: the head export agrees with `SCHEMA_VERSION`, the chain is continuous,
and the entities agree with the export. All three are statements about *files*.

One property is not a statement about files: **does a migration produce the
schema its successor claims?** That is a property of running the chain, and
Room itself checks it — after the migrations, Room validates the schema it
finds against the entities the code declares and throws when they differ. A
user with an existing database finds out; a test suite that creates its own
database never does.

The repository had hand-written tests for some of the steps
(`Migration31To32Test`, `Migration44To45Test`, …), each one written for the
semantics of that particular migration. The steps without one — most of them —
had no test at all, and nobody noticed: the schema was exported, the chain was
continuous, and the upgrade was broken.

The first run of the generated test found the defect this gate could not see.
The exported schema `16.json` was internally inconsistent: its `createSql`
created six columns — `start_date`, `start_time`, `end_date`, `end_time`,
`accent_color`, `emoji` — that its own `fields` list did not declare. Room's
auto-migration for 16 → 17 adds exactly those six columns, so a database built
from that DDL cannot be upgraded: the `ALTER TABLE` fails on a column that is
already there. The export had been merged from two versions, and the gate
compared table *names*, so it never saw it.

## Idea

Say the property once, in a test, and generate the test from the chain.

Room exports every schema version it has ever been at. Those exports are the
starting points of the round trip: take the export of version *N*, open it
through Room, let the chain run, and let Room's own validation say whether the
result is the schema the head declares. The list of versions to do that for is
already in the repository — the exports and the chain — so the test does not
have to be written by hand to exist.

## Decision

`MigrationRoundTripTest.kt` is **generated** from the exported schemas, one case
per migration step, and the gate that owns the schema also owns the generation:

* `python3 scripts/check-room-schema-integrity.py --write` regenerates the
  file;
* the gate fails when the committed file is not what the generator produces,
  so a migration added without a regeneration is a red build;
* the fixture — the part that builds a database of version *N* and reads the
  anchor row back — is hand-written (`MigrationRoundTripSupport.kt`) and does
  not change when the chain grows.

The generated file holds *one* parameterized case over the versions, not one
method per version: forty methods in one class is forty functions, and detekt's
bar for a class is sixteen. The case still reports one result per version,
which is what the CI annotations want.

The generator lives in the gate rather than in a separate tool because the gate
is what knows the chain. One owner for the chain, one owner for the test that
covers it, and the check that the test is current.

The export `16.json` is repaired: its DDL now declares the columns its own
field list declares. A new rule — *the export's DDL must list exactly the
columns the export declares* — keeps the corruption from coming back, and the
positive control for that rule plants it.

## Rationale

A hand-written test per migration is the wrong shape for this property. The
property is about the chain, and the chain grows by one every time a schema
changes; a test that has to be written by hand is a test that is not written.
Generating the test from the exports makes "every step has a round trip" a
fact about the tree rather than a fact about somebody's memory.

The generator covers the versions from the support floor (v5) upwards. Below
the floor the app has no migration to offer, so there is no upgrade to test;
the floor is declared by the gate and asserted against the chain.

The fixture seeds one row in `tasks` rather than running the chain against an
empty database. A schema that survives the chain while the data in it does not
is not an upgrade, and the values of the three converted columns are the
enum's own names (`None`, `Task`, `LOCAL_ONLY`) so that a migration reading
them fails on a defect rather than on the fixture.

## Consequences

* Every step of the chain has a round trip, and a new step gets one by
  regenerating — a migration cannot be added without its test.
* The exported schemas are now checked against themselves, which is what found
  the v16 defect.
* The test class is generated, and the file says so at the top. The generator
  is the only way to change it.
* The test crosses the process boundary — a real file, a real SQLite, Room's
  own validation — so it carries the `slow` tag and runs in the full suite.
* The repair of `16.json` changes a committed artefact. Room regenerates only
  the export of the current version, so the historical export stays as it is
  repaired — and the new rule keeps it from drifting again.

## Links

* `scripts/check-room-schema-integrity.py`
* `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/MigrationRoundTripTest.kt` (generated)
* `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/MigrationRoundTripSupport.kt`
* `shared/schemas/com.singularity.todo.core.database.AppDatabase/16.json`
