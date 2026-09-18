---
title: "SettingsNavGraph and SearchNavGraph — single-route nested graphs"
date: 2026-09-16
tags: [navigation, nav3, settings, search]
status: accepted
---

## Context

Settings and Search screens previously used direct callbacks threaded from `AndroidNavEntries` / `JvmNavEntries`. This required passing `onNavigateToProfileSwitcher` through multiple layers of composable parameters in Settings. Search had no cross-feature navigation wired at all — taps on tasks, notes, and projects were empty `{}` lambdas.

Both screens needed a cleaner pattern: a nested graph that provides a type-safe navigator, while keeping their internal tab/state management simple.

## Decision

### SettingsNavGraph (single-route)

`Settings` is a single-entry nested graph. Tabs remain local `var selectedTab by remember` state in `SettingsContent` — no navigation needed for tab switching. The only cross-feature hop is Settings → ProfileSwitcher.

New files in `feature/settings/presentation/nav/`:
- `SettingsRoute.kt` — `data object Settings : NavKey`
- `SettingsNavigator.kt` — `open fun openProfileSwitcher()`, `back()`
- `LocalSettingsNavigator.kt` — `compositionLocalOf<SettingsNavigator>`
- `SettingsNavGraph.kt` — `expect fun SettingsNavGraph(navCallbacks, modifier)`
- `SettingsNavGraph.android.kt` / `.jvm.kt` — actuals with `BackHandler` + `rememberViewModelStoreNavEntryDecorator` on Android

`SettingsScreen` drops `onNavigateToProfileSwitcher`. `AccountSettingsScreen` reads `LocalSettingsNavigator.current.openProfileSwitcher()` directly.

### SearchNavGraph (single-route)

`Search` is a single-entry nested graph. Cross-feature hops: task → `TasksGraph(Detail)`, note → `NotesGraph(Preview)`, project → `ProjectDetail`.

New files in `feature/search/presentation/nav/`:
- `SearchRoute.kt` — `data object Search : NavKey`
- `SearchNavigator.kt` — `openTask(TaskId)`, `openNote(NoteId)`, `openProject(ProjectId)`, `back()`
- `LocalSearchNavigator.kt` — `compositionLocalOf<SearchNavigator>`
- `SearchNavGraph.kt` — `expect fun SearchNavGraph(navCallbacks, modifier)`
- `SearchNavGraph.android.kt` / `.jvm.kt` — actuals

`SearchScreen` reads `LocalSearchNavigator.current` and wires `TaskCard(onClick = { navigator.openTask(task.id) })`, etc.

## Rationale

- Single-route graphs are intentional: both features have no inner navigation — their "screens" are single-page forms. The nested graph infrastructure gives us `LocalXxxNavigator` without imposing any boilerplate on the inner UI.
- `SettingsNavigator` owns only the cross-feature hop (ProfileSwitcher). All tab state stays in `SettingsContent` as plain `remember` — correct, since tabs are ephemeral UI state.
- `SearchNavigator` follows the same pattern as `ProjectsNavigator` / `NotesNavigator`: open methods for each cross-feature destination type.
- Tag tap in Search stays `onClick = {}` — no destination defined.

## Consequences

- `SettingsScreen` no longer accepts `onNavigateToProfileSwitcher` — `AccountSettingsScreen` navigates directly.
- `NavEntries.kt` wires `SettingsNavGraph(navCallbacks = nav)` and `SearchNavGraph(navCallbacks = nav)` instead of the raw screens.
- Preview for `AccountSettingsScreen` uses a separate `AccountSettingsScreenPreviewContent` composable that takes an explicit callback, since `LocalSettingsNavigator` is only available inside the graph.

## Links

- `feature/settings/presentation/nav/Settings*.kt`
- `feature/search/presentation/nav/Search*.kt`
- `SettingsScreen.kt`, `AccountSettingsScreen.kt` (modified)
- `SearchScreen.kt` (modified)
- `AndroidNavEntries.kt`, `JvmNavEntries.kt` (updated entries)
