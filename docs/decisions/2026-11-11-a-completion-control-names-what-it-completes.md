---
title: "A completion control names what it completes"
date: 2026-11-11
tags: [accessibility, tasks, checklist, semantics]
status: accepted
---

## Context

The task family's completion controls reached the platform as unnamed nodes. The
list checkbox carried a role and a checked state but no accessible name; the card
toggle carried the hardcoded English description `"Toggle complete"`; the shared
checklist row — the widget that renders both subtasks and checklist items —
carried role and state but no name either.

A screen reader therefore announced a control with no way to say *what* it
completes. On a list of twenty tasks every checkbox announced the same thing, and
a user could not tell one from another without walking the surrounding text.

The issue text proposes the names `"Complete task: <title>"` and `"Complete
subtask: <title>"`. That wording carries the action inside the name, so the
name changes when the action does: a control that toggles rather than completes
would announce a promise it does not keep, and the wording is a second place
where the completion state would have to be kept in sync.

## Idea

Split the two things the control is already saying. The **name** says which
thing the control belongs to — `Task:`, `Subtask:`, `Item:` — and the
**state** says whether that thing is done. The state stays a state
(`stateDescription`), never encoded in the name.

## Decision

- Every completion control in the task family exposes a checkbox role, its
  current checked state, and a non-empty accessible name that identifies the
  thing it completes.
- The name is `<Kind>: <title>`, where the kind is one of **Task**, **Subtask**
  or **Item**. The title is the thing's own title; a blank title falls back to
  the existing "untitled" string rather than producing a dangling colon.
- The checked state is carried by the state description only. It never appears
  in the name. All three controls use the same two strings for it, so a user
  hears "Done" on every screen rather than "Checked" on one of them.
- One shared helper owns the mapping from kind to localized name, so the three
  controls cannot drift into three wordings.
- The kind is decided by the row, not by the widget. A row with a parent is a
  **Subtask**; a checklist row without a parent is an **Item**; a top-level task
  row is a **Task**.
- The name lives on the control itself. A test tag, a sibling label or the
  visible text elsewhere in the row is not a substitute.

## Rationale

The action belongs to the role, not to the name. A control with a checkbox role
and a checked state already says "toggle this thing's completion"; adding
"Complete:" to the name repeats that and makes the name wrong the moment the
control is a toggle rather than a one-way action.

Keeping the state out of the name is what lets the same name serve both states,
which is what a screen reader needs: the user hears the same thing on every
visit and hears the state change separately.

The kind travels with the row rather than with the widget because the widget
does not know what it is rendering — the shared checklist row renders a subtask
in one place and a checklist item in another, and only the caller knows which.

One helper for the three controls is the smallest thing that keeps the wordings
aligned. Three separate functions, or one function per feature, are three places
to change the next time the wording moves.

## Consequences

- Every completion control added later must carry a kind. A control that cannot
  say what it completes does not ship.
- The wording is user-visible copy and lives in the string resources, in both
  locales.
- The name is not derived from the action, so the same control can be reused
  where the action differs.

## Links

- Issue: <https://github.com/gazon1/sing/issues/2753>
- OpenSpec change: `openspec/changes/a-completion-control-names-what-it-completes/`
- Shared helper: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/CompletionNames.kt`
- Test: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskCompletionSemanticsTest.kt`
