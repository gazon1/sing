---
title: "One marking vocabulary, not one badge"
date: 2026-10-26
status: accepted
tags: [tasks, agenda, ui, domain]
---

# One marking vocabulary, not one badge

## Context

The repository has two vocabularies for the same question — "what is this task
marked with?" — and both are defective in a way this feature exposes.

The agenda has `AgendaBadge` and a first-non-null-wins priority table
(`AgendaBadgePolicy.kt:15-25`). That table is why a task that is both blocked and
overdue shows only one of the two facts. Worse, the badge is **never rendered at
all**: `AgendaContent.kt:392-421` builds a `TaskUi` and drops `rowItem.badge` on the
floor, and `AgendaBadge` appears nowhere else outside domain logic and preview data.
There is no badge widget to widen — the surface has to be built.

Meanwhile the task list shows the same facts through booleans on `TaskUi`
(`isBlocked`, `isOverdue`, `isRecurring`) rendered by `TaskMetaRow.kt:55-93`. That
surface already shows several facts at once, but it cannot carry a workflow state,
and it cannot express "this task is in progress *and* blocked" as one coherent set.

## Idea

A new `TaskMarking` enum in `feature/tasks/domain/model/` becomes the single
vocabulary. A pure policy function — `TaskMarkingPolicy.compute(task, context)` —
returns an `ImmutableSet<TaskMarking>`, replacing `computeAgendaBadge`'s single
value and the row's parallel booleans. A shared `TaskMarkingsRow` renders the set
in the display order from `data-model.md`.

## Decision

`TaskMarking` is a new enum in `feature/tasks/domain/model/`, with nine values —
`Blocked`, `FollowUp`, `NextUp`, `InProgress`, `Overdue`, `NoDate`, `Pinned`,
`Recurring`, `Completed`. It lives in the **tasks** feature, not the agenda: the
task list must not depend on the agenda (the agenda already depends on the tasks),
and a marking is a fact about a task rather than about a view. The agenda imports
it. `AgendaBadge` and `computeAgendaBadge` are retired; `TaskUi.isBlocked`,
`isOverdue` and `isRecurring` are replaced by `markings: ImmutableSet<TaskMarking>`.

The marking policy is a pure domain function over an explicit context —
`TaskMarkingContext(today, blockedIds, followUpIds)` — returning
`ImmutableSet<TaskMarking>`. Both sets are computed once per evaluation from the
whole task list and shared, not recomputed per task. `FollowUp` and `Blocked` are
mutually exclusive by construction: a `FOLLOWS_UP` edge never makes a task wait —
only a `BLOCKS` edge does — so the two cannot both be set by the same relation.

## Rationale

- **The vocabulary has to live in the tasks feature or below.** The alternative —
  making `AgendaBadge` a set and reusing it from there — was rejected: it lives in
  the agenda feature, and the task list cannot depend on the agenda.
- **A growing set of parallel booleans cannot answer the question.** Keeping the row
  booleans and adding two more was rejected: it cannot express "in progress *and*
  blocked" as one set, and it is exactly what made the badge's first-non-null-wins
  priority table necessary in the first place.
- **A marking is a fact, not a thing.** A separate "markings" table was rejected:
  a marking is derived from the task and its neighbours, not stored on the task.
- **A pure function with an explicit context is testable without infrastructure.**
  Computing in SQL was rejected: blocked-ness spans `tasks` and `task_dependencies`
  and needs a join plus a "is the prerequisite finished?" test; the agenda already
  does this in memory once per evaluation, and doing it once and sharing it is
  cheaper than doing it per query. Computing in each ViewModel was rejected: two
  ViewModels answering the same question is how the two vocabularies came to
  disagree.
- **The renderer imposes a display order** so that two rows with the same markings
  look the same — `InProgress → NextUp → Blocked → FollowUp → Overdue → NoDate →
  Pinned → Recurring → Completed`. When the row is too narrow for all of them, the
  renderer states how many it did not show rather than dropping one silently.

## Consequences

- `AgendaBadgePolicy` and the `AgendaBadge` type are retired; the agenda evaluator
  calls `TaskMarkingPolicy.compute` instead of `computeAgendaBadge`.
- `TaskUi` loses `isBlocked`, `isOverdue` and `isRecurring` and gains
  `markings: ImmutableSet<TaskMarking>`. `blockedBy` stays — it is the set of
  prerequisite ids, not a marking, and the detail screen shows it as the dependency
  list.
- `TaskMarkingsRow` is rendered by both the task list (`TaskRowContent`) and the
  agenda (`AgendaContent`), so the same fact looks the same in both places.
- The duplicated rules in `SelectorBadge.kt` are folded into `TaskMarkingPolicy`,
  which becomes the single source of truth for matching a marking to a
  `Selector`.
- `FOLLOWS_UP` edges must be visible to the marking policy: the read path that
  filtered to `BLOCKS` only is widened, and the write path that hard-coded
  `DependencyVerb.BLOCKS` becomes verb-aware, so a follow-up relation can be
  created and read. This is the prerequisite that makes the `FollowUp` marking
  non-vacuous.

## Links

- `docs/decisions/2026-10-19-selector-registry-completeness-guard.md`
- `docs/decisions/2026-10-23-the-dependency-graph-has-two-projections.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaBadgePolicy.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/AgendaUiState.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/model/TaskUi.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskMetaRow.kt`
