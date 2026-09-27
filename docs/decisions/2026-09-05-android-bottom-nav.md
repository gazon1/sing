---
title: "Android bottom navigation bar via AppShell + NavHost (no separate ViewModels)"
date: 2026-09-05
tags: [navigation, compose, shell, ui]
status: accepted
---

> **Superseded in part (2026-09-27):** the `isDesktop` flag described below was removed — it
> had no `expect`, no usages and no callers. Platform-specific shells are selected through the
> concrete actual, not a runtime boolean. See
> [2026-09-27-remove-platform-clock-object.md](2026-09-27-remove-platform-clock-object.md).

## Context

The Android app previously used a `ModalNavigationDrawer` (mobile) / `PermanentNavigationDrawer` (desktop) driven by a hand-rolled `selectedIndex` state in `feature/nav/Navigation.kt`. The user wanted the Android layout to match a reference design with a bottom navigation bar (Menu / Inbox / Today / Plans / Habits / Calendar) and a global FAB.

The Voyager 1.1.0-beta03 dependency was declared in `libs.versions.toml` and `build.gradle.kts` but never imported anywhere — dead weight.

## Idea

We considered three approaches:

1. **`TabRow` from Material3** with `selectedTab` state and a `when` switch.
   - Simple, but loses backstack per tab and doesn't scale to sub-routes.
2. **Voyager TabNavigator** (already on the classpath).
   - Single-platform assumption; KMP compatibility is iffy; adds another mental model.
3. **`androidx.navigation:navigation-compose` with type-safe routes.**
   - Standard KMP API; `popUpTo + saveState + restoreState + launchSingleTop` gives per-tab backstacks out of the box; type-safe `composable<T>` infers payload types at compile time.

We chose option 3.

## Decision

Replace the drawer + `selectedIndex` switch with an `AppScaffold` pattern on Android and keep the existing drawer on Desktop:

```
App()
  └── rememberAppNavigator()
        ├── Android (isDesktop=false)
        │     └── AndroidShell(navigator) — Scaffold + NavigationBar + FAB + AppNavHost + MenuBottomSheet
        └── Desktop (isDesktop=true)
              └── DesktopShell(navigator) — reuses existing AppShell drawer
```

Key files:

| Path | Role |
|---|---|
| `feature/nav/AppDestination.kt` | `@Serializable sealed interface AppDestination` (10 destinations: 5 tabs + 5 menu + 5 sub-routes) + `BottomBarEntry` enum + `MenuEntry` enum + `DestinationKind` predicate |
| `feature/nav/AppNavigator.kt` | `@Stable class AppNavigator(controller)` — wraps `NavHostController`, enforces the contract: `navigateTopLevel` (popUpTo + saveState + restoreState + launchSingleTop), `navigate` (push), `popBackStack`, `currentTopLevelDestination` |
| `shell/AndroidShell.kt` | `Scaffold(bottomBar=NavigationBar{6 items}, floatingActionButton=ExtendedFAB)` + `AppNavHost` + `if (menuVisible) MenuBottomSheet(...)`. Accepts optional `content` for testability. |
| `shell/AppNavHost.kt` | Single `NavHost` with all `composable<AppDestination.X>` routes; per-tab sub-navigation via `rememberSaveable` |
| `shell/DesktopShell.kt` | Adapts `AppNavigator` to the existing `AppShell` drawer (mapper functions `AppDestination.toNavDestination()` and `tabDestinationFor(NavDestination)`) |
| `shell/MenuBottomSheet.kt` | `ModalBottomSheet` with Account / Search / Destinations sections |
| `App.kt` | 5-line entry: `if (isDesktop) DesktopShell(navigator) else AndroidShell(navigator)` |

`MenuBottomSheet` is **not** a navigation destination — it's a local overlay (`rememberSaveable { mutableStateOf(false) }`) that appears above `NavHost` without disturbing the underlying back stack. This matches the reference plan §9.

`AndroidShell` and `AppNavHost` accept the navigator (not the raw `NavHostController`) so screens stay type-safe and we can swap in a fake for tests.

### Dependencies

- **Added**: `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` (covers main + test APIs; `TestNavHostController` ships with the main artifact in 2.8+).
- **Removed**: 4 `cafe.adriel.voyager:*` artifacts — never imported anywhere.
- **AndroidHostTest**: added `org.jetbrains.compose.ui:ui-test-junit4:1.11.1` + `ui-test:1.11.1` for Compose UI Test infra.
- Added a tiny `src/androidHostTest/AndroidManifest.xml` that registers `androidx.activity.ComponentActivity` — required by Robolectric 4.16+ (see [robolectric/robolectric#4736](https://github.com/robolectric/robolectric/pull/4736)).

### Testing

**Unit (`commonTest`)**: `DestinationKindTest` — 8 pure tests verifying which destinations are tabs / menu / sub-routes and that classification is mutually exclusive.

**Widget + Integration (`androidHostTest`)**:

| Test file | Coverage |
|---|---|
| `AppNavigatorTest` | 6 tests against a real `rememberNavController`: `navigateTopLevel` idempotency, per-tab backstack save/restore, sub-route restore, contract guard (rejects sub-routes), `popBackStack` semantics. |
| `AndroidShellFlowTest` | 5 tests against the real chrome: 6 bottom-bar items visible, FAB visible, FAB navigates to `TaskEditor`, tab tap navigates to `Plans`, Menu tap doesn't change navigation (sheet is overlay, not destination). |

Tests use no mocks — only real `NavHostController` and the production `AndroidShell` (with a synthetic `content` slot that swaps in a placeholder NavHost for isolation).

## Rationale

Why this layout and not `TabRow`:

- `TabRow` would lose per-tab backstacks. Going Today → TaskDetail → Plans → Today would reset Today.
- `TabRow` would mix `state` and `event` — see the `singularity-todo-ui-event-vs-state` skill.

Why a wrapper (`AppNavigator`) and not bare `NavHostController`:

- The contract (`popUpTo + saveState + restoreState + launchSingleTop`) is greppable in one place.
- Tests construct `AppNavigator(realController)` and verify the contract — no mocks.
- `@Stable` lets Compose skip recomposition.

Why a separate `AppNavHost` composable:

- Keeps the graph definition in one place — testable + greppable.
- `AndroidShell` stays focused on chrome (`Scaffold` + bottom bar + FAB).
- Desktop and Android share the same graph — only the chrome differs.

Why `MenuBottomSheet` is overlay, not destination:

- The user expects "today" to remain visible underneath the menu.
- Dismissing the sheet should not pop the back stack.
- It's presentation-only — no business state lives here.

## Consequences

- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync.
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack.
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored.
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before.
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile.
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`.

## Links

- Reference plan: `/docs/plans/2026-09-05-android-bottom-nav-plan.md` (user-provided)
- Compose Navigation type-safe routes: https://developer.android.com/guide/navigation/design/type-safety
- `koinBridge` precedent: `2026-09-05-koin-suspend-bridge.md`
- Robolectric manifest issue: https://github.com/robolectric/robolectric/pull/4736
