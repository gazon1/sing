---
title: "AutoCloseableCoroutineScope — ViewModel lifecycle scope pattern"
status: accepted
date: 2026-09-21
---

# AutoCloseableCoroutineScope — ViewModel lifecycle scope pattern

## Context

23 ViewModels in the codebase follow the "Tier-1" pattern: their `onCleared()` override does nothing but `scope.cancel()`. This is pure boilerplate — the cancellation logic is identical across all 23, yet each VM requires:

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: CoroutineScope,
) : ViewModel() {
    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
```

Lifecycle 2.8.0 introduced new `ViewModel` APIs:

- `addCloseable(closeable: AutoCloseable)` — registers resources to be closed before `onCleared`
- The `AutoCloseable` parameter is **`kotlin.AutoCloseable`** (the commonMain expect interface from Kotlin 2.0), **not** `java.io.Closeable`
- Resources added via `addCloseable` are auto-closed in a specific order during `clear()`

## Decision

Introduce `AutoCloseableCoroutineScope` in `core/coroutines/` that wires a `CoroutineScope` together with the standard `kotlin.AutoCloseable` lifecycle so the scope cancels itself when the ViewModel is cleared:

```kotlin
class AutoCloseableCoroutineScope(
    override val coroutineContext: CoroutineContext,
) : AutoCloseable, CoroutineScope {
    override fun close() { cancel() }
    companion object {
        operator fun invoke(): AutoCloseableCoroutineScope =
            AutoCloseableCoroutineScope(createBackgroundScope().coroutineContext)
    }
}
```

Tier-1 ViewModels migrate to:

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init {
        addCloseable(scope)
    }
    // no onCleared() override needed
}
```

Test helper `testScope(scope: CoroutineScope): AutoCloseableCoroutineScope` wraps a `TestScope` (which doesn't implement `kotlin.AutoCloseable`) for use in VM test factories.

Tier-2 ViewModels (those with extra cleanup beyond scope cancellation) retain their manual `onCleared()` override.

## Rationale

- `ViewModel.addCloseable(key, AutoCloseable)` is available in `androidx.lifecycle:lifecycle-viewmodel:2.8+`. The project uses 2.9.4.
- Auto-cancellation on `ViewModel.clear()` (which calls `close()` on the registered scope) means no more forgotten `onCleared()` overrides.
- `testScope()` helper solves the `TestScope !is AutoCloseable` problem uniformly across all 23 VMs.
- The pattern is idiomatic and matches the official documentation's guidance on resource cleanup.

## Implementation notes

### Why `init { addCloseable(scope) }` instead of `ViewModel(scope)`

`AutoCloseableCoroutineScope` implements both `CoroutineScope` and `kotlin.AutoCloseable`. The lifecycle 2.8+ `ViewModel` exposes two constructors:

```kotlin
constructor(viewModelScope: CoroutineScope)              // replaces default scope
constructor(vararg closeables: AutoCloseable)            // closes on clear
```

When a class implements both interfaces, `ViewModel(scope)` is ambiguous — Kotlin's overload resolution cannot choose between the two. Passing the scope as a constructor parameter would either require a typealias hack (drop one interface) or a runtime ClassCastException.

Using `init { addCloseable(scope) }` sidesteps the ambiguity entirely. The downside: the VM's `scope` field doesn't replace the default `viewModelScope` — it lives alongside it. This is fine for our use case because we never read `viewModelScope` from Tier-1 VMs (they use the injected `scope` field for all work).

### Why `kotlin.AutoCloseable` and not `java.io.Closeable`

They are different types in commonMain KMP. The lifecycle ViewModel API uses `kotlin.AutoCloseable` (Kotlin 2.0 commonMain expect interface). Using `java.io.Closeable` would fail to compile in commonMain on iOS/Native targets, and would not match the `ViewModel.addCloseable` signature even on JVM.

### Why `kotlin.coroutines.CoroutineContext` instead of `kotlinx.coroutines.CoroutineContext`

`kotlinx.coroutines.CoroutineContext` is a **type alias** for `kotlin.coroutines.CoroutineContext`. In KMP commonMain metadata compilation, the type alias resolves correctly. However, some Gradle tasks fail to resolve the type alias in FQN or import form when the kotlinx.coroutines metadata is not fully available at the time of resolution. Using `kotlin.coroutines.CoroutineContext` directly works universally and is the underlying type.

### Why no `expect`/`actual` for the default context

The default `invoke()` companion factory delegates to `createBackgroundScope()`, which already has platform-specific implementations (Android: `Main.immediate + SupervisorJob`, JVM: `Default + SupervisorJob`). No additional expect/actual is needed.

## Consequences

- 23 Tier-1 VMs lose their `onCleared()` override — the scope is now auto-cancelled via `addCloseable(scope)`.
- Test factories for those VMs use `testScope(backgroundScope)` (or `testScope(this)` in `runTest`).
- Tier-2 VMs are unaffected.
- The default `viewModelScope` is still created by the ViewModel but is unused in Tier-1 VMs (negligible memory cost: one empty `SupervisorJob`).

## Supersedes

- [2026-09-18-vm-scope-cancellation-oncleared](./2026-09-18-vm-scope-cancellation-oncleared.md) — replaced manual `override fun onCleared() { scope.cancel() }` with this pattern

## Related

- [2026-09-06-koin-vm-viewmodelof-koinviewmodel](./2026-09-06-koin-vm-viewmodelof-koinviewmodel.md) — canonical VM constructor pattern
- `docs/decisions/2026-09-21-user-scoped-repository.md` — lessons learned from Phase 5.5
- [Lifecycle 2.8 release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.8.0) — `addCloseable` and `AutoCloseable` migration
