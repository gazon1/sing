---
status: open
status-was: proposed
date: 2026-10-10
deciders: mavis
gh: https://github.com/gazon1/sing/issues/260
---

# SyncEngine singleton never closed [#260](https://github.com/gazon1/sing/issues/260)

## Context

`SyncEngine` implements `AutoCloseable by scope`:

```kotlin
internal class SyncEngine(
    ...
    private val scope: AutoCloseableCoroutineScope,
) : AutoCloseable by scope
```

It launches a session-watching coroutine in `init {}`:

```kotlin
init {
    scope.launch {
        authRepository.currentSession.collect { session ->
            when (session) {
                is Session.SignedIn -> scheduler.enqueuePush()
                is Session.Anonymous, is Session.SignedOut, is Session.Loading -> scheduler.cancelPush()
            }
        }
    }
}
```

The `scope` (`AutoCloseableCoroutineScope`) is closed when the `Application` is torn down. `SyncEngine` itself is never explicitly closed — it relies on process death. There is no `close()` call in the codebase for `SyncEngine`.

## Decision

**Deferred as acceptable for current requirements.**

`SyncEngine` is a `single {}` that lives for the process lifetime. The session-watching coroutine is cancelled when the `Application` scope is cancelled (process death or explicit `Application.close()`). There is no requirement for account-reset or scope-switching without process death.

If `SyncScopeProvider.current` can emit a new scope (signing in as a different user) without process death, the old session-watching coroutine would continue running until the engine is recreated. This would require either:
1. Making `SyncEngine` closeable/recreatable, or
2. Adding `close()` to `SyncEngine` and calling it when the sync scope changes.

**No action required until** a future requirement introduces user switching without process death.

## Consequences

- No behavioural defect in the current product.
- The `AutoCloseableCoroutineScope` contract (injected, not constructed) means the scope lifetime is owned by the component that injects it — typically the `Application`. Process death is the de-facto close.
- `SyncRunner` holds a `var engine: SyncEngine?` and recreates it on `syncScopeId` changes — this is the scope-switching mechanism, and it does not call `close()` on the old engine.

## Links

- `SyncEngine.kt:338–352` — `init {}` session watcher
- `SyncRunner.kt` — `var engine: SyncEngine?` recreation on scope change
- `CoreDiModule.kt:331–360` — `SyncEngine` DI registration
