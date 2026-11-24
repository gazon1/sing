---
title: "The agenda read walks the relation table once, and the evaluator claims rows in place"
date: 2026-11-24
status: accepted
tags: ["agenda", "performance", "database"]
---

# The agenda read walks the relation table once, and the evaluator claims rows in place

## Context

After #3154 moved the task-detail screen off the profile-wide read, the first
failure on the reference device moved to `agenda.load_failed`, with frames in
`TaskExtrasKt.enrichedWith` (#3156). That is the agenda's own read: it is the
screen built to show the profile, so it legitimately reads every live task. The
question the issue asks is not "make the agenda read less" but "stop paying for
the same data more than once inside it".

The path was measured rather than assumed. A warmed JVM probe against real Room
(50,000 tasks, 50,000 tag edges, 100,000 dependency edges, in-memory fixture)
gave these phases:

| Phase | Before |
|---|---|
| `observeByFilter(All)` — the read the agenda does | 822 ms |
| `AgendaEvaluator.evaluate(All)` | 253 ms |
| `AgendaEvaluator.evaluate(Upcoming)` | 102 ms |
| retained after the read (50k tasks) | 94 MB |

Four pieces of avoidable work showed up in the read and the evaluation:

1. `withExtras` and `withScopedExtras` built the dependency projections with two
   `filter` + `groupBy` passes over the same list. Both projections are questions
   about one table, answered from one read of it — and #3154 had already made the
   *query* a single read, so the second pass was pure waste over rows already in
   memory.
2. `freshTasks` read `task_dependencies` twice for the same two projections.
3. `enrichedWith`'s mapper decoded the `ai_suppressed_tag_ids` JSON column for
   every row. The column's default is `"[]"`, which is what almost every row
   carries, so most rows paid for a JSON parse that could only return nothing.
4. `AgendaEvaluator` held `remaining` as an immutable `Set` and did
   `remaining - matched.toSet()` per discard section. The wide view (`All`) has
   six discard sections, so a 50k profile copied the whole set six times per
   evaluation.

## Scope of this record

This is an **implementation-level** measurement, not the phase-level device
baseline #3156 asks for. The issue requires a documented device run — exact
commit, device, Android version, build variant, heap limit, dataset manifest —
separating restore, Agenda query, relation loading, enrichment, evaluation, state
publication, first render and initial scroll. None of that exists here, and this
record does not claim it.

What the numbers below are: a warmed JVM probe against real Room, on a synthetic
profile of the shape `scripts/verify-large-restore.py` generates (50,000 tasks,
50,000 tag edges, 100,000 dependency edges, seeded into an in-memory fixture).
They answer "did removing the duplicated passes help, and by how much", which is
the question this change can answer on its own.

The device boundary in `docs/architecture/large-restore-results.md` is unmoved by
this change, and #3156 stays open until a device run moves it.

## Idea

Remove the second pass, not the data. Each item above is the same rows being
processed twice, or a parse whose answer is known from the storage format — none
of them needs a narrower query, a new read model, or a cache.

## Decision

- `TaskExtras.kt` has one `taskExtras(tagRows, depRows)` builder, and one
  `splitByVerb()` that walks the dependency list **once** into the blocking and
  the follow-up maps. `withExtras`, `withScopedExtras` and `freshTasks` all end in
  that builder and in `enrichedWith`; the hand-written copies of the grouping are
  gone.
- `Mappers.kt` maps `ai_suppressed_tag_ids` through `toTagIds()`, which returns
  `emptySet()` for the stored `"[]"` default and decodes only a real payload.
- `AgendaEvaluator` keeps `remaining` as a `MutableSet` and calls
  `removeAll(matched)`. The matching order, the discard semantics and the
  emitted sections are unchanged — only the copy per discard section is gone.

## Rationale

The measured result, same probe and same dataset:

| Phase | Before | After |
|---|---|---|
| `observeByFilter(All)`, no edges | 647 ms | 411 ms |
| `evaluate(All)`, no edges | 247 ms | 62 ms |
| `observeByFilter(All)`, 100k edges | 822 ms | 640 ms |
| `evaluate(All)`, 100k edges | 253 ms | 181 ms |

The evaluator improvement is larger than the read improvement because the set
copy happened once per discard section rather than once per read.

Paging was not adopted, and the issue says why: the agenda's selectors, saved
views, grouping and discard semantics are whole-profile semantics, so a page
evaluated alone would silently change which task lands in which section. The
measured cost here is not "the list is too long to load"; it is "the same rows
are handled twice", which paging would not touch.

No index was added. The measured phases are dominated by row mapping and
grouping, not by table scans: the tag-edge read of 50,000 rows is 117 ms, and the
`user_id` index the queries already use is not the constraint.

## Consequences

- The agenda still retains the whole profile's task graph — that is what the
  screen is for, and the retained 94 MB at 50k is the data, not duplication. What
  changed is the transient cost of building it and of evaluating it.
- The mapper's fast path depends on the column default being exactly `"[]"`. A
  writer that stored `" []"` or `"[] "` would fall through to the decode and
  still be correct, only slower.
- `splitByVerb` ignores a verb that is neither `BLOCKS` nor `FOLLOWS_UP`, which
  is what the two `filter`s did before it. The typed read
  (`observeTypedDependenciesForUser`) still sees every verb, and
  `TaskDependencyVerbFilterTest` pins both facts.
- The 50k claim this issue forbids was not made. This change was measured on
  the JVM against a synthetic profile of the documented shape; the device
  scenario in `docs/architecture/large-restore-results.md` still needs its own
  run to move the boundary.

## Links

- gazon1/sing#3156 — the issue.
- gazon1/sing#3148 / #3154 — the detail-screen read this builds on.
- `docs/architecture/large-restore-results.md` — the measured device limit.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskExtras.kt`
- `core-database/src/commonMain/kotlin/com/singularity/todo/core/database/Mappers.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaEvaluator.kt`
