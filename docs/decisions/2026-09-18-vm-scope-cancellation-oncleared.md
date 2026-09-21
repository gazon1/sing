---
status: superseded
date: 2026-09-18
superseded-by: 2026-09-21-auto-closeable-coroutine-scope
---

# ADR: ViewModel scope cancellation on `onCleared()` (SUPERSEDED)

## Status

**Superseded by [2026-09-21-auto-closeable-coroutine-scope](./2026-09-21-auto-closeable-coroutine-scope.md)** as of 2026-09-21.

The `override fun onCleared() { scope.cancel() }` pattern has been replaced by
`init { addCloseable(scope) }` using `AutoCloseableCoroutineScope`, which handles
cancellation automatically via the lifecycle 2.8+ `ViewModel.addCloseable()` mechanism.

## Original Decision (superseded)

The original ADR approved adding `override fun onCleared() { scope.cancel(); super.onCleared() }`
to 24 ViewModels. This has been fully implemented and is now superseded.

## Why superseded

`AutoCloseableCoroutineScope` (lifecycle 2.8+) + `init { addCloseable(scope) }` provides
the same cancellation guarantee with less boilerplate and no risk of forgetting `onCleared()`.

## Links

- [2026-09-21-auto-closeable-coroutine-scope](./2026-09-21-auto-closeable-coroutine-scope.md) — replacement
- `singularity-todo-vm-lifecycle-addcloseable` skill — migration procedure
