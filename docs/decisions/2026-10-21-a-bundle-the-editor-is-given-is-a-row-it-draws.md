---
title: "A bundle the editor is given is a row it draws"
date: 2026-10-21
status: accepted
tags: [architecture, compose, task-editor]
---

# A bundle the editor is given is a row it draws

## Context

The task editor takes a bundle of callbacks. Among them are two bundles that describe
*content* rather than behaviour:

```kotlin
val checklist: ChecklistCallbacks? = null,
val attachments: AttachmentsCallbacks? = null,
```

`ChecklistCallbacks` carries an `onOpen` — "the user pressed the checklist row" — and the
same for the attachments. The create screen filled both in, and the ViewModel behind them
had intents that added, toggled and removed items, and the draft held the items. The
model carried them.

Nothing drew a row. The editor's body draws rows for the priority, the estimate, the due
date, the start date, the project, the tags, the recurrence and the pin — and none for
the checklist or the attachments, because those two are not rows in the body: in the
detail flow they are sections under the fields.

So the create flow had a complete checklist feature — the callbacks, the intents, the
draft's items, the model carrying them — and nothing the user could press. The
`onOpen` was wired to a sheet that no row opened.

## Idea

A bundle the editor is handed is a row the editor draws. If the editor takes a bundle
and draws nothing from it, the bundle is dead wiring, and the honest states are two: the
row exists, or the bundle is not there.

## Decision

**A non-null bundle means a visible row.**

- The editor draws a checklist row and an attachments row when it is given the
  corresponding bundle, and the row opens the sheet the bundle's `onOpen` names.
- The row shows what the model holds: `Checklist (3)`, `Attachments (1)`. A row that
  says "Checklist" with nothing behind it is a promise the screen cannot keep; the
  count is the promise kept.
- The detail flow keeps its sections and is unaffected: it hands over no bundle, so no
  row is drawn.
- The checklist is added through the **sheet**, not through a section. The sections the
  detail flow uses list items and let the user tick them off; they have no input, so
  they cannot add one. The sheet has the input, and it is the surface the callbacks
  were written for.

## Rationale

- **Dead wiring is the failure mode the house cares about.** A feature that is ready and
  does nothing is worse than one that is missing: the code says it works. The audit
  script (`scripts/find-unwired-surfaces.py`) does not catch this case, because the
  surface *is* wired — to a sheet no row opens.
- **A bundle handed over and not drawn is a question the editor cannot answer.** The
  editor was asked for a checklist and had no row to put it in. Two answers exist, and
  the editor's answer should be the same in both: draw it.
- **The count is the promise.** The user has to see that the sheet holds three items
  before opening it, or the sheet is a place items may be.

## Consequences

- The create flow gained a checklist and an attachments row, and the checklist is
  reachable: an item added in the sheet shows up in the row that opened it.
- The editor's exploded body carries four more parameters (the two counts and the two
  bundles). That is the cost of the exploded shape; the alternative — the editor
  reading a screen's model — is worse.
- The attachments row opens a sheet that can add a URL but not a file: a task that has
  not been saved has no id to attach a file to. The sheet hides the button rather than
  offering one that cannot work.

## Links

- `2026-10-21-the-editor-owns-its-sheets-and-its-model` — the sheets the rows open.
- `2026-10-21-the-sheet-state-is-not-defaulted` — the state the rows open them through.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorContent.kt`
  — the rows.
- `openspec/changes/archive/2026-10-25-the-editor-draws-the-rows-it-is-given/` — the change this records.
