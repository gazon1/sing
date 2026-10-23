---
title: "The offer comes before the navigation: a toast belongs to the screen that shows it"
date: 2026-10-23
status: accepted
tags: [tasks, series, undo, ux, navigation]
---

## Context

Deleting a series archives every occurrence of it and offers the way back: a
toast that says how many dates went away and a button that puts them back.
The screen then leaves.

The first implementation emitted both events back to back — the undo and the
navigation — and the navigation won. `NotificationHost` keeps **one** slot for
the notification it shows, so the second event replaced the first, and the
toast's `LaunchedEffect` was cancelled by the key change before it could draw.
Then the screen was popped, and the toast — which lived in that screen's
composition, inside its own `Popup` window — went with it.

The result was a feature that looked complete in every layer except the one
that shows it: the rows were archived, the message was written, the button was
wired, the ids were restored correctly when the button was pressed — and the
user never saw the offer. The review of the branch found it by reading the
host, not by running the app.

The single-task delete does not have this defect, and that is why it went
unnoticed: it offers the undo and stays on the screen.

## Idea

A toast that is offered on the way out has to be shown *before* the screen
goes — or it has to be hosted by something that outlives the screen. The second
is a bigger change: the toast would have to move to the application shell, on
both platforms, and every screen would then have to know about it. The first
is a change of order, and the order is what was wrong.

## Decision

1. **The offer is made, and the screen leaves when the offer ends.** Deleting a
   series shows the toast and stays. The screen navigates back when the toast
   goes away *without* the action being taken.

2. **Taking the offer keeps the screen.** The work is back, so the screen that
   describes the work still describes it. It also keeps the restore from being
   cut short: the restore runs in the screen's own scope, and a navigation that
   tears that scope down in the same frame could cancel the write that the
   button had just asked for.

3. **The host says which of the two happened.** `Notification.Undo` carries an
   `onDeclined` callback, and the host calls it when the snackbar ends without
   the action. The screen decides what "declined" means — it is the one that
   knows where the toast is hosted.

4. **The rule has a check that can fail.** `TaskLifecycleSlotTest` asserts that
   deleting a series does not navigate away. The defect this decision is about
   was invisible to every other check: the archive was correct, the ids were
   correct, and the toast was correct — it was the order that was wrong.

## Rationale

**Why the offer comes first.** The toast is drawn in the composition of the
screen that offers it. A navigation that happens first disposes that
composition, and a `Popup` inside a disposed composition is not on the screen.
There is no ordering of two events that both reach a one-slot holder and both
survive: the second one replaces the first.

**Why the screen leaves when the offer is declined.** The screen has to leave —
the task it shows is archived, and staying would show an empty detail screen
for as long as the toast is up. Leaving *after* the offer keeps the promise
("the way back is right here") and the shape of the flow ("the work is gone,
here is the way back").

**Why the scope is not cancelled mid-restore.** The restore is a write in the
screen's scope. A navigation in the same frame as the button press disposes
that scope, and a cancelled write is a write that did not happen — which is the
same class of defect as the offer that never appeared: the user was told the
work came back, and it did not.

## Consequences

- The detail screen keeps showing the archived task behind the toast — the
  observation that feeds it does not filter the archived rows out. That is the
  house behaviour for an undo on the way out, not a new shape: a single-task
  delete leaves the same screen standing.
- The undo of a series is offered by the screen that offers it. A toast that
  has to outlive its screen — an undo offered by a screen that leaves at once —
  still has no home, and that is a separate change: a toast hosted by the
  application shell.
- `Notification.Undo` grew a callback. The screens that do not navigate on a
  declined offer do not pass it.

## Links

- OpenSpec change: `openspec/changes/task-series-presentation` — the undo of a
  deleted series
- ADR `2026-09-08-task-restore-undo.md` — the undo contract for a single task
- ADR `2026-10-09-undoable-delete-contract.md` — the write comes first, the
  offer comes second
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/NotificationHost.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskLifecycleSlot.kt`
