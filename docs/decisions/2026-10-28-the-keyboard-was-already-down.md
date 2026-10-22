---
title: "The keyboard was already down, and the back key closed the editor"
date: 2026-10-28
status: accepted
tags: [testing, maestro, ui-automation, android]
---

# The keyboard was already down, and the back key closed the editor

## Context

`Maestro/flows/tasks/17-create-daily-recurring.yaml` failed on a real phone
(`0O65321F32204DD5`, realme RMX3938, SDK 35) at the last step of the flow:

```
Tap on id: sheet_confirm... COMPLETED
Assert that id: task_editor_recurrence_row is visible... COMPLETED
Tap on id: task_editor_save... FAILED
Element not found: Id matching regex: task_editor_save
```

The debug artifacts do not show a broken screen. They show the **agenda**:
the task editor was gone before the flow asked for its save button. The flow
never tapped anything that saves or dismisses the editor — the one command
between the two was `- hideKeyboard`.

`hideKeyboard` is a command with a side effect the flow did not intend. When
the keyboard is not on screen, there is nothing to hide, and Maestro's way of
hiding a keyboard that is not there is a **BACK keypress**. The task editor is
a dialog, and BACK is what dismisses a dialog.

So the flow asked "hide the keyboard" a second time, after the keyboard was
already hidden, and paid for it with the editor it was about to save from.

Measured on the device, the same way:

| step | screen |
|---|---|
| tap the title field | IME shown (`mInputShown=true`), editor open |
| one BACK keypress | IME hidden **and** editor closed |

The editor is a dialog and it is the app's own dialog, so BACK closing it is
correct Android behaviour. The flow was wrong: it asked for the keyboard to be
hidden when the keyboard was already hidden.

The second `hideKeyboard` was not doing any work. The flow already had one,
right after `inputText`, which is where it belongs — the IME is up there, and
that is the one place a `hideKeyboard` has something to do. The second copy
was a leftover of "hide the keyboard before you tap the trailing action"
applied where there was no trailing action left to reach.

## Idea

Delete the `hideKeyboard` that has nothing to hide, and say in the flow why
it is not there — so the next person who adds a field to this flow does not
put it back.

## Decision

**One `hideKeyboard` per keyboard.** A flow hides the keyboard exactly once
per `inputText`, at the point where the IME is up. A `hideKeyboard` that
follows another `hideKeyboard`, or that follows anything that is not a text
field, is a BACK keypress in disguise.

Concretely, three flows lost their second `hideKeyboard`:

| flow | where it was | what it did |
|---|---|---|
| `tasks/17-create-daily-recurring.yaml` | before `task_editor_save` | BACK dismissed the editor; the save button was not found |
| `tasks/13-create-full-task.yaml` | before `task_editor_save`, after the due-date picker | the same |
| `agenda/04-saved-view-results.yaml` | before scrolling to the back button, after the save | the same, one step earlier |

## Rationale

The alternative is to make the editor survive BACK — to stop it from
dismissing while the IME is up, so that a BACK meant for the keyboard does not
also close the editor. That is a change to the *product* (an editor that
keeps the keyboard's back press), and it is a real wart: pressing BACK with
the keyboard open today closes the editor and loses the title that was typed
into it. It is filed separately, because it is a change to what the app does,
not to what the flow measures.

A flow fix and a product fix are not the same change, and mixing them would
hide both. The flow's fault is that it sends a destructive keypress it did not
mean to send; that is fixable in the flow, today, with a one-line deletion
that the next phone run proves.

## Consequences

- `tasks/17-create-daily-recurring` passes end to end on a real phone with
  the deletion in place — including the step that `tasks/17` used to fail on
  (`recurrence_option_daily is visible`, which #874 owned).
- The rule is a rule about *flows*, not about the app: it says when a
  `hideKeyboard` is meaningful, and it names the one that is not.
- `Maestro/README.md` still says "always follow `inputText` with
  `hideKeyboard`". That is right and it stays — but it now has a companion
  sentence: only once, and only where the keyboard is up.
- The editor still closes on BACK while the keyboard is open. That is the
  product wart, and it is filed on its own.

## Links

- `Maestro/flows/tasks/17-create-daily-recurring.yaml`
- `Maestro/flows/tasks/13-create-full-task.yaml`
- `Maestro/flows/agenda/04-saved-view-results.yaml`
- `Maestro/README.md`
- `docs/decisions/2026-10-28-the-sheet-host-maps-the-tags.md`
