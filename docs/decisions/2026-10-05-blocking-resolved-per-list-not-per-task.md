---
title: "Blocking is resolved per-list, not per-task"
date: 2026-10-05
tags: [tasks, performance, dependencies, agenda]
status: accepted
---

## Context

`TaskComputed.isBlocked(task, allTasks)` builds a lookup table of `allTasks`
(`associateBy { it.id }`) on **every call**. That is the right shape for a
one-off question about a single task.

The problem is where it was called from. `AgendaEvaluator.evaluate` maps over
every matched row and asked twice per row — once for `AgendaRowItem.isBlocked`
and once more inside `computeAgendaBadge`. For a list of *n* tasks that is O(n²)
against the whole task list.

Measured on this repo's test hardware, evaluating `isBlocked` for every task in a
list:

| tasks | cost |
|---|---|
| 500 | ~56 ms |
| 1000 | ~88 ms |
| 2000 | ~298 ms |
| 4000 | **~1.1 s** |

The doubling from 2000 → 4000 is ~3.8×, which is the quadratic curve, not noise.
At the top of that range the agenda screen spends a second on its main thread
re-evaluating a value that does not change between rows.

## Idea

Two options, both defensible:

1. Keep the per-task call and accept the cost — the evaluator is already O(sections × tasks).
2. Resolve blocking once per evaluation and consult the result.

## Decision

`TaskComputed.blockedIds(allTasks)` resolves every blocked task in a list with
**one** index, O(n + edges). `AgendaEvaluator.evaluate` calls it once and passes
the resulting `Set<TaskId>` down; `computeAgendaBadge` gained an overload taking
that set, and `ProjectDetailViewModel`'s blocked filter uses the same call.

`isBlocked(task, allTasks)` is **kept, unchanged, and still correct** for a
single task. Its KDoc now states the cost and points at `blockedIds`, because the
failure mode here is invisible: the function returns the right answer, it is just
slow, so nothing reports a problem until a user with a large task list sees a
stalled screen.

## Rationale

The two call sites shared a property that made the batch form natural: every row
in an evaluation is blocked by the *same* task list. The answer varies per task,
but the input never does. Building the index once per evaluation is therefore not
an optimisation trick — it is the shape the question actually has.

Keeping `isBlocked` rather than deleting it matters too: it is the readable
expression of the rule, it is what tests assert against, and it remains the right
call for a single task. The batch version is an addition, not a replacement.

## Consequences

- `AgendaEvaluator` is now linear in tasks for the blocked computation, and
  resolves blocked-ness once per emission instead of twice per row.
- `ProjectDetailViewModel` builds the set only when the filter is on, so the
  default view pays nothing — matching the principle in
  `2026-10-05-projectdetail-hide-blocked`.
- **The quadratic trap is still reachable** by writing `isBlocked` in a loop. The
  KDoc now names the measured cost so the next caller can find it. A detekt rule
  forbidding `isBlocked` inside a `filter`/`map` over tasks would close this
  properly; it is not written yet (see Unresolved).
- Semantics are unchanged and pinned by tests: a dependency absent from the list
  does not block, and a completed or trashed dependency does not block.

## Unresolved

- A detekt rule rejecting `TaskComputed.isBlocked` inside a lambda over tasks
  would make the fast path structural rather than advisory. Worth adding if the
  call site count grows.

## Links

- `feature/tasks/domain/logic/Computed.kt` — `blockedIds`, and the cost note on `isBlocked`
- `feature/agenda/domain/logic/AgendaEvaluator.kt` — resolves once per evaluation
- `feature/agenda/domain/logic/AgendaBadgePolicy.kt` — the set-taking overload
- `2026-09-18-task-dependencies` — the blocking rule itself
- `2026-10-05-projectdetail-hide-blocked` — the other consumer