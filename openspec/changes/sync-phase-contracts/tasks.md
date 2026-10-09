# Tasks — sync-phase-contracts

## PhaseResult type

- [ ] Introduce `sealed interface PhaseResult<out T>` in `core/sync/SyncEngine.kt`
      — four arms: `Completed`, `Failed(AppError)`, `NotRun`, `Superseded`
      — `Completed` carries `T` (the summary) so callers do not pattern-match on `Result`
- [ ] Change `push()` return type from `Result<PushSummary>` to `PhaseResult<PushSummary>`
- [ ] Change `pull()` return type from `Result<PullSummary>` to `PhaseResult<PullSummary>`

## SyncOutcome reshape

- [ ] Rename `SyncOutcome.Success` → `SyncOutcome.Completed`
- [ ] Add `CouldNotStart(AppError)` — replaces `Failed` for "cycle could not start" cases
- [ ] Replace `Skipped(reason: String)` with `data object NothingToDo`
- [ ] Update `SyncCoordinator.runCycleCatching()` to return `SyncOutcome.Completed`

## SyncEngineStatus reshape

- [ ] Introduce `enum class Phase { PUSHING, PULLING }`
- [ ] Collapse `Pushing` and `Pulling` into `Running(phase: Phase)`
- [ ] Update `SyncPhaseReporter` to use `Running(phase)` instead of the two separate values

## PushSummary

- [ ] Add `superseded: Int = 0` to `PushSummary`

## PushPlan

- [ ] Make `PushPlan` sealed with `Ready(scope: SyncScope, ...)` and `Empty`
- [ ] `PushPlan.Ready.init` enforces `require(profileId.isNotBlank())` on the scope
- [ ] Remove `null`-able `active: SyncScope?` from `Ready` — it is non-null by construction

## PushPhase extraction

- [ ] Create `core/sync/PushPhase.kt` — extract `push()`, `planPush()`,
      `deferOrDeadLetter()`, `resolveLostRace()`, `settleShadow()`
- [ ] Constructor: `api`, `authRepository`, `outboxDao`, `deadLetterDao`, `shadowDao`,
      `idGenerator`, `scopeProvider`, `patchBuilder`, `clock`, `phases`,
      `writerProvider`, `retryPolicy`
- [ ] D1 fix (Superseded): `result.ok && patch == null` → `superseded++`, skip outbox delete
- [ ] D2 fix (scope re-read): pass `active: SyncScope` from `plan.active` into
      `deferOrDeadLetter`; remove `scopeProvider.current.first()` call inside it

## PullPhase extraction

- [ ] Create `core/sync/PullPhase.kt` — extract `pull()`, `applyEvent()`,
      `applyPage()`, `PullStep`, `PageOutcome`
- [ ] Constructor: `api`, `authRepository`, `shadowDao`, `scopeProvider`,
      `handlers`, `stateRepository`, `clock`, `phases`

## HandlerRegistry extraction

- [ ] Create `core/sync/HandlerRegistry.kt` — extract `_handlers`, `handlers`,
      `registerHandler()`
- [ ] Uses `MutableStateFlow.update` (no `Mutex`)

## SyncEngine façade

- [ ] Retain `SyncEngine` as `internal` façade
- [ ] Hold `PushPhase`, `PullPhase`, `HandlerRegistry` as delegate fields
- [ ] Remove extracted logic from `SyncEngine` body
- [ ] `init` block stays — session collector pattern (exempt from detekt rule)

## enqueue D4 fix

- [ ] Wrap each DAO call in `enqueue()` with `phases.localStorage(...)`
      — `deleteByEntity`, `insert`
- [ ] `phases` is already a constructor parameter of `SyncEngine`

## Detekt rule

- [ ] Create `SyncEngineInitLaunchRule.kt` in `detekt-rules`
- [ ] Register `SyncEngineInitLaunchProvider` in `META-INF/services`
- [ ] Add `sync-engine-init-launch:` block to `config/detekt/detekt.yml`
- [ ] Baseline all existing violations at detection time

## DI wiring

- [ ] Update `CoreDiModule` to construct `HandlerRegistry`, `PushPhase`, `PullPhase`
- [ ] Pass `PushPhase`, `PullPhase`, `HandlerRegistry` to `SyncEngine` constructor
- [ ] Verify `:androidApp:assembleDebug` green after DI change

## Tests

- [ ] Update `SyncEngineFakes` to expose `FakePushPhase`, `FakePullPhase`
- [ ] `SyncEnginePushTest` — assert `PhaseResult.Completed(summary)` for success
- [ ] `SyncEnginePullTest` — assert `PhaseResult.Completed(summary)` for success
- [ ] New test: superseded patch when plan snapshot and live outbox diverge
- [ ] Verify all 14 existing sync tests still pass

## Verification

- [ ] `openspec validate --all --json --strict` — exit 0
- [ ] `:shared:jvmTest` green; detekt green
- [ ] `assembleDebug` passes after each extraction stage
- [ ] `check.sh` green
