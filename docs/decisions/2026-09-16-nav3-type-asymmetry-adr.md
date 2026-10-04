---
title: "Nav3 type asymmetry: rememberInMemoryNavBackStack returns NavBackStack<T>, Android rememberNavBackStack returns NavBackStack<NavKey>"
date: 2026-09-16
tags: [navigation, nav3, android, jvm, tech-debt]
status: accepted
---

## Context

After refactoring to split Android vs JVM back stack strategies, a type asymmetry was identified:

- **`rememberInMemoryNavBackStack(start: T)`** (JVM) is `@Composable reified`. It returns `NavBackStack<T>` — fully typed at compile time.
- **`rememberNavBackStack(config, start)`** (Android) is `@Composable` but **not** `reified`. It returns `NavBackStack<NavKey>` — the type parameter is erased.

This means:
- On JVM: `stack.last()` returns `TasksRoute` (typed) — no cast needed.
- On Android: `stack.last()` returns `NavKey` — requires an explicit `as TasksRoute` cast.

All 5 Android `*NavGraph.android.kt` files currently have:

```kotlin
@Suppress("UNCHECKED_CAST")
val backStack: NavBackStack<TasksRoute> = rememberNavBackStack(savedStateConfig, start)
    as NavBackStack<TasksRoute>
```

The `as NavBackStack<TasksRoute>` cast is necessary but unsafe — `NavBackStack<NavKey>` and `NavBackStack<TasksRoute>` are unrelated types at runtime due to type erasure.

## Idea

Resolve the asymmetry so that both platforms return a typed `NavBackStack<T>` without casts.

**Option A — Android typed wrapper (preferred):**
Create a `@Composable reified` inline wrapper in Android actuals that calls `rememberNavBackStack(config, start)` internally and casts the result:

```kotlin
// In Nav3SavedState.kt or a new Android-specific file
@Composable
inline fun <reified T : NavKey> rememberNavBackStackTyped(
    config: SavedStateConfiguration,
    start: T,
): NavBackStack<T> = rememberNavBackStack(config, start) as NavBackStack<T>
```

The `reified` inline gives us `T` at runtime, making the cast safe. Called from Android NavGraphs as `rememberNavBackStackTyped(savedStateConfig, start)` — no explicit cast needed.

**Option B — Accept the asymmetry:**
Document the asymmetry in KDoc and accept it as a known technical debt. No changes to existing code.

## Decision

**Option A — Android typed wrapper (accepted).**

The `@Composable reified inline` wrapper in `Nav3SavedState.kt` (Android source set) resolves the asymmetry:

```kotlin
@Composable
inline fun <reified T : NavKey> rememberNavBackStackTyped(
    config: SavedStateConfiguration,
    start: T,
): NavBackStack<T> = rememberNavBackStack(config, start) as NavBackStack<T>
```

The `reified` inline gives compile-time type `T`, making the cast safe. All 5 Android NavGraph files replace the `as NavBackStack<T>` suppression with `rememberNavBackStackTyped`.

## Consequences

- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files.
- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression.
- The inline wrapper is `internal` to the Android source set — no API surface change.
- JVM path is unchanged.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt` — `rememberInMemoryNavBackStack` (reified, typed)
- `shared/src/androidMain/.../feature/*/presentation/nav/*NavGraph.android.kt` — Android actuals with `as NavBackStack<T>` casts
- `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md`
- `docs/decisions/2026-09-16-nav3-savedstate-serializers-required.md`
