---
title: "SyncEngine refactor: split into three collaborators, reshape contracts, fix four defects"
date: 2026-10-09
status: proposed
tags: [sync, architecture, cleanup]
---

# SyncEngine refactor: split into three collaborators, reshape contracts, fix four defects

## Context

`SyncEngine` (1000 lines, `SyncEngine.kt`) is the monolith at the centre of `core/sync`.
It owns all the logic: session-driven scheduling, handler registration, outbox enqueue,
push planning, server communication, lost-race resolution, dead-letter shelving, pull
looping, and per-phase status reporting. The coherence this offered when the sync core
was new has become a liability:

- **No unit of compilation.** A change to `push()` requires compiling every consumer of
  the engine, even when the public surface did not change.
- **God class.** 1000 lines in one class is past the complexity threshold where detekt
  would warn if it were not already in the baseline.
- **Four latent defects** that require extracting the containing logic before they can be
  fixed cleanly (see §Defects).
- **Sealed contract types** that need to be introduced but should not land in the
  engine file itself.

The refactor splits the engine into three **collaborators** (`HandlerRegistry`,
`PushPhase`, `PullPhase`), reshapes the three public contract types
(`PhaseResult`, `SyncOutcome`, `SyncEngineStatus`), fixes the four defects, adds one
detekt rule, and updates the OpenSpec.

## Constraints

- `SyncEngine` stays `internal` — it is the module's public façade.
- DAO and API names are not renamed (deferred by `detekt.yml:215–228`).
- `kotlin.time.Clock` stays as-is (87 call sites in `commonMain`).
- No `Mutex` in `HandlerRegistry` — `registerHandler` is non-suspend and called from
  a non-suspend `init` block; `MutableStateFlow.update` is sufficient.
- No DSL for handler registration — one 6-line call site.
- `NoConnection` and `lastPush`/`lastPull` are out of scope (behaviour-change items,
  documented as follow-ups).
- Existing `init { scope.launch }` sites are **not migrated** — they are baselined by
  the new detekt rule.
- `SyncOutboxDao` and `SyncShadowDao` stay as direct DAO dependencies of the engine
  (not port interfaces — they are already interfaces with in-memory fakes).

## Defects

### D1: "Superseded" patches counted as `failed`

**Root cause (confirmed by live trace):** `planPush()` builds a snapshot of the outbox
rows at lines 440–447 (`pending`). The response handler at line 550 looks up each
server-returned `patchId` in `patches`, which is that same snapshot. A `null` lookup
means the row was **deleted from the outbox between plan-time and response** — the most
common cause is a local delete or overwrite that coalesced away the pending patch. The
current code falls through to `} else { failed++ }` (line 580–594) and treats this as a
server refusal. It is not: the server answered `ok=true` for a patch this device no
longer has queued. The correct classification is **superseded**.

The server's `sync_batch_apply` iterates `jsonb_array_elements(p_patches)` 1:1 and
in-order — a `result.patchId` always corresponds to exactly one patch in the request.
There is no `Unrecognised` arm in the server response, so a `null` lookup here is
always a local supersession, never a server-side anomaly.

**Fix:** in the `result.ok` branch (line 565), before processing, check whether
`patch` is `null`. If `null`: increment `superseded++`, skip the outbox delete
(the row is already gone) and skip `settleShadow` (the shadow is already settled by
the coalescing delete). Add `superseded: Int = 0` to `PushSummary`. Introduce
`PhaseResult.Superseded` as a new arm in `PhaseResult` (distinct from `Failed`).

`attemptsOf(entity.patchId)` at line 631 is also affected: it reads live DB state,
not the plan snapshot. If a superseded patch is also a retry (already had `attempts >
0`), the null from `attemptsOf` correctly indicates the row was already removed, so the
null is handled correctly by `entity.attempts` fallback. No change needed there.

### D2: `scopeProvider` re-read in `deferOrDeadLetter`

**Root cause (confirmed by live trace):** At line 638, inside `deferOrDeadLetter`,
the code calls `scopeProvider.current.first()?.let { active ->` to re-read the current
scope. This re-read can return a **different** scope than the `active` captured at
`push():512` from `plan.active`. If the profile switched between the push response
handling and this call, the dead-letter row would be filed under the wrong owner.

The `plan.active` field was specifically captured at plan-building time (line 426)
to serve as the authoritative scope for all response processing. Using it instead
of re-reading is the fix.

**Fix:** pass `active: SyncScope` from `plan.active` into `deferOrDeadLetter` as a
parameter. At the call site (line 583), `active` is already in scope from
`val active = plan.active` at line 514. Remove the `scopeProvider.current.first()`
call inside `deferOrDeadLetter`. The dead-letter entity already carries
`entity.ownerId` (line 656) — no change needed there.

### D3: `PushPlan` is not sealed; `Ready` has no invariant

**Root cause:** `PushPlan` is a plain `data class` with `active: SyncScope?`. Every
consumer that pattern-matches on it must handle the null case, even though in
practice a non-null `active` is the only operationally meaningful path (a null scope
means "empty queue" and is the terminal case that returns `null` from `planPush`).

**Fix:** make `PushPlan` sealed. Two substates:
- `PushPlan.Ready(scope: SyncScope, pending: List<SyncOutboxEntity>, patches:
  List<DeltaPatch>, request: BatchPushRequest)` — non-null scope, with
  `init { require(profileId.isNotBlank()) }` on `SyncScope` or in the `Ready`
  constructor.
- `PushPlan.Empty` — nothing to push; returned instead of `Result.success(null)`.

### D4: `localStorage` guard does not cover `enqueue`

**Root cause:** `phases.localStorage("sync.outbox.read", ...)` at line 442 guards the
outbox read, but `enqueue()` at line 332–370 calls `outboxDao.deleteByEntity` and
`outboxDao.insert` **without** wrapping them in `localStorage`. A storage failure in
`enqueue` would throw, not return a classified `Result`. See `M-2` in
`2026-10-04-sync-core-remaining-work.md`.

**Fix:** wrap each DAO call inside `enqueue` with `phases.localStorage(...)`. The
reporter is not yet available at `enqueue()` call site — expose it as a constructor
parameter to `SyncEngine` (or introduce a separate `EnqueueResult` type that carries its
own error classification). Alternatively, D4b from the plan: introduce
`EnqueueOutcome { Queued, NotAvailable }` where `NotAvailable` has no reason until a
caller needs one.

## Architecture after refactor

```
SyncEngine (internal façade)
├── HandlerRegistry   — _handlers: MutableStateFlow<Map<DocType, EntityApply>>
│   └── registerHandler(docType, apply)
├── PushPhase        — push(), planPush(), deferOrDeadLetter(), resolveLostRace()
│   └── PhaseResult  — Completed, Failed(AppError), NotRun, Superseded
├── PullPhase        — pull(), applyEvent(), applyPage()
│   └── PhaseResult  — Completed, Failed(AppError), NotRun
└── SyncCoordinator  — request(): SyncOutcome
    └── SyncOutcome  — Completed(push: PhaseResult, pull: PhaseResult),
                        CouldNotStart(AppError), NothingToDo
```

`SyncEngine.status: StateFlow<SyncEngineStatus>` stays on the engine (it is the
observable status surface). `SyncEngine.lastPush` and `SyncEngine.lastPull` also stay
(even though they are not yet consumed — documented as follow-up).

`SyncPhaseReporter` stays as a separate class (it is the authority on phase-ending
rules and is used by both `PushPhase` and `PullPhase`).

## Public contract changes

### `PhaseResult` (new sealed interface)

```kotlin
sealed interface PhaseResult<out T> {
    data object Completed : PhaseResult<Nothing>
    data class Failed<T>(val error: AppError) : PhaseResult<T>
    data object NotRun : PhaseResult<Nothing>
    // D1: for push only
    data object Superseded : PhaseResult<Nothing>
    data class Ok<T>(val value: T) : PhaseResult<T>
}
```

Note: `Ok` wraps a value so `PhaseResult<PushSummary>` can carry the summary on
`Completed`. An alternative is `data class Completed<T>(val summary: T)`.

### `SyncOutcome` (reshaped)

```kotlin
sealed interface SyncOutcome {
    data class Completed(
        val push: PhaseResult<PushSummary>,
        val pull: PhaseResult<PullSummary>,
    ) : SyncOutcome

    data class CouldNotStart(val error: AppError) : SyncOutcome

    data object NothingToDo : SyncOutcome
    // (Skipped(reason: String) removed — NotRun covers it)
}
```

`SyncOutcome.Success` renamed to `Completed`. `SyncOutcome.Skipped` replaced by
`NothingToDo` (data object — no reason string; the reason is implicit in the type).

### `SyncEngineStatus` (unchanged except for `Running`)

```kotlin
sealed interface SyncEngineStatus {
    data object Idle : SyncEngineStatus
    data class Running(val phase: Phase) : SyncEngineStatus  // Phase = PUSHING | PULLING
    data object NoConnection : SyncEngineStatus
    data class Failure(val error: AppError) : SyncEngineStatus
}
```

`Pushing` and `Pulling` collapsed into `Running(phase: Phase)` where `Phase` is a
common enum. `Idle.isSuccess()` still returns `true`.

## Contract migration

`SyncOutcome.Completed` replaces `SyncOutcome.Success`. A compatibility adapter in
`SyncRepositoryImpl` provides `toLegacy(): SyncOutcome` for the six consumers
(`SyncViewModel`, `SyncButton`, `SyncSettingsScreen`, `AccountSwitcher`,
`SyncPushJob`, `SyncOutboxWorker`). One consumer per commit; `assembleDebug` gates
each.

## Detekt rule: `NoCoroutineLaunchInInit`

**Rule ID:** `NoCoroutineLaunchInInit`
**RuleSet:** `sync-core` (new)
**What it bans:** `scope.launch { ... }` inside `init { ... }` blocks in `commonMain`
production code.

**Rationale:** `init { scope.launch { ... } }` captures the injected scope at
construction time. If the constructing module has a longer lifetime than the object
(e.g. a ViewModel that outlives the screen that created it), the launched coroutine
is not cancelled when the screen is disposed — it races with the next screen's
construction. The canonical pattern is `init { addCloseable(scope) }` + a secondary
`init { this.scope.launch { ... } }` that uses the injected scope. The rule targets
the first `init` only.

**Scope:** `com.singularity.todo.**` in `commonMain` and `jvmMain` production sources.
Exempts `SyncEngine.init` (the session collector, which must start at construction).
Exempts `SyncBootstrapper` and `SyncCoordinator` (both have the same session-collector
pattern). All other sites are baselined at detection time.

**Exemption mechanism:** `// nolint: NoCoroutineLaunchInInit` comment on the `init` block.

**Migration:** the injected `scope: AutoCloseableCoroutineScope` in canonical VMs is
designed to be cancelled by the ViewModel's `close()`, so a collector started with that
scope does not need to be in `init`. Move collectors to a secondary `init` that uses the
already-wrapped scope, or use `scope.launch { ... }` where `scope` is the injected
field (not `this.scope`).

**Implementation:** `SyncEngineInitLaunchRule.kt` in `detekt-rules`. Checks for
`InitBlock` containing `DotQualifiedExpression` whose `selector` is
`safeCall: SafeAnonymousFunctionCall` matching `scope.launch` or `scope.async`.

## Extraction plan

Each extraction follows the same shape:
1. Create the new class with the extracted logic (copy from `SyncEngine`).
2. Add a delegating field in `SyncEngine` so callers still use `SyncEngine`.
3. Run `assembleDebug` — must pass.
4. Update DI wiring in `CoreDiModule` — remove from engine ctor, add as new binding.
5. Update `SyncEngineFakes` to provide fakes for the new collaborator.
6. Migrate the compatibility adapter consumer.
7. Run `jvmTest` — must pass.
8. Remove the delegating field and delegation comment.

### Stage 3: HandlerRegistry

File: `core/sync/HandlerRegistry.kt`

Extract `_handlers`, `handlers`, `registerHandler()` (lines 273–299). No external
dependencies — uses only `MutableStateFlow`.

### Stage 4: PushPhase

File: `core/sync/PushPhase.kt`

Extract: `push()`, `planPush()`, `deferOrDeadLetter()`, `resolveLostRace()`,
`settleShadow()`, `PushPlan` (as sealed), the `lost` counter and
`resolveLostRace`-specific logic.

Constructor dependencies: `api`, `authRepository`, `outboxDao`, `deadLetterDao`,
`shadowDao`, `idGenerator`, `scopeProvider`, `patchBuilder`, `clock`,
`phases: SyncPhaseReporter`, `writerProvider`, `retryPolicy`.

D1 and D2 fixes land here (D1: superseded outcome; D2: captured scope parameter).

### Stage 5: PullPhase

File: `core/sync/PullPhase.kt`

Extract: `pull()`, `applyEvent()`, `applyPage()`, `PullStep`, `PageOutcome`.

Constructor dependencies: `api`, `authRepository`, `shadowDao`, `scopeProvider`,
`handlers`, `stateRepository`, `clock`, `phases: SyncPhaseReporter`.

### Stage 6–8: D3, D4, and remaining fixes

- D3: `PushPlan` sealed + `Ready.init` invariant.
- D4: `phases.localStorage` wrapping each step of `enqueue` in `SyncEngine`
  (D4b: `EnqueueOutcome { Queued, NotAvailable }`).

## OpenSpec change

Change `offline-sync` requirement REQ-OS-026 (push lost-race outcome) to also cover
the `Superseded` classification. New requirement or amendment: when the server
confirms a patch but the local outbox no longer holds it, the summary increments
`superseded` and the shadow is not released.

## Consequences

- `SyncEngine` drops from ~1000 lines to ~200 (façade + coordinator).
- `PushPhase` ≈ 350 lines, `PullPhase` ≈ 200 lines, `HandlerRegistry` ≈ 30 lines.
- Each collaborator is independently testable and independently compiled.
- The four defects are fixed in the extraction commits, verified by existing tests.
- One new detekt rule; existing `init { scope.launch }` sites are baselined.
- Public contracts (`SyncOutcome`, `SyncEngineStatus`) gain `Completed` and
  `Running(phase)` — no behaviour change for consumers.
- `NoConnection` and `lastPush`/`lastPull` remain follow-up items.

## Links

- `docs/decisions/2026-10-04-sync-core-remaining-work.md` — M-2, M-3, S-1, S-4
- `docs/decisions/2026-10-05-sync-storage-failure-is-not-a-server-refusal.md` — M-2
- `openspec/specs/offline-sync/spec.md` — REQ-OS-026, REQ-OS-027
- `supabase/migrations/2026-10-07-sync_schema.sql` — `sync_batch_apply` (server-side)
- `core/sync/SyncEngine.kt` — source of truth
- `core/sync/SyncPhaseReporter.kt` — phase-ending authority
- `core/sync/SyncCoordinator.kt` — cycle owner
- `skill: singularity-todo-sync` — sync architecture reference
