---
title: "LocalAppNavigator + shared rememberNav3State factory"
date: 2026-09-16
tags: [navigation, nav3, architecture]
status: accepted
---

## Context

Navigation 3 composition-local pattern was established for Tasks, Projects, and Notes graphs, but two gaps remained:

1. **LoginScreen** was signed out when `AuthGuard` showed it — no `LocalAppNavigator` was available yet because `App.kt` (commonMain) didn't own the `Nav3State`. State construction happened inside `AndroidShellNav3` / `DesktopShellNav3`, which live inside `PlatformShell` (called *after* `AuthGuard`).

2. **`rememberNav3State` was platform-specific** with no common factory — Android and JVM each had their own private version. Sharing state between them required duplicating the factory logic.

## Decision

### 1. `LocalAppNavigator` in commonMain

`shared/src/commonMain/.../feature/nav/LocalAppNavigator.kt`:

```kotlin
val LocalAppNavigator = compositionLocalOf<NavCallbacks> {
    error("AppNavigator not provided — wrap with the shell")
}
```

`App.kt` (commonMain) now constructs `Nav3State`, `Navigator`, and `NavCallbacks`, then provides `LocalAppNavigator` via `CompositionLocalProvider` **before** calling `PlatformShell` / `AuthGuard`. This makes `LocalAppNavigator` visible to every screen including `LoginScreen`.

### 2. `rememberNav3State` as expect/actual

`Nav3StateFactory.kt` (commonMain — expect):
```kotlin
expect @Composable
fun rememberNav3State(): Nav3State
```

`Nav3StateFactory.android.kt` and `.jvm.kt` — two actuals using `SavedStateConfiguration { }`.

Both platforms now use an empty `SavedStateConfiguration { }`, which enables `rememberSaveable` in `rememberNavBackStack` to persist back stacks across process death.

### 3. Platform shell signature change

Both shells now take `state: Nav3State`, `navigator: Navigator`, `navCallbacks: NavCallbacks` as parameters instead of constructing them internally.

`App.kt` owns construction:
```kotlin
val state = rememberNav3State()
val navigator = remember(state) { Navigator(state) }
val navCallbacks = remember(navigator) {
    NavCallbacks(navigate = navigator::navigate, goBack = navigator::goBack)
}
CompositionLocalProvider(LocalAppNavigator provides navCallbacks) {
    AuthGuard { PlatformShell(state, navigator, navCallbacks) }
}
```

## Rationale

- `LocalAppNavigator` before `AuthGuard` solves the chicken-and-egg: `LoginScreen` now has cross-feature navigation without a callback.
- `LocalAppNavigator` is `CompositionLocalOf<NavCallbacks>` — the same interface used everywhere, no new abstraction.
- Hoisting `rememberNav3State` to commonMain eliminates duplication and makes the state construction contract explicit.
- `SavedStateConfiguration { }` on Android fixes the latent bug where back stacks were lost on process death. Before this change, Android had `rememberNavBackStack` without a state config (default empty serializers), while JVM used `SavedStateConfiguration { }`. Behavior is now identical.

## Consequences

- Android back stacks now survive process death. Smoke test required: open Inbox → Today → TaskDetail, force-stop via `adb shell am force-stop com.singularity.todo`, reopen — verify TaskDetail is restored.
- `JvmNav3State.kt` and the old per-platform `rememberNav3State` bodies are deleted.

## Links

- `Nav3StateFactory.kt`, `Nav3StateFactory.android.kt`, `Nav3StateFactory.jvm.kt`
- `App.kt` (commonMain + androidMain + jvmMain actuals)
- `AndroidShellNav3.kt`, `DesktopShellNav3.kt` (renamed to `*Root` signature)
- `LocalAppNavigator.kt`
