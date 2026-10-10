---
title: "Undo Restore Failure Notify"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** [#78](https://github.com/gazon1/sing/issues/78) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1 retro-gate, `AgendaViewModel.onUndoDelete`.

When `taskRepo.restore(taskId)` fails, `_pendingDelete` is already set to `null`
and the snackbar has dismissed. The user gets no feedback.

**Checks already performed:** `restore` returns `Result<Unit>`, failure is caught
but only logged.

**Fix:** On restore failure, re-set `_pendingDelete` with an error flag and show
an error snackbar; or emit a `AgendaUiEvent.ShowError` event.

---
