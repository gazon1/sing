---
title: "One sealed NavKey root — Settings and Search crashed the app on open"
date: 2026-09-29
tags: [nav3, serialization, android, crash]
---

## Context

Navigation 3 restores a back stack by deserializing keys polymorphically under
`NavKey`, so `SavedStateConfiguration` has to register every concrete route. The
app registered them with `subclassesOfSealed(routeHierarchy)`, per NavGraph,
passing that graph's own route serializer.

That call only accepts a **sealed** hierarchy. Two graphs violated it:
`Settings` and `Search` were lone `@Serializable data object`s extending `NavKey`
directly, because a single-entry graph has no hierarchy to seal. Opening either
screen threw and killed the process:

```
IllegalArgumentException: subclassesOfSealed only supports automatic adding of
subclasses of sealed types with standard serializers.
  at kotlinx.serialization.modules.PolymorphicModuleBuilder.subclassesOfSealed
  at com.singularity.todo.feature.nav.Nav3SavedStateKt.navSavedStateConfig(Nav3SavedState.kt:48)
  at ...SettingsNavGraph(SettingsNavGraph.android.kt:72)
```

`NavSavedStateConfigTest` covered six route hierarchies and passed, because
`Settings` and `Search` were the two it never named. The suite was green and the
crash shipped. `Maestro/flows/smoke/12-settings-cycle-tabs-smoke.yaml` found it:
the flow failed, and the screen behind it was the system dialog
"Singularity_cllone_kmp keeps stopping".

## Idea

1. **Branch inside `navSavedStateConfig`.** Keep the per-graph registration and
   test the serializer type at runtime — `if (serializer is SealedClassSerializer)`,
   else `subclass(...)`. Smallest diff, fixes both screens.
2. **One sealed root.** Add `AppNavKey : NavKey`, make every route a leaf of it,
   and register the whole app with a single `subclassesOfSealed` call. This is the
   pattern the Navigation 3 docs describe, and it removes the special case rather
   than encoding it.

## Decision

We did (2). `AppNavKey` is the single sealed root; every route — `AppDestination`,
`AgendaStartRoute`, `TasksRoute`, `ProjectsRoute`, `NotesRoute`, `CalendarRoute`,
the four `*StartRoute` interfaces nested in `AppDestination`, and the `Settings`
and `Search` objects — is a leaf of it. `appNavSavedStateConfig` registers
`AppNavKey` once and is shared by all eight NavGraphs.

Kotlin requires a sealed hierarchy to live in one package, so the six route types
that lived under `feature/<name>/presentation/nav/` moved to
`feature/nav/`. `SearchRoute.kt` and `SettingsRoute.kt` were renamed to
`Search.kt` and `Settings.kt`, since detekt's `Filename` rule requires a file to
be named after its single top-level declaration.

## Rationale

(1) is a branch that encodes a mistake: it makes "forgot to seal this route" a
runtime failure instead of an impossible state. Under (2) a route declared against
`NavKey` is simply invisible to the config, and the tests below catch it. The
per-graph configs also duplicated a `remember { }` in eight places to describe one
schema, which is the shape of bug that hides.

Sharing one configuration across graphs is deliberate: a nested graph only holds
keys from its own hierarchy, so registering the whole app costs one sealed walk at
startup and removes any chance of a graph's route hierarchy drifting out of the
config.

## Consequences

- A new route must extend `AppNavKey`, not `NavKey`. Declaring against `NavKey`
  makes the route invisible to `subclassesOfSealed` and crashes its screen on open.
- Route types live in `com.singularity.todo.feature.nav`, not beside the screen
  they open. Per-feature `*NavGraph` and `*EntryProvider` still live per feature;
  only the route keys moved.
- `NavSavedStateConfigTest` now names `Settings` and `Search` explicitly and holds
  a list of every nested graph route, so a new graph without a matching case fails.
- `navSavedStateConfig(routeHierarchy)` is gone — replaced by the
  `appNavSavedStateConfig` val. There is no per-graph configuration to get wrong.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppNavKey.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/nav/NavSavedStateConfigTest.kt`
- `Maestro/flows/smoke/12-settings-cycle-tabs-smoke.yaml`
- Related: `2026-09-28-android-cold-start-nav3-serializer-crash.md` (the first crash in the same function)
