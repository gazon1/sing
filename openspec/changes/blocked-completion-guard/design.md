# blocked-completion-guard — Design

Required: cross-cutting (two features, five surfaces, two platforms), a new
data model field, and a product rule that every future "close a task" surface
has to honour.

The rationale lives in `docs/decisions/2026-10-21-blocked-completion-guard.md`.
This document records the shape of the implementation, not why it was chosen.

## The check is a pure function, and the write stays where it was

The closing of a task happens through two different write paths:

| Surface | Path | What it does |
|---|---|---|
| Agenda row checkbox, agenda context menu (Android), agenda context menu (Desktop) | a SQL toggle on the task row | flips the completion timestamp |
| Task detail checkbox | a copy-and-write of the task, and for a recurring task a separate "complete and schedule the next" operation | sets the completion timestamp, or moves the recurrence on |
| Task detail subtask checkbox | a copy-and-write of the subtask | sets the completion timestamp |

A single write point would have meant moving the recurring-task distinction
into a new layer, and would have made the agenda's toggle a special case of
something it is not. Instead:

- **the check** is one pure function over data the caller already holds — the
  task and the list of tasks in scope. It answers "which prerequisites are still
  open for this task", and it returns them, because the dialog has to name them.
- **the write** stays on the three paths it is on today, each asking the check
  first.

The function is not a use case: it does not write, it does not read, and a use
case that only answers "may I?" would be a pass-through with a repository
dependency and no behaviour of its own.

## What counts as blocking, and where the filter goes

A dependency edge carries a relationship. Only one of them means "this task
cannot be finished until that one is".

The filter is applied where the edges are read into the task model — in the
repository, not in SQL. The reasons:

- The cross-reference query already returns the relationship, so the filter is a
  predicate over rows that have already been read.
- The same table is read by the cycle analysis, by the backup export, and by
  the sync payload's dependency list. All three need *every* edge. Narrowing the
  query would have changed the wire format and the cycle walk without anyone
  noticing.

The narrowing is behaviourally inert today: no production path creates an edge
of any other kind. The dependency picker writes the blocking kind; the backup
format does not carry the relationship at all. So this fixes the meaning
without changing anything a user can observe today.

One consequence worth naming: the set of dependencies a task carries and the set
of edges in the graph are no longer the same thing. The model keeps the ones
that block; the graph keeps them all.

## The dialog's state belongs to the view model

The decision — "close it anyway or not" — is a decision, and decisions are
made in the view model. But the dialog must survive a rotation: an open
confirmation that vanishes when the screen is recreated is a confirmation the
user never answered.

So the pending confirmation is **state** in the view model, not a one-shot
event. The screen renders the dialog from that state, and the user's answer
comes back as an intent. The domain data the dialog needs (which prerequisites
are open) is computed, not stored, so the state only has to carry the task's
identity and the names to show.

The dialog itself is shared between the agenda and the task detail, because
both need the same words and the same buttons.

## Closing a project is one operation, applied fail-fast

Closing a project touches two things: the project itself, and its still-open
tasks. The two have to move together or the state is wrong: a closed project
with live tasks still in the agenda is a promise the system did not keep.

So it is one operation with a precondition:

1. Collect the project's direct tasks that are still open.
2. Check every one of them against the blocking rule. If any is blocked, stop
   and report which — **nothing is written yet**.
3. Otherwise write: close the tasks, then close the project.

The fail-fast is the precedent already set by the existing bulk-completion
operation, which validates every id before it mutates any of them. A partial
cascade is possible only if the database fails mid-way, which is accepted and
named in the confirmation text ("all still-open tasks will be closed").

The cascade stops at direct tasks. A sub-project is a position in a hierarchy,
not something the parent contains — closing a parent project does not close a
child project's work, and it does not close the child project.

Recurring tasks in the set are closed without a next occurrence, and the
confirmation says so. A closed project that scheduled a new instance of one of
its tasks tomorrow would be a project that is not closed.

## The new project state is a timestamp, not a flag and not a deletion

The project entity already has a soft-delete pair (`isDeleted`, `deletedAt`).
Reusing it for "closed" would have made the archive contain closed projects and
the closed set contain deleted ones, and the two are not the same thing: one is
recoverable from the bin, the other is a state the work reached.

So the project gains a `closedAt` timestamp, nullable, meaning "not closed".
It follows the completion timestamp that tasks already carry.

Consequences that follow from the field existing:

- The project's list queries must exclude closed projects, the way they already
  exclude archived ones. Every query that lists projects has to be checked;
  a missed one shows a closed project in the list.
- The field is part of the project's synchronised state, so it is in the
  allow-list that the server applies. The server stores documents as opaque
  JSON, so no server-side schema change is needed — only the allow-list entry
  and the regenerated seed.
- The field is part of the backup format, in both directions.

## The window between the check and the write is accepted

The check reads the task list; the write happens later. In between, a
prerequisite can be finished (the confirmation was unnecessary but harmless) or
a new one can appear (the close happens without a question).

The window is narrow — the operation is interactive, and the check and the
write are separated by one user action at most. Eliminating it would mean
re-checking at write time inside the write path, which is where the
recurring-task distinction and the SQL toggle live, and which would have meant
moving the rule into the data layer — the layer that also serves writes arriving
from synchronisation, a backup restore and an accepted AI proposal, none of
which should be gated.

The decision records this as an accepted limitation rather than pretending it
does not exist.
