---
title: "Flows Select By Localised Text And The Device Is Russian"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** [#90](https://github.com/gazon1/sing/issues/90)

**Found in:** 2026-10-04, the third `smoke` run, immediately after
`tasks/04-delete` was fixed — and the same root cause as
`a-testtag-built-from-a-localised-label-changes-with-device-locale`, one level
up.

**Symptom:** `tasks/06-delete-undo.yaml` waits for `text: "Undo"` and taps it.
The emulator is Russian, so the snackbar's action button reads "Отменить" and
the flow can never pass. `archive/01-restore.yaml` and `tasks/04-delete.yaml`
were fixed and pass on the same device — the difference is exactly whether the
flow selects by **id** or by **text**.

**Ruled out:** this is not a flow typo. `Notification.Undo` has a perfectly good
stable `actionLabel` field, and the delete-undo feature itself works — a user on
a Russian device gets a working Undo button. Only the *selector* is wrong.

**Why it was never noticed:** the flows were written on an English device, and
every flow that selects by text was therefore correct at the time it was
written. The device locale is not part of any gate's inputs, so nothing
re-checks it.

**Fix, two options, and the second is better:**

1. Change the flow to select the action by whatever text the device shows. This
   makes the flow pass and teaches nothing — it is a flow that only works in one
   locale, which is the bug restated.
2. **Tag the snackbar action button** and select by id. `NotificationHost.kt:59`
   calls `snackbarHostState.showSnackbar(actionLabel = …)`; the rendered button
   belongs to Material3's `SnackbarHost` and cannot be tagged from the call
   site, so this needs a small custom `SnackbarHost` (or wrapping the action in
   one). That is the same shape as the `TaskEditorSheetHost` pattern already in
   the codebase, and it is the only option that makes the selector locale-proof.

**Do this first:** option 2, then sweep every flow for `text:` selectors and
convert the ones naming user-visible chrome. `grep -rn "text:" Maestro/flows/`
is the starting point; the ones worth converting are the labels that appear in
more than one place, since those are the ones a translation will move.

The general rule is now stated twice in this file, in two directions — once for
tags built from localised labels in code, once for flows selecting localised
text. Both are the same defect: **a selector that a translator can move.**

---
