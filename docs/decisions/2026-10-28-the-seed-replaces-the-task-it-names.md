---
title: "The seed replaces the task it names, and the run id leaves the title"
date: 2026-10-28
status: accepted
tags: [testing, maestro, android]
---

# The seed replaces the task it names, and the run id leaves the title

## Context

Thirty-five of the ninety-two Maestro flows put the run id into the title of
the task they seed:

```yaml
- openLink: "todo-debug://seed?task=${RUN_ID}_PinnedTask2026&due=today"
- extendedWaitUntil:
    visible:
      id: task_item_${RUN_ID}_pinnedtask2026
```

The reason is defensible: the row's test tag is derived from its title, so a
run-scoped title makes the row unique to the run, and a row left behind by an
earlier run cannot be mistaken for the current one.

The cost is paid everywhere else. A failure reads

```
Assertion is false: "r5869995730 PinnedTask2026" is visible
```

— a reader cannot tell what the task was supposed to be called, and the
measurement gate has to replace the digits with a placeholder to have a
stable cause name at all (`Assertion is false: "r<n> Buy milk" is visible`).
The run id also leaks into the app's data: every seeded task is called
"r5869995730 PinnedTask2026" in the user's task list.

And the protection is weaker than it looks. With the run id in the title, a
leftover row from an earlier run is a *different* row that the flow cannot
see, so the flow's assumption of exactly one row is simply false whenever the
previous run was not cleaned up. The run id does not make the assertion
correct; it makes the wrong row invisible.

## Idea

The seed should be a **definition** of the state, not an append to it. A
seed that replaces the task it names gives the flow exactly one row to
assert on, with a stable name, in every run — whether or not the previous run
was cleaned up.

## Decision

**The seed replaces; it does not append.** `SeedWriter` (in `core/seed/`)
looks for the title among the live rows and in the trash. It finds one, it
updates it in place — keeping the row's id, which is the identity the flows
address — and restores it if it was trashed. It finds none, it creates one.
Any other row with the same name is soft-deleted, so the live set holds
exactly one.

**A seeded title carries no run id.** The title a flow seeds is the title the
flow asserts on, and it is the same in every run.

**The activity is the entry point, not the logic.** `DebugSeedActivity` builds
a `SeedWriter` and calls it; the seeding rules live in `commonMain`, where a
test can reach them. `SeedWriterTest` proves the idempotency: the same title
twice leaves one task, and the second seed rewrites the row.

**The run id stays available** for the flows that need a unique value for
their own reasons, and it leaves the *titles* of the seeded entities.

## Rationale

A flow asserts on a row it seeded. If the seed appends, that assertion is
only as good as the isolation behind it; if the seed replaces, it is as good
as the seed. The second is a property of one piece of code and the first is
a property of the whole suite.

The seeded title is also the name the failure is reported under, and a
failure whose name changes on every run cannot be catalogued, cannot be
compared with the last run's, and cannot be read.

Replacing rather than creating also removes the litter: a run that seeds
twelve tasks leaves twelve rows with a run id in their names, and the next
run leaves twelve more.

The cost is the seed has to look before it writes, and it has to decide what
to do with a row it finds in the trash. Restoring it is the right answer: a
flow that seeds a task wants to see it, and a task the flow trashed in an
earlier run is the same task.

## Consequences

- The ids a flow asserts on are stable, and so are the failure messages and
  the cause names the measurement gate catalogs.
- The seed's contract changes: it is now idempotent, and any code that
  relied on it creating a second row is wrong.
- A task the flow *types* into the editor is the flow's own row and is not
  the seed's business. Those flows still carry the run id, because the
  title is what the flow types; they are the remaining half of this work.
- The two id catalogues — `config/docs/maestro-element-kinds.txt` and the
  allowlist in `scripts/ci/validate-maestro-selectors.py` — have to follow
  the names, and a gate that finds an element nobody budgeted is the
  mechanism that says so.
- The documentation in `Maestro/config.yaml` that told the flows to prefix
  the title with the run id is stale and is corrected.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedWriter.kt`
  — the seeding rules.
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedWriterTest.kt`
  — the idempotency.
- `docs/decisions/2026-10-28-reset-content-reaches-the-whole-profile.md` —
  the other half of the isolation story: what a flow inherits from the run
  before it.
- `docs/decisions/2026-10-28-the-measurement-names-its-causes.md` — why a
  stable cause name is worth the trouble.
