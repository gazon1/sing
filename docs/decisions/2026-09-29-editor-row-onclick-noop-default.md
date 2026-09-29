---
title: "Editor rows did nothing — onClick defaulted to a no-op lambda"
date: 2026-09-29
status: accepted
tags: [ui, tasks, android]
---

## Context

Writing the PR-2 task-editor flows surfaced that the task detail's attribute rows
were dead taps: "Добавить дату" and "No priority" did nothing at all.

The cause is a single character of API design. `RowCallbacks` and
`DateRowCallbacks` declared:

```kotlin
val onClick: () -> Unit = {}
```

and the row consumed it as:

```kotlin
.clickable(onClick = onDueDateClick ?: { sheets.show(TaskEditorSheet.Date) })
```

The fallback exists and is correct, but it only fires when the value is `null`.
An empty lambda is a *supplied* value, so the default suppressed the fallback and
the row became a no-op. `null` and `{}` are not interchangeable defaults; the
`:?` idiom treats them as opposite when they mean the same thing to a caller.

Every call site that omitted `onClick` got the broken behaviour — which is all
of them, since the sheets already did the right thing on their own.

## Idea

1. Default `onClick` to `null` so the row's own fallback fires.
2. Keep the no-op default and have each call site pass an explicit sheet-opening
   lambda.
3. Delete the fallback and require every call site to pass an `onClick`.

## Decision

We did (1). Both bundles now declare `val onClick: (() -> Unit)? = null`, with a
KDoc note explaining why the default must stay nullable. A call site that
genuinely wants to override the row's behaviour still can.

(2) would have put the same seven call sites in the file that already had them
and re-introduced the possibility of a future no-op. (3) would have made the
common case — "the row opens its own sheet" — the verbose one.

## Consequences

- A `null` `onClick` is meaningful: "use the row's own behaviour". An empty
  lambda is never correct for these bundles; if a row genuinely does nothing,
  that is a decision to express, not an accident of a default value.
- The Material3 date picker's day cells expose only a contentDescription
  ("Tuesday, September 29, 2026"), and Maestro has no `content-desc` selector.
  `Maestro/flows/tasks/02-set-due-date.yaml` therefore picks a day with a regex
  anchored on the day number plus a year wildcard, which survives the turn of the
  year. The same constraint made the detail screen's save-button assumption wrong:
  the detail screen has no save button (edits autosave); `task_editor_save` exists
  only on the create screen.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorCallbacks.kt`
- `Maestro/flows/tasks/02-set-due-date.yaml`
- Same family: `2026-09-29-task-longpress-menu-and-archive-restore.md`
