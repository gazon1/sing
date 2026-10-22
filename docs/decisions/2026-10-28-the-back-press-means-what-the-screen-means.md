---
title: "The back press means what the screen says it means"
date: 2026-10-28
status: accepted
tags: [ui, android, dialogs, text-fields]
---

# The back press means what the screen says it means

## Context

The task editor asks before it throws a draft away: the header's back button
goes through a guard, and a dirty draft opens a "discard changes?" dialog. The
**system** back press went somewhere else. It popped the navigation entry, and
the title that had been typed into the editor was gone.

Measured on a real phone (2026-10-28, realme RMX3938, SDK 35):

| step | screen |
|---|---|
| tap the title field | IME shown (`mInputShown=true`), editor open |
| one BACK keypress | IME hidden **and** editor closed, draft gone |

Two things went wrong in one keypress, and neither of them was the platform's
doing:

- the editor was a *screen*, and the navigation layer owns the gesture, so the
  gesture never reached the guard the button used;
- the keyboard was open, and a back press with the keyboard open is the
  keyboard's — it closes the keyboard, not the screen. The app got the press
  instead, the screen left, and the keyboard hid as a side effect of the field
  losing its focus.

The same bypass was in every editor that has a guard: the task detail, the
saved agenda (its "discard?" dialog is only on the header button), the project
editor and the note editor. None of them asked.

The repository already had the seam for this — `TasksBackHandler`, an
expect/actual that delegates to `androidx.activity.compose.BackHandler` — and
**nothing called it**. It had been written for a purpose that was never
finished.

## Idea

One seam, in the place every editor can reach, and one rule about what a back
press means before the screen's own meaning applies.

## Decision

**The seam is shared.** `PlatformBackHandler` lives in `core/ui/components` as
an expect/actual: Android delegates to `BackHandler`, desktop does nothing
(there is no back gesture on the desktop). The tasks feature's unused
`TasksBackHandler` is removed — a seam per feature would be the same seam
twice, and the one that existed was in the wrong place to be used.

**The keyboard comes first.** `ImeAwareBackHandler` decides, through the pure
function `backAction(imeVisible)`:

- the keyboard is open → the back press closes the keyboard;
- the keyboard is closed → the back press is the screen's own back press.

The screen's own meaning stays where it was: in the lambda it passes. The task
create editor passes its guard, the saved agenda screen passes its discard
dialog, the task detail, the project editor and the note editor pass their
back.

**The guard now covers the gesture.** Because the gesture and the button reach
the same lambda, a dirty draft asks before it is discarded — in the task
editor, in the saved agenda, and in every editor that has a guard to offer.

## Rationale

The alternative was to leave the gesture to the navigation layer and accept
that a screen's back button and the system's back key mean different things.
They may differ — a screen may want the gesture to leave while the button asks
— but they do not differ *by accident*, and a difference nobody wrote down is
not a policy, it is a gap. The gap is what cost a title.

The keyboard rule is not this repository's invention; it is the platform's
contract, and the app was breaking it in the direction that destroys work.
Deciding it in one place — a pure function that is asserted without a device —
means the rule cannot drift per editor.

The seam went to `core/ui` because the question is asked by every editor, and
the editors are not all in the tasks feature.

## Consequences

- A back press in the task editor with the keyboard open closes the keyboard
  and leaves the editor and the draft alone; a second press reaches the screen
  and asks before discarding.
- The editors that had no guard (the project editor, the note editor) keep
  their behaviour — they leave the screen — but the gesture no longer leaves
  while the keyboard is up.
- Desktop is unchanged: the seam is a no-op there, and the desktop has no back
  gesture to lose.
- The device test (`EditorBackGuardTest`) holds the contract on the phone, and
  the pure decision (`BackActionTest`) holds it everywhere.
- The task editor's cancel path in `CreateTaskFlowTest` is now two presses and
  a confirmation — the test follows the behaviour the platform asks for, so
  the path it claims to exercise is still the path it exercises.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ImeAwareBackHandler.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/PlatformBackHandler.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/BackActionTest.kt`
- `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/EditorBackGuardTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskCreateScreen.kt`
