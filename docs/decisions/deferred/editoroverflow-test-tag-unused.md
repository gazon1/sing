---
title: "Editoroverflow Test Tag Unused"
date: 2000-01-01
status: closed
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability audit. `EditorOverflow` in
`core/ui/TestTags.kt` has 12 test references and zero production composables
apply it.

**Status: CLOSED — EditorOverflow IS wired as of the auth-flows work (2026-10-14).**
`TaskDetailContent.kt` applies `TestTags.EditorOverflow.ARCHIVE`, `.DELETE`, and
`.RESTORE` to the three overflow menu items in the task detail screen. The 12
test references and the three production usages together mean the tag is no longer
unwired. The baseline entry has been removed.

**Tracked as:** #464

**Symptom:** the same shape as `SNACKBAR_SAVED`, which was resolved by wiring the
tag. A test tag that no production code emits is a test asserting a state the app
can never reach — so those 12 references are either no-ops or they are skipped
without notice.

**Resolution (2026-10-14):** EditorOverflow is now wired. The baseline entry for
it has been removed. No further action needed.

---
