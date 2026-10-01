---
title: "Desktop navigation follow-up: FAB hijack + tab-back regression"
date: 2026-10-01
tags: [desktop, nav3, regression, mr-followup]
status: accepted
---

# Desktop navigation follow-up

## Context

During MR-11 work (SavedAgendaResults screen) a pre-existing desktop regression was observed:
after saving a task in `TaskCreateScreen` or `SavedAgendaScreen` and pressing back, the entire
desktop UI went blank (0 semantics nodes). Investigation revealed three separate bugs.

## Bug 1 — Shell FAB targets wrong screen

**Severity**: High — silently wrong behavior, not a crash

`DesktopShellNav3Root` computed `fabActionForNav3(current)` where `current = state.topLevelRoute`
(the tab, e.g. `AgendaGraph(Today)`). While a nested screen was pushed on top
(e.g. `TasksGraph(Create)` from a FAB tap), the shell FAB still resolved to "Add task"
for the tab, overriding the nested screen's own FAB.

### Root cause

`FabActionResolver.fabActionForNav3` is keyed on `AppDestination` — it returns a FAB action
for tab routes (`AgendaGraph(Inbox)`, `AgendaGraph(Today)`, etc.) but returns `null` for
nested routes (`TasksGraph(Create)`, `SavedAgendaEdit`, etc.). The shell passed the tab,
not the topmost nested route.

### Fix

```kotlin
// DesktopShellNav3Root.kt — compute topMostRoute from the back stack
val topMostRoute: NavKey = state.requireBackStackFor(state.topLevelRoute).lastOrNull()
    ?: state.topLevelRoute
val fabCurrent: AppDestination = topMostRoute as? AppDestination ?: current
// Pass fabCurrent instead of current to fabActionForNav3
```

`fabCurrent` is the actual topmost route; for a tab with no nested push it equals `current`.

---

## Bug 2 — goBack() at tab root always teleports to Today

**Severity**: Medium — hidden by `canGoBack` guard, but fires on double-back

`Navigator.goBack()` at the root of a non-default tab (e.g. `Upcoming`) sets
`topLevelRoute = startRoute` (Today), silently teleporting the user to Today instead
of returning to the previously active tab.

### Root cause

`goBack()` had no memory of the previous tab:

```kotlin
// Before
if (currentRoute == state.topLevelRoute) {
    state.topLevelRoute = state.startRoute  // Always Today!
}
```

### Fix

`Nav3State` now tracks `previousTopLevelRoute` — the tab the user was on before switching.
`navigate(route)` (for a top-level route) records the current tab before switching.
`goBack()` at a tab root now restores `previousTopLevelRoute` instead of `startRoute`:

```kotlin
// Nav3State.kt
private var _previousTopLevelRoute: NavKey = startRoute

fun navigate(route: NavKey) {
    if (route in state.topLevelRoutes) {
        state.setPreviousTopLevelRoute(state.topLevelRoute)
        state.topLevelRoute = route
    } else {
        state.requireBackStackFor(state.topLevelRoute).add(route)
    }
}

fun goBack() {
    val currentStack = state.requireBackStackFor(state.topLevelRoute)
    val currentRoute = currentStack.lastOrNull() ?: return
    if (currentRoute == state.topLevelRoute) {
        state.topLevelRoute = state.previousTopLevelRoute  // Restore previous tab
    } else {
        currentStack.removeLastOrNull()
    }
}
```

---

## Bug 3 — Nested back stack dies on tab switch (latent)

**Severity**: Medium — nested graph NavBackStack is created with `remember` inside entry content,
so it is re-created on every recomposition of the entry. On Desktop, switching tabs causes
a full recomposition of `NavDisplay`'s entries, destroying the nested stack state.

This was not fixed in this session — it requires hoisting nested `NavBackStack` management
out of the entry content (similar to how the outer stack is managed by `Nav3State`), or
keying `remember` on a stable owner.

### Deferred

`nested-back-stack-lost-on-tab-switch` — tracked separately.

---

## Files changed

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3State.kt` — `previousTopLevelRoute`
  tracking; `goBack()` now restores previous tab instead of always going to `startRoute`
- `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt` — FAB resolves
  from `topMostRoute` (last item in current stack) instead of `topLevelRoute`

## Verification

```bash
./gradlew :shared:jvmTest            # green
./gradlew :desktopApp:test           # green
./gradlew :shared:detekt             # clean
```

## Related

- `docs/decisions/deferred-backlog.md` — `desktop-nav-goBack-blank-screen`, `nested-back-stack-lost-on-tab-switch`
- `Nav3State.kt` — the multi-back-stack state holder
- `DesktopShellNav3.kt` — desktop shell composable
- `FabActionResolver.kt` — FAB action resolution
