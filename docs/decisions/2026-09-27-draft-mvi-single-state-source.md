---
title: DraftMviViewModel — one state source, no open-member calls from a constructor
status: accepted
date: 2026-09-27
authors: ZCode Agent
deciders: Singularity Developer
tags: [mvi, viewmodel, draft, coroutines, initialization-order]
epic: tech-debt/repair-broken-build
---

# DraftMviViewModel — one state source, no open-member calls from a constructor

## Context

Commit `d8a7e164` rewrote `core/ui/DraftMviViewModel.kt` and `DraftState.kt`
but never compiled. Two defects in the rewritten base class made all 12 tests
in `DraftMviViewModelTest` fail with the same
`NullPointerException: ... "this.validateImpl" is null`.

### Defect 1 — an open member called from the constructor

The initial state was built as:

```kotlin
private val _uiState = MutableStateFlow(
    DraftUiState(
        draft = initialDraft,
        isSaveEnabled = validate(initialDraft) == null,   // ← open member
    ),
)
```

`validate()` is `protected abstract`. Kotlin initialises a class in this order:
constructor arguments → **superclass constructor** → subclass property
initialisers and `init` blocks. Calling `validate()` from the base class's own
property initialiser therefore runs *before* any subclass field is assigned, so
any implementation that reads its own state sees `null`. `TestDraftVm` stores
its lambdas in properties, so it hit this immediately.

This is a latent trap for *every* subclass, not just the test double — a real
editor VM whose `validate()` consults a private field would break the same way,
and only in tests that construct it.

### Defect 2 — a shadow state that was never published

`DraftMviViewModel` kept a private `_uiState: MutableStateFlow<DraftUiState<D>>`
*alongside* the `currentState` it inherits from `MviViewModel`. Only
`pushUiState()` bridged the two, via `updateState { _uiState.value }`.
But `save()` and `dismissError()` mutated `_uiState` **directly** and never
called `updateState`. Since `state` is derived from `currentState`, an error set
by a failed `save()` was written into a copy that the UI never observes — the
"save failed" banner silently never appeared.

## Idea

Delete the shadow. Make the inherited `currentState` the only place editor
state lives, and move the first `validate()` call out of the constructor.

## Decision

1. **Remove `_uiState` entirely.** Every mutation — `pushUiState`, `save`,
   `open`, `discard`, `dismissError` — goes through `updateState { … }`, so
   whatever the UI reads is always the same value that was written.
2. **Compute the initial state after construction.** The initial
   `DraftUiState` is the plain `initialDraft`; the first `pushUiState()` runs
   from the `init` coroutine, which is dispatched after the constructor
   returns and can therefore safely call `validate()`.
3. **Suppress validation errors on that first pass only**
   (`pushUiState(includeValidationError = false)`). An editor opened on an
   invalid draft must not greet the user with an error banner they did not
   cause; the first *edit* is what makes a validation error true.
4. **Validation errors are recomputed, not sticky.** `error` is derived from
   `validate(draft)` on each push, so a validation error disappears the moment
   the draft becomes valid. The previous KDoc claimed persistence errors
   "survive across edits"; that was never implementable with a single
   `error: String?` field, and the tests assert auto-clear. The KDoc now
   describes what the code does.

## Rationale

`MviViewModel` already models "one state, updated through `updateState`". The
shadow flow was an artefact of trying to batch draft + saving + error flags
into one atomic update — but `updateState`'s transform *is* already atomic, so
the batching bought nothing and cost correctness. Fixing the constructor call
removes a class of bug that would otherwise recur in every future
`DraftMviViewModel` subclass.

## Consequences

- The first `pushUiState()` is asynchronous, so `isSaveEnabled` / `isDirty` are
  briefly their initial defaults before the first frame. In practice this lands
  within the same dispatch as the restore step; tests that assert on them
  already `delay(...)` first.
- `isSaveEnabled` starts `false` even for a valid draft, and is corrected on the
  first push. A caller that reads `state` synchronously in the constructor's
  own scope could observe the default; none does.
- Any subclass relying on `error` persisting across unrelated edits will see it
  clear instead. `NoteEditor`, the only real subclass, surfaces autosave
  failures through `onAutosaveError` → a `UiEvent`, not through `error`, so it
  is unaffected.

## Links

- `docs/decisions/2026-09-27-remove-platform-clock-object.md` — the other
  contract repair in the same sweep
- `docs/decisions/DIGEST.md`
