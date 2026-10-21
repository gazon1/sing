---
title: "The reset reaches the whole profile, drafts included"
date: 2026-10-28
status: accepted
tags: [testing, maestro, android, data, isolation]
---

# The reset reaches the whole profile, drafts included

## Context

`2026-10-21-each-flow-gets-a-clean-device.md` gave every flow a clean device
by wiping the content before it: `todo-debug://seed?reset=content`. The wipe
asked the repositories to empty the active profile. Two holes were left in it,
and both were found by running the suite on a device instead of reading it.

**H1 — the wipe only reached the live rows.** `TaskRepositoryImpl.observeAll()`
was `watchActive`, so the wipe enumerated the *active* tasks and deleted
those. Every archived, trashed and soft-deleted row survived, and a row that
survives is a row the next flow sees: the trash is not empty, the archived
list is not empty, and a flow that asserts an empty list fails on the
previous run's data. The saved agenda views (`agenda_views`) were not
touched at all — the wipe did not know they existed.

**H2 — the drafts are not in the database.** The task editor's draft lives in
the app's DataStore, not in Room, so no database wipe reaches it. The create
screen restores its draft on open, which is right for a user and wrong for a
suite: the field arrives holding the *previous flow's* title, `inputText`
appends to it, and a flow that asserts the title it typed fails on a title
that is three flows old. Measured on the device (2026-10-28), the field read
`r5545449600 Full tr5549817520 Buy milkr5` — three runs' worth of text, one
inside the next.

Both holes have the same shape: the reset reaches what it knows about, and
what it knows about is not everything the next flow can see.

## Idea

Ask the store to empty itself, and ask *every* store the profile owns.

Enumerating rows and deleting them one by one is a sweep, and a sweep is
defined by the query that feeds it — so a sweep of the live rows leaves the
dead ones behind, by construction. A bulk delete has no such filter.

## Decision

**The repositories empty the profile; they do not sweep it.** `TaskRepository`,
`NotesRepository`, `ProjectsRepository` and `SavedAgendaViewsRepository` each
grow a `deleteAllForCurrentProfile(): Result<Int>` that issues one bulk
delete per profile — archived, trashed and soft-deleted rows included — and
the wipe calls those instead of iterating `observeAll()`.

**The drafts are cleared too.** `DraftStore` grows
`clearPrefix(prefix: String)`: the store can only delete a *named* draft
otherwise, and a store that can only delete by name cannot be emptied.
`DataStoreDraftStore` removes every key under the prefix; `UserScopedDraftStore`
prepends the current user's prefix, so the reset empties the active user's
drafts and no one else's. The debug activity calls it with the empty prefix,
which means "everything this user has".

**The flows that type into a restored field erase it first.** `eraseText`
before `inputText` in the two flows that create a task by typing. This is
belt and braces: the draft is cleared now, and a flow that must not depend on
the state of the field it found says so itself.

## Rationale

The sweep was not wrong because of a bug in the sweep. It was wrong because
of what a sweep is: a query decides what a sweep sees, and the query that
lists "the tasks" is not the query that lists "the tasks that exist". A bulk
delete has no query — it is the storage engine's own promise that the rows
are gone — so it cannot be narrowed by accident. The same argument applies
to the notes, the projects and the agenda views, and the same implementation
was applied to all four rather than to the one that happened to fail.

The drafts are the same argument in a different store. A database wipe cannot
reach them, and the flow that showed it — the create screen restoring the
previous flow's title — is the flow that has to write an exact title. The
store's own key namespace is the boundary, and clearing by prefix is the only
operation that matches it: the alternative, a list of known draft keys, is
the list that has to be kept up to date by hand, which is the defect this
round removed from the cleanup scope.

`eraseText` stays in the flows even though the draft is now cleared. The two
answers the flow can have are "the field is empty because nobody wrote in it"
and "the field is empty because the flow emptied it"; the second is the one
the flow can rely on, and a flow that does not say so depends on the state it
found.

## Consequences

- The archived list, the trash and the saved agenda views are empty at the
  start of a flow, not only the live lists. The three flows that failed on
  that leak in the 2026-10-21 run have a chance to pass.
- The drafts of the active user are empty at the start of a flow. A flow that
  types into the create screen writes the title it meant to write.
- `deleteAllForCurrentProfile` is a production API that deletes a profile's
  content. It is used by a debug-only action; it is documented as such, and
  it is scoped to the active profile by construction — a flow that switched
  profiles is cleaned while that profile is still active. Per-profile
  isolation (a profile per flow) is still owed; it is issue #813.
- The reset is still not concurrency-safe: it wipes other runs' in-flight
  fixtures too. The runner holds a per-device lock for the whole run, so a
  single suite per device is the rule.
- The port grew a method, so every implementation grew one: the DataStore
  store, the user-scoped wrapper and the in-memory fake. A store that cannot
  be emptied is a store that accumulates.

## Links

- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt`
  — `reset=content`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt`
  — and the notes, projects and agenda-views repositories
- `shared/src/commonMain/kotlin/com/singularity/todo/core/draft/DraftStore.kt`
  — `clearPrefix`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/draft/DataStoreDraftStore.kt`
- `Maestro/flows/tasks/create-task.yaml`, `Maestro/flows/tasks/13-create-full-task.yaml`
  — `eraseText` before `inputText`
- `2026-10-21-each-flow-gets-a-clean-device.md` — the decision this amends
- `2026-10-28-the-bridge-policy-is-a-file.md` — the cleanup scope, in the same area
