---
title: "The sheet state is not defaulted"
date: 2026-10-21
status: accepted
tags: [architecture, compose, task-editor]
---

# The sheet state is not defaulted

## Context

The task editor's sheets are opened by rows and rendered by a host, and both must be
looking at the same state. Before #824 they were not: the screen opened a sheet through
the state it held, and the editor rendered the sheets from a state of its own, so in the
create flow every attribute row was inert.

#824 gave the editor one state and made the create screen hand it over. The fallback
stayed:

```kotlin
val ownSheets = rememberDialogState<TaskEditorSheet>()
val sheets = callbacks.sheets ?: ownSheets
```

So the state was still optional, and the bug was still available: a screen that wires a
row to `sheets.show(TaskEditorSheet.Priority)` and forgets to hand the state over opens
a sheet nobody renders — silently, with the screen looking perfectly fine.

The state also lived in the **callback bundle**, although it is not a callback. It is the
thing the editor depends on, and it is read in exactly one place.

## Idea

A state with a fallback is two states waiting to disagree. If the editor's sheets must be
one state, the state must be *one* — not "the caller's if there is one, mine otherwise".

## Decision

**The sheet state is a required parameter of both editors, and it is not defaulted.**

- `TaskEditorContent(model, …)` and `TaskCreateEditorContent(…)` take
  `sheets: DialogState<TaskEditorSheet>`. There is no default and no fallback.
- The state leaves `TaskEditorCallbacks`: a bundle of handlers is not where a thing the
  editor depends on belongs. It sits in the editor's signature, next to the model and the
  sources.
- The caller's rows and the editor's host read the same object, because there is only
  one object.

## Rationale

- **An omission that the compiler catches is an omission that cannot ship.** The bug of
  #824 was a silent no-op: a row that opens a sheet nobody shows. A required parameter
  turns that into a compile error in the screen that forgot — where the author is, and
  where the fix is one line.
- **A default is a second state.** The fallback existed for a caller that had no state to
  give — but such a caller has rows that open nothing, which is not a mode anybody
  wanted; it is the bug wearing a default's clothes.
- **A dependency belongs in the signature.** The state is not a handler, and putting it
  among the handlers made it invisible in the editor's own contract: a reader of the
  signature could not tell that the editor's sheets depend on a state at all.

## Consequences

- The editor's public surface says what it needs: a model, the sources, the callbacks,
  the clock, and the state the sheets live in.
- `TaskEditorCallbacks` is a bundle of handlers again, and nothing else.
- The previews build their own state, as they should: a preview has no caller.

## Links

- `2026-10-21-the-editor-owns-its-sheets-and-its-model` — the decision that put the
  sheets in one state; this one says the state cannot be defaulted.
- `2026-10-21-the-kind-of-the-source-is-a-property-of-the-parameter` — the same
  principle applied to the sources: what a component depends on is in its signature.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorContent.kt`
  — the editors and the scaffold.
- `openspec/changes/archive/2026-10-25-the-sheet-state-is-not-defaulted/` — the change this records.
