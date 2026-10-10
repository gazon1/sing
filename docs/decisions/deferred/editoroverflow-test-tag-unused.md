---
title: "Editoroverflow Test Tag Unused"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability audit. `EditorOverflow` in
`core/ui/TestTags.kt` has 12 test references and zero production composables
apply it.

**Status: CLOSED (65 closed)** — the tracked issue is closed,
so this finding is no longer an open commitment.

**Tracked as:** #464

**Symptom:** the same shape as `SNACKBAR_SAVED`, which was resolved by wiring the
tag. A test tag that no production code emits is a test asserting a state the app
can never reach — so those 12 references are either no-ops or they are skipped
without notice.

**Already ruled out:** not a dynamic lookup — the constant is referenced
statically, and `grep` for `testTag(EditorOverflow` in `commonMain` is empty.

**Try next:** find which screen the tests mean to cover and apply the tag there,
or delete the constant and the 12 references. Confirm which first: if the tests
pass today without the tag being emitted, they are not testing the overflow at
all, which is the more interesting finding.

---
