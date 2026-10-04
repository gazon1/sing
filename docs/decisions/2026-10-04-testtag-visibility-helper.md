---
title: "One helper for testTag visibility, applied inside window-owning surfaces"
date: 2026-10-04
status: accepted
tags: [testing, compose, maestro, ui-automation, android]
---

# One helper for testTag visibility, applied inside window-owning surfaces

## Context

Journey 03 could not find `id: dialog_confirm`. The button is tagged correctly
in `ConfirmActionDialog`, the tag exists in `TestTags.kt`, and the desktop
Compose tests use it successfully. On Android it is simply not there — the
captured UI hierarchy shows the dialog rendering "Delete view?", "Delete" and
"Cancel" with **no resource-id on any of it**.

The cause is structural, not a missing tag. Android maps a Compose `testTag` to
a UIAutomator `resource-id` only where the semantics property
`testTagsAsResourceId` is set. The app root sets it once (`App.kt`), which covers
the main window and nothing else. A Compose `AlertDialog`, a `ModalBottomSheet`
and a `DropdownMenu` each render into **their own window**, and that window does
not inherit the property.

The codebase already knew this. `MenuBottomSheet` takes a `modifier` parameter
whose KDoc explains that "the sheet lives in its own window, so an app-root flag
never reaches it", and `AndroidShellNav3` passes
`Modifier.semantics { testTagsAsResourceId = true }` at the call site.
`AgendaNavGraph.android.kt` does the same for its long-press menu.

## Idea

Two ways to stop this class of bug.

**A. Keep the parameter pattern.** Every window-owning surface takes a
`modifier`, and every Android call site passes the semantics flag.

**B. Apply it inside the shared surface.** The surface that owns the window
applies the flag to its own content, so a caller cannot forget.

## Decision

**B**, via a single expect/actual helper.

```kotlin
// commonMain — core/ui/TestTagResourceId.kt
expect fun Modifier.mapTestTagsAsResourceIds(): Modifier

// androidMain — the real thing
actual fun Modifier.mapTestTagsAsResourceIds(): Modifier =
    semantics { testTagsAsResourceId = true }

// jvmMain — desktop reads the semantics tree, where this has no meaning
actual fun Modifier.mapTestTagsAsResourceIds(): Modifier = this
```

Applied inside `ConfirmActionDialog` (which also covers `DiscardChangesDialog`,
since it delegates), `ListPickerSheet`, and `MenuBottomSheet`.

`MenuBottomSheet` loses its `modifier` parameter and its one caller loses the
flag. The property is Android-only in Compose — `testTagsAsResourceId` does not
even resolve in commonMain — which is exactly why the pattern had to be a
parameter in the first place. The expect/actual keeps the *decision* in
commonMain and confines the platform detail to a one-line actual.

## Rationale

The parameter pattern is a discipline, and a discipline that is invisible when
broken is not worth much. Nothing warns a caller who omits the flag; the tag
looks correct in `TestTags.kt`, the desktop tests pass, and the failure appears
only as "element not found" in a flow on a device — which is how
`profile/02-isolation.yaml` has been sitting broken, outside every tag set any
gate runs.

Applying the flag inside the surface makes omission impossible. It also removes
the inconsistency: after this change there is one idiom, not two, and the next
surface someone writes gets the right behaviour by default.

The cost is that it is a real API decision — a shared dialog now hard-codes an
Android automation concern into its modifier chain. That is acceptable because
the concern is invisible (a no-op on every other platform) and because the
alternative is a class of bug that has now cost two debugging sessions.

## Consequences

- `TestTags.Dialog.*` and `ListPickerItem.testTag` become usable from Maestro on
  Android. Journeys 03 and 07 go back to id selectors, which is stronger than
  the label-based workarounds they carried while this was unfixed.
- `profile/02-isolation.yaml` stops being latently broken.
- The fix is verified on a device, not by a unit test — the property has no
  meaning off Android, so a JVM test would pass vacuously. The journeys that
  select by id *are* the test.
- `MaestroFlowTagsTest` still cannot catch a regression here. It checks that ids
  are declared and used; it cannot check that they resolve on a platform. A flow
  using a dialog id is now genuinely covered, but only when someone runs it.

## Amendment, 2026-10-04: the audit that was never done

The first application covered the shared surfaces. It did **not** sweep for
window-owning surfaces that had not adopted the helper yet, and two were left:

1. `CreateProfileDialog` (`ProfileSwitcherScreen.kt`) — its own `AlertDialog`
   with a `testTag`'d confirm button and a `testTag`'d name field. The flow
   `profile/02-isolation.yaml` selects **both** by id, so it was doubly
   unreachable.
2. `TaskEditorDiscardDialog` (`TaskEditorSheetHost.kt`) — a hand-rolled
   duplicate of `DiscardChangesDialog` with **zero call sites**, invisible to
   `find-unwired-surfaces.py` because that script skips `/components/`.

The first was fixed. The second was deleted rather than tagged, on the
principle already recorded in `log-export-has-no-surface`: a tested, tagged,
never-invoked dialog is worse than no dialog, because the next reader assumes it
is wired. `DiscardChangesDialog` already covers the use case and delegates to
`ConfirmActionDialog`, so the tag path is preserved.

**The lesson is about the sweep, not the two fixes.** Applying a fix to the
surfaces you happen to be looking at leaves the same bug alive in the ones you
are not. "I fixed the dialogs" was true and incomplete at the same time, and
only a deliberate `grep Dialog.CONFIRM` over `commonMain` found the rest. Any
future structural fix of this shape should start with that grep, not with the
call stack of the failure.

### Second sweep, same class, three more surfaces

The first sweep was itself incomplete, which is the point. Running the `smoke`
set for the first time found the same defect in a different window type:

- **`TaskEditorContent.kt` — `DropdownMenu`.** Its rows carried **no
  `testTag` at all**, and the menu had no exposure. Two independent reasons it
  was unreachable, either of which alone was fatal.
- **`TaskContextMenuSheet.kt` — `ModalBottomSheet`.** Correctly tagged
  (`TASK_CONTEXT_MENU_SHEET`), rows correctly tagged via `taskAction(label)` —
  and still invisible to Maestro, because the sheet had no exposure. Being
  tagged is not the same as being *findable*.
- **`SavedAgendaCard.kt`, `ViewModeDropdown.kt`** and any other `DropdownMenu`
  in the tree: still unaudited. This is the residue of doing the sweep by hand
  instead of by rule.

The generalisation, and the reason this amendment is longer than the ADR it
amends: **the class of bug is "window-owning surface", and `AlertDialog` is only
one member of it.** `ModalBottomSheet`, `DropdownMenu`, `Popup` and context menus
all qualify. A fix scoped to the type that happened to fail first will leave the
others behind — twice over, as this entry now records.

**What would have prevented both rounds:** a check that every
window-owning composable applies `mapTestTagsAsResourceIds()`. That is a
static, cheap, greppable property — an `AlertDialog(` / `ModalBottomSheet(` /
`DropdownMenu(` whose subtree does not reach the helper — and it is the obvious
next detector for `find-unwired-surfaces.py`, which already parses Kotlin and
already owns this class of finding. It is not done; recording it here so the
next person does not make the same sweep a third time by hand.

The stale-flow finding from the same sweep is worth separating out, because it
is a *different* defect with the same symptom. `profile/02-isolation.yaml` also
waited for `id: saved_agenda_name_input` after tapping the profile-create
button, while the dialog tags that field `profile_create_name_input`. Both ids
are declared in `TestTags.kt`, so the registry check passed, the tag was
"correct", and the flow could never have worked. A wrong-but-valid id is
invisible to any check that only asks whether the tag exists.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTagResourceId.kt`
- `core/ui/components/ConfirmActionDialog.kt`, `core/ui/components/sheet/ListPickerSheet.kt`,
  `core/ui/components/sheet/MultiSelectSheet.kt`
- `feature/profile/ProfileSwitcherScreen.kt` (create dialog),
  `feature/tasks/presentation/components/TaskEditorSheetHost.kt` (host; dead dialog removed)
- `feature/tasks/presentation/components/detail/TaskEditorContent.kt` (editor overflow menu),
  `feature/tasks/presentation/contextmenu/TaskContextMenuSheet.kt` (long-press sheet)
- `shell/MenuBottomSheet.kt`, `androidMain/.../AndroidShellNav3.kt`
- `docs/plans/2026-10-04-mr6-retro-gate.md` — §7
- `docs/decisions/deferred-backlog.md` — `dialog-testtags-do-not-reach-uiautomator`,
  `overflow-menu-rows-were-tagged-with-a-nobody-reads-scheme`
