---
title: A composite identity that cannot be half-built, and a seed marker written last
date: 2026-10-04
status: accepted
deciders: [sync client]
deciders: [sync client]
---

# A composite identity that cannot be half-built, and a seed marker written last

## Context

Two independent pieces of phase 9 and 10, sharing a shape: both are cases where
the convenient answer is a default, and the default is wrong in a way nothing
downstream can detect.

**Identity.** Every store the sync path writes to is keyed on
`(owner_id, profile_id, …)` — the shadow, the outbox's coalescing key, the
server's six document tables. Two profiles of the same account can hold a task
with the same id. Something has to turn a scope into that pair and back, and the
obvious implementation is `id.split('/')` with a `getOrElse { "default" }`.

**Seeding.** REQ-OS-013 has two halves that pull in opposite directions: a second
sign-in on the same account must upload nothing further, and an interrupted seed
must resume rather than lose the rest.

## Idea

Make the invalid value unrepresentable on one side, and make the ordering of two
writes carry the guarantee on the other. In both cases the cheap implementation
is the one that loses data quietly.

## Decision

1. `SyncIdentity` is a `@JvmInline value class` whose `init` requires exactly one
   separator and two non-blank halves. A half-built identity cannot be
   constructed, so every value of the type is already valid.

2. `SyncIdentityMapper.toScope` **throws** on an unparseable value rather than
   defaulting the profile to `"default"`. The default is the profile name a fresh
   install happens to use, which is what makes it tempting and what makes it
   wrong: it attributes a row to an account and profile the user has never had,
   and reads it back to a place where no such row exists — invisible in one
   direction, orphaned in the other, with nothing to point at the moment it
   happened.

3. `sync_state.seed_completed` is written **only when every document was queued**,
   and **after** the enqueue. Room 35 → 36; the column defaults to `false`.

4. Seeding is `SyncEngine.enqueue` called once per entity. There is no second
   upload path.

## Rationale

The seed marker is the interesting one, because the ordering has a failure in
*both* directions and they are not symmetric.

Writing the marker **first** means an interrupted seed looks finished: the
documents that never reached the outbox are never uploaded, and the flag now says
there is nothing to come back for.

Writing it **last** costs a re-run. And that is nearly free, because the outbox
coalesces per entity — `deleteByEntity` then insert — so a second run *replaces*
an entity's row rather than adding a second one. The re-run is a query over tables
that are usually empty, and an enqueue that lands on a row that already exists.

The bug this reasoning missed, and the test caught, was the third case: the first
version marked the scope complete after a run in which one enqueue had *failed*.
That is neither of the two orderings above — the run completed, the marker was
written, and one document was stranded with no way back. The marker is now
conditional on the count.

The column's default is the same kind of decision. A column added as `true` would
read, for every row existing at upgrade time, as "this scope's data has already
been uploaded". Nothing would say otherwise, the planner would skip, and the data
a user created before signing in — the exact data the requirement is about — would
never reach the server. `Migration35To36Test` asserts the default, not just the
upgrade, because the default is the part that is wrong silently.

**On the second upload path.** Seeding through `SyncEngine.enqueue` means retry,
backoff, dead-lettering and shadow settling are the code that already runs all
day. A dedicated path would need its own of each, and would be the one never
exercised until a user with a year of data signs in — the worst possible moment
to discover it is broken.

`SeedPlanner` also breaks the `core` → `feature` dependency rule, as
`SyncBootstrapper` already does. A registry of providers would keep the
dependency out and add six registration calls that must be made in the right
order, to a class whose failure mode is "one type silently not seeded". A
bootstrapper's whole job is to be the one place that knows every entity type
exists.

## Consequences

- An identity read from storage is either one this build wrote or it is a loud
  failure naming the value.
- A failed enqueue costs a re-scan; a lost document costs the user their data.
- A debug build with a developer's test project compiled in still loses to a
  stored value, so a user typing their own URL is not silently overridden.
- Time entries are seeded through their tasks, because
  `TimeTrackingRepository` has no "all entries" observation. An entry whose task
  is gone is not seeded — an orphaned entry belongs to a document the user can no
  longer see.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncIdentity.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SeedPlanner.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SeedPlannerTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/Migration35To36Test.kt`
