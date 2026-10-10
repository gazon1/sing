---
status: open
status-was: proposed
date: 2026-10-10
deciders: mavis
gh: https://github.com/gazon1/sing/issues/258
---

# SyncBootstrapper and SeedPlanner core→feature dependency [#258](https://github.com/gazon1/sing/issues/258)

## Context

Two classes in `core/sync/` take **feature repository** parameters:

```kotlin
// core/sync/SyncBootstrapper.kt — registers pull handlers
internal class SyncBootstrapper(
    private val engine: SyncEngine,
    private val writer: SyncDocumentWriter,   // knows all supported DocTypes
    // writer.supportedTypes is the single source of truth for which types sync
)

// core/sync/SeedPlanner.kt — one-time upload before sign-in
internal class SeedPlanner(
    stateRepository: SyncStateRepository,
    enqueue: (SyncableEntity) -> Result<Unit>,  // reaches into engine
    taskRepo: TaskRepository,    // ← feature
    noteRepo: NotesRepository,   // ← feature
    ...
)
```

This violates the stated layering rule: `core` must not depend on `feature`. It is **intentional** — the bootstrapper is the one place that knows every entity type, and `SeedPlanner` needs to enumerate pre-existing data before the sync cycle runs. But the Koin compiler cannot verify the registration-order dependency: if `SeedPlanner` is resolved before a feature repository is registered, Koin throws at runtime.

## Idea

Introduce a `SyncEntityRegistry` port in `core/sync/`:

```kotlin
/**
 * Port for registering entity-type handlers during bootstrap.
 * Implementations live in feature modules and are injected by Koin
 * after all feature repositories are registered.
 */
interface SyncEntityRegistry {
    val supportedTypes: Set<DocType>
    fun registerHandlers(engine: SyncEngine)
}
```

`SyncBootstrapper` would then take a `SyncEntityRegistry` instead of a `SyncDocumentWriter`, and the concrete implementation (backed by `SyncDocumentWriter`) would be bound in the feature module's DI. This inverts the dependency: `core/sync` defines the port, `feature` provides the implementation.

`SeedPlanner` would similarly take an `EntitySource` port rather than concrete repositories.

## Decision

**Deferred.** The current design works correctly in production (verified by the #177 fix), and the ordering constraint is documented. Introducing a port adds indirection without changing behaviour. The Koin DI graph is verified at compile time by the `koin-compiler-plugin`; if a dependency is missing, compilation fails.

**When to revisit:** If more entity types are added and the registration-order bugs recur, or if `SeedPlanner` needs to enumerate entities across a boundary that doesn't fit the current repository pattern.

## Consequences

- Deferred; no immediate action required.
- The current `SyncDocumentWriter.supportedTypes` pattern is self-registering and self-verifying (the `unwired-surface` gate would catch a type added to the writer but not registered in the bootstrapper).
- Koin compiler plugin validates the graph at compile time — missing dependencies fail the build, not at runtime.

## Links

- `SyncBootstrapper.kt:16–24` — constructor and `init`
- `SyncDocumentWriter.kt` — `supportedTypes` property
- `SeedPlanner.kt` — full constructor
- `CoreDiModule.kt:358–360` — bootstrapper instantiation after engine is cached
