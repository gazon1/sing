---
title: "The editor owns its sheets and its model"
date: 2026-10-21
status: accepted
tags: [architecture, compose, task-editor]
---

# The editor owns its sheets and its model

## Context

The task editor is reached from two screens: the create screen, which opens the editor to
make a new task, and the detail screen, which opens it to look at an existing one. Both
hand it a model, a bundle of callbacks, and the two bridged text fields.

The editor took all of that apart and put it back together in two places.

**The sheets had two states.** The caller's rows opened their sheets through a state the
caller held — `sheets.show(TaskEditorSheet.Priority)` and its eleven siblings. The editor
rendered the sheets from a state the editor held. The detail screen happened to hand the
editor its state, so the detail editor's sheets worked. The create screen did not, so the
create editor's rows were inert: tapping the priority row, the due date row, the project
row, the tags row and the recurrence row did nothing at all, and the screens looked
perfectly fine.

**The sheets had two models.** The editor unpacked the model and the callbacks into forty
parameters, and then — to render the sheets — packed them into a *second* model and a
*second* callback bundle:

```kotlin
TaskEditorSheetsHost(
    model = TaskEditorModel(
        taskId = null,
        checklist = emptyList(),
        attachments = emptyList(),
        ...
    ),
    callbacks = TaskEditorCallbacks(checklist = null, attachments = null, ...),
    ...
)
```

That second model was empty where it mattered. The task id was `null` for a task that
exists. The checklist and the attachments were empty for a draft that holds items. The
second bundle answered the sheets with no-ops, so a sheet that asked to be told what to do
was told to do nothing.

## Idea

The editor is given the task and the state. It must not answer the sheets from anything
else.

## Decision

**One sheet state, one model, one callback bundle.**

- The sheet state is resolved once, by the editor's scaffold: the caller's state when the
  caller has one, the editor's own when it does not. The rows open sheets through that
  state, and the host renders from that state. The two are the same object, so a row that
  opens a sheet is a sheet that opens.
- The host is rendered by the scaffold, which holds the model and the callback bundle the
  editor was given. There is no second model and no second bundle.
- The caller's rows keep their own state and hand it over. The create screen hands its
  state to the editor; the detail screen already did.
- The exploded editor body — the one with forty parameters — is private. Only the scaffold
  and the previews use it, and it was never a thing a caller could get right by accident.

## Rationale

- **Two states that must agree are two states that will not.** The failure is silent and
  looks like a working screen: a row that opens a sheet nobody renders does nothing, and
  nothing on the screen says why. This is the same mistake as a value and a counter held
  in two fields — see `2026-10-21-the-count-travels-with-the-value` — and the same answer:
  the pair belongs in one object.
- **A model rebuilt from a model's values is a model that has drifted.** The editor
  unpacked forty parameters and rebuilt a model out of them, so the fields the editor
  forgot to carry — the task id, the checklist, the attachments — came out empty. The
  editor had the model; the second one was a worse copy of it.
- **The caller owns the state because the caller's rows use it.** The row's click handler
  is written in the screen, where the screen's state is. Asking the screen to write down
  the sheet it wants *and* asking the editor to own a state of its own is asking for two.
- **A private body is a smaller surface.** Forty parameters that only the scaffold passes
  are forty parameters a caller can only get wrong. Making the body private means the
  only way in is the two editors that are meant to be in.

## Consequences

- Every attribute row in the create editor opens its picker. The rows were inert before;
  the desktop flow test asserts it now.
- The sheets are given the task they belong to. A picker that needs the task id — the
  checklist, the attachments — is no longer handed `null` for a task that exists.
- The editor's public surface is two functions and two data classes, instead of two
  functions, two data classes and a forty-parameter body.
- The create screen's checklist and attachments callbacks reach sheets that no row opens.
  That is a separate question — a row for them, or no callbacks for them — and this change
  does not answer it.

## Links

- `2026-10-21-the-count-travels-with-the-value` — the same principle for a value and its
  count.
- `2026-10-21-the-kind-of-the-source-is-a-property-of-the-parameter` — the split of the
  editor that this change finishes cleaning up.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorContent.kt`
  — the scaffold that owns the state, the model and the host.
- `openspec/changes/the-editor-owns-its-sheets-and-its-model/` — the change this records.
