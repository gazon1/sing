---
title: "Long-press task menu on Android, and restoring from the archive"
date: 2026-09-29
status: accepted
tags: [ui, tasks, agenda, android]
---

## Context

Two gaps found by driving the real UI, both invisible to the test suite.

**Long-press opened the editor.** The agenda row (`TaskRowFlat`) used a plain
`Modifier.clickable`, which fires `onClick` for a long press too. So a long-press
went to the task editor instead of a context menu. The infrastructure for a menu
existed but was unreachable on touch: `AgendaViewModel` already emitted
`ShowTaskContextMenu` on `TaskLongClicked`, `AgendaNavigator.showTaskContextMenu`
existed, and the slot was named `desktopContextMenuHost` — because only the JVM
graph ever passed a host. Android passed the default no-op, so the whole
long-press path dead-ended. On desktop the right-click menu worked, which is
exactly why this went unnoticed: the feature was implemented, tested on one
platform, and absent on the other.

**The archive was a one-way door.** "Archive" is a soft-delete
(`archivedAt != null`, exposed as `Task.isTrashed`), and `taskRepo.restore` was
already there and already used by the undo-delete path. No screen offered it:
the detail overflow menu was a fixed Архивировать / Удалить pair regardless of
task state, and the Archive screen listed archived tasks with no action at all.
`TaskDetailIntent.Domain.Restore` existed but meant *replay the delete that just
happened on this screen* — it reads a `recentlyDeleted` snapshot — so it was the
wrong tool for un-trashing a task opened from the Archive screen.

## Idea

1. Add `onLongClick` to the row and give Android a bottom-sheet menu.
2. Reuse `Domain.Restore` for un-archiving, changing it to fall back to the open
   task when there is no recent delete.
3. Add a separate `Domain.Unarchive` and gate the detail menu on `isTrashed`.
4. Unwire the agenda's `onSecondaryClick` on Android and let right-click be a
   desktop-only affordance again.

## Decision

We did (1) and (3), and rejected (2) and (4).

The row now uses `combinedClickable` with an `onLongClick` parameter. The slot
was renamed `desktopContextMenuHost` → `contextMenuHost`: the JVM graph still
passes its right-click popup, and `AgendaNavGraph.android.kt` now passes a new
`TaskContextMenuSheet`. Desktop right-click is unchanged.

`Domain.Unarchive` is separate from `Restore` and calls
`taskRepo.restore(taskFlow.value.id)` directly, then pops back. The detail
overflow menu is built by `buildDetailMenuItems(isTrashed)`: an archived task
gets Восстановить alone; an active task keeps Архивировать / Удалить.

(2) was rejected because it would overload one intent with two different sources
of truth — a transient snapshot versus the screen's own task — and a restore
fired on a task that was never deleted would silently no-op.

(4) was rejected as a behaviour regression: Compose Desktop maps
`onSecondaryClick` from a *right* press, which users of an Android-like layout
also expect, and the modal `DropdownMenu` risks covering a list the user is
scrolling. Mobile convention is the bottom sheet, desktop keeps its native
popup.

## Rationale

The two bugs are the same shape: a platform-specific affordance implemented on
one side and quietly absent on the other, passing every test because the tests
followed the code that *was* wired. The lesson is the naming — a parameter called
`desktopContextMenuHost` reads as "context menu is a desktop thing", and that
sentence was true enough to be believed.

`TaskContextMenuSheet` deliberately lists only the wired actions (Open, complete,
pin, archive) rather than mirroring the 28-item `buildTaskContextMenu`. Fifteen
of those have no write behind them yet, and a bottom sheet is the worst place to
surface fifteen dead rows. As builder items gain writes they get added, in the
same order.

## Consequences

- `AgendaContent`'s menu slot is `contextMenuHost`; both platform graphs must
  pass a renderer or long-press/right-click is a silent no-op again.
- `TaskRowFlat` takes `onLongClick`; the agenda wires it, other callers get
  plain click behaviour.
- The detail overflow menu is state-dependent — a flow that archives and then
  expects Удалить will not find it. `Maestro/flows/smoke/11-archive-restore-smoke.yaml`
  asserts the archived menu shape explicitly.
- Sheet rows carry `sheet_item_<label>` tags (`TestTags.sheetItem`) so UI
  automation selects actions by id rather than visible text. Note the tag is
  derived from the *current* label, so "Mark as completed" becomes
  `sheet_item_Mark_as_completed` while the task is open and
  `sheet_item_Mark_as_uncompleted` once completed.
- The swipe-to-delete on `SwipeableTaskRow` still dispatches
  `TaskCheckClicked` (it toggles completion rather than deleting). That is a
  separate pre-existing bug, untouched here.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/contextmenu/TaskContextMenuSheet.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskDetailViewScreen.kt` — `buildDetailMenuItems`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskLifecycleSlot.kt` — `unarchive()`
- `Maestro/flows/smoke/13-task-context-menu-smoke.yaml`
- Supersedes in part: `2026-09-29-archive-has-no-restore-ui.md`
