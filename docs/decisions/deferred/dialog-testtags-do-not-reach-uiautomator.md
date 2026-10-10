---
title: "Dialog Testtags Do Not Reach Uiautomator"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. The expect/actual helper (`TestTagResourceId.kt` + `.android.kt` + `.jvm.kt`) is applied in seven composables, `UiAutomationSelectorTest.kt:87` enforces the rule, and `profile/02-isolation.yaml` now selects on `dialog_confirm`. ADR `2026-10-04-testtag-visibility-helper` is the record.

**Found in:** MR-6, the Phase 6 Maestro gate. Journey 03 could not find
`id: dialog_confirm`.

`ConfirmActionDialog` tags its confirm button with `TestTags.Dialog.CONFIRM`
(`Modifier.testTag`), and the tag is declared in `TestTags.kt` and asserted by
the desktop Compose tests — where it works, because those read the semantics
tree directly.

On Android it does not work, and cannot: a Compose `AlertDialog` is a separate
window. The `testTagsAsResourceId` semantics property is applied to the main
window's root and is not inherited into it, so no `testTag` inside a dialog
becomes a resource-id. Confirmed against a captured hierarchy — the dialog's
"Delete" and "Cancel" render correctly and carry no resource-id at all.

**Checks already performed:** pulled the failure artifact's
`screen-hierarchy` JSON; every node in the dialog subtree has
`resource-id=""`. The same pattern applies to `ModalBottomSheet` (see the
MR-5 note about the profile picker selecting by label).

**Fix (journey):** superseded — journeys 03 and 07 now select by `id:`
again, because the structural fix below made the tag work.

**Fix (structural): DONE** (2026-10-04). `Modifier.mapTestTagsAsResourceIds()`
is an expect/actual helper (`core/ui/TestTagResourceId.kt` + `.android.kt` +
`.jvm.kt`; a no-op on JVM) applied **inside** each window-owning surface, so
every tagged node in it reaches UIAutomator. Applied to `ConfirmActionDialog`,
`ListPickerSheet`, `MultiSelectSheet`, `MenuBottomSheet` (MR-6) and, on
2026-10-04, to `CreateProfileDialog` in `ProfileSwitcherScreen` — its confirm
button *and* its name field, which the profile flow also selects by id.

The "apply it at each call site" alternative was rejected deliberately: a
`modifier` parameter is discipline, and discipline is what fails silently at
3am. ADR: `2026-10-04-testtag-visibility-helper.md`.

**The latent case is closed too.** `Maestro/flows/profile/02-isolation.yaml`
tapped `id: dialog_confirm` and could not find it. The flow carried a *second*
defect on top of that one, which is the more interesting find: it waited for
`id: saved_agenda_name_input` after tapping the profile-create button, but
`CreateProfileDialog` tags its field `profile_create_name_input`. Both the id
and the tag existed in `TestTags.kt`, so every existing check passed while the
flow could never have worked. Fixed by correcting the selector and exposing the
dialog.

**Why no gate caught it:** `MaestroFlowTagsTest` resolves each `id:` against
the `TestTags.kt` registry, which proves the tag *exists* — not that the right
element on the right screen carries it. A wrong-but-valid id is invisible to a
registry check; only running the flow finds it. The flow was outside the
`agenda` tag, and the new `maestro-smoke` CI job runs the `smoke` set, which
this flow is part of. See `maestro-ci-job-unproven`.

**Note for the next sweep:** `TaskEditorDiscardDialog` was a hand-rolled
duplicate of `DiscardChangesDialog` with zero call sites, untagged for
automation and invisible to `find-unwired-surfaces.py` (which skips
`/components/`). It was deleted rather than fixed. `TaskEditorSheetHost` — the
sheet host that *is* used by eight features — carries no `testTag` of its own
today, so it needs no exposure yet; that will change the moment a sheet puts a
tagged control inside it.

---
