---
title: "Saved Views Crud Flow Selects A Snackbar That Does Not Exist"
date: 2000-01-01
status: CLOSED.
tags: ["deferred", "notification-routing-must-be-total"]
---

**Status (re-verified 2026-10-04):** CLOSED. MR-10 (`:feat/agenda-test-ratchet`) confirmed the red flow is fixed; the remaining `SNACKBAR_SAVED` declaration is inert but harmless (zero consumers). See the entry body for the two residues.

**Tracked as:** #110
**OpenSpec change:** `openspec/changes/notification-routing-must-be-total/`

**Found in:** MR-2, while cross-checking the constants slated for deletion
against their real consumers. Not a regression — a dormant red flow.

**Status: CLOSED.** MR-10 (`:feat/agenda-test-ratchet`).

**Root cause:** `Notification.Text("Saved", null)` → `ResultDialog(title, text=null)` →
`if (text == null) return` — the dialog is never shown, and there is no snackbar
either. `SNACKBAR_SAVED` is dead code: defined in `TestTags.kt` but applied by no
composable anywhere in `shared/src`. The flow was waiting for a UI element that
never rendered.

**Fix:** Removed the `extendedWaitUntil: id: snackbar_saved` step from the flow.
The save operation completes synchronously from the flow's perspective (the button
re-enables after persist), and the editor stays open without any visible
acknowledgement. A proper "Saved" toast requires `Notification.Undo` (which
routes to `SnackbarHost`) — a separate product decision documented as item #3
below.

**Future (not MR-10):**
1. Product decision: implement a non-undo "Saved" toast via `NotificationHost`
   routing `Notification.Text` to `SnackbarHost` instead of `ResultDialog`.
   Requires ADR + `SNACKBAR_SAVED` applied to the snackbar host.
2. If dialog is the intent instead: re-point the flow at `Dialog.CONFIRM`
   (button currently untagged — see `dialog-buttons-untagged`).
3. Sweep all flows: resolve every `id:` in `Maestro/flows/**` against
   `Modifier.testTag` sources. Add to `Maestro/scripts/check-tags.sh`.

---
