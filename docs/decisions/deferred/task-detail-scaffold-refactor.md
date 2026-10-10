---
title: "Task Detail Scaffold Refactor"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** [#79](https://github.com/gazon1/sing/issues/79) · OpenSpec change `delete-safety-feedback` (proposed)

**Found in:** MR-1, attempting to add `Scaffold` + `SnackbarHost` to `TaskDetailViewScreen`.
Private composables (`LoadingState`, `ErrorState`, etc.) are defined at file level and
become inaccessible inside `Scaffold.content` lambda.

**Checks already performed:** `Box` structure works. Snackbars via `Notification.None`
pattern (LaunchedEffect) work correctly.

**Fix:** Extract private composables into a separate internal composable function
`private fun TaskDetailLoadedScaffold(...)` that takes `snackbarHostState` as parameter,
or move them to a companion object. Alternative: use a `SnackbarHostState` at the
parent nav-graph level and pass it down.

---
