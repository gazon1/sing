---
title: "Countdown Snackbar"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED** — Material3 design constraint. Standard `SnackbarHost` does not support countdown; implementing a custom composable with `LinearProgressIndicator` is non-trivial and was ruled out in the backlog entry. This is a known Material3 limitation, not a bug.

**Tracked as:** [#80](https://github.com/gazon1/sing/issues/80) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1 retro-gate. `LaunchedEffect(pendingDelete)` only re-triggers on
value changes, not on a timer. The snackbar shows no visual countdown.

**Ruled out:** Standard Material3 `SnackbarHost` does not support countdown. Custom
`Snackbar` with `ProgressIndicator` is non-trivial.

**Fix:** Replace `SnackbarHost` with a custom composable that shows a `LinearProgressIndicator`
inside the snackbar, animated from 100% to 0% over 5 seconds using `animateFloatAsState`.

---
