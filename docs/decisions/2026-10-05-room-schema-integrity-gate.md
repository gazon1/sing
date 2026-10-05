---
title: "A schema change without a version bump passes every test and crashes every install"
date: 2026-10-05
tags: [database, room, migrations, gates, crash]
status: accepted
---

## Context

Integrating the publication work onto `origin/main` surfaced a defect that had been
sitting in the tree: `Entities.kt:17` declared `server_version` in `SyncColumns`, and
that mixin is `@Embedded` into `SyncShadowEntity`. The exported Room schema
`36.json` had no such column — it appeared only in `37.json`. `SCHEMA_VERSION` was 36,
and there was no `Migration36To37`. `AppDatabaseFactory` has no
`fallbackToDestructiveMigration`, so Room had nothing to fall back to either.

The effect is an `IllegalStateException` at the first query for any user with an
existing database. Fresh installs are fine.

This is worth writing down because of *why* nothing caught it, which is the part that
generalises:

- **It compiles.** The `@ColumnInfo` is a legitimate declaration; nothing about it is
  a type error.
- **Every test passes.** All 1859 tests in `shared:jvmTest` pass on a tree carrying
  this bug, because every one of them creates its own database. A database that has
  just been created has no stored identity hash to disagree with.
- **It is invisible to review in the diff that causes it.** The diff is "add a column
  to a mixin". The thing that has to change with it — a version number in another file,
  a new migration class, a new exported schema — is not mentioned in that diff.

Room exports `4.json` … `37.json` on every build and gives the compiler enough
information to detect the class of drift. The gap was not Room's. It was that the one
place in the repository where all three relevant facts meet — the `@Database`
annotation, the exported schemas, and the `Migration*.kt` files — was never checked
against itself. Thirty-four exported schemas sat in the tree unread by anything.

## Idea

Check the three artefacts against each other, cheaply, on every commit.

The alternative considered and rejected: extend the migration tests to open a v36
database and upgrade it. That is the *right* test and it is worth having, but it is
`slow`-tagged, it needs a fixture database on disk, and it runs in one module. A
source-level consistency check costs milliseconds, runs in `check.sh` and in CI, and
fails at the commit that introduced the drift rather than at the commit that noticed.

These are complementary, not alternatives: the cheap check says "these three files
describe one schema", the migration test says "this migration produces that schema".

## Decision

`scripts/check-room-schema-integrity.py`, registered in `SCRIPT_GATES`, invoked from
both `check.sh` and `ci.yml`. Four rules:

1. **Head agrees.** The highest `NN.json` exists and its recorded `version` equals
   `SCHEMA_VERSION`. This is the rule that catches the `server_version` defect.
2. **Chain continuous.** Every step from the support floor to `SCHEMA_VERSION` has an
   `AutoMigration`, or is listed in `MANUAL_MIGRATIONS` *and* named in
   `AppDatabaseFactory.addMigrations`. Both halves are asserted, so the exemption list
   cannot drift away from the code.
3. **Entities agree.** Tables in the head export match `tableName`s declared in source.
4. **Floor consistent.** `SUPPORTED_FROM_VERSION` equals the lowest `AutoMigration`'s
   `from`, so the declaration cannot outlive the fact that justified it.

### The support floor is a declaration, not a tolerance

The gate found a second thing: there is no migration `4 → 5`. `4.json` was exported
when the codebase moved to Room 3 on 2026-09-04; `5.json` added `checklist_items` —
one table, no data movement. Nothing migrates across that step.

The honest reading is that v4 was never distributed: the app is `versionName 0.1.0`,
has no release tag, and the repository is private. A v4 database can only exist on a
developer machine that ran a build from a three-week window in September.

So `SUPPORTED_FROM_VERSION = 5` is written down with that reasoning attached, rather
than either writing a migration for a population of zero or leaving the gap to be
rediscovered. Rule 4 exists because a declaration without a check is a comment that
will be wrong later: once someone writes the `4 → 5` migration, the floor is stale and
the gate says so.

## Rationale

The project's standing rule is that a noisy gate is worse than no gate, and that every
new rule needs a positive control. Both shaped the result.

**The positive control is the real bug, not a convenient one.** `--self-test` plants
eleven defects and asserts each is caught; the sabotage registered in
`check-gate-wiring.py` rewrites `SCHEMA_VERSION = 37` to `36` in the working tree,
which reproduces the shipped defect exactly. Verified by hand as well: with
`SCHEMA_VERSION` rolled back the gate reports `head-agrees`; with only
`Migration36To37` removed it reports `chain-continuous`.

**The controls found two bugs in the gate itself**, which is the reason to have them:

- `parse_manual_migrations` originally captured `addMigrations(...)` with `[^)]*`, which
  stops at the paren of `Migration31To32()` and reads the argument list as empty. The
  gate reported "not registered in addMigrations (found: none)" for a factory that did
  register it — a finding that is both wrong and the kind that trains people to ignore
  a gate. It now balances parentheses.
- The exemption cross-check sat *after* `if pair in auto: continue`, so a pair holding
  both an `AutoMigration` and a `MANUAL_MIGRATIONS` entry was never compared against
  the factory. That is precisely the state where the list can rot unnoticed: the step
  looks covered either way, so nothing else would notice. The check now runs first.

**The gate compares table names, not DDL.** Comparing every column across 34 schema
files would produce findings this project has already decided are noise. Column-level
drift is caught by Room's own identity-hash comparison on open, and by the migration
tests.

## Consequences

- The class of defect that produced a shipped crash is now a red build at the commit
  that introduced it.
- `Migration36To37.kt` and `37.json` exist on `origin/main` as a result of finding this
  during integration, not before.
- A future `4 → 5` migration requires revisiting `SUPPORTED_FROM_VERSION`, and the gate
  will say so rather than letting the declaration quietly become false.
- Not covered: whether a migration *produces* the schema its successor claims. That
  stays with the migration tests, and the newest of them was missing until
  `Migration36To37Test` was written on 2026-10-05 — see below.

## Amendment, 2026-10-05: the half the gate cannot see

`Migration36To37Test` now exists, and writing it was worth doing for a reason
beyond coverage: it is where the division of labour between the two kinds of check
becomes concrete.

The gate asserts that a migration is *declared*. The test asserts that it
*produces* the schema its successor claims. Neither is sufficient, and the seam
between them is where the interesting failure lives — a migration that runs
happily and leaves the wrong default in place is invisible to both. Room's DDL
check proves the column exists; nothing proves its default is `0`, and `0` is
the entire content of this migration. The client parsed the server's version on
every push and dropped it, so a pre-upgrade row genuinely knows nothing, and any
other default would put every subsequent patch's base back to "the server has
never seen this row".

The positive control was run by hand, not just written: changing
`defaultValue = "0"` to `"1"` in `SyncShadow.kt` fails
`a row that predates the upgrade reads as version zero` and only that one. A test
that passes on a broken migration is the failure mode this project keeps paying
for, so the control is part of the work rather than a claim about it.

**The same shape applies to `pro/`, and it is why `ProObservabilityModuleTest`
exists.** `KoinGraphValidationTest` validates the free DI graph; it cannot see a
project that only exists behind `-PwithPro=true`. So the one binding `pro/` exists
to provide — re-binding `CrashReportingPort` from `FileCrashReportingPort` to the
vendor-backed implementation — was checked by nothing. If the override stopped
taking effect, both configurations would still resolve the same interface, every
test would still pass, and crash reports would go to the local log file while the
vendor dashboard stayed empty. Nothing throws. Its control was also run: emptying
`proObservabilityModule()` fails three of the six cases, and passes the other
three — which is the correct division, since the cases that do not concern
rebinding should not notice it.

Note the cost both additions carry: `:pro` gains a `testImplementation` set and a
`useJUnitPlatform()` block, and CI gains a pro step. A `pro/` test that nobody
runs is worse than no test, because it appears in the coverage picture.

**And the arch gate caught the omission that followed.** Adding
`pro/src/test/kotlin` created a new source set, and `pro/build.gradle.kts` sets
`detekt.source` to a literal list — so the new directory was not scanned, and
detekt said nothing about that, because detekt reports findings and not
absences. `DetektSourceSetsAreAllScannedTest` failed with "pro: src/test/kotlin
exists and detekt is not told to scan it", and the fix was to name it.

That test earned its keep a second time in the same session it was written for
`pro`: it is the reason the only test in the pro catalogue is a linted test
rather than a silently unscanned one. The general shape — *a new source set is a
new obligation, and the gate that says so is the one that reads the filesystem
rather than a list* — is the same one `check-room-schema-integrity.py` exists for
three directories over.

## Links

- `scripts/check-room-schema-integrity.py` — the gate
- `scripts/tests/test_check_room_schema_integrity.py` — 31 tests
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/Migration36To37Test.kt`
  — the migration test the gate cannot replace
- `pro/src/test/kotlin/com/singularity/todo/pro/observability/ProObservabilityModuleTest.kt`
  — the rebinding the free graph cannot see
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt` —
  the annotation, and the file the sabotage rewrites
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseFactory.kt`
  — the `addMigrations` seam and the migration discipline note
- `docs/decisions/2026-10-05-provenance-audit.md` — the publication work whose
  integration onto `origin/main` surfaced this defect
