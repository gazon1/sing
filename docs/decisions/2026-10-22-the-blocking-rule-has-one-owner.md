---
title: "The blocking rule has one owner, and the write is what refuses"
date: 2026-10-22
tags: [tasks, dependencies, projects, database, sync]
status: accepted
supersedes: 2026-10-05-blocking-resolved-per-list-not-per-task
---

## Context

The blocked-completion guard landed in `2026-10-21-blocked-completion-guard`: closing a
task with an unfinished prerequisite asks the user first. That change made the rule
visible, and a visible rule is a rule that has to have exactly one owner. It had three
problems.

**The rule had two owners.** `TaskComputed.isBlocked` and `TaskComputed.blockedIds` asked
"is this task blocked", and the new `Blocking.activeBlockers` asked "by what". They
agreed that day, but they were free to drift — and they had already drifted once, which
is why the guard existed: the badge asked whether the dependency set was empty while the
rule asked whether the prerequisites were finished. Two answers to one question, and the
user met both.

**The check and the write were two statements.** The guard ran in the view model, read
the task list, and asked; the write ran afterwards, and did what it was told. Between
them there was a window, and the window is where another device reopens the
prerequisite: the check said yes, the write said yes, and the task was closed while the
work it was waiting for had just come back. The old ADR recorded this as an accepted
limitation. It was accepted because there was no cheaper way to say it — but there was.

**The question was asked in three places and answered in two.** The agenda, the task card
and the subtask checkbox asked. The bulk close did not: `TaskMutationsUseCase.bulkComplete`
called the repository's toggle directly, so closing ten tasks at once was the way around
the question. There is no UI for it yet, which is exactly why it survived — a rule with
no caller is a rule nobody notices is broken.

Two smaller things came with it. The project's close cascade wrote several rows with no
transaction around them, so a failure in the middle left half the work closed and the
project open — a state that matches neither what the user asked for nor what the app
promised. And the cascade asked "is this task blocked" by reading *every task the user
has*, to answer a question about ten of them.

## Idea

Three options, all defensible:

1. Keep the check in the view model and document the race.
2. Move the check into the write, so the statement itself refuses.
3. Make the write unconditional and let the UI be the only gate.

Option 3 is what the old design was, and it is what let the bulk path through: a rule
that lives in the UI is a rule the next caller re-implements, or forgets.

## Decision

**The rule has one owner.** `Blocking` owns it, in four forms that differ only in what
they return: `activeBlockers` (by what), `activeBlockersByTask` (by what, for a list),
`isBlocked` (yes or no), `blockedTaskIds` (yes or no, for a list). `TaskComputed` keeps
the predicates about time and shape — overdue, ready, active — and nothing about
blocking.

**The write is what refuses.** `TaskRepository.complete` completes a task only while no
blocking prerequisite is open, and the condition is *inside* the statement
(`markCompleteIfNotBlockedForUser`). There is no separate check, so there is no window.
The answer and the write are the same fact. `force = true` is the confirmed path, and
only the caller that has seen the user's confirmation passes it.

The surfaces changed accordingly: they try the write, and ask when it refuses, rather
than asking first and writing after. The observable behaviour is the same — the user is
asked, and nothing is written until they confirm — and the difference is that the second
device's answer is the one that counts.

**The bulk close asks too.** `bulkComplete` resolves the blocking edges of the selection
before writing anything, and a blocked task in it stops the whole operation with the
names of the tasks in the way.

**The cascade is one transaction, and its reads are targeted.** The project's close
writes the open tasks and then the project inside one `UnitOfWork`, so it lands whole or
not at all. It reads the project's own tasks and the prerequisites of those tasks — not
the whole task list.

**Reopening a task reopens its project.** A closed project leaves the project list, but
its tasks can still be picked up in the agenda. Without the rule the task would come
back alone and belong to nothing. The reopen of the task and the open of the project
happen together.

**The field says what it holds.** `Task.dependsOn` became `Task.blockedBy`: the set holds
only the edges that block, since `2026-10-21`. The *serialized* name stayed `dependsOn`
(`@SerialName`), because that name is in the sync allow-list and in every backup ever
written, and the server stores documents by it. The domain says what it means; the wire
keeps saying what it has always said. `TaskWireFieldNamesTest` pins both directions,
because a rename that quietly reached the wire would have been invisible to every test
that only looked at Kotlin.

## Rationale

The through-line is that a rule the user can be asked about has to be enforced by the
write, not by the caller. Every defect above is a case of the rule being somewhere
other than where the decision is made: in a badge, in a view model, in a bulk path that
nobody wired yet. Moving the rule into the statement makes the three cases one case.

The rename is the same argument applied to a name. A field called `dependsOn` that holds
only the blocking edges is a lie the reader has to disprove by reading the KDoc; the
KDoc is one edit away from being out of date. `blockedBy` cannot be wrong.

The wire name stays because the wire is not ours to rename. The sync allow-list is a
server-side contract and every backup already on disk uses the old name; a domain
rename that changed the wire would have been a migration of other people's data, done in
a feature about closing tasks.

## Consequences

- The agenda and the task card no longer decide for themselves whether a close is
  allowed — they ask the write and react. The recurring path still checks first,
  because rolling a recurrence forward is a different write and the refusal is not part
  of it; that path keeps the race, and it is the one place it does.
- `TaskRepository.toggleComplete` is gone. `complete` and `reopen` say which direction
  they mean, and a toggle had to be read to be understood.
- `observeDependencies`, `observeTypedDependencies` and `observeBlockedBy` are gone from
  the repository: nothing outside the graph needed them, and the graph is read where
  the graph lives.
- The blocked-task read is a query, not a walk: `blockedTaskIdsForUser` resolves the
  edges of the given tasks only.
- A closed project can be opened again, and it opens when one of its tasks comes back
  to work. There is still no screen that lists closed projects — a user who closes a
  project by mistake can only get it back through one of its tasks.

## Links

- `docs/decisions/2026-10-21-blocked-completion-guard.md` — the guard itself
- `docs/decisions/2026-10-05-blocking-resolved-per-list-not-per-task.md` — superseded in
  the part that named `TaskComputed.blockedIds` the canonical form
- `openspec/changes/archive/2026-10-25-close-rule-consolidation/` — the observable behaviour, stated
