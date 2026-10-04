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
// commonMain — core/ui/TestTagExposure.kt
expect fun Modifier.exposeTestTagsAsResourceId(): Modifier

// androidMain — the real thing
actual fun Modifier.exposeTestTagsAsResourceId(): Modifier =
    semantics { testTagsAsResourceId = true }

// jvmMain — desktop reads the semantics tree, where this has no meaning
actual fun Modifier.exposeTestTagsAsResourceId(): Modifier = this
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

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTagExposure.kt`
- `core/ui/components/ConfirmActionDialog.kt`, `core/ui/components/sheet/ListPickerSheet.kt`
- `shell/MenuBottomSheet.kt`, `androidMain/.../AndroidShellNav3.kt`
- `docs/plans/2026-10-04-mr6-retro-gate.md` — §7
