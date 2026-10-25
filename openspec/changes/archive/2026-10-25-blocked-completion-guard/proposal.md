# blocked-completion-guard

## What

Two user-visible changes that share one cause — the dependency graph records
more than the model carries, and the model records more than the system enforces.

1. **Closing a blocked task asks first.** A task whose blocking prerequisite is
   still open can today be closed from five places in the app, silently, with
   nothing said about what is still unfinished. Closing one now shows a
   confirmation that names the open blockers, and nothing is written until the
   user confirms.
2. **A project can be closed, and closing it takes its open tasks with it.** A
   project can be archived and it can be deleted; it cannot be *closed*. Closing
   a project closes its still-open tasks, and asks first if one of them is
   blocked.

Alongside these, the meaning of "blocked" is corrected: only a dependency that
*blocks* counts. A dependency that merely *follows up* no longer stops a task
from being reported as blocked.

## Why

The badge that tells the user a task is blocked and the checkbox next to it that
closes the task anyway are two halves of one feature, and only one of them was
implemented. The badge says "this is not ready"; the checkbox says "close it" and
does. The user is told and then overridden by the very affordance they were
looking at.

The word "blocked" also means two things. The dependency edges carry a
relationship — one blocks the other, or one follows up on the other — but the
task only remembers *that* it has dependencies, not *which kind*. So every
consumer of "is this blocked" — the badge, the selector, the health bucket, the
filter that hides blocked tasks, the picker — reads "has a dependency" where it
means "has an unfinished prerequisite". The model's own documentation has said
"must be completed before" since the beginning.

And the project has no end state other than deletion. There is a way to put a
project in the bin and a way to take it out of sight; there is no way to say
"this work is finished", which is the thing a project list is for.

## Scope

### In scope

- The confirmation when closing a task that has an open blocking dependency,
  at every place the user can close a task.
- The correction of "blocked" to mean "blocked by", not "has any dependency".
- Closing a project, and the cascade to its still-open tasks.
- The confirmation for that cascade when one of the tasks is blocked.
- The new project state in storage, in synchronisation and in the backup.

### Out of scope

- Refusing to close a blocked task. The user may always close it; the system
  asks.
- Blocking the reverse direction — removing a task that others depend on.
- Closing sub-projects as part of closing a project. A sub-project is a
  hierarchy position, not something the parent contains.
- Changing what synchronisation, a backup restore, or an accepted AI proposal
  may write. A write that arrives from outside the user's own action is
  authoritative.
- Deleting dependencies as part of the cascade, and archiving the project.
- Offering to close a project from the project list. The list keeps the actions
  it has.

## Why a spec and not a change

The rule that is easy to get wrong is not the dialog itself — it is *where* the
rule applies and what it considers blocking. A future screen that closes a task
has to know that closing a blocked task is a two-step interaction, and has to
know which dependencies count. That is a rule worth writing down, and the
project's closing semantics are a rule of the same kind.

## References

- `docs/decisions/2026-10-21-blocked-completion-guard.md` — the decision this
  change implements.
- `docs/decisions/2026-09-18-task-dependencies.md` — the earlier decision whose
  "completion is always allowed" consequence this change replaces, in part.
- `openspec/specs/destructive-action-safety/spec.md` — the pattern of a risky
  action requiring an explicit confirmation.

## Status

**Proposed.**
