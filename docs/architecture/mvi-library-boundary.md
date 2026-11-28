# The `:mvi` library boundary

> Reference for #3269 (stage 1 of epic #3268). **Design only: no module exists, no file has
> moved.** This note decides what `:mvi` will own, what stays app-side, and the exact API the
> module publishes, so that #3270 is a mechanical extraction. The decision itself, with its
> rejected alternatives, is ADR `docs/decisions/2026-11-27-mvi-library-module-boundary.md`.
>
> Counts are of the tree at the time of writing. The ones that anchor a rule (`0`, `1`) are the
> ones worth re-checking before the move.

## Decision in one paragraph

One KMP module, `:mvi`, holding the MVI runtime: the intent/event markers, `MviViewModel`, its
`EventBus`, the typed dispatch wrapper, and one library-owned diagnostics seam. It depends on
kotlinx-coroutines and AndroidX `ViewModel` — the sole source of ViewModel lifecycle — and on
nothing else.

It does **not** own coroutine infrastructure. `:mvi` accepts a plain
`kotlinx.coroutines.CoroutineScope` and cancels it at teardown; the closeable-scope class, the
scope factory, the dispatcher policy and the crash-reporting adapters stay in `:shared`. The app's
error vocabulary, Kermit logging and crash reporter cross the boundary through one app-supplied
`MviDiagnostics`, whose default is silent. `DraftMviViewModel` and the coordinator slot idiom stay
in `:shared`, because both encode app policy rather than MVI mechanics.

## Coroutine infrastructure ownership

This is the part of the boundary that is easiest to get wrong, because "generic-looking coroutine
helper" and "MVI runtime" are not the same thing. Three owners, no overlap:

### MVI-owned (`:mvi`)

| Owned | Why it is MVI's |
|---|---|
| The `scope: CoroutineScope` **parameter** of `MviViewModel` | The base class states a lifecycle contract: the ViewModel owns no work of its own, it runs on a scope the caller supplies. That is the MVI loop's shape. |
| **A `Job` in that scope** | The supplied scope **must** contain a `kotlinx.coroutines.Job`. `MviViewModel` checks this at construction and fails loudly if the `Job` is missing; it never silently skips teardown cancellation. Use a real child `Job` when the scope must be tied to a parent: `SupervisorJob(parent.coroutineContext[Job])` (or `Job(...)`) passed into the child's context. `CoroutineContext + Job()`/`SupervisorJob()` **replaces** the context's `Job`; without an explicit parent it is independent, not a child scope. |
| **Cancelling that scope at teardown** — `addCloseable { scope.coroutineContext[Job]!!.cancel() }` | The base class's terminal step, after the `Job` presence is established at construction. `cancel()` on the owned `Job` cancels work launched in that scope; a separate child-`Job` scope keeps the parent's other work intact. |
| **Scope ownership** | The passed scope is **ViewModel-owned**: it is created for this one ViewModel and the ViewModel is responsible for cancelling it at teardown. Do not pass a shared `applicationScope`, process/global scope, or any scope whose lifetime is broader than the ViewModel. For tests, create a dedicated scope whose `Job` is a child of the test/root scope (e.g. `val job = SupervisorJob(testScope.coroutineContext[Job]); CoroutineScope(testScope.coroutineContext + job)`) or use `:shared`'s `testScope(...)` helper where that module is already in use. |
| Collecting flows and launching work on that scope (`catchTo`, `emitError`, `vmScope`) | The loop itself. `vmScope` is typed `CoroutineScope`. |

`:mvi`'s public API therefore names **no** closeable-scope type and **no** scope factory. It takes a
`CoroutineScope`, which every consumer already has.

### Application-owned (`:shared`)

| Stays | Why it is not the library's |
|---|---|
| `AutoCloseableCoroutineScope` | **15 non-ViewModel production files import it** — `SyncEngine`, `SyncRunner`, `AuthRepository`, `SyncAuthBridge`, `HlcFactory`, `PullPhase`, `PushPhase`, `SyncPrefs`, `CountdownStateMachine`, the three task collectors, `TaskDetailScreenSession`, `BackgroundCrashReporting`, and a test stub — and 4 of them call `.close()`. It is app coroutine infrastructure with 63 production importers, only 48 of which are ViewModels. Putting it in `:mvi` would make sync and auth depend on the MVI library to cancel a scope, which is exactly the inversion this issue exists to avoid. |
| `createBackgroundScope(failureHandler)` | Dispatcher policy (`Dispatchers.Default`) plus a **required** failure handler. Its KDoc already argues that the required argument is the point: it turns "did you think about this?" into a compile error. That is an application decision about where background failures go, not a rendering of the MVI loop. 21 files use it. |
| `backgroundFailureHandler(onFailure)` | The `CancellationException` guard shared by every scope policy — a general coroutine concern, used by callers that are not ViewModels. |
| `loggingBackgroundFailureHandler()` | Names Kermit and a tag. A logging policy. |
| `reportingScope(port)` and `crashReportingFailureHandler(port)` | Compose the app's `CrashReportingPort` into a scope's failure policy. 45 call sites. Application wiring. |
| `AppMviDiagnostics(port)` | The adapter. Fulfils the library seam with app knowledge. |

**Consequence, stated as a rule:** a non-MVI component must not depend on `:mvi` to obtain coroutine
utilities. If a future component needs a closeable scope, a dispatcher policy or a failure handler,
it imports `:shared`'s `core.coroutines` as it does today. `:mvi` publishes nothing in that space,
so the dependency arrow can only ever point the right way. #3270 must not add a coroutine helper to
`:mvi` just because it is convenient for the base class.

**Scope contract, stated explicitly.** The scope is a lifecycle boundary, not a shared execution
pool:

- **ViewModel-owned only.** The caller creates the scope for this ViewModel instance. The ViewModel
  cancels it at teardown; no other owner may cancel it on the ViewModel's behalf, and the ViewModel
  must not be given a scope it does not own.
- **No shared scopes.** Passing `applicationScope`, a process/global scope, or a scope shared with
  other ViewModels/services is a contract violation: destroying one ViewModel would cancel unrelated
  work. Application/background work stays in `:shared`'s application-owned scopes (`createBackgroundScope`,
  `reportingScope`, sync/auth scopes), not in a ViewModel scope.
- **`Job` is required.** `MviViewModel` requires `scope.coroutineContext[Job]` at construction and
  fails loudly when absent. Silent no-op teardown is not acceptable: a scope without a `Job` would
  leak launched coroutines after `ViewModel` destruction.
- **Tests use a dedicated child scope.** `runTest`/`TestScope` provide the scheduler; pass a scope
  owned by the test/ViewModel under test whose `Job` is an explicit child of the test/root `Job`.
  Example: `val parentJob = checkNotNull(testScope.coroutineContext[Job]); val vmJob = SupervisorJob(parentJob); val vmScope = CoroutineScope(testScope.coroutineContext + vmJob)`.
  Use the existing `:shared` `testScope(...)` helper where that module is already present; it does
  the same child-`Job` wiring. Do **not** use `CoroutineScope(SupervisorJob() + dispatcher)` as a
  "child" of `runTest` — without a parent argument it is independent, so test completion/cancellation
  may not cancel the ViewModel work. `backgroundScope` is fine only when its semantics match the
  test.

**The base loses its default scope.** Today `scope` defaults to a scope composed from
`crashReporter`, which is precisely the application policy above. All 35 subclasses already declare
`scope: AutoCloseableCoroutineScope = reportingScope(crashReporter)` and pass it explicitly
(verified: 0 rely on the base default), so making `scope` required in `:mvi` changes no call site
and deletes the last reason for the base to know a failure handler exists.

### Test-support helpers (`:shared`, currently in `commonMain`)

`testScope(scope)` / `testScope(context)` wrap a `CoroutineScope` in a child `Job` so a test can
cancel a ViewModel's subtree without cancelling `runTest`'s root. They stay **where they are** in
`:shared` `commonMain` as test-support helpers. This is an existing pragmatic compromise, not a
recommended pattern for new test APIs: the functions are technically reachable from production
source sets, but their intended use is tests.

`:mvi` does **not** export `testScope()` or equivalent wrappers from its production `commonMain`.
This doc does not move or refactor the existing `:shared` helper; it records the boundary for the
future library.

Three reasons not to promise them as `:mvi` public library API:

1. A library should not ship production `commonMain` API whose only justified callers are tests.
2. `kotlinx-coroutines-test` already provides `runTest` and `TestScope`, and that is the documented
   mechanism. What these helpers add is one child-`Job` wrapper; a consumer that needs it can write
   the same three lines.
3. 33 files use them, including **`desktopApp` tests**, which cannot see another module's test
   sources — the reason Kotlin has no cross-module test-fixture source set here. Keeping them in
   `:shared`'s `commonMain` is the pragmatic answer, recorded as such, not an endorsement.

Whether `:mvi` should publish such a helper is settled by #3271: **only if the consumer fixture
actually needs it.** The fixture is the evidence; a public API added on speculation is exactly what
this issue's acceptance criteria forbid. Until then the child-`Job` wrapper remains a `:shared`
test-support helper.

## Source classification

Every candidate, with the destination that follows from what it actually depends on.

| Source | Destination | Rationale |
|---|---|---|
| `core/ui/Markers.kt` — `MviIntent`, `MviEvent` | **`:mvi`** | Typing only, zero dependencies. Deliberately not `sealed`, so the same package split across two modules stays legal. |
| `core/ui/EventBus.kt` | **`:mvi`**, decoupled | Channel semantics, backpressure, graceful close and the drop counter are the contract. Loses three dependencies: the Compose `@Stable` annotation, the Kermit `Logger`, and the never-called `CrashReportingPort`. |
| `core/ui/MviViewModel.kt` | **`:mvi`**, decoupled | The loop itself: one state flow, two final writers, an `onIntent` entry point. Keeps `ViewModel` (see below) and a required `CoroutineScope`. Loses `AppError`, the error helpers, the crash reporter and the defaulted scope; gains `MviDiagnostics`. |
| `core/ui/IntentActions.kt` | **`:mvi`** | Typed dispatch over `MviIntent`; its only import is the marker. |
| `core/coroutines/AutoCloseableCoroutineScope.kt` | **`:shared`** | App coroutine infrastructure: 63 production importers, 15 of them non-ViewModel, 4 calling `.close()`. `:mvi` accepts `CoroutineScope` and cancels its `Job`, so it never needs this type. |
| `core/coroutines/BackgroundScope.kt` — `backgroundFailureHandler`, `createBackgroundScope`, `loggingBackgroundFailureHandler` | **`:shared`** | Dispatcher and logging policy, and a required failure handler. The base no longer defaults a scope, so `:mvi` has no use for them. |
| `core/coroutines/testScope.kt` | **`:shared`** (unchanged) | Test helper in `commonMain`, used by 33 files including `desktopApp` tests. Not exported from `:mvi` production `commonMain`; #3271 decides whether a consumer needs one. |
| `core/observability/CrashReportingPort.kt` (+ `NoOpCrashReportingPort`) | **`:shared`** | The app's port. `report`/`addBreadcrumb` is an app contract; the library must not name it. |
| `core/observability/BackgroundCrashReporting.kt` | **`:shared`** | `BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY` is a dashboard contract, and `reportingScope(port)` composes app policy. Application wiring, not MVI. |
| `core/error/` — `AppError`, `Either`, `toMessage`, `runCatchingCancellable`, `TransportFailure` | **`:shared`** | Domain vocabulary. 538 references; seven error subtypes with dot-separated codes. Moving it would move the application into the library. |
| `core/ui/DraftMviViewModel.kt` | **`:shared`**, app-owned | Kermit, `AppError`/`Either` in `persist`, `toMessage`, two direct `crashReporter.report` calls with app issue keys, and app draft-persistence callbacks. Two consumers (`TaskCreateViewModel`, `NoteEditor`). Its generic-looking mechanics are *editor policy*: checkpoint-after-explicit-save-only, silent autosave, stale-write forgetting. Extracting them would need a draft-policy port invented for one application. |
| `core/ui/DraftState.kt` (+ `DraftUiState`, same file) | **`:shared`** | `internal` today; the whole point of extracting it would be to make it public, and no independently useful draft API exists. `DraftUiState` carries draft vocabulary (`isDirty`, `isSaveEnabled`), not MVI vocabulary. |
| `core/ui/featureSlot/FeatureSlot.kt` | **`:shared`** | A coordinator decomposition idiom *over* the runtime, not the runtime. Depends on `MviIntent` + `StateFlow` and would move without friction, but its 10 implementations all live under `feature/tasks` and `feature/timetracking` with zero external consumers; adding it would widen the library's public API for no behavioural gain. Revisit trigger: a second consumer needs the slot seam. |
| `core/ui/featureSlot/CombineStates.kt` | **`:shared`** | Exists because slots exist; coroutines-only, but it is the slot idiom's other half. Moves with `FeatureSlot`, or not at all. |
| `core/ui/components/UiEvent.kt` | **`:shared`** | Not MVI: a Compose/notification-layer marker that `NotificationHost` collects. `MviEvent` and `UiEvent` are different contracts and stay apart. |
| `core/ui/` presentation (`components/`, `theme/`, `text/`, `rich/`, `onboarding/`, …) | **`:shared`** | Compose. Out of scope by construction. |
| `core/refresh/RefreshViewModel.kt`, all `feature/**` ViewModels | **`:shared`** | Application. They consume `:mvi`; it must never consume them. |
| detekt rules naming `MviViewModel` / `DraftMviViewModel` | **`:detekt-rules`**, unchanged | They match on the raw type name, which the extraction does not change. See the enforcement note below. |

**Instrumentation (#3267).** That issue delivered no observer API (ADR
`2026-11-27-mvi-instrumentation-is-not-a-new-seam.md`), so there is no seam to carry. The only
diagnostic surface `:mvi` publishes beyond its behaviour is `MviDiagnostics.log`, which is a
*logging bridge*, not an observer: it reports two events the bus already records, and it cannot
be used to observe intents, completions, state transitions or consumption.

## The one dependency the extraction has to invent

`MviViewModel` today derives three things from a failure, all of them using app types:

| What the base class needs | Today | Where the knowledge lives |
|---|---|---|
| the throwable to report | `(error as? AppError)?.original() ?: error` | `AppError.original()` |
| the grouping key | `AppError.code`, falling back to the call-site label for `error.unknown` | `issueKeyFor` |
| the user-facing message | `error.toMessage(errorLabel)` | `AppError.message`, then `Throwable.message` |

All three are "unwrap the application's error". The library cannot keep them and cannot drop
them — four tests pin the behaviour, including "grouped by its domain code, not the call-site
label" and "the cause carries the stack trace". So the library owns the *shape* and the app owns
the *knowledge*:

```kotlin
// :mvi
interface MviDiagnostics {
    /** Names a failure: what to report, how to group it, what to say about it. */
    fun classify(error: Throwable, errorLabel: String): MviFailure

    /** Sends a handled failure where the application collects them. Must not throw or block. */
    fun report(error: Throwable, issueKey: String)

    /** Records a diagnosis line. Default is silent: a library has no sink of its own. */
    fun log(level: MviLogLevel, message: String)
}
```

One object rather than three parameters, because every `MviViewModel` subclass passes it once and
all three members answer the same question — what this app does when something fails. It also
keeps the *absence* assertion honest: the closed-bus test proves "a discard is never reported" by
injecting a recording implementation, exactly as it injects `CrashReportingPort` today.

The app supplies one implementation, `AppMviDiagnostics(port)` in `:shared`, which reproduces the
current semantics line for line and maps `MviLogLevel.Warn` to Kermit's `w`. The default,
`NoOpMviDiagnostics`, classifies as "report the throwable itself, group by the label, say
`Throwable.message ?: label`" — enough for a library consumer, and never reached by an app
ViewModel.

**Why not keep `AppError` out of the library by moving the policy into the report call.** The
port's `report(error, issueKey)` signature already happens to match what the base class needs, so
making `CrashReportingPort` extend a library interface was tempting. Rejected: it would force a
`classify` member onto nine implementations (four production — free Android, pro Android, JVM,
no-op — and five test doubles), and a default implementation in the library would silently produce
the wrong grouping key for whoever forgot to override it. The explicit adapter costs one line per
ViewModel, and it is wrong-loud rather than wrong-quiet.

## Lifecycle stays AndroidX — explicitly

`MviViewModel : androidx.lifecycle.ViewModel`, `addCloseable { … }` in the initializer,
`onCleared()` closes the bus. `:mvi` depends directly on
`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`, which is the **sole** source of ViewModel
lifecycle in the library. Three reasons:

1. **It is the same authority on both targets.** `androidx.lifecycle.ViewModel` is multiplatform
   in 2.11.0; Android and JVM Desktop share one type, one `ViewModelStore`, one teardown path.
   `MviViewModelTest` drives teardown through `ViewModelStore.clear()` because `ViewModel.clear()`
   is `internal` — that is the public path an owner actually uses, and a platform-neutral
   replacement would not have it.
2. **The current dependency is already transitive and accidental.** `:shared` declares no
   `lifecycle-viewmodel` dependency at all: `androidx.lifecycle.ViewModel` reaches `MviViewModel`
   through `lifecycle-viewmodel-compose`. The extraction is what makes this an explicit `api`
   dependency instead of an accident.
3. **Essenty's model is a different answer to a question this app does not have.** A
   platform-neutral `Lifecycle` would be a *second* lifecycle authority beside the one Nav3 and
   `ViewModelStore` already drive. Two authorities is worse than one dependency.

`addCloseable { }` rather than `addCloseable(scope)` is what makes removing the closeable scope type
possible: the SAM overload is already used in this tree (`AcpExternalAgentViewModel` registers a
subprocess that way), so the base class can cancel a `Job` without naming an app type.

`InstanceKeeper` is recorded as **not needed**: retention is already scoped by Navigation 3's
entry-scoped `ViewModelStore`. A parallel retention registry would be the second authority problem
again. And #3269's own boundary review says not to add `:mvi-lifecycle`: no non-AndroidX consumer
exists.

State restoration is #3273 and back-handler arbitration is #3274. Neither belongs in this module,
because both cross Compose, Nav3 and app persistence.

## Public API

```kotlin
package com.singularity.todo.core.ui

interface MviIntent
interface MviEvent

interface MviDiagnostics { /* classify / report / log, above */ }
object NoOpMviDiagnostics : MviDiagnostics

data class MviFailure(val reportable: Throwable, val issueKey: String, val message: String)
enum class MviLogLevel { Debug, Warn }

abstract class MviViewModel<S, I : MviIntent, E : MviEvent>(
    initialState: S,
    diagnostics: MviDiagnostics = NoOpMviDiagnostics,
    scope: CoroutineScope,                          // required, no default, no library factory
) : ViewModel() {
    val stateFlow: StateFlow<S>
    val state: StateFlow<S>
    val events: Flow<E>
    protected val currentState: S
    protected val vmScope: CoroutineScope
    protected fun updateState(transform: (S) -> S)
    protected fun setState(newState: S)
    protected suspend fun emit(event: E)
    protected fun tryEmit(event: E): Boolean
    protected fun catchTo(
        errorLabel: String,
        onError: suspend (String) -> Unit,
        onBeforeReport: suspend (Throwable) -> Unit = {},
        block: suspend () -> Result<*>,
    ): Job
    protected fun emitError(errorLabel: String, errorEvent: (String) -> E, block: suspend () -> Result<*>): Job
    abstract fun onIntent(intent: I)
    override fun onCleared()
}

class EventBus<E : MviEvent>(
    capacity: Int = Channel.BUFFERED,
    diagnostics: MviDiagnostics = NoOpMviDiagnostics,
) {
    val flow: Flow<E>
    val droppedAfterClose: Int
    suspend fun emit(event: E)
    fun tryEmit(event: E): Boolean
    fun close()
}

@JvmInline
value class IntentActions<I : MviIntent>(private val dispatch: (I) -> Unit) {
    operator fun invoke(intent: I)
}
```

That is the complete public library surface listed above. No coroutine helper, no closeable scope,
no factory, no test fixture.

**`scope` is required, and it stays the last parameter.** Required because the base class has no
business choosing a dispatcher or a failure destination — that is the rule the scope factory's own
KDoc already states, and no call site relies on the current default. Last because `VmScopePosition`
bans a `scope` parameter that is not last; `diagnostics` therefore precedes it, which is also the
order the detekt rule's own message prescribes.

**Visibility and API discipline.**

- Everything listed is `public` and was already public. Nothing new becomes public: the bus's
  refusal guard, its per-bus flag and `MviViewModel`'s `issueKeyFor`/`originalIfAppError` stay
  private, and their replacements move into the app adapter rather than onto the library surface.
- `extraEventCapacity` is **deleted** from `MviViewModel`. It has zero callers in the repository
  (only the bus's own `capacity` parameter is exercised, by tests), and it is a knob on the base
  class that no consumer ever turned. `EventBus(capacity)` keeps its parameter, because a
  consumer constructing a bus directly is a real case the tests already cover.
- The state names do not change: `stateFlow` is canonical, `state` is its read-only alias, and
  the backing flow stays private. ADR `2026-11-26-mvi-state-is-observed-read-only.md` pins both.
- `DraftMviViewModel`, `DraftState`, `DraftUiState`, `FeatureSlot`, `combineStates` and every
  `core.coroutines` helper keep their packages, because they stay in `:shared`. Splitting one
  package across two modules is legal for non-`sealed` types, and it is what keeps the diff a
  module move instead of a 120-file import rewrite — the same reasoning `:core-base` records for
  `com.singularity.todo.core.platform`.
- `vmScope` narrows from `AutoCloseableCoroutineScope` to `CoroutineScope`. 24 test call sites use
  `vmScope.close()` or `vmScope.job?.cancel()`; each already holds the scope it constructed and
  passed in, so they keep a local reference and call it there. Production uses neither (verified).
  The passed scope is still ViewModel-owned and must contain a `Job`; narrowing the exposed type
  does not relax the lifecycle contract.

**Semantics that must not move.** Read-only `StateFlow` observation; the two `final` state
writers; `Channel`-backed, buffered, single-consumer events with suspend-on-full and
`tryEmit`-never-blocks; graceful close, drain-after-close, drop counting, and no crash report for
a discard; `CancellationException` never absorbed; `scope.coroutineContext[Job]` required at
construction (fail loudly when missing); scope cancellation as the only teardown registration; a
fatal `Error` escaping to the scope's handler.

**Compatibility.** Source compatibility *inside* the repository is broken deliberately at three
points: the super-call gains `diagnostics` and requires `scope`, `extraEventCapacity` disappears,
and the bus takes a `MviDiagnostics` instead of a `CrashReportingPort` + `Logger`. No publishable
artifact exists, so binary compatibility, coordinates and versioning are #3271's problem, and only
if a second consumer appears.

## Why no `@Stable` on the bus

`@Stable` is a Compose annotation and Compose is not in the module. It is removable without
consequence: `EventBus` has exactly one production construction site — `MviViewModel`'s private
field — and no Compose parameter anywhere in the tree receives it (verified: no reference to
`EventBus` outside the base class and the tests). Composition stability is therefore unaffected,
and the compose stability configuration never listed it.

## Dependency boundary

- `kotlinx-coroutines-core` — the primary async dependency. It is `api` because `StateFlow`,
  `Flow`, `Job`, `CoroutineScope`, `Channel` and `CoroutineExceptionHandler` appear in public
  signatures, including the caller-supplied `scope`.
- AndroidX Lifecycle `lifecycle-viewmodel` — the **sole** source of ViewModel lifecycle. `:mvi`
  extends `androidx.lifecycle.ViewModel` and uses `addCloseable`/`onCleared`; it adds no
  `lifecycle-runtime`, `-compose`, `-navigation3`, or `savedstate`.
- `kotlinx-coroutines-test` — test dependency only. `runTest`/`TestScope`/`advanceUntilIdle` live
  in `commonTest`; never in production `commonMain`.
- Nothing app-specific in core: no Compose, Koin, Room, Navigation, domain, or app-specific error
  types (`AppError`, `Either`, `toMessage`, `CrashReportingPort`, `reportingScope`), no
  `com.singularity.todo.feature.*`, no Kermit/Koog/serialization/collections-immutable. `:adr-core`
  and `:core-base` are also not dependencies — the module needs no clock, no platform facts, no ADR
  storage.

| Dependency | Scope | Why exactly this |
|---|---|---|
| `kotlinx-coroutines-core` | **`api`** | Primary async dependency; scope and flow types are public. |
| `androidx-lifecycle-viewmodel` (`org.jetbrains.androidx.lifecycle`, 2.11.0) | **`api`** | Sole ViewModel lifecycle authority; appears in the public supertype. |
| `kotlinx-coroutines-test` | **test only** | Contract tests only; never in production `commonMain`. |
| `kotlin-junit5` + `junit-jupiter` | **test only** | Repository test stack, matching `:core-domain`. |

The catalog has **no** `androidx-lifecycle-viewmodel` alias today — only the `-compose` and
`-navigation3` variants — so #3270 adds it as one line in `gradle/libs.versions.toml`, consistent
with `scripts/build-version-catalog-gate.py`. The artifact resolves for both targets from the
version already pinned (`2.11.0`).

```
:mvi
  plugins     kotlinMultiplatform, androidMultiplatformLibrary, detekt
  targets     jvm(); android { compileSdk = libs.versions.sdk.compile, minSdk = libs.versions.sdk.min,
                              jvmTarget = JVM_11 }   with jvmToolchain(21)
  commonMain  api(libs.kotlinx.coroutines.core)
              api(libs.androidx.lifecycle.viewmodel)      ← catalog alias must be added
  commonTest  libs.kotlin.test.junit5, libs.junit.jupiter, libs.kotlinx.coroutines.test
  detektPlugins(project(":detekt-rules"))                  ← repository convention, every module
```

## Dependency graph

```
androidApp ──► pro
     └───────► shared ──► adr-core ──► core-base
desktopApp ──► shared
adr-cli ─────► adr-core ──► core-base

shared ──► mvi ──► (kotlinx-coroutines, androidx-lifecycle-viewmodel)
```

Acyclic, and `:mvi` has no edge back into anything of the application's. `pro` is unaffected: it
imports `CrashReportingPort`, which stays in `:shared`. Nothing outside `:shared` imports an MVI
type today (verified: no reference in `pro`, `mcp-server`, `androidApp` or `desktopApp`
production sources), so `:mvi` is a private dependency of `:shared` until #3271's fixture, and the
app modules need no change at all.

`easement.yml` must gain one component and one layer entry (`mvi` at the bottom of the order,
alone, since it depends on no project), plus `shared: may_depend_on: [mvi, adr-core, core-base]`.
That file is not edited here, and the easement skill forbids regenerating its baseline without a
human — #3270 needs that approval before the module can be built.

## Move map

Sources `:mvi` receives (package unchanged, `shared/` → `mvi/`):

```
core/ui/Markers.kt
core/ui/EventBus.kt                      (edited: -@Stable, -Kermit, -CrashReportingPort, +diagnostics)
core/ui/MviViewModel.kt                  (edited: -AppError/-helpers/-reporter/-defaulted scope/
                                                   -extraEventCapacity, required CoroutineScope, +diagnostics)
core/ui/IntentActions.kt
core/ui/MviDiagnostics.kt                (new)
```

Left behind in `:shared`, unchanged except for the adapter and the base-class super-call:

```
core/ui/DraftMviViewModel.kt           core/ui/DraftState.kt
core/observability/*                   core/error/*
core/ui/featureSlot/*                  core/coroutines/*        (all of it: scope, scope factory, testScope)
core/ui/AppMviDiagnostics.kt           (new: the app's MviDiagnostics)
```

Only five files move. Every coroutine file stays, which is the point of the ownership split above.

Tests: `EventBusTest`, `EventBusContractTest`, `MviViewModelTest`, `IntentActionsTest` move to
`:mvi`'s `commonTest`. `MviViewModelTest`'s `AppError`-specific cases move to `:shared`, where the
adapter lives; the library's own recording double is a local `MviDiagnostics`, because the library
must not depend on `shared/…/test/fakes`. `CrashReportingTest` and
`AutoCloseableCoroutineScopeTest` stay in `:shared` — they test the port, the app policy and the
scope helper, not the runtime.

## Migration order for #3270

Each step compiles and passes gates on its own, except where noted; no step introduces a
compatibility facade, and none survives the change.

1. **Module skeleton.** Catalog alias, `settings.gradle.kts` include, `mvi/build.gradle.kts`,
   easement approval + entry, `scripts/changed-modules.py` mapping. Nothing moved yet.
2. **The pure move.** `Markers.kt` and `IntentActions.kt` — typing only, no dependency decisions.
   Behaviour identical; only `shared/` → `mvi/` in the path.
3. **The seam, atomically.** Add `MviDiagnostics` + `NoOpMviDiagnostics` to `:mvi` and
   `AppMviDiagnostics` to `:shared`; move `EventBus` (drop `@Stable`/Kermit/reporter) and
   `MviViewModel` (error internals → `classify`, `scope: CoroutineScope` required with `Job`
   presence checked at construction, fail-loud on missing `Job`, `addCloseable { job.cancel() }`,
   drop `extraEventCapacity`). Update the 33 production subclasses + `DraftMviViewModel` + the test
   harnesses to pass `diagnostics = AppMviDiagnostics(crashReporter)` and a plain
   ViewModel-owned `CoroutineScope` containing a `Job`, and move the 24
   `vmScope.close()`/`vmScope.job` test sites onto their own scope reference. One commit, because
   the seam, the narrowed `vmScope` type and their only consumers cannot land apart.
4. **Contract tests move**, split as described above, and `:mvi`'s detekt/test tasks are wired
   into the gate suite (a module whose tests nobody runs is the "feature that works but is never
   called" defect, one layer down). Add an explicit contract test that constructing
   `MviViewModel` with a `CoroutineScope` lacking a `Job` fails loudly, and that teardown does not
   silently skip cancellation.
5. **Enforce the adapter.** Extend the existing `NoDivergentScopeAndReporter` rule — which
   already checks that a ViewModel's `scope` default derives from its `crashReporter` — to also
   require that a super-call passing `crashReporter` passes a `diagnostics` derived from it. This
   is the one real risk in the design (a new ViewModel that forgets the argument loses
   `AppError` grouping *and* user-facing messages, quietly), and it is the same defect shape that
   rule already exists to prevent.

**Verification that the boundary holds**, added to the arch tests: `:mvi`'s `commonMain` imports
nothing from `com.singularity.todo` outside its own packages, and names no Kermit, Compose, Koin,
Room, Navigation, `AppError` or `CrashReportingPort` type — and no `core.coroutines` type either,
which is the new half of the assertion and the one that keeps a future "helpful" helper out. That
assertion is the whole acceptance criterion, expressed as something a build can fail on. Note that
Konsist's shared scope (`CommonMainKonsistScope`) is built from `commonMain.root`,
`coreDomain.root` and `coreDatabase.root` — `:shared`, `:core-domain` and `:core-database`, not
`:mvi` — so #3270 must add a fourth root (and its `jvmTest` build property, which the object reads
through `System.getProperty`) or the boundary test is inert. That scope's own KDoc records why a
module missing from it is the failure mode to watch: it reports green while reading less code.

## What would falsify this design

#3271's consumer fixture is the test, and it only counts if it is built honestly:

- It depends on `:mvi` **only** — not on `:shared`, not on `com.singularity.todo`, and not on
  app-specific coroutine factories (`reportingScope`, `createBackgroundScope`,
  `AutoCloseableCoroutineScope`, `testScope`) or app types (`AppError`, `CrashReportingPort`).
- It defines its own state, intent and event types, its own `MviDiagnostics` (or accepts
  `NoOpMviDiagnostics`), its own ViewModel-owned scope with an explicit child `Job` of the test
  scope, e.g. `val job = SupervisorJob(testScope.coroutineContext[Job]); CoroutineScope(testScope.coroutineContext + job)`
  (contains a `Job`, not shared, not an independent `SupervisorJob()`), and its own test using
  `runTest` + `TestScope` from `kotlinx-coroutines-test`.
- It compiles and runs for both targets.

If the fixture needs a coroutine helper, a closeable scope or a test wrapper to be written at all,
that is evidence the boundary is wrong — and the fix belongs in the design, not in a convenience
export from `:mvi`. A fixture that reaches into `:shared` proves files moved; it does not prove the
library is independent.

## Pre-merge checks

- [ ] Links and line references in this doc and the ADR resolve.
- [ ] MVI lifecycle scope vs application background scope is unambiguous: ViewModel-owned, Job required, fail-loud on missing Job, no shared/application scope passed to `MviViewModel`.
- [ ] `kotlinx-coroutines-test` remains test-only; no `testScope()`/test wrapper is promised from `:mvi` production `commonMain`.
- [ ] Crash reporting, Kermit, `AppError`, and DraftStore are not declared `:mvi` dependencies.
- [ ] AndroidX Lifecycle remains the sole lifecycle contract source.
- [ ] Independent consumer fixture (#3271) requires no `:shared`, app packages, or app coroutine factories.
- [ ] Existing documentation and architectural-constraint checks pass.

## Links

- Epic #3268; this stage #3269; extraction #3270; consumer proof #3271; out of scope here:
  state restoration #3273, back-handler arbitration #3274.
- ADR `docs/decisions/2026-11-27-mvi-library-module-boundary.md`.
- Direction ADR `2026-11-27-mvi-architecture-direction.md` — no framework swap, no plugin layer.
- `docs/architecture/mvi-ownership.md` — who owns the state, the events and the scope.
- `docs/decisions/2026-11-26-mvi-state-is-observed-read-only.md` — the two state names.
- `docs/decisions/2026-11-27-mvi-instrumentation-is-not-a-new-seam.md` — why there is no observer
  API to carry.
- `docs/decisions/2026-10-22-an-event-bus-has-one-consumer.md` — the bus's one-consumer contract.
