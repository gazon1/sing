# sync-phase-contracts

## What

Reshape the sync phase contracts so they accurately represent every way a phase can end,
and fix two classification bugs in the push phase's handling of server responses.

## Why

`PhaseResult` does not exist as a type — the engine returns `Result<PushSummary>` from
`push()` and `Result<PullSummary>` from `pull()`. A `Result` conflates "completed
successfully" with "failed" and cannot represent "did not run". The callers that pattern-
match on the result — `SyncCoordinator`, `SyncViewModel`, `SyncSettingsScreen` — must
handle a `Result` that may be a success or a failure without any shared vocabulary for
what each arm means.

`SyncOutcome` has a `Success` arm whose two `Result` fields make callers guess which of
the four combinations (both ok / push failed / pull failed / both failed) they are looking
at. `SyncOutcome.Skipped` takes a `reason: String` but the reason is only ever
"No active sync scope" — it is a string-typed enum. `SyncOutcome.Failed` has the same
shape as a phase-level failure but lives at the cycle level.

The push phase also misclassifies two server-response cases:

- **Superseded:** a `result.patchId` that is not in the plan snapshot was coalesced away
  by a later local edit before the response arrived. The server answered `ok: true`; the
  engine counts it as `failed`. The correct classification is `superseded`.
- **Scope re-read:** `deferOrDeadLetter()` re-reads `scopeProvider.current` instead of
  using the `active` scope that was captured at plan-building time. A profile switch
  between the response and this call would file a dead-letter under the wrong owner.

Both bugs require extracting the containing logic before they can be fixed cleanly. The
extraction is the right unit of work, and the contract reshape is the right time to do
it — the types are already being touched.

## Scope

### In scope

- New `PhaseResult<T>` sealed interface in `core/sync` — replacing `Result<T>` returns
  from `push()` and `pull()`.
- `SyncOutcome` reshaped: `Completed(push, pull)`, `CouldNotStart(error)`,
  `NothingToDo` — replacing `Success`, `Failed`, `Skipped`.
- `SyncEngineStatus` collapsed from `Pushing` / `Pulling` to
  `Running(phase: Phase)`.
- `PushSummary` gains `superseded: Int = 0`.
- `PushPlan` becomes sealed with `Ready` and `Empty` substates.
- `enqueue()` wrapped with `localStorage` guards.
- `SyncEngine` split into `PushPhase`, `PullPhase`, `HandlerRegistry`.
- New detekt rule `NoCoroutineLaunchInInit`.

### Out of scope

- `NoConnection` removal or behaviour change.
- `lastPush` and `lastPull` consumers (no production reader today).
- DAO or API rename.
- iOS (this project has no iOS).
- HLC field population in `buildPatch` (S-1 from
  `2026-10-04-sync-core-remaining-work.md`).

## Verification

- `:shared:jvmTest` green; detekt green.
- `assembleDebug` passes after each extraction stage.
- `openspec validate --all --strict` exit 0.
