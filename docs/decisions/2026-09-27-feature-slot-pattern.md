---
title: "FeatureSlot — split a god ViewModel into a coordinator plus focused slots"
date: 2026-09-27
tags: [mvi, viewmodel, architecture, flow]
status: accepted
---

## Context

`TaskDetailViewModel` reached 524 lines owning 19 unrelated concerns: inline title and
description editing with debounced writes, checklist CRUD, subtasks, attachments, reminders
with platform scheduling, dependencies, recurrence, backlinks, AI actions, delete/archive/
restore, and the task entity itself. Thirty intent variants, eighteen injected dependencies,
nine top-level `Mutable*Flow`s, and a `combine` chain nested three levels deep.

The symptoms were not the line count — they were concrete:

- `draftState.seed(...)` was called **inside a `combine` transform**, so the write re-fired on
  every checklist, attachment, and subtask update rather than once per task change.
- `_linkedNotes.value` and `_linkedTasks.value` were read in the final transform, but the
  `combine` did not depend on either flow — so when backlinks loaded, the assembled state was
  never recomputed and the UI kept showing the stale empty list.
- `_latestTask.value = task` was assigned **inside `flatMapLatest`**, the same class of
  side-effect-in-a-pure-operator bug.
- The file hand-rolled its own `_state`, `Channel` and intent dispatcher, tripping the
  `MviViewModelExt` detekt rule that the framework migration had promoted to a build failure.
- Only 10 of the 30 intents had any UI wired to them — the handlers had outrun the screen.

Splitting the VM into sibling Koin ViewModels was rejected. Eight `koinViewModel()` calls
would each re-subscribe `taskRepo.observe(id)`, and the screen would need an aggregation layer
to rebuild the one `TaskDetailUi` the editor slot API already consumes. The repo's own
`2026-09-26-pr24-rescope.md` had already rejected exactly that shape for `SettingsViewModel`
and `ProjectDetailViewModel` on those grounds.

## Idea

1. **Sibling ViewModels at screen level** — rejected: N parallel subscriptions, merge problem
   moves into the Composable, no gain over the coordinator.
2. **Fix the `combine` chains in place** — treats the symptom; the VM stays at 19 concerns and
   the next feature lands on top of it.
3. **A `FeatureSlot` abstraction: plain Kotlin classes inside one coordinator ViewModel.**
   Each slice owns its own state type and its own small dispatcher; the coordinator holds one
   flow per slot and merges them once.

## Decision

Introduce `FeatureSlot<S, I : MviIntent>` in `core/ui/featureSlot/`:

```kotlin
interface FeatureSlot<S, I : MviIntent> {
    val state: StateFlow<S>
    fun onIntent(intent: I)
}
```

A slot is a plain class, not a ViewModel. It is constructed by its coordinator, is not
registered in Koin, has no lifecycle of its own, and receives the coordinator's
`AutoCloseableCoroutineScope`. `state` must be a `StateFlow` so the coordinator always has a
current value to merge without seeding.

Alongside it, `combineStates` provides type-safe `combine` for two through eight flows.
`kotlinx.coroutines` only ships typed overloads up to five; past that the `vararg` form returns
`Flow<Array<Any?>>` and pushes an unchecked cast into every call site. `combineStates` keeps the
transform fully typed and confines the cast to one private helper per arity.

**The `combineStates` transform is non-suspending on purpose.** A `suspend` transform would
permit a suspending repository write inside a projection — precisely the bug class that
produced the three defects above. Making it non-suspending makes that a compile error.

Each transform overload also takes `Flow` inputs rather than `StateFlow`, so a coordinator can
chain a slot's derived flow into the next merge without a `StateFlow` conversion.

## Rationale

The slot boundary is enforced by types rather than by convention. `onIntent` is parameterised
by the slot's own intent type, so dispatching `TaskReminderIntent` to `TaskChecklistSlot` is a
compile error, not a silently-wrong branch — the failure mode that section comments and
`when` blocks in one 500-line `onIntent` cannot catch.

Each slot test constructs one slot with only the dependencies that slot uses, instead of
standing up all eighteen for a checklist assertion. That is the concrete testability win, and
it is what the migration map in the follow-up refactor is measured against.

The coordinator keeps the 30-intent public API unchanged, so `TaskDetailViewScreen` needs no
edit and the shared `TaskEditorContent` slot API keeps consuming one `TaskDetailUi`.

Not taken, deliberately: an `AbstractFeatureSlot` base class (each slot has a different
constructor; the inheritance buys nothing), a `SlotRegistry` DSL (the coordinator's `when` over
intent types is the same thing without a new vocabulary), and an `events` type parameter on
`FeatureSlot` (no slot emits its own one-shot events; the coordinator owns the event bus).

`SettingsContributor` was **not** made a `FeatureSlot` in the same pass. The two differ in
three ways at once — `Flow` vs `StateFlow`, `observe()` vs `state`, `suspend process` vs
`onIntent` — and `SettingsIntent` is not yet an `MviIntent`. Formalising it means editing
`SettingsViewModel.bind()` and `dispatch()` at the same time, which belongs with the Settings
slotification, not with the framework introduction.

## Consequences

- A detail-screen ViewModel that owns more than one repository observation should be a
  coordinator plus slots. One flow per slot, merged by a single `combineStates` call.
- A `combine` transform must be pure. `NoCombineSideEffect` fails the build on `.value =`,
  `seed()`, `Channel.send`, or `launchIn` inside a transform.
- `combineStates`' transform is non-suspending; perform writes in a `collect { }` block.
- `FeatureSlot.state` is a `StateFlow`, not a `Flow` — the coordinator must be able to read a
  current value synchronously.
- Slot tests construct one slot and its own fakes. Share one test-dispatcher-backed
  `FakeProfileAwareCurrentUser` across them so `advanceUntilIdle()` drives the chain.
- A slot that is read-only and has no intent surface (the backlinks collector) is a plain
  class, not a `FeatureSlot`. Do not name it `…Slot` to avoid implying otherwise.
- `SettingsContributor` remains a separate abstraction until `SettingsViewModel` is migrated;
  do not force the two into one interface before that migration happens.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/featureSlot/FeatureSlot.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/featureSlot/CombineStates.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoCombineSideEffectRule.kt`
- `2026-09-27-framework-drift-resolution.md`
- `2026-09-26-pr24-rescope.md` — the earlier rejected sub-VM split
- `2026-09-09-notes-vm-split.md` — the accepted precedent for splitting a god VM
