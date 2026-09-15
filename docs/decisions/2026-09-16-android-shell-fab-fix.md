---
title: "AndroidShellNav3 FAB — wire to real navigation"
date: 2026-09-16
tags: [navigation, nav3, android, fab]
---

## Context

The FAB in `AndroidShellNav3.fabActionForNav3` had empty `onClick` handlers for Inbox, Today, and Plans. The FAB was visible but did nothing when tapped.

```kotlin
// Before
AppDestination.Inbox, AppDestination.Today -> FabAction("Add task") { }
AppDestination.Plans -> FabAction("Add project") { }
```

## Decision

Wire the FAB to real navigation:

```kotlin
// After
AppDestination.Inbox, AppDestination.Today -> FabAction("Add task") {
    navigator.navigate(AppDestination.TasksGraph(AppDestination.TasksStartRoute.Create))
}
AppDestination.Plans -> FabAction("Add project") {
    navigator.navigate(AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.Editor()))
}
```

Notes continues to navigate to `AppDestination.Notes` (its own graph, which shows the notes list with a create FAB of its own).

## Rationale

- Inbox/Today FAB should open `TasksGraph` with `Create` start — consistent with the `TasksStartRoute.Create` pattern used throughout the app.
- Plans FAB should open `ProjectsGraph` with `Editor()` start — `ProjectsStartRoute.Editor()` exists (confirmed in `AppDestination.kt`), so the create-project flow works correctly.
- The FAB label "Add project" matches the `ProjectsStartRoute.Editor()` create mode.

## Consequences

- Users can now create tasks directly from Inbox/Today via the FAB.
- Users can now create projects directly from Plans via the FAB.
- Smoke test: tap FAB on Inbox → verify CreateTask opens; tap FAB on Plans → verify CreateProject opens.

## Links

- `AndroidShellNav3.kt` (`fabActionForNav3`)
