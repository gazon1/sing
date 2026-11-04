---
title: The overlay survives a rotation
date: 2026-11-04
status: accepted
tags: [ui, state, android, compose, overlay]
supersedes: [2026-09-21-state-hoisting-audit, 2026-10-02-routing-state-on-screen]
---

# The overlay survives a rotation

## Context

`OverlayState<S>` holds three things for a screen: which sheet or dialog is open, whether
the overflow menu is open, and a `SnackbarHostState`. `rememberOverlayState` created it
with a plain `remember`, and two earlier decisions said that was fine:
`2026-09-21-state-hoisting-audit` recorded "not yet saved across process death" as an
accepted trade-off, and `2026-10-02-routing-state-on-screen` recorded "closes on
rotation/back" as a property of the design.

Neither holds up against the app. `MainActivity` declares no `android:configChanges`, so a
rotation destroys and recreates the activity — that is not a rare "process death", it is
every turn of the phone. And `2026-10-21-blocked-completion-guard` had already decided the
opposite for one dialog: "a rotation must not silently cancel a pending confirmation".

The screen code followed neither rule consistently. Nine call sites lost their open sheet on
every rotation, among them `SavedAgendaDialog.Delete`, `ActiveSheet.ConfirmDelete`,
`NotePreviewSheet.DeleteConfirm` and `BackupDialog.CloudRestore` — exactly the class of
confirmation the 2026-10-21 decision says must not be cancelled silently. Issue #1452 asked
for the contradiction to be settled.

## Idea

Three ways out:

1. **Keep it ephemeral and say so once** — the status quo, but documented in one place
   instead of three. Rejected: it leaves a pending destructive confirmation cancelled by a
   rotation, which a later decision already forbids.
2. **Declare `android:configChanges="orientation|screenSize|…"`** so the activity is never
   recreated on rotation. One manifest line, and it would preserve every screen's `remember`
   state, not only overlays. Rejected: it does nothing for process death, which the issue
   names too, and it pushes configuration handling onto every imperative resource reader and
   onto the activity theme.
3. **Make the holder saveable** — `rememberSaveable` with a `Saver`, so the sheet and the
   overflow flag are handed to the activity's saved-instance bundle.

## Decision

The holder's state survives rotation and process death. `rememberOverlayState` takes the
sheet type's `KSerializer` and is built on `rememberSaveable`; the saved value is the sheet
itself, encoded with `StableJson`, plus the overflow flag. Each screen's sheet type is
`@Serializable`, so its variant and its payload are restored as they were.

`SnackbarHostState` is deliberately outside the saved state and is recreated empty: a
snackbar is a message about something that already happened, and replaying it into a
restored screen would say it twice.

A sheet whose saved form no longer decodes — the type changed shape between builds — restores
as "nothing open" rather than throwing during composition.

The serializer is a parameter, not a reified type argument, so a sheet type that is not
`@Serializable` is a compile error at its call site and not an empty screen after a rotation.

## Rationale

Saving the sheet, rather than a per-screen token that each screen maps back to a sheet,
keeps the rule in one place: whatever the screen was showing, it is still showing it. The
alternative — a hand-written `Saver` per screen — duplicates each payload's shape in a second
place (`RemoteBackup`'s four fields, `Tag`'s eleven) with nothing keeping the two in step, and
a `private` saver in `shared-ui` cannot be reached by a test in `shared`.

`rememberSaveable` is the smaller change than `configChanges` in consequence, not in line
count: it fixes process death as well as rotation, and it leaves the activity's configuration
handling alone. It also confines the decision to the one type that owns the state.

The payload is restored verbatim rather than re-resolved. That accepts a confirmation naming
a row that has since been removed; the write the dialog guards is idempotent, and the user
still has to press the button. Re-resolving would be a second mechanism for a case the
screen already tolerates.

## Consequences

- `rememberOverlayState` takes the sheet's `KSerializer`; a sheet type that is not
  `@Serializable` does not compile at its call site.
- The open sheet and the overflow menu survive a rotation and process death. The
  `SnackbarHostState` does not, and comes back empty.
- A restored confirmation re-asks its question; restoration itself writes nothing and
  re-validates nothing.
- A saved sheet that no longer decodes restores as "nothing open" instead of throwing.
- Do not add `android:configChanges` to the Android activity to keep state across a
  rotation; save the state instead.
- The "Closes on rotation/back" row of `2026-10-02-routing-state-on-screen` no longer holds.
  Routing state still lives on the screen, but it is now saveable.

## Links

- Issue #1452 (follow-up to #1411).
- `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/components/OverlayState.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/OverlayStateTest.kt`
- Superseded in part: `2026-09-21-state-hoisting-audit`, `2026-10-02-routing-state-on-screen`.
- Related: `2026-10-21-blocked-completion-guard` (a pending confirmation must not be
  cancelled by a rotation — this decision is what makes that true for every screen, not
  only the blocked-completion one).
