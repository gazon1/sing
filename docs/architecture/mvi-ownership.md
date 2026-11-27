# MVI ownership and call-site inventory

> Reference. The production inventory behind issue #3265: which MVI abstractions exist,
> who owns the coroutine scope and the events, and which couplings block a future package
> extraction. Counts are of the tree at the time of writing; the ones that anchor a rule
> (`0`, `1`) are the ones worth re-checking.

## Retained abstractions and what each one owns

| Abstraction | File | Owns | Production call sites |
|---|---|---|---|
| `MviViewModel<S, I, E>` | `core/ui/MviViewModel.kt` | the state flow, the event channel, the scope registration, the error path | 33 subclasses (36 including `DraftMviViewModel` and the base itself) |
| `EventBus<E>` | `core/ui/EventBus.kt` | one-consumer delivery, backpressure, graceful close, drop counter | **1** — the private bus inside `MviViewModel`. Nothing else constructs one. |
| `MviIntent` / `MviEvent` | `core/ui/Markers.kt` | typing only; no behaviour | every feature's intent/event hierarchy |
| `AutoCloseableCoroutineScope` | `core/coroutines/AutoCloseableCoroutineScope.kt` | a scope whose `close()` cancels its job | every VM's `scope` parameter; also `CurrentUser`, `SyncEngine`, `SyncCoordinator` |
| `DraftMviViewModel` / `DraftState` | `core/ui/DraftMviViewModel.kt`, `DraftState.kt` | the draft value, its checkpoint, debounced autosave, explicit save | `TaskCreateViewModel`, `NoteEditor`, plus the test fixture |
| `FeatureSlot` | `core/ui/featureSlot/FeatureSlot.kt` | one concern's state + intents, no lifecycle of its own | 9 slots under `feature/tasks/…/slot/` and `feature/timetracking/…/slot/` |
| read-only collectors | `TaskBacklinksCollector`, `TaskLogbookCollector`, `TaskProposalsCollector` | a `Flow` projection with no intent surface | `TaskDetailCoordinator` |

Nothing here is a second implementation of another. Each row answers a different question:
`MviViewModel` is the loop, `EventBus` is the transport, `DraftMviViewModel` is the
persistence protocol, `FeatureSlot` is the decomposition seam. No abstraction was removed
for being small — the audit found no duplicate to remove.

## Who owns the coroutine scope

**One owner, always the instance that created the scope.**

- A ViewModel's scope belongs to the ViewModel. `MviViewModel`'s initializer calls
  `addCloseable(scope)`, so `clear()` closes it and its cancellation *is* the teardown.
  A plain `androidx.lifecycle.ViewModel` registers the same way in its own `init`.
- Long-lived infra (`CurrentUser`, `SyncEngine`, `SyncCoordinator`, the calendar-sync
  orchestrator) owns per-instance scopes built with `createBackgroundScope(...)` and closed
  by the container that created them.
- A `FeatureSlot` owns nothing. It receives the coordinator's scope.

Same contract on Android and JVM Desktop: `AutoCloseableCoroutineScope` is `commonMain`,
`close()` cancels the job, and `addCloseable` is the androidx lifecycle mechanism on both
targets. A scope built without a `Job` closes as a no-op rather than throwing inside
`onCleared()`.

**Where the scope is readable.** The recommended form takes `scope` as a plain constructor
parameter: it is visible in `init` blocks and property initializers and nowhere else, and a
member function uses the protected `vmScope`, which is the base class's alias for the same
object. `scope.launch { }` inside `onIntent` does not compile there (`Unresolved reference
'scope'`).

That is the form the skills teach. It is **not yet universal**: 16 of the 35 MVI subclasses
still declare `private val scope` (`AgendaViewModel`, `ArchiveViewModel`, `TagsViewModel`,
`SearchViewModel`, `StatisticsViewModel`, …). Those compile and behave correctly — a property
is readable everywhere — so this is a consistency debt, not a defect, and aligning them is a
separate change. Keep new subclasses on the parameter form; do not add a property that
duplicates `vmScope` without reason.

Note what the `VmCloseable` rule does and does not police: it reports **duplicate
registration** (`addCloseable(scope)` in a subclass whose base already registers it). It does
not require the parameter form, and a `private val scope` property is not a violation of it.

**Duplicate registration was the one real defect, and it is fixed.** Six subclasses
(`AiUsageViewModel`, `ArchiveViewModel`, `BackupViewModel`, `StatisticsViewModel`,
`TagGroupsViewModel`, `TaskDetailCoordinator`) called `addCloseable(scope)` in their own
`init` on top of the base class's call — the same object registered twice, i.e. two stated
owners for one resource. The calls are gone; the `VmCloseable` detekt rule now reports that
shape, so it cannot come back.

`AcpExternalAgentViewModel` keeps its `addCloseable { conversation.terminate() }`: that is a
*different* closeable (a subprocess), not the scope.

## Who owns the events

`MviViewModel.events` is `EventBus.flow`, which is `Channel.receiveAsFlow()`.

- **One consumer.** Two collectors split the events; each event reaches one of them. Every
  screen collects once, through one `CollectEvents(vm.events)`.
- **Backpressure, not loss.** `emit` suspends while the buffer is full. `tryEmit` returns
  `false` instead.
- **Close is graceful.** `onCleared()` closes the bus; buffered events still arrive and the
  flow then completes. An `emit` after the close is dropped, counted in
  `droppedAfterClose`, logged — and never reported as a crash.

A feature-local `MutableSharedFlow` remains correct where a genuine broadcast exists
(`Nav3State`, `OnboardingSettingsRepository`, `TaskDraftSlot`'s per-field input flows). It is
not a substitute for the event bus, and the bus is not a substitute for it.

Two VMs (`ProposalsViewModel`, `NoteProposalsViewModel`) override `onCleared()` — they
cancel two stored collector jobs and call `super` so the bus still closes. Those are the only
`onCleared` overrides in production.

## State-first, without a blanket migration

Durable state (a pending confirmation, an undo offer, a refused close) belongs in
`UiState`: a person who arrives after a rebuild still has to act on it. A transient effect
(navigate back, snackbar, "Saved" pulse, system dialog) stays an event: replaying it after a
rotation is wrong, and a state flag for it buys a "consumed" rule nobody needs.

Events are therefore not migrated to state mechanically — see
`2026-11-27-mvi-architecture-direction.md`, which places a broad event-to-state migration
outside the adopted direction.

## Why `StatefulViewModel` is not in this inventory

It is a historical symbol. `2026-09-27-mvi-single-state-entry-and-vm-sweep.md` merged it
into `MviViewModel`; it has no subclass and no file. It appears only in:
`docs/decisions/2026-09-25-*` and `2026-09-27-*` (superseded records, correctly left as
history), `docs/progress-archive/`, and baselined dead references in
`config/docs/dead-refs-baseline.txt`. `PROGRESS.md` no longer exists. No instruction tells an
agent to use it.

## Coupling a package extraction has to carry (#3268)

Recorded here; **no module is extracted in this issue**.

| Coupling | Where it bites |
|---|---|
| `androidx.lifecycle.ViewModel` | `MviViewModel` extends it; `addCloseable`/`onCleared` are that type's lifecycle |
| `androidx.compose.runtime.Stable` | annotates `EventBus` |
| `AppError` and the error helpers | `catchTo` / `emitError` fold `core.error` types (`AppError.code`, `toMessage`, `runCatchingCancellable`) |
| `CrashReportingPort` | the scope default's failure policy and every `catchTo` report go through it |
| Kermit (`co.touchlab.kermit`) | `EventBus` logs its drop-after-close |
| `AutoCloseableCoroutineScope` + `createBackgroundScope` | the teardown contract; the scope factory is a `core.coroutines` function with a required handler |
| `combineStates` | the typed `combine` for 2–8 flows that coordinators merge with |
| `ImmutableList`/`ImmutableMap` (`kotlinx.collections.immutable`) | every coordinator's merge transform |
| `DraftState` + the draft store port | `DraftMviViewModel`'s restore/autosave path |

## Checks that hold this shape

All existing; no new lint framework.

| Invariant | Rule |
|---|---|
| a VM with its own scope closes it; an MVI subclass does not register the base's scope twice — detected by inheritance from `MviViewModel`/`DraftMviViewModel`, **not** by the `*ViewModel` name, so `TaskDetailCoordinator`, `NoteEditor` and `NotePreview` are covered | `VmCloseable` (`mvi-viewmodel`) |
| an intent handler is named `onIntent` | `IntentMethodName` |
| `scope` is the last constructor parameter | `VmScopePosition` |
| no shadow `uiState`/`_uiState` beside the base state | `ShadowedState` |
| no hand-rolled `MutableStateFlow` + `Channel` VM outside the base | `MviViewModelExt` |

## Links

- Issue #3265; direction ADR `docs/decisions/2026-11-27-mvi-architecture-direction.md`.
- `docs/decisions/2026-10-22-an-event-bus-has-one-consumer.md`,
  `2026-11-26-mvi-state-is-observed-read-only.md`,
  `2026-09-27-mvi-single-state-entry-and-vm-sweep.md`.
- Package extraction (separate initiative): epic #3268, stages #3269–#3271.
- Skills: `singularity-todo-mvi-framework`, `singularity-todo-ui-event-vs-state`,
  `singularity-todo-testable-vm`.
