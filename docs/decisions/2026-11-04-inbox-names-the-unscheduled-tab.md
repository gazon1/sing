---
title: "Inbox names the unscheduled tab; the wider filter is NotDeferred"
date: 2026-11-04
status: accepted
tags: [tasks, naming, agenda, ai]
---

# Inbox names the unscheduled tab; the wider filter is NotDeferred

## Context

Two different sets were called Inbox. The agenda tab `AgendaPresets.Inbox` holds the
tasks with no due date, because the three tabs are a partition and this one answers
"what have I not scheduled yet". The code-level `TaskFilter.Inbox` — read by the
pomodoro picker and by the AI tool's `inbox` selection — held every task that was not
deferred and not in the trash: dated or undated, and finished ones too. The
disagreement was deliberate after the tab was narrowed, but only a comment and one
consequence bullet said so, so a caller who reached for "Inbox" got the wider answer
while a user who saw an "Inbox" tab got the narrower one.

## Idea

Keep the tab and rename the filter, or keep the filter and rename the tab to "No
Date". Renaming the tab would touch navigation labels, routes, stable content keys,
Maestro flows and the desktop flow tests, and "Inbox" is the name the product says for
the pile that has not been scheduled. Renaming the filter touches the filter's own
callers and the model-facing vocabulary.

## Decision

The tab keeps the name Inbox. The filter becomes `TaskFilter.NotDeferred`, and the AI
selection becomes `open` (`@SerialName("open")`), described as every unfinished task
that is not deferred, dated or undated.

## Rationale

The user-facing name is the expensive one to change and the one the product wants to
keep; the filter and the AI selection are caller-facing, so they take a name that says
what they answer. `NotDeferred` is the honest name for the domain rule — not in the
trash, not deferred. `open` is the honest name for the AI set, which applies the
application's active-status rule on top and so excludes finished tasks.

## Consequences

- `TaskFilter.NotDeferred` never filters by completion. A caller that wants the
  unfinished tasks narrows it with `TaskStatus.Active`, as `TaskSelection.OPEN` does.
- The repository's `NotDeferred` read is `watchNotDeferred`
  (`archived_at IS NULL AND someday = 0`), so the name is a description of the query.
  It used to be `watchActive`, which returned the deferred tasks as well; the name and
  the query disagreed and only this section said so. That is no longer the case: the
  two filters that remain answer two questions, and `TaskRepositoryContractTest` pins
  what each read selects so a rename cannot change it quietly.
- `TaskFilter.All` is the wider question — everything not in the trash, the deferred
  tasks included — and it keeps `watchActive`.
- The AI tool's selection vocabulary has no `inbox`. A caller that sends it gets a
  validation error and should ask for `open`.
- A cursor issued before the rename carries the old fingerprint and is refused with a
  warning rather than paging a different list.
- The agenda's Inbox tab, the three-tab partition and the undated section are
  unchanged.

## Links

- Issue #1950
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/Task.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskSelection.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ListTasksTool.kt`
- [2026-10-31-the-agenda-tabs-are-disjoint.md](2026-10-31-the-agenda-tabs-are-disjoint.md) — the partition this name sits under
