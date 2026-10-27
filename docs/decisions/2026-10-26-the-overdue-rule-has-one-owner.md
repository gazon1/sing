---
title: "The overdue rule has one owner, and the trash is part of it"
date: 2026-10-26
status: accepted
tags: [tasks, agenda, ui]
refs: ["#1188"]
---

# The overdue rule has one owner, and the trash is part of it

## Context

`TaskComputed.isOverdue` says a task is overdue when it has a due date before today,
is not completed and is **not trashed**. That is the rule the task filter
(`TaskFilter.ByDateBucket`) and the calendar mapper already ask.

Two surfaces answered the same question themselves, and both of them stopped at
"not completed":

- `Selector.Overdue` and `Selector.DateBucket(RelativeBucket.Overdue)` in the agenda
  matched `dueDate < today && !isCompleted`, so a task that had been archived still
  appeared in the Overdue section of the agenda.
- `computeTaskMarkings` marked a task `TaskMarking.Overdue` on its own `dueDate < today`
  check, so the same archived task carried an overdue badge on the row.

The two copies had already drifted from the owner by the one condition that separates
them, and the drift was visible to the user: the task was in the trash, and the agenda
and the badge still said it was late.

The blocking side of the same question had already been settled — `Blocking` is the one
owner of "is this task blocked?" (`2026-10-22-the-blocking-rule-has-one-owner`).

## Idea

A rule that more than one surface answers is a rule that will drift again. The overdue
rule already has an owner; the other two surfaces should ask it instead of restating it.

## Decision

`TaskComputed.isOverdue(task, today)` is the single implementation of "is this task
overdue?".

- `Selector.Overdue` and `Selector.DateBucket(RelativeBucket.Overdue)` delegate to it.
- `computeTaskMarkings` delegates to it, so the marking and the predicate cannot
  disagree.
- Nothing is stored: overdue stays a computed value, read on demand.

The blocked rule keeps its own owner, `Blocking` — it answers more than a yes/no, and
the surfaces that need "by what" ask it there.

## Rationale

One implementation per question. Two answers to one question drift, and the drift is
silent until a user sees a task in the trash that the agenda calls late.

A trashed task is not overdue: the user has put it away. The owner already said so, and
the copies were the ones that had to change.

Delegating also removes the possibility of a third copy. A new surface that wants to
know whether a task is overdue now has one function to call, and a test that pins the
delegation cannot drift away from it.

## Consequences

- An archived task stops matching the Overdue selector and stops carrying the Overdue
  marking. This is the intended change of behaviour, and the tests that pin it live
  with the two surfaces that changed.
- The health buckets, the AI task tools and the statistics counts do not change by
  themselves: they ask through the selector, and the selector now asks the owner.
- `Selector.Overdue` is no longer "a shortcut for `DateBucket(Overdue)` combined with
  `Statuses(Active)`" — it is the same question as the date bucket, asked of the owner.

## Links

- `docs/decisions/2026-10-22-the-blocking-rule-has-one-owner.md` — the same shape on
  the blocked side.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/Computed.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/selector/SelectorMatcher.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskMarkingPolicy.kt`
