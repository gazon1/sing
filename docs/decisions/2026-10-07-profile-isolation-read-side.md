---
title: "Profile isolation, read side: what the gate allows and why"
date: 2026-10-07
status: accepted
tags: [architecture, testing, database, profile-isolation, gates]
---

## Context

`ScopedWriteQueryIsolationTest` covers `UPDATE` and `DELETE`, and says nothing about reads.
The reason is defensible: a write already has an outer guard in `assertCanWrite`, so the
`user_id = :userId` predicate is the second lock, not the only one.

Reads have no such guard. Nothing checks that the caller was entitled to the row it is
asking for — the SQL predicate is the whole of profile isolation on the read path.

That gap is not hypothetical. `ReminderDao.watchAllProfiles()` was added deliberately, in
commit `2757cc4d`, to fix a real bug: arming OS alarms from a profile-scoped query meant a
reminder for a profile that was not active never fired at all. The decision was recorded in
a new `CrossProfileReadRegistry` — and its KDoc then claimed a `CrossProfileReadRegistryTest`
existed, which it did not. That is the same defect the two WS4 bugs shared: a document
asserting a guard that was never built.

So the registry shipped unenforced, and the thing that found the gap was writing the gate.

## Idea

Before building a lint, count what it would have to allow. Scanning `Daos.kt` for `SELECT`
statements that name no scoping column and join that against the tables in `Entities.kt`
that actually have one gave **10**, not 1. All ten are legitimate today. The interesting
part was not the count but the three shapes they fall into — because a rule that has to
allow ten things by name is a rule that will be extended by name next time, and that is how
a gate becomes a list.

The shapes:

1. **Five reads on `profiles`.** The profile table *is* the isolation boundary's subject.
   Its `user_id` is a nullable "whose profile this is, or null while it belongs to nobody",
   not a key rows are selected by. Scoping a profile listing by ownership is circular — the
   listing is how you learn who is who.
2. **Join tables and `checklist_items`** (`task_tags`, `task_dependencies`). They carry no
   scoping column at all, so there is nothing to filter on. Exempt by construction, exactly
   as `INSERT` is exempt in the write gate.
3. **Three reads by primary key** — `watchById` on `tasks`, `tags`, `tag_groups`. The id
   came from an already-scoped query.

## Decision

Three outcomes, and only three. A `SELECT` on a profile-owned table must either name a
scoping column, be a primary-key hydration, or be listed in `CrossProfileReadRegistry`.

**`profiles` is exempt at table level**, with the reason written at the exemption rather
than at each of the five call sites, and the exemption list pinned by its own test so it
cannot quietly acquire neighbours.

**A primary-key hydration is allowed only while a scoped sibling exists** in the same DAO
for the same table. This is the part that matters. "Hydrate the row whose id a scoped query
returned" is a real argument; it stops being true the moment the DAO stops offering the
scoped way to do the same lookup, which is exactly when a by-PK read silently becomes the
only door in. The gate checks for that rather than trusting the comment.

**The list of profile-owned tables is parsed from `Entities.kt`, never written down.** A
hand-maintained list is a list that is wrong the first time somebody adds an entity, and
wrong in the direction that fails open. This follows the reasoning that made the write gate
accept both `user_id` and `owner_id` for `sync_state`: the gate's input has to come from the
schema or it will drift from it.

`CrossProfileReadRegistryTest` then does what `CrossUserWriteRegistryTest` does: every entry
still names a method that exists, the count has not grown quietly, each entry explains the
boundary where the query is read, and the sanctioned method is still called by the re-arm
path — an approved hole nobody calls is a hole that no longer needs approving.

## Rationale

A registry with one entry is cheap to keep honest. A registry with ten, where each entry is
justified by "it was already like that", is a list of defaults. Splitting the ten into a
table-level exemption with a written reason, a construction-level exemption, and a
sibling-gated allowance keeps the registry at one — and keeps the reason for each allowance
attached to the allowance.

The sibling rule is the strongest of the three and the one worth keeping. It converts a
comment ("the id came from a scoped query") into a checkable claim.

## Consequences

- A new `SELECT` on `tasks`, `tags`, `task_reminders`, `projects`, `notes`, `time_entries`
  or `llm_usage` that names no scoping column fails the build unless it is a PK hydration
  with a scoped sibling, or is sanctioned.
- Adding a new entity to `Entities.kt` is covered by the gate with no action, because the
  gate reads the schema.
- Renaming `user_id` on a table is a breaking change *for the gate*, deliberately. It should
  be noticed.
- The anti-vacuity test asserts the scan still finds at least ten queries and still derives
  the expected tables, so a moved source root or a renamed file turns this into a visible
  failure rather than a green no-op.
- `CrossProfileReadRegistry.sanctioned` stays at one. If it grows, the KDoc already says the
  gate should be extended rather than the list relaxed, and the count test says so too.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ScopedReadQueryIsolationTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/CrossProfileReadRegistryTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/reminders/data/CrossProfileReadRegistry.kt`
- Prior: `2026-10-07-decisions-nothing-could-reach.md`,
  `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ScopedWriteQueryIsolationTest.kt`,
  `shared/src/jvmTest/kotlin/com/singularity/todo/arch/CrossUserWriteRegistryTest.kt`