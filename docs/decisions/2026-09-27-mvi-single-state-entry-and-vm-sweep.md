---
title: MVI Base — Single State-Update Entry + ViewModel Sweep
date: 2026-09-27
status: accepted
deciders: Singularity Developer
deciders: Singularity Developer
---

# MVI Base — Single State-Update Entry + ViewModel Sweep

## Context

`MviViewModel` offered four ways to write state. A census over the whole repository
(`shared`, `androidApp`, `desktopApp`, `mcp-server`, all test source sets) measured
what each was actually used for:

| Member | Production call sites | Overrides | Verdict |
|---|---|---|---|
| `updateState { }` | **75** in 20 files | — | the project's dominant idiom |
| `setState(…)` | **15** in 7 files | — | live and used |
| `updateStateAs<T>` | **0** | 0 | dead code |
| `StatefulViewModel.update {}` | **0** external (2 self-calls) | — | dead bypass |
| `onStateChanged` | — | **0** | speculative hook, never used |
| `StatefulViewModel` | — | 1 subclass | ceremony |
| `StateStrategy` | **0** | — | dead, one variant, `Atomic` never landed |
| `fireAndForget` | 11 | — | overlapped the base after this change |
| `vmScope` overrides | 8 | 8 | all assigned the same `scope` back |
| VMs extending `ViewModel()` directly | 3 | — | bypassed the whole hierarchy |
| `uiState` / `_uiState` | 2 | — | shadowed the inherited `state` |
| Public side-channel `StateFlow`s | 5 | — | duplicated data already in state |

So the redundancy was not in `updateState` — it was `updateStateAs`, `update`,
`onStateChanged`, `StatefulViewModel` and `StateStrategy`.

The project had also written a detekt rule for the bypass class
(`MviViewModelExt`: "owns MutableStateFlow + Channel/MutableSharedFlow but does not
extend MviViewModel"). `BackupViewModel` and `TaskDetailViewModel` matched it, yet
neither was migrated — and `MviViewModelExt` had a latent bug that made its
"already extends MviViewModel" early-return dead (it compared the full type text,
which always carries generic arguments, against the bare name).

## Idea

Collapse to two final state writers and one error-routing primitive, then apply them
everywhere the census pointed, so the base class is the only way to hold UI state.

## Decision

**1. `StatefulViewModel` merged into `MviViewModel`.** It had one subclass and no
other role. `MviViewModel` now owns `_state`, `state`, `currentState`, `updateState`
and `setState` directly. Both writers are `final` — an `open` writer a subclass can
silently override is how the state stream and the event bus drift apart.

**2. `updateStateAs<T>`, `onStateChanged` and `StateStrategy` deleted.** Zero call
sites, zero overrides, one dead variant respectively.

**3. `catchTo` / `emitError` replace `fireAndForget`.** `catchTo`'s `onError` is
`suspend`, so `emit` is called directly:

```kotlin
protected fun catchTo(errorLabel: String, onError: suspend (String) -> Unit, block: suspend () -> Result<*>): Job =
    vmScope.launch { block().onFailure { onError(it.toMessage(errorLabel)) } }

protected fun emitError(errorLabel: String, errorEvent: (String) -> E, block: suspend () -> Result<*>): Job =
    catchTo(errorLabel, { msg -> emit(errorEvent(msg)) }, block)
```

The old `CoroutineScope.fireAndForget` took a **non-suspend** `onError: (Throwable) -> Unit`,
so every call site that wanted an event had to wrap `emit` in a nested
`scope.launch { }` — the 22-site pattern this replaced. `catchTo` removes the nesting
by fixing the signature instead of working around it. After the sweep `fireAndForget`
had zero call sites and was deleted along with its test.

**4. `vmScope` is `final`.** The 8 overrides were `override val vmScope = scope`.

**5. Three VMs migrated to the hierarchy.** `BackupViewModel` (215 LOC),
`SettingsViewModel` (189 LOC) and `ChatViewModel` (which extended `MviViewModel` but
ignored its `state`). Their 13 public methods became sealed `BackupIntent` +
`onIntent`; `processIntent` became `onIntent`. This also removed the two stale
baseline entries for `SettingsViewModel` (`IntentMethodName`, `VmScopePosition`).

**6. Side-channel state folded into the main state.** `ProjectDetailViewModel` exposed
`hideCompleted`, `parentOptionsFlow`, `availableTasksFlow`; `NotesListViewModel`
exposed `filter` and `sortOrder` — the latter two were **pure duplicates** of
`NotesListState.filter` / `.sortOrder`, which already lived in `NotesUiState.Content`.
The screen was collecting five independent flows, so it could render a
`hideCompleted` toggle that disagreed with the task list already on screen.

`lastEditedAt` was deliberately **left alone**: it is written by `mutate` after the
repository write, not by the `combine`. Folding it in would give the state two
writers and open a lost-update window — the exact anti-pattern `DraftMviViewModel`'s
KDoc warns about.

**7. New detekt rule `ShadowedState`.** Flags a class extending `MviViewModel` that
declares `uiState` / `_uiState`. Name-based on purpose (like the existing
`IntentMethodName`): PSI has no type inference here, and a broader heuristic would
flag the legitimate side-flows VMs use as `combine` inputs. `MviViewModelExt`'s
supertype check was fixed to compare the raw name before `<`.

## Rationale

- **All 75 `updateState` and 15 `setState` call sites compile unchanged.** The base was
  reshaped around existing usage rather than forcing ~94 lines across 27 files to
  adopt a new name. That was the whole point of the census: the two members everyone
  used were fine, the unused ones were the problem.
- **`emitError` is one line** where the old pattern was five, and it is readable —
  `emitError("Pin failed", NotesUiEvent::Error) { repo.togglePinned(id) }`.
- **`final` writers make the contract structural.** No lint rule is needed to stop a
  subclass bypassing the state contract, because there is nothing to bypass.
- **Folding the side channels is a correctness fix**, not cosmetics: one snapshot
  instead of five independently-timed flows.

## Consequences

- `MviViewModel` is now the single place that holds UI state. `TaskDetailViewModel`
  is the only remaining `ViewModel()` subclass (MR-2).
- `catchTo`'s `onError` being `suspend` is load-bearing. Reverting it to non-suspend
  would bring the nested-`launch` pattern straight back.
- `errorLabel` is a **fallback**, not a prefix: `toMessage(label)` prefers
  `AppError.message`, then `Throwable.message`, then the label. Pinned by two tests.
- `NotesUiState` lost its `Empty` variant — an empty list is `Content` with
  `isEmpty == true`. The variant could not carry `filter`/`sortOrder`, so choosing a
  filter that matched nothing silently reset the filter chip to "All".
- `SettingsUiState.Loading` / `.Error` were never produced by anyone; the two screen
  branches that handled them could not render and were removed.

### Bugs found and fixed during the sweep

- **Duplicate snackbar on settings export.** `exportSettingsSnapshot` emitted both
  `SettingsSnapshotExported` and `snackbar.emit("Settings snapshot ready")`, and the
  screen rendered a snackbar for each — the same message appeared twice.
- **`BackupScreen` collected `events` twice** (its own `LaunchedEffect` plus
  `NotificationHost`). Harmless on `SharedFlow`, but `EventBus` is a **Channel** and
  single-consumer: the two collectors would have split the events. The screen now
  fans out once via `shareIn`. `BackupScreen` was the only screen in the project with
  this shape.
- **`MviViewModelExt` early-return was dead code** (full type text vs bare name).

### Test infrastructure

`FakeProfileAwareCurrentUser` defaulted to `dispatcher = Dispatchers.Default` — a real
thread pool in a test double, which `advanceUntilIdle()` cannot drive. Both overloads
now default to `Dispatchers.Unconfined`. Note honestly: **this was not what fixed the
failing tests** (see below); it removes a latent flake source, and the whole suite
passes with it, but its necessity was not demonstrated.

The actual cause of the four state-assertion failures was that the new assertions read
*derived* state, which needs the collector to run: `advanceUntilIdle()` does not
drain the nested `flatMapLatest` collection. A diagnostic showed
`repo.observeAll().first()` returning immediately while the state stayed `Loading`
until `testScheduler.runCurrent()`. This is already the documented pattern elsewhere in
this suite ("advanceUntilIdle() may return before background coroutines settle").

## Отложенные находки

| # | Finding | Why deferred | Where it goes |
|---|---|---|---|
| 1 | `TaskDetailViewModelTest > TitleChanged debounce saves after delay` fails, hanging 60 s on `runTest`'s timeout — **pre-existing, present before this MR** | Real fix is dispatcher injection into `TaskDetailDeps` / `CreateTaskUseCase`; the test's own KDoc and ADR `2026-09-25-testable-vm-dispatcher-clock` both name it. Not a test tweak. | MR-2 |
| 2 | `TaskDetailViewModel` (523 LOC) still extends `ViewModel()`, owns 7 side-channel flows and its own `Channel` | Scheduled as its own PR per plan — 2.5× the size of the other migrations with different DI risk | MR-2 |
| 3 | The same detekt file-name gate (`endsWith("ViewModel.kt")`) hides `TaskDetailViewModel` from all four MVI rules | Fixing the gate alone would make `MviViewModelExt` and `VmCloseable` fire on the not-yet-migrated class and break `just lint` | MR-2, together with #2 |
| 4 | 11 sites still read `.collect { updateState { it } }` where `setState(it)` is now identical | The census counted the broader error pattern; after the sweep only 11 collector-copies remain, and they are correct | Optional, not worth a PR |
| 5 | `mutate(transform)` duplicated in `ProjectDetailViewModel` and `TaskDetailViewModel` | Different semantics (one needs `Clock` + a timestamp, the other takes the entity as a parameter). A shared `MutableEntityViewModel` would need a 4th type parameter for 2 users | Revisit at the 3rd call site |
| 6 | Seed+dirty draft logic duplicated across `ProjectDetailDraftState`, `TaskDetailDraftState`, `SavedAgendaDraftState` | A shared `DraftSeedState` is a real candidate, but `DraftMviViewModel` does not fit the detail screen's silent write-through | After MR-2 |
| 7 | `ProjectDetailUi.parent: Project?` only supplies `.id` and `.name` to the screen, at the cost of a 4th `combine` branch and a dedicated `projectRepo.observe` | Deriving it from `parentOptions` would hide the chip for a deleted parent — a UX change, not a refactor | Separate PR, needs a UX call |
| 8 | `just setup-hooks` computes `.git/.githooks` for worktrees (should be the repo's `.githooks/`), and that path does not exist — so `core.hooksPath` points nowhere and **hooks are disabled repo-wide**, in the main checkout too | Environment/tooling, not MVI. Pre-existing | Separate tooling issue |
| 9 | The real `pre-commit` hook resolves its root as `../..` from `.git/hooks`, so inside a worktree it compiles the **main** checkout, not the worktree | Same as #8 | Separate tooling issue |
| 10 | `BackupViewModel`'s `BackupUiEvent` KDoc and `BackupUiState` carry no `sealed`-hierarchy note; `SettingsIntent` now extends `MviIntent` from `core.settings`, coupling `core.settings → core.ui` | Not prohibited by any arch test (those only govern `feature.<x>.<layer>`), but the new dependency is worth a conscious decision | Noted, no action |

## Links

- `docs/decisions/2026-09-25-local-mvi-framework.md` — original base class; describes
  the now-deleted `StatefulViewModel` and `updateStateAs`
- `docs/decisions/2026-09-18-mutation-result-handling.md` — introduced `fireAndForget`
- `docs/decisions/2026-09-23-tech-debt-audit.md` — proposed extracting ProjectDetail
  sub-VMs; this ADR deliberately took the opposite path (shrink, don't split)
- `docs/decisions/2026-09-25-testable-vm-dispatcher-clock.md` — the real fix for #1
