---
title: "A stale schema export is visible before the build"
date: 2026-10-23
status: accepted
tags: ["room", "schema"]
---

# A stale schema export is visible before the build

## Context

`scripts/check-room-schema-integrity.py` compares the head export `NN.json`
with `SCHEMA_VERSION`, checks that the migration chain is continuous, and
checks that the *table names* in the export are the table names the entity
classes declare. Every one of those is a statement about files.

The columns were not part of that comparison. The reason was recorded in the
gate's own docstring, and it was a decision rather than an oversight:

> Comparing every column across 34 schema files would produce findings this
> project has already decided are noise; the column-level drift is caught by the
> identity-hash comparison that Room itself performs on open, and by the
> migration tests.

The first half of that is right and still is: a column that differs between
version 37 and version 38 is the chain doing its job, and diffing the exports
against each other is noise.

The second half is where the money was. "Room performs the identity-hash
comparison on open" is true, and it is also true that Room performs it *at
runtime, on the user's device, after the build*. The defect it catches — an
entity that grew a column and was never re-exported — is therefore invisible
until the second minute of CI, on the branch that changed it, and only if
somebody opens a database that already exists. A test suite that creates its
own database never sees it, which is exactly how `SyncColumns.server_version`
reached `main` the first time.

## Idea

Ask the question Room asks, from the sources.

The export is written by KSP at build time, but nothing about the *question*
needs a build: the table name is in the `@Entity` annotation, and the column
name is in the property or in its `@ColumnInfo`. Both are text.

So the gate reads the entity classes and compares them with the head export:
the same tables, the same columns. A stale export — one that does not say what
the code says — is then a finding of a static check, in milliseconds, before
anything is built.

## Decision

The comparison compares **names only**: tables and columns. The column's type,
its affinity, its default value and its place in the primary key are Room's
business, and a rule that re-implemented Room's type mapping would produce a
second answer to a question Room already answers. A gate that reports
differences where there are none is a gate that gets ignored, and an ignored
gate guards nothing.

Two rules of Room's naming had to be read, because the sources cannot be read
without them: a column is named by `@ColumnInfo("name")`, by
`@ColumnInfo(name = "name")`, or — when neither says — by the property's own
name; and an `@Embedded` property is not a column, because Room flattens the
embedded class's properties into the parent's table. The reader lives in
`sgate.gatekit.room`, with its own tests, because a second reader of the
entities would be a second answer to the same question.

The comparison is against **the head export only**. The exports are not
compared with each other; that decision stands.

## Rationale

The gate's own self-test is the control: it plants an entity with a column the
export does not have, and an entity whose table the export does not have, and
asserts the rule fires for each. A rule that stops matching reports success
having checked nothing, and that is invisible from the outside.

The rule also refuses to be vacuous: a tree where no `@Entity` class is found
is a finding, not a pass. "Found zero tables" must never be reported as "found
no problems" — the same rule the gate already applies to the schema directory.

## Consequences

- A column added to an entity without re-exporting the schema is now a red
  build in the static gate, not a crash on a user's device.
- The reader of the entity classes is a shared piece of machinery
  (`sgate.gatekit.room`), with tests, rather than a private helper of one gate.
- The gate's docstring said it did not compare columns. It now says what it
  compares and, more importantly, *with what*: one export and one source of
  truth, not a diff between two histories.

## Links

- `scripts/check-room-schema-integrity.py` — the gate and the rule
  `columns-agree`.
- `scripts/src/sgate/gatekit/room.py` — the reader of the entity classes.
- `docs/decisions/2026-10-23-the-migration-chain-has-a-generated-round-trip-test.md`
  — the other half of the schema story: what a migration *produces* is a
  property of running the chain, and it needs a test.
