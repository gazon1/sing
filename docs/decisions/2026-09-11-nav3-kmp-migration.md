---
title: Nav3 KMP Migration (Android + JVM Desktop)
date: 2026-09-11
status: accepted
---
# Nav3 KMP Migration (Android + JVM Desktop)

## Context

The app used Navigation Compose (Nav2) on Android with a dead Nav2 implementation on Desktop. Android was being migrated to Navigation 3 (terrakok nav3-recipes multiplestacks pattern) but the Desktop stayed on Nav2.

## Decision
### 1. `App` is expect/actual at top level

`App` is the expect/actual seam (common → android → jvm), not individual shell functions. This avoids the problem where a common module referencing a function that only exists on one platform.

```kotlin
// commonMain
expect @Composable fun App()

// androidMain
@Composable actual fun App() { ... androidShellNav3() }

// jvmMain
@Composable actual fun App() { ... desktopShellNav3() }
```

### 2. `Nav3State` + `Navigator` in commonMain

`Nav3State` and `Navigator` are in `feature/nav/Nav3State.kt` (commonMain). Platform-specific is only the `rememberNav3State` factory:
- Android: no-arg `rememberNavBackStack(key)` — platform provides SavedState automatically
- JVM: requires `SavedStateConfiguration` with serializers for all `AppDestination` subclasses

### 3. `entryProvider {}` DSL for nav entries (not koin's `navigation {}`)

The koin `navigation {}` DSL has a classpath conflict where the multiplatform metadata JAR shadows the platform-specific implementation. We use `androidx.navigation3.runtime.entryProvider { }` directly instead.

### 4. `NavCallbacks` in commonMain

`NavCallbacks` (data class with `navigate` and `goBack` lambdas) is in `commonMain` so both androidMain and jvmMain can use it without cross-module visibility issues.

### 5. `PomodoroTimer` interface + platform implementations

`PomodoroTimer` is an interface in commonMain because Android's implementation uses `ViewModel` (not available on JVM). Platform bindings:
- Android: `AndroidPomodoroTimer(get(), get(), get())` — a ViewModel scoped by `viewModel { }` in DI
- JVM: `JvmPomodoroTimer()` — no-op placeholder since timer requires AlarmManager

### 6. `TasksScreenEntry` sealed class with `FromProject`

`TasksByProject` nav entry was hardcoded to `TasksScreenEntry.FromToday`. Fixed by:
- Changed `TasksScreenEntry` from enum to `sealed class` with `FromProject(projectId: ProjectId)`
- `TasksScreen` applies the entry filter on first composition via `LaunchedEffect`
- Both Android and JVM nav entries now pass `FromProject(ProjectId.fromString(route.projectId))`

### 7. Desktop: `ModalNavigationDrawer` with hamburger menu

Desktop uses `ModalNavigationDrawer` (not bottom bar). Drawer lists `DestinationKind.tabs` and `DestinationKind.menuEntries`. TopAppBar shows current destination title with hamburger icon.

### 8. `DestinationKind` in commonMain

`DestinationKind` (tabs list, menuEntries list, predicate helpers) lives in commonMain alongside `AppDestination`. `icon` extension and `title` are on `AppDestination` itself.

## Consequences

- Both Android and Desktop now use the same Nav3 architecture (multi-back-stack, `Navigator`, `NavDisplay`)
- `AppNavHost.kt`, `AppNavigator.kt`, `DesktopShell.kt` (old Nav2 files) are deleted
- Dead Nav2 code removed from Android
- `AppDestination.Habits` → `AppDestination.Pomodoro`, `AppDestination.Calendar` → `AppDestination.Statistics`
