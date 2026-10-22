---
title: "A blocked task asks before it closes, and a closed project takes its tasks with it"
date: 2026-10-21
status: accepted
tags: [tasks, projects, dependencies, ui, confirmation]
supersedes: 2026-09-18-task-dependencies
---

# A blocked task asks before it closes, and a closed project takes its tasks with it

## Context

Two gaps, one cause.

**The task gap.** `2026-09-18-task-dependencies` (MR-1) decided, in one line:
"`dependsOn` is **not** enforced at the data layer — completion is always allowed."
The decision was reasonable then — MR-1 shipped a *badge*, not a rule — but it left
the one behaviour a user actually notices unspecified. A task whose prerequisite is
still open can be closed from the agenda checkbox, from the task detail checkbox, or
from a subtask checkbox, with no question asked and nothing said about what was
still unfinished. The badge that MR-1 built says "this is blocked"; the checkbox next
to it says "close it anyway" and does. The two disagree, and the second one wins.

**The verb gap.** `TaskDependency` has carried a verb (`BLOCKS`, `FOLLOWS_UP`) since
`2026-10-01-typed-task-dependency-links`, but `Task.dependsOn` is a flat
`Set<TaskId>` with no verb attached. Every consumer therefore reads "has a
dependency" where it means "is blocked by" — `TaskComputed.isBlocked`,
`TaskComputed.blockedIds`, the agenda badge, `Selector.Blocked`, the health bucket,
the "hide blocked" filter, the dependency picker. The KDoc on the field has said all
along that it means "IDs of tasks that **must be completed** before this task can be
completed", which is the `BLOCKS` meaning, so the model and its documentation already
disagree with the implementation.

**The project gap.** A project can be archived and it can be deleted. It cannot be
*closed*. `Project` has no `closedAt`, no `completedAt`, no `status` — only
`isDeleted`, which is a soft delete with a different meaning and a different
reversibility. And there is no cascade anywhere: closing a set of work leaves every
task in it live and actionable.

## Idea

For the task gap, three options:

1. **Hard block.** The data layer refuses to complete a task with an open blocker.
   Deterministic, impossible to bypass — and wrong for a real workflow: sometimes the
   prerequisite *is* done and the user knows better than the graph, and a rule that
   cannot be overridden is a rule the user will route around by deleting the edge.
2. **A warning badge.** Already shipped by MR-1, and it is what produced the split
   described above.
3. **A confirmation dialog.** The task stays closable, but closing it asks first and
   names what is still open.

For the verb gap, the choice was whether to widen `Task.dependsOn` to carry verbs or
to narrow what it means. Widening touches every consumer and every call site;
narrowing touches one place and every consumer inherits the correction.

For the project gap: reuse `isDeleted` as "closed", add a `status` enum, or add a
`closedAt` timestamp.

## Decision

**Blocking means `BLOCKS`, and only `BLOCKS`.** `Task.dependsOn` is filled from the
dependency edges whose verb is `BLOCKS`; edges with any other verb are not part of the
set. The filter lives in the repository, not in the SQL: the cross-reference query
already returns the verb, so narrowing is a predicate over rows that have already
been read. The cycle-detection reads, the backup export and the sync payload's
dependency list keep every verb.

**Closing a blocked task asks.** When the user closes a task that has at least one
active blocker, the write does not happen; the screen shows a confirmation that names
the open blockers, and the write happens only if the user confirms. Un-closing a task
is never blocked — only the closing direction asks.

**The check is one pure function over data the caller already has.** It is not a use
case and it does not live in the data layer, because it does not write: the view
model asks, the view model writes, through the path that already existed for that
surface. The function returns the blocking *tasks*, not a boolean, because the dialog
has to say which ones are in the way.

**Closing a project is a new operation, not an archive.** `Project` gains
`closedAt`. Closing a project also closes its direct, still-open tasks. The cascade
is confirmed in the same way when one of those tasks is blocked, and it is applied
fail-fast: if any task in the set cannot be closed, nothing is closed. A closed
project leaves the project list, like an archived one.

**Synchronisation, backup restore, and AI proposals are not guarded.** A write that
arrives from outside the user's own action is authoritative.

## Rationale

The dialog was chosen over the hard block because the rule is advisory by nature:
"you may close this, but something is unfinished" is information, and information
belongs in a prompt, not in a refusal. It was chosen over the badge because the badge
already exists and did not prevent anything — the user was told and then the task
closed anyway, which is the same as not telling them.

Narrowing `dependsOn` to `BLOCKS` rather than widening the field was chosen because
the model's own documentation already promises the `BLOCKS` meaning: the contract was
written down and not executed, so this is an implementation catching up with a
decision rather than a new contract. Narrowing at the repository rather than in the
SQL was chosen because the SQL reads are shared — cycle detection, backup export and
the sync payload all need the unfiltered graph, and a narrowed query would have
silently changed the wire format and the cycle walk. The audit that justified the
risk found that no production code path creates a non-`BLOCKS` edge at all: the
dependency picker only ever writes `BLOCKS`, and the backup format does not carry the
verb. So the narrowing is behaviourally inert today and fixes the semantics for
when the other verbs get a writer.

`closedAt` was chosen over reusing `isDeleted` because the two mean different things:
`isDeleted` is a soft delete whose recovery path is the trash, and `closedAt` is a
state a project can be in while still being fully readable. Overloading one with the
other would have made the archive show closed projects and the closed set show
deleted ones.

Cascading over leaving tasks open was chosen because "close the project" that leaves
its tasks in the agenda is a promise the system does not keep. The cascade was
limited to direct tasks because `parentId` is a hierarchy, not an ownership relation:
a subproject is not contained by its parent the way a task is contained by a project.

The check was kept out of the repository because there are two write paths — a SQL
toggle in the agenda and a copy-and-write in the task detail — and the second
distinguishes recurring tasks. A single write point would have meant moving that
distinction into a new layer; three call sites of one pure function is the smaller
change. The alternative, one check in the repository, would have left the task
detail screen unguarded.

## Consequences

- Only `BLOCKS` edges count as blocking. A task that "follows up" on another is no
  longer reported as blocked by it. Every consumer of the blocking predicate
  inherits this without being changed — badge, selector, health bucket, filter,
  picker.
- The `dependsOn` set of a task is no longer the set of all its dependency edges; it
  is the set of edges that block it. Code that needed every edge must ask for the
  typed edges.
- Completion of a blocked task is a two-step interaction from now on: the request,
  then the confirmation. Any surface that closes a task must route through the
  check, and the check must be asked *before* the write, not after.
- Un-completing a task is never gated.
- A closed project is a distinct state from an archived or deleted one, and closed
  projects are excluded from the project list.
- Closing a project closes its open direct tasks. Recurring tasks in that set are
  closed without a next occurrence.
- A confirmation dialog is state in the view model, not a one-shot event: a rotation
  must not silently cancel a pending confirmation.
- Between the check and the write there is a window in which a blocker can appear or
  disappear. The window is accepted: the operation is interactive, and the window is
  narrow. It is not eliminated.
- A write arriving from synchronisation, from a backup restore, or from an accepted
  AI proposal bypasses the check, and that is intended.
- `Project.closedAt` participates in synchronisation and in the backup format, and
  the project entity's schema version moves.

## Links

- Supersedes, in part: `2026-09-18-task-dependencies` — its "completion is always
  allowed" consequence is replaced by the confirmation.
- `2026-10-01-typed-task-dependency-links` — the verb model the narrowing relies on.
- `openspec/changes/blocked-completion-guard/` — the specification of the behaviour.
- `openspec/specs/tasks/spec.md` — the requirement that closing a blocked task asks.
- `openspec/specs/destructive-action-safety/spec.md` — the pattern of a risky action
  requiring an explicit confirmation.
- Files: `feature/tasks/domain/logic/Blocking.kt`,
  `feature/projects/domain/usecase/CloseProjectUseCase.kt`,
  `core/ui/components/BlockedCompletionDialog.kt`,
  `core/database/Migration42To43.kt`.
