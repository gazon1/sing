---
status: open
status-was: proposed
date: 2026-10-10
deciders: mavis
gh: https://github.com/gazon1/sing/issues/261
---

# SyncScopeProvider interface in wrong module [#261](https://github.com/gazon1/sing/issues/261)

## Context

`syncOnce()` needs to know which profile/account is currently active:

```kotlin
// SyncStateRepository.kt:169–178
interface SyncScopeProvider {
    /** Flow of the scope sync currently operates on (ownerId + profileId). */
    val current: Flow<SyncScope?>
}
```

This interface lives in `core/sync/` (specifically in `SyncStateRepository.kt` alongside its only implementation). Its sole implementing class is `ProfileSyncScopeProvider` in `feature/profile/`. This creates an import dependency: `core` imports from `feature` at the **type level** (`ProfileSyncScopeProvider` is a `SyncScopeProvider`).

```
core/sync/          →  feature/profile/ProfileSyncScopeProvider
     ↑                            ↑
     └─────── SyncScopeProvider ──┘  (type-level import)
```

At runtime this is safe (Koin resolves `SyncScopeProvider` to the concrete `ProfileSyncScopeProvider`). But it means `core` cannot be compiled in isolation from `feature`.

## Idea

Move `SyncScopeProvider` to `core/profile/` — a new package that sits between `core` and `feature`, containing only cross-cutting profile interfaces. `SyncStateRepository` would import it, and `ProfileSyncScopeProvider` would implement it in `feature/profile/`.

Alternatively, keep `SyncScopeProvider` in `core/sync/` and accept the layering violation as intentional — the scope provider is inherently a "who is currently signed in" concept, which belongs to auth/profile more than to sync.

## Decision

**Deferred as acceptable.** The violation is at the type-import level (interface declaration), not at the call level. `core/sync` never instantiates or calls methods on `ProfileSyncScopeProvider` — it only holds a `SyncScopeProvider` reference injected by Koin. The Koin graph is the actual wiring layer, and it already puts the right implementation behind the interface.

**If the module boundary becomes a build problem** (e.g., `core` needs to compile without `feature`), `SyncScopeProvider` can be moved to `core/auth/` (it is essentially an auth concept) or a new `core/profile/` package. This is a mechanical change with no behavioural impact.

## Consequences

- No runtime defect.
- `core` cannot be compiled as an isolated KMP module without `feature` — this may matter for incremental compilation but is not a correctness issue.
- `SyncScopeProvider` is an `expect/actual` candidate if `core` needs to be platform-agnostic and the implementation differs per platform.

## Links

- `SyncStateRepository.kt:169–178` — `SyncScopeProvider` interface
- `feature/profile/` — sole implementation location
- `SyncEngine.kt:219` — engine depends on `SyncScopeProvider`
