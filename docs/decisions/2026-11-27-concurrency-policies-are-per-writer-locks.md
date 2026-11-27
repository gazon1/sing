---
title: "Concurrency policies are per-writer locks, not a global intent queue"
date: 2026-11-27
status: open
tags: [mvi, concurrency, tasks]
---

# Concurrency policies are per-writer locks, not a global intent queue

Settles #3266.

## Context

`MviViewModel.onIntent()` dispatches; it does not queue. That is the right default —
independent intents stay concurrent — but it means every feature that starts asynchronous
work owns its own ordering, and three different guarantees get conflated:

1. `MutableStateFlow.update` makes a state read-modify-write atomic.
2. That says nothing about the order of two *repository* writes.
3. Cancellation does not stop a write that has already passed its last suspension point.

`DraftMviViewModel` already answers this for the editor: `writeLock` serialises the
debounced autosave against the explicit save, and `forgottenDraft` guards the one
completion that can outlive the clear. The audit asked whether any other production
boundary needs the same, and found three.

## Idea

Audit the production `MviViewModel` subclasses and the slot/coordinator dispatch paths for
overlapping writes to the same entity, fix only the pairs with a demonstrated lost update,
and use the plain coroutine primitive each pair needs.

## Decision

Three policies, one per resource that had evidence of a lost update. Nothing global.

**`UpdateTaskUseCase` serialises its read-modify-write with a `Mutex`.** The use case
reads the row, applies the caller's transform, and writes the whole row back. Its kdoc
claimed this was "safe against concurrent modifications"; it was safe against a *stale
snapshot*, not against a second writer. The task detail runs its slots on one
`Dispatchers.Default` scope, and the debounced title write (`TaskDraftSlot`), the
description flush, and an entity edit (`TaskEntitySlot`) are three independent coroutines
calling in here for the same task. Two of them overlap, both read the row as it was, and
the second write silently reverts the first field — while both report success.

**`TaskEntitySlot` serialises the two tag paths that read and write the tag set.**
`RemoveTag` and `RestoreTag` ask the repository what the task carries *now*, change one
member, and write the whole list. A remove and its undo, both launched on the slot's
scope, read the same pre-change set and one edit is lost. `SetTags` stays outside on
purpose: it states the user's complete selection, so a concurrent read can lose nothing it
owns.

**`ProposalDispatch` serialises the additive tag plans for the same reason.** `AddTags`
and `RemoveTags` are deltas computed from a fresh read; `SetTaskTags` is a replacement and
stays outside. A confirm racing the bulk confirm is two additive plans against one set.

## What was audited and left alone

| Path | Why it needs no policy |
|---|---|
| `SearchViewModel` | `flatMapLatest` + the request compared against `currentRequest()` — an answer for a superseded question is dropped |
| `NotesListViewModel`, `CalendarViewModel`, `StatisticsViewModel`, `ProjectDetailViewModel`, `ProjectsViewModel` | `flatMapLatest` over the observable, so the newest input cancels the older collector |
| `ChatViewModel` | `claimTurn()` is a CAS on `isLoading`; `turnJob.cancel()` is the stop, and a cancelled turn keeps its partial answer and stays cancelled (`CancellationException` is rethrown) |
| `AttachmentAnnotationViewModel` | intents mutate state synchronously; the only async write is the initial load |
| `AgendaViewModel` | per-row writes are narrow DAO statements (`togglePinned`, `complete`), which do not overwrite whole rows |
| `BackupViewModel` | `isWorking` gates the buttons; the operations are mutually exclusive by screen |
| `SavedAgendaViewModel` | one coroutine owns the whole save, and the `isSaving` re-entry guard is set before the launch |
| `DraftMviViewModel` | already has both policies — untouched here |

`MutableStateFlow.update` was nowhere treated as evidence that a *write* was ordered.

## Consequences

- `UpdateTaskUseCase` is bound as a `factory`, so the lock is per-ViewModel-instance,
  which is the sharing the defect needs. Two screens editing the same task still race;
  that is the cross-device problem, not a local ordering one.
- Series writes go through `UpdateTaskSeriesUseCase`, a separate instance with its own
  lock. A series edit can still race a single-occurrence edit of the same row. Recorded
  as a `shortcut:` in `UpdateTask.kt`; the upgrade is the optimistic version column that
  `2026-09-27-write-layer-soundness.md` already defers.
- No synchronization was added for uniformity: `SetTags`, `SetTaskTags`, and the
  `flatMapLatest` readers stay lock-free.
- Regression tests hold one write open with a `CompletableDeferred` and assert the second
  waits, so they fail if the lock is removed. No sleeps.

## Links

- #3225 — the epic this belongs to
- #3235 — the MVI direction this follows
- `2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md` — the Draft policies this mirrors
- `2026-09-27-write-layer-soundness.md` — the deferred version-column answer
