---
title: "MVI error paths: a thrown exception is an error event, never a crashed coroutine"
date: 2026-09-30
status: accepted
tags: [mvi, testing]
---

## Context

`MviViewModel.catchTo` runs a block on `vmScope` and routes a returned
`Result.failure` into an error event. Its type, though, is
`suspend () -> Result<*>` — a block is free to *throw* instead, and before this
change a throw escaped `vmScope.launch` uncaught:

- **On Android**, an uncaught exception in a `viewModelScope` child kills the
  process. Any `require(...)`, `getOrThrow()`, or throwing repository call
  inside an `emitError` block — code whose entire purpose is the error path —
  was a shipped crash.
- **In tests**, the throw cancels whatever Job the test host shares with the
  VM scope, silently freezing every collector on it. That is how MR-5's
  rename-of-a-missing-tag bug first surfaced: not as a failing assertion, but
  as a ViewModel permanently stuck in `Loading`.

Found during MR-5: `TagsViewModel.rename` threw `AppError.NotFound` for a
missing tag; the test failed with the raw exception on the stack instead of an
assertion message, which is the only reason it was caught at all.

## Idea

1. **Document the contract** ("block must not throw") and audit every
   `catchTo`/`emitError` call site.
2. **Harden the helper** — wrap the block in `runCatching` so a throw follows
   the same `onError` path as a returned failure.
3. **Type-level fix** — change the block type to something that cannot throw
   (Kotlin has no checked exceptions; nothing enforces this).

## Decision

Approach (2). `catchTo` now runs `runCatching { block() }` and feeds both
failure shapes through `onError`. `AppError` subtypes keep their domain
messages (`NotFound: Tag … no longer exists`), non-`AppError` throwables fall
back to the label per the existing `toMessage` rules. The KDoc states the
history so the next reader does not "simplify" it back.

## Rationale

(1) leaves the crash one refactor away — a future `require()` reappears and
nothing fails until a user hits it. (3) is not expressible in Kotlin without
wrapping every call site in `Either`, which the codebase deliberately does not
do for repository returns (repositories return `Result<T>`; the use cases
`getOrThrow()` inside their own `runCatchingResult`). The central helper is the
one place where the guarantee is enforceable once.

## Consequences

- **Never** rely on an exception escaping a `catchTo`/`emitError` block to
  signal a bug — it is now an error event like any other failure. Tests that
  want to assert the failure assert the event/state, which is what they should
  have done anyway.
- ViewModel error-path blocks may now legitimately use `require(...)` and
  `getOrThrow()`. The guarantee covers the block only — a throw from a
  `updateState` reducer or an `init` collector outside `catchTo` still crashes
  (those are programming errors, not expected failures). `CancellationException`
  is caught too, harmless today; rethrow it explicitly if it ever surfaces as a
  user-visible message.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/MviViewModel.kt` — `catchTo`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/mvi/MviViewModelTest.kt` — the thrown-exception tests
- `2026-09-30-testscope-background-work-semantics.md` — why the throw froze the ViewModel in `Loading` instead of failing loudly
- `2026-09-30-tag-rename-and-validation.md` — the rename feature that found it
