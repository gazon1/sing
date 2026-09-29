---
title: "A control wired to a no-op reads as working; three of them shipped"
date: 2026-09-29
status: accepted
tags: [backup, agenda, ui, gap, seam]
---

## Context

Fixing unwired surfaces kept turning up a class of defect that is more serious than
the ones we set out to find, and that the audit script cannot see: a control that is
fully rendered, fully interactive, and wired — to nothing, or to the wrong intent.

`scripts/find-unwired-surfaces.py` looks for declarations with no call site. These
three are the inverse. They have call sites. The button exists, the confirm dialog
exists, the row is styled, the icon is tinted. The handler is empty, or dispatches
an intent that means something else.

Concretely, all three found in this pass:

**Agenda swipe-to-delete.** `AgendaContent.kt:282` passed
`onDelete = { onIntent(AgendaIntent.TaskCheckClicked(task.id)) }` to
`SwipeableTaskRow`. `TaskDeleteClicked` was already defined, already handled by the
ViewModel (`deps.taskRepo.softDelete`), and already used correctly by both
`AgendaNavGraph.android.kt` and `AgendaNavGraph.jvm.kt`. One call site was wrong, so
swiping a task row to delete it silently marked the task complete — the opposite of
what the user asked for, and invisible, because the row animates away either way.

**Backup per-row Restore.** `BackupScreen.kt:237` passed
`onRestore = { /* parent handles */ }` to `BackupListItem`. The restore button opens
an `AlertDialog` whose body text is "Restore will overwrite current data. Are you
sure?", and whose confirm button calls that no-op. The user reads a real warning,
confirms, the dialog dismisses, and nothing happens.

**Settings backup tab.** Three callbacks — `onSelectRestoreFile`,
`onSelectSettingsFile`, `onShareSettingsJson` — were empty lambdas behind real
buttons. The `AppFilePicker` seam built in PR-0.1 for exactly this had **zero call
sites**; these were its intended consumers, and the comment on each said so
("Platform shell provides file picker on Android") while providing nothing.

## Idea

The failure is not that these were never wired. It is that the codebase has no way to
distinguish *"deliberately not implemented yet"* from *"wired and broken"*, and both
look identical to a reader and to a static check.

## Decision

Fix the three now. Record the pattern, the checks that would have caught it, and the
remaining instances here.

On **fixing**:

- Agenda: dispatch `TaskDeleteClicked`. No new intent, no new handler.
- Backup restore: forward `backup.path` to the screen's existing `onRestore`.
- Settings tab: wire both pickers through `rememberAppFilePicker`, and add a
  `SharePort` (Android `ACTION_SEND` chooser, JVM clipboard + `mailto:`) for the
  share action the snackbar was already offering.

Settings import needed one genuinely new thing: `BackupIntent.ImportSettingsFrom(path)`.
The file read happens in the ViewModel through the existing `FileSourceFactory`,
not in the Composable. On Android the picker returns a `content://` URI that only a
`ContentResolver` can open, so a screen that read the file itself would work on
desktop and fail on the platform most users are on. A read failure surfaces as an
error event with `isWorking` cleared, rather than an exception.

## Rationale

The shared property of all three is that the *feedback* is indistinguishable from
success. The swiped row dismisses. The dialog closes. The button responds to the
press. A user has no signal that the system declined, and no reason to retry.

That property is what makes them critical rather than cosmetic. A visibly-missing
button is self-reporting: the user sees the absence and asks. A button that lies
costs the user their data model — here, a task silently marked complete because they
tried to delete it, or a restore the user confirmed on the strength of a warning and
watched do nothing.

**Why `SharePort` and not a platform-conditional in the screen.** It follows the
project's existing port pattern (`FileRevealer`, `FileSourceFactory`,
`NotificationPort`) and keeps `SettingsScreen` free of platform branching. The
return type is `Boolean`, not `Result`: a dismissed chooser is not an error, and a
`Result` here would invite a caller to report failures that are not failures.

**Why the read is not in the Composable.** Beyond the SAF-URI problem, a Composable
that opens a file is untestable without a device and unreviewable — the read would
be invisible to `FakeFileSourceFactory`. The intent-based path makes the read
assertable in `jvmTest`, which is where both new tests live.

## Consequences

- `Archive` vs `Delete` are the same operation in this codebase —
  `TaskLifecycleSlot.archive()` calls `taskRepo.softDelete`, and the Archive screen
  lists soft-deleted tasks. Two names, one action. `AgendaNavGraph.android.kt`'s
  `onArchive = TaskDeleteClicked` is **correct**, and a grep that reads the name
  literally will flag it. Recorded here so the next audit does not "fix" it.
- `AppFilePicker` now has call sites, so the seam is exercised and can no longer
  rot in place. `find-unwired-surfaces.py` will no longer report the backup settings
  path as a silent gap.
- **A no-op lambda is now ambiguous by construction.** A reader cannot tell a
  deliberate placeholder from a broken wire. The honest fix is a named
  `notImplemented("reason")` that at least shows up in review and in a stack trace,
  but that is a convention, not a check — see below.
- `SharePort` returns `false` on a platform that cannot share. No caller currently
  inspects it; the settings screen simply does nothing. That is acceptable today
  because both actuals effectively always succeed, but it is a silent no-op by
  design and should be revisited if a third platform lands.

## Remaining instances (not fixed here)

Low severity, deliberate placeholders, recorded so they are not rediscovered:

| Site | Behaviour |
|---|---|
| `MiniCalendarPanel.kt:130-134` | 5 filter rows, `onClick = {}`. Comment says they connect to `CalendarFilterPanel` state later. |
| `MonthGridView.kt:239`, `TimeGridView.kt:171` | "More tasks" overflow labels, `onClick = {}`. No handler exists in either file. |
| `SearchScreen.kt:205` | Tag result card, `onClick = {}`. `SearchNavigator` has `openTask/openNote/openProject` but no `openTag`. |
| `TaskMenuBuilder.kt:259-264` | 28-item desktop menu; `onArchive` deliberately omitted from `TaskMenuActions`. |
| `SyncConfigScreen.kt` | No call site at all — sync transport is out of scope for this branch. |

## What would have caught these

- A detekt rule rejecting an empty lambda assigned to a parameter named `on*Delete`,
  `on*Archive`, `on*Restore`, or `on*Remove` unless it is spelled `notImplemented(...)`.
  The codebase already runs custom rules (`NoRunBlocking`, `NoStateIn`,
  `PassThroughUseCase`), so this fits the established mechanism.
- A detekt rule requiring a `//` comment on any `= {}` callback, so the placeholder
  states *why* it is empty. The three bugs above each carried a comment that read as
  a justification rather than an admission.

Both are small. Neither is written. The unwired-surfaces script stays necessary —
it catches the true zero-call-site case — but on its own it is blind to everything
in this ADR.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt:282`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/backup/BackupScreen.kt:237`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsScreen.kt:239-246`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/files/SharePort.kt`
- `scripts/find-unwired-surfaces.py`
- Same family: `2026-09-29-editor-row-onclick-noop-default.md`,
  `2026-09-29-notes-and-calendar-unreachable-controls.md`
