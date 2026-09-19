---
status: accepted
---
# ADR: ViewModel scope cancellation on `onCleared()`

## Context

24 ViewModels in the codebase follow the canonical constructor pattern introduced in `2026-09-18-vm-migration-scope-injection`. Each holds a `CoroutineScope` (injected as a constructor parameter; `SupervisorJob() + Dispatchers.Main.immediate` in production, `backgroundScope` in tests).

Several VMs launch long-running fire-and-forget coroutines — for example, `NoteEditor`'s autosave job and any sync-related background work. Currently, when the ViewModel is cleared by the lifecycle owner (e.g., navigation leaving a screen), those coroutines are **not** cancelled. They outlive the screen, creating resource leaks and potential race conditions where a resumed screen sees stale state from a background job launched by a previous instance.

The previous accepted ADR (`2026-09-18-vm-migration-scope-injection`) explicitly noted this trade-off: *"SupervisorJob child failures are silently dropped"* and *"No automatic cancellation on onCleared() — callers must cancel explicitly if needed."*

## Decision

For each ViewModel, add `override fun onCleared() { scope.cancel(); super.onCleared() }`.

### Exemptions

The following jobs **intentionally** outlive the VM lifecycle and MUST NOT be cancelled in `onCleared()`:

| VM | Job | Reason |
|---|---|---|
| `NoteEditorViewModel` | `autosaveJob` | User may navigate away and back; draft must persist across sessions |
| `SyncEngine` (if exists as a singleton service) | `pushJob` | Background sync must continue regardless of active screens |

All other `scope.launch { }` calls within each VM are cancelled by `scope.cancel()`.

### Implementation

Each VM gets this override added after its class declaration closing brace:

```kotlin
// At the end of the class body, after all functions:
override fun onCleared() {
    scope.cancel()
    super.onCleared()
}
```

For Koin-wired VMs (secondary constructor), `scope` is the `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` created in that constructor. Cancellation propagates to all child coroutines.

### Test implications

`runTest { }` disposes `backgroundScope` automatically when the test coroutine completes. Adding `scope.cancel()` in `onCleared()` to VMs tested with `backgroundScope` is safe — the test framework already handles cleanup. No test code changes are required.

## Rationale

1. **Consistency**: Every VM using the injected `scope` now has deterministic cleanup. Previously, only VMs with explicit `cancel()` calls in tests had this guarantee.

2. **No performance cost**: `SupervisorJob` cancellation is O(children). The VMs in this codebase have at most a handful of concurrent jobs.

3. **Exemption is narrow**: Only `NoteEditor`'s autosave is explicitly exempted based on documented user-facing behavior. No other long-lived background jobs exist in the current codebase.

4. **`super.onCleared()` called**: Ensures Android ViewModel's own `onCleared()` chain (including any future Koin cleanup) is not short-circuited.

## Consequences

- All 24 VMs gain deterministic scope cancellation.
- `NoteEditorViewModel` and any future singleton services with intentionally long-lived jobs must opt out explicitly by not routing through the canonical scope or by using a separate non-cancellable scope.
- The exemption list must be updated whenever a new intentionally-long-lived job is added to any VM.

## Links

- `2026-09-18-vm-migration-scope-injection` — canonical VM pattern this builds upon
- `docs/decisions/2026-09-18-mutation-result-handling.md` — `fireAndForget` and async error handling convention
- `singularity-todo-coroutine-scopes` skill — launch/cancel semantics, scope lifecycle
