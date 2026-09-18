---
title: "Nav3 Desktop uses in-memory NavBackStack; SavedStateConfiguration is Android-only"
date: 2026-09-16
tags: [navigation, nav3, jvm, desktop, android]
doesNotSupersede: 2026-09-16-nav3-savedstate-serializers-required
status: accepted
---

## Context

`SavedStateConfiguration` is backed by Android's `SaveableStateRegistry`. On JVM Desktop, `LocalSaveableStateRegistry` is always `null` — the `savedstate-compose-desktop` artifact is a deliberate empty stub created by JetBrains as a/kotlin-wrappers interop workaround (linked to KT-61096). It intentionally provides no-op implementations for all `saveable` APIs so that code compiled for Android can run on Desktop without modification, but `SaveableStateRegistry` never restores or saves anything on Desktop.

The previous session correctly added `serializersModule = SerializersModule { polymorphic(NavKey::class) { subclass(...) } }` to all `SavedStateConfiguration` blocks, fixing the JVM `SerializationException`. However, the configuration is entirely dead code on Desktop — `rememberSaveable` calls resolve to no-ops because the registry is `null`.

The dead code is more than harmless noise: each `SavedStateConfiguration { serializersModule = ... }` block is a copy-paste maintenance burden (10 locations) and the `polymorphic(NavKey::class) { subclass(...) }` boilerplate is verbose and error-prone.

## Idea

1. On JVM Desktop: use an in-memory `NavBackStack` directly via `remember { NavBackStack(start) }`. No `SavedStateConfiguration`, no `serializersModule`, no `polymorphic` registration. The `NavBackStack<T>(vararg elements)` constructor wraps `mutableStateListOf`, implements `MutableList<T>` + `StateObject` — exactly what `NavDisplay` needs, and it survives for the lifetime of the composition.

2. On Android: keep `SavedStateConfiguration` + `navSavedStateConfig(...)` helper for real process-death persistence. The Android path is unchanged from the previous session's fix.

3. Consolidate the `navSavedStateConfig(...)` helper into a single `commonMain` file (`Nav3SavedState.kt`) so Android NavGraphs share one implementation of the serializers module registration.

## Decision

1. **`Nav3SavedState.kt`** — new `commonMain` file provides:
   - `navSavedStateConfig(vararg routeSerializers: KSerializer<out NavKey>)` — returns a `SavedStateConfiguration` with a `SerializersModule` registering all given route serializers at the `NavKey` polymorphic level. Used only by Android.
   - `rememberInMemoryNavBackStack(start: T)` — `@Composable` inline function that calls `remember(start) { NavBackStack(start) }`. Used only by JVM Desktop.

2. **`Nav3StateFactory.jvm.kt`** — replaced `SavedStateConfiguration { serializersModule = ... }` + `rememberNavBackStack(savedStateConfig, key)` with `rememberInMemoryNavBackStack(key)`. Removed all imports of `SavedStateConfiguration`, `SerializersModule`, `polymorphic`, `subclass`.

3. **All 5 `*NavGraph.jvm.kt`** — same transformation: replace the `SavedStateConfiguration` block and `rememberNavBackStack(savedStateConfig, start) as NavBackStack<T>` with `rememberInMemoryNavBackStack(start)`. All 5 `@Suppress("UNCHECKED_CAST")` annotations are now gone — `NavBackStack<T>(start)` is already typed.

4. **All 5 `*NavGraph.android.kt`** — replaced inline `SavedStateConfiguration { serializersModule = ... }` blocks with calls to the shared `navSavedStateConfig(...)` helper. The `@Suppress("UNCHECKED_CAST")` cast is removed because `rememberNavBackStack(savedStateConfig, start)` returns `NavBackStack<TasksRoute>` directly.

5. **`SettingsRoute.kt` and `SearchRoute.kt`** remain `@Serializable` on `commonMain`. Android needs them; JVM simply never exercises the serializer.

## Rationale

- **`rememberNavBackStack(key)` no-config overload** exists only in `navigation3-runtime-android` (the Android AAR). On JVM Desktop (`navigation3-runtime-desktop`), only the `rememberNavBackStack(configuration, vararg elements)` overload exists. The in-memory `remember { NavBackStack(start) }` is the documented JetBrains KMP pattern for local state — the official `DecoratedNavEntries` docs show `val backStack = mutableStateListOf(A)` and navigate by mutating the list directly.
- **`LocalSaveableStateRegistry == null` on Desktop** is not a bug — it is the documented design of `savedstate-compose-desktop`. Any `SavedStateConfiguration` set on Desktop is inert.
- **Code health**: 10 copies of `SavedStateConfiguration { serializersModule = SerializersModule { polymorphic(NavKey::class) { subclass(...) } } }` become 5 calls to `navSavedStateConfig(...)` on Android and 5 calls to `rememberInMemoryNavBackStack(...)` on Desktop. The `subclass(...)` boilerplate disappears from JVM files entirely.
- **Android regression-free**: Android's `SavedStateConfiguration` path is unchanged — it still registers all polymorphic subtypes and still survives process death. The `navSavedStateConfig(...)` helper is a pure refactor (same runtime behavior, better readability).
- **`@Suppress("UNCHECKED_CAST")` removal**: `NavBackStack<T>(start)` already carries the type parameter from `start: T`; no cast needed. The suppression was a hint that the original code used `rememberNavBackStack(savedStateConfig, start)` which returned the non-generic `NavBackStack` base type.

## Consequences

- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit.
- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android.
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`.
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt` — shared helpers
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/Nav3StateFactory.jvm.kt` — JVM in-memory path
- `shared/src/androidMain/kotlin/com/singularity/todo/feature/nav/Nav3StateFactory.android.kt` — Android SavedState path
- All 5 `*NavGraph.jvm.kt` files — simplified (no SavedStateConfiguration)
- All 5 `*NavGraph.android.kt` files — use `navSavedStateConfig(...)` helper
- `docs/decisions/2026-09-16-nav3-savedstate-serializers-required.md` — Android serializer requirement (unchanged)
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/Nav3StateFactory.jvm.kt` — JVM path
- JetBrains/kotlin-wrappers#3057 (`savedstate-compose-desktop` deliberately empty)
- `androidx.navigation3.runtime.NavBackStack` — commonMain constructor used on JVM
