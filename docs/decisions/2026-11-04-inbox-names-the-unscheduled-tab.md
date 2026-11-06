---
title: "Inbox names the unscheduled tab; the wider filter is NotDeferred"
date: 2026-11-04
status: accepted
tags: [tasks, naming, agenda, ai]
---

# Inbox names the unscheduled tab; the wider filter is NotDeferred

> **Superseded in part (2026-11-06):** the `inbox` alias is gone. The wire no longer
> answers to that word — a caller that sends it is refused rather than given a wider
> answer than the one it asked for. The names themselves, the tab partition and the
> filter's new name stand. See
> [2026-11-06-one-name-one-question.md](2026-11-06-one-name-one-question.md).

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
selection becomes `active_not_deferred` (`TaskSelection.ACTIVE_NOT_DEFERRED`,
`@SerialName("active_not_deferred")`), described as every unfinished task that is not
deferred, dated or undated. The old `inbox` name decoded as that selection at first,
so a caller that sent it kept working; the alias was removed on 2026-11-06, and a
caller that sends it now gets an error (see the note above).

## Rationale

The user-facing name is the expensive one to change and the one the product wants to
keep; the filter and the AI selection are caller-facing, so they take a name that says
what they answer. `NotDeferred` is the honest name for the domain rule — not in the
trash, not deferred.

The AI selection needs a longer name, because the vocabulary already holds a shorter
one that means something else. `active` is every unfinished task, the deferred ones
included, and the app already calls a task "open" when it is not finished. A bare `open`
beside `active` would therefore leave a reader guessing which of the two it wants. So
the narrower set states its exclusion in its own name: `active_not_deferred` says both
halves — unfinished, and not deferred — while `active` says nothing extra, because
nothing is excluded from it. Read the pair as a whole and the qualifier is what tells
them apart.

## Consequences

- `TaskFilter.NotDeferred` never filters by completion. A caller that wants the
  unfinished tasks narrows it with `TaskStatus.Active`, as
  `TaskSelection.ACTIVE_NOT_DEFERRED` does.
- The repository's `NotDeferred` read is `watchNotDeferred`
  (`archived_at IS NULL AND someday = 0`), so the name is a description of the query.
  It used to be `watchActive`, which returned the deferred tasks as well; the name and
  the query disagreed and only this section said so. That is no longer the case: the
  two filters that remain answer two questions, and `TaskRepositoryContractTest` pins
  what each read selects so a rename cannot change it quietly.
- `TaskFilter.All` is the wider question — everything not in the trash, the deferred
  tasks included — and it keeps `watchActive`. The pomodoro picker reads it, because
  the filter it used to read resolved to the same query: the picker's contents did not
  change with the rename. Narrowing the picker to `NotDeferred` was considered on
  2026-11-06 and rejected: the picker keeps the wide set, because hiding a task from
  it hides work the user chose to keep visible, and a session on a deferred task is
  the user's call to make.
- The AI tool's selection vocabulary has `active_not_deferred` and no `inbox` of its
  own. `inbox` was a read-only alias that decoded as `active_not_deferred` until
  2026-11-06; it is gone now, and a caller that sends it is refused rather than given
  a wider answer than the one it asked for. A test pins both directions, because a name
  that quietly stops decoding is a break nobody notices until a caller is refused.
- The wire name buys the *selection*, and nothing else. A cursor names the selection it was
  reading by its wire name, so renaming the constant does not invalidate the cursors
  already handed out; a cursor issued by a build that keyed the fingerprint on the
  constant's name is refused with a warning rather than paging a different list. The
  tool's description says so, because a model handed a cursor it cannot use has to be
  able to name why.
- The agenda's Inbox tab, the three-tab partition and the undated section are
  unchanged.

## Links

- Issue #1950
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/Task.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskSelection.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ListTasksTool.kt`
- [2026-10-31-the-agenda-tabs-are-disjoint.md](2026-10-31-the-agenda-tabs-are-disjoint.md) — the partition this name sits under
