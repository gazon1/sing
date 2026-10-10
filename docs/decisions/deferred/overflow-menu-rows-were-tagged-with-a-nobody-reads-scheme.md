---
title: "Overflow Menu Rows Were Tagged With A Nobody Reads Scheme"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04; **#65 closed with it.** `TaskEditorContent.kt:287-306` applies `Modifier.mapTestTagsAsResourceIds()` and each `DropdownMenuItem` gets `item.testTag`, with items carrying `EditorOverflow.RESTORE/ARCHIVE/DELETE` from `TaskDetailContent.kt:229`. Residual kept deliberately: `PIN`/`UNPIN` remain in the allowlist, and `taskAction` still coexists for the context menu — that is the #109 work, not this defect.

**Found in:** 2026-10-04, the second `smoke` run, chasing why
`archive/01-restore.yaml` failed on `id: overflow_archive` *inside* a single flow
— so not cascade residue, and therefore a real defect.

**Symptom:** the flow taps `task_editor_more_menu` (that tag is real and applied,
`TaskDetailTopBar.kt:56`), the editor's three-dot menu opens correctly, and then
`overflow_archive` is not there. The captured hierarchy shows the menu rendering
"Архивировать / Удалить" — a `DropdownMenu` with **no resource-id on any row**.

**Two causes, either of which alone was fatal:**

1. **The rows were never tagged at all.** `TaskEditorContent.kt:286` built its
   `DropdownMenuItem`s with only `text` and `onClick`. No `Modifier.testTag`.
2. **The `DropdownMenu` had no exposure.** Same structural defect as the dialogs
   in `dialog-testtags-do-not-reach-uiautomator` — a `DropdownMenu` is its own
   window and never inherits the app-root `testTagsAsResourceId`.

**The naming trap, which is the real lesson.** The flows ask for
`overflow_archive` because `TestTags.EditorOverflow.ARCHIVE` is *declared* with
exactly that value. But that constant has **no call site**: the overflow rows
are tagged through a different scheme entirely, `TestTags.taskAction(label)`,
producing `task_action_archive`. The two schemes differ by one prefix, and the
unused one is the one a test author finds first by reading `TestTags.kt`.

`TestTagsWiringTest` knew. Its `knownUnapplied` allowlist already lists all five
`EditorOverflow.*` constants with the reason "the overflow menu renders rows
through `TestTags.taskAction(action)`, so this constant has no call site". So
the registry, the wiring test and the flows disagreed, and the flows were the
only ones nobody ran.

**Fix:** rows now carry `TestTags.taskAction(item.label)`, and both the
`DropdownMenu` and its items get `mapTestTagsAsResourceIds()`. Flows
`archive/01-restore`, `tasks/04-delete` and `tasks/06-delete-undo` were
repointed from `overflow_*` to `task_action_*`.

**Left standing, deliberately:** the five dead `EditorOverflow.*` constants stay
in the registry, because deleting them would make `MaestroFlowTagsTest` fail —
correctly, but for the wrong reason. A flow written tomorrow would hit the same
trap. The honest fix is to delete the constants *and* the flows' dependence on
them in one change, which is what the allowlist entry has been asking for since
`2026-09-30-draft-save-failure-and-testtag-honesty`.

**Do this first:** decide whether the editor overflow should be
`EditorOverflow.ARCHIVE` or `taskAction("Archive")` — pick one, delete the
other, and let the registry shrink. The cost of keeping both is precisely this
class of bug, and it has now cost two debugging sessions.

`TaskContextMenuSheet` was fixed in the same pass: it *is* tagged
(`TASK_CONTEXT_MENU_SHEET`) and its rows do use `taskAction`, but the
`ModalBottomSheet` had no exposure, so the tag was invisible on Android for the
same structural reason.

---
