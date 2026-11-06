---
title: "The write keeps the time it was given, so a seeded fixture can be old"
date: 2026-11-06
status: accepted
tags: [testing, maestro, database]
---

# The write keeps the time it was given, so a seeded fixture can be old

## Context

The seed defines a fixture's timestamps: `updatedAt=-22d` is how a flow puts a
task in the stagnant health bucket, and `SeedWriter` hands that instant to
`TaskRepository.create`/`update` as both `createdAt` and `updatedAt`.

The repository did not keep it. Every create and update called
`saveOutgoingLinks`, whose `updatedAt` parameter defaulted to
`clock.now()`. The link write therefore re-stamped the row with the wall clock a
moment after the row had been written with the seed's stamp. On the device the
seeded task came back with `created_at` in the past and `updated_at` at the
moment of the seed, so it was not stagnant, the bucket was empty, and the
`smoke/03-health-drilldown` flow failed on a screen it could not reach.

## Idea

A write carries the timestamps its caller gave it. The clock belongs to the use
case that decided an edit, not to the repository's bookkeeping: a repository that
reads the clock turns every write into "now", including the writes whose whole
point is that they are not now.

## Decision

`TaskRepositoryImpl.saveOutgoingLinks` takes the row's `updatedAt`, and every
call site — `create`, `update`, `createSeries`, `updateAll`, `realignSeries` —
passes it. The default parameter is gone, so a new call site has to decide
instead of silently reading the clock.

`TaskRepositoryContractTest` pins the rule for both the fake and Room
implementations: create and update keep the caller's `createdAt`/`updatedAt`.

## Rationale

The seed's stamp is part of the definition of the fixture (ADR
`2026-10-28-the-seed-replaces-the-task-it-names`), and a definition the
repository overwrites is not a definition. The normal edit paths already set
"now" in their use cases (`CreateTask`, `UpdateTask`), so honouring the caller's
stamp changes those writes not at all; it changes only the writes that
deliberately hand in another time.

## Consequences

- A seeded `updatedAt` reaches the database, so the stagnant bucket can be
  seeded and the health drill-down is testable.
- A writer that means "now" says so in its use case, where the decision is
  visible, rather than relying on the repository's default.
- The link write is no longer an invisible edit: it writes the timestamp of the
  row it belongs to.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt`
  — the link write and its call sites.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskRepositoryContractTest.kt`
  — the timestamp contract.
- `docs/decisions/2026-10-28-the-seed-replaces-the-task-it-names.md` — the seed
  defines the state, timestamps included.
- Issue #2055 — the device run that found it.
