---
title: "TagsScreen — No Scaffold Snackbar Workaround"
date: 2026-10-09
status: accepted
---

# ADR: TagsScreen — No Scaffold Snackbar Workaround

`TagsScreen` is a leaf screen in the navigation graph — it has no `Scaffold`, no `TopAppBar`, no `SnackbarHost`. It renders inside another screen's content area.

When implementing #104 (tags soft-delete + undo snackbar), the undo snackbar needed a `SnackbarHost` to render in. The solution was to hoist `tagsCountdownProgress` from `TagsViewModel` all the way up to `SettingsScreen`, which does have a `Scaffold`.

Current workaround in `SettingsScreen.kt` (tags tab):

```kotlin
var tagsCountdownProgress by remember { mutableStateOf<Float?>(null) }

LaunchedEffect(vm.countdownProgress) {
    vm.countdownProgress.collect { progress ->
        tagsCountdownProgress = progress
        if (progress == null && snackbarShown) {
            snackbarShown = false
        } else if (progress != null && !snackbarShown) {
            snackbarShown = true
            val result = scope.launch {
                snackbarHostState.showSnackbar("Tag deleted", actionLabel = "Undo", withDismissAction = true)
                    .asVoice()
                // ...
            }
        }
    }
}
```

This is awkward: `LaunchedEffect` syncs state across two scopes (TagsViewModel's scope and SettingsScreen's scope), and `SettingsScreen` manages the snackbar lifecycle for a feature it doesn't own.

## Decision

**Option A (preferred):** Give `TagsScreen` its own `Scaffold` with a `SnackbarHost`, and navigate to `TagsScreen` as a full screen rather than a tab content. Tags tab becomes a `NavHost` or a `Box` that can launch a full-screen `TagsScreen`.

**Option B (simpler):** Create a `TagsSnackbarHost` composable that `TagsScreen` exposes, which `SettingsScreen` places inside its own `Scaffold`. The snackbar state still lives in `TagsViewModel`, but the rendering is co-located.

## Consequences

- Current workaround works but is fragile: if Tags tab is replaced with a direct `TagsScreen` navigation, the `LaunchedEffect` in `SettingsScreen` breaks silently
- Tags tab is currently the ONLY place where undo snackbar for tags is needed — if that's the only consumer, the workaround is acceptable for now
- A dedicated `TagsScreen` with its own `Scaffold` would also solve the problem of Tags having no TopAppBar (currently Tags tab shares Settings' top bar)

## Related

See `ADR 2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` for the broader pattern of VM scoping and screen ownership.
