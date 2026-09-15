---
title: "Nav3 SavedStateConfiguration must register all NavKey subtypes polymorphically"
date: 2026-09-16
tags: [navigation, nav3, serialization, jvm, android]
supersedes: 2026-09-16-nav3-shared-state-factory-and-local-app-navigator
---

## Context

`rememberNavBackStack(SavedStateConfiguration, vararg elements)` (the only overload available on JVM Desktop) is backed by `NavBackStackSerializer(PolymorphicSerializer(NavKey::class))`. `NavKey` is a plain marker interface — it is not sealed and not itself `@Serializable`, so `kotlinx.serialization` cannot discover subtypes automatically. Every concrete `NavKey` subtype the stack may contain must be registered via `polymorphic(NavKey::class) { subclass(X.serializer()) ... }` in the `SavedStateConfiguration.serializersModule`.

The shared factory introduced in `2026-09-16-nav3-shared-state-factory-and-local-app-navigator` wired both Android and JVM with `SavedStateConfiguration { }` — empty by default. On JVM this fails immediately on first composition because `rememberSaveable` triggers a `SnapshotStateListSerializer.serialize(...) → NavBackStackSerializer.serialize(...)` and the polymorphic dispatcher cannot find a serializer for the very first key in the back stack (`AppDestination.Inbox` in our case).

The Android overload `rememberNavBackStack(vararg elements)` (Android-only, defined in `navigation3-runtime-android`) takes a different code path: a reflection-based `NavKeySerializer` that uses `Class.forName(name).kotlin.serializer()` to resolve subtypes on the fly. Android worked by accident as long as that overload was used, but the moment we passed `SavedStateConfiguration` into the common overload (to opt into real `SavedStateRegistry` persistence), Android shared the same fate as JVM — it just hadn't been exercised in a real rotation / process-death scenario yet.

Nested graphs had the same bug locally:
- `TasksNavGraph.jvm.kt`, `ProjectsNavGraph.jvm.kt`, `NotesNavGraph.jvm.kt` already used a polymorphic `serializersModule` — those happened to work because they were the first places someone hit the failure and fixed it.
- `SettingsNavGraph.jvm.kt`, `SearchNavGraph.jvm.kt` used `SavedStateConfiguration { }` for a single-element stack of an `@Serializable`-less `data object : NavKey`. They crashed even before the top-level factory did.

A second symptom surfaced in the same traceback: `Size(2147483647 x 64) is out of range` inside `TopAppBarMeasurePolicy`. That was a cascade effect — after the `SerializationException` aborted the first composition pass, the `MeasurePassDelegate` re-entered with `Constraints.Infinity` on width. Fixing the serializer fixed the layout.

## Decision

1. **Every `SavedStateConfiguration` that backs a `rememberNavBackStack` must declare a `SerializersModule` registering every concrete `NavKey` subtype reachable in that stack.** No exceptions, no "empty module", no reliance on reflection.

2. **Single-route nested graphs (`Settings`, `Search`) must also register their `@Serializable` data object** in a `serializersModule`. The previous code comment claiming "the nested graph uses an empty SavedStateConfiguration" was wrong — `SavedStateConfiguration { }` is not a no-op, it opts into `PolymorphicSerializer(NavKey::class)` which still requires explicit registration.

3. **`Settings` and `Search` data objects are now `@Serializable`.** Without `@Serializable` they have no generated `.serializer()`, and `subclass(...)` cannot reference them. (The two `AppDestination.Settings` / `AppDestination.Search` siblings were already `@Serializable` — the fix was for the parallel top-level `data object Settings` / `data object Search` in `SettingsRoute.kt` / `SearchRoute.kt`.)

4. **Top-level `Nav3StateFactory` registers all 20 `AppDestination` subtypes** — 12 `data object`s for tabs/menu entries and 8 `data class` sub-routes (`TasksGraph`, `TasksByProject`, `TaskDetail` (deprecated), `TaskDetailCreate` (deprecated), `ProjectEditor`, `ProjectDetail`, `ProjectsGraph`, `NotesGraph`).

5. **Per-nested-graph modules only register that graph's own subtypes.** `TasksNavGraph` registers `TasksRoute.*`, `ProjectsNavGraph` registers `ProjectsRoute.*`, etc. Don't register foreign types — the module is local to that `SavedStateConfiguration`.

## Rationale

- The contract is documented in the upstream API: `rememberNavBackStack(configuration, ...) { ... }` throws `IllegalArgumentException` if `configuration.serializersModule == SavedStateConfiguration.DEFAULT.serializersModule`. Passing `SavedStateConfiguration { }` (which has no explicit `serializersModule` and so falls back to `DEFAULT`) violates the contract — we only escaped the explicit `require` because of how the DSL lambda is compiled.
- Android's reflection overload is Android-only (`navigation3-runtime-android`); the JVM must use the configuration overload. Standardising on the configuration overload on both platforms removes a hidden class of bugs.
- Registering types at the `NavKey` polymorphic level (not at the sealed-interface level) is mandatory because the dispatch type the runtime sees is `PolymorphicSerializer(NavKey::class)`. A sealed interface that is itself `@Serializable` would have worked only if the runtime had picked the sealed serializer; it didn't.
- Keeping modules local to nested graphs avoids accidentally making one graph depend on another's types, and prevents future refactors from accidentally removing registrations that one graph still relies on.

## Consequences

- **Always** provide a `serializersModule` that calls `polymorphic(NavKey::class) { subclass(...) }` for every concrete route type in the stack.
- **Never** write `SavedStateConfiguration { }` for any `rememberNavBackStack` call — the empty body silently falls back to `DEFAULT.serializersModule` and breaks the polymorphism contract.
- **Always** mark every `NavKey` subtype that may appear in a stack as `@Serializable`. Without it, there is no `.serializer()` to pass to `subclass(...)`.
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this.
- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract.

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/Nav3StateFactory.jvm.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/feature/nav/Nav3StateFactory.android.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/{tasks,projects,notes,settings,search}/presentation/nav/*NavGraph.jvm.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/feature/{tasks,projects,notes,settings,search}/presentation/nav/*NavGraph.android.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/presentation/nav/SettingsRoute.kt` (now `@Serializable`)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/presentation/nav/SearchRoute.kt` (now `@Serializable`)
- New skill: `singularity-todo-nav3-savedstate`
- Upstream: `androidx.navigation3.runtime.rememberNavBackStack(configuration, vararg elements)` and `NavBackStackSerializer(PolymorphicSerializer(NavKey::class))`
