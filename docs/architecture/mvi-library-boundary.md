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
`EventBus`, the typed dispatch wrapper, and the closeable scope with its two generic helpers.
It depends on kotlinx-coroutines and AndroidX `ViewModel` — and on nothing else. The app's error
vocabulary, Kermit logging and crash reporter do not move: the runtime reaches them through one
app-supplied object, `MviDiagnostics`, whose default is silent. `DraftMviViewModel` and the
coordinator slot idiom stay in `:shared`, because both encode app policy rather than MVI mechanics.

## Source classification

Every candidate, with the destination that follows from what it actually depends on.

| Source | Destination | Rationale |
|---|---|---|
| `core/ui/Markers.kt` — `MviIntent`, `MviEvent` | **`:mvi`** | Typing only, zero dependencies. Deliberately not `sealed`, so the same package split across two modules stays legal. |
| `core/ui/EventBus.kt` | **`:mvi`**, decoupled | Channel semantics, backpressure, graceful close and the drop counter are the contract. Loses three dependencies: the Compose `@Stable` annotation, the Kermit `Logger`, and the never-called `CrashReportingPort`. |
| `core/ui/MviViewModel.kt` | **`:mvi`**, decoupled | The loop itself: one state flow, two final writers, an `onIntent` entry point. Keeps `ViewModel` (see below). Loses `AppError`, the error helpers and the crash reporter; gains `MviDiagnostics`. |
| `core/ui/IntentActions.kt` | **`:mvi`** | Typed dispatch over `MviIntent`; its only import is the marker. |
| `core/coroutines/AutoCloseableCoroutineScope.kt` | **`:mvi`** | Coroutines only. AndroidX appears in its KDoc, never in its code. |
| `core/coroutines/BackgroundScope.kt` — `backgroundFailureHandler`, `createBackgroundScope` | **`:mvi`** | Generic coroutine policy, and the scope default is built from them. |
| `core/coroutines/BackgroundScope.kt` — `loggingBackgroundFailureHandler` | **`:shared`** | Names Kermit and a tag. It is a *logging* policy, not a scope policy; the file splits along that line. |
| `core/coroutines/testScope.kt` | **`:mvi`** | Wraps any `CoroutineScope` as a closeable one. The library's own contract tests need it, and a consumer writing a ViewModel test needs exactly this, so it stays public in `commonMain` — Kotlin has no shareable test-fixture source set across modules. Nothing app-specific in it. |
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

`MviViewModel : androidx.lifecycle.ViewModel`, `addCloseable(scope)` in the initializer,
`onCleared()` closes the bus. `:mvi` depends directly on
`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`. Three reasons:

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
    scope: AutoCloseableCoroutineScope = mviScope(diagnostics),
) : ViewModel() {
    val stateFlow: StateFlow<S>
    val state: StateFlow<S>
    val events: Flow<E>
    protected val currentState: S
    protected val vmScope: AutoCloseableCoroutineScope
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

package com.singularity.todo.core.coroutines

class AutoCloseableCoroutineScope(override val coroutineContext: CoroutineContext) :
    AutoCloseable, CoroutineScope {
    val job: Job?
    override fun close()
}

fun backgroundFailureHandler(onFailure: (Throwable) -> Unit): CoroutineExceptionHandler
fun createBackgroundScope(failureHandler: CoroutineExceptionHandler): CoroutineScope
fun testScope(scope: CoroutineScope): AutoCloseableCoroutineScope
fun testScope(context: CoroutineContext): AutoCloseableCoroutineScope
fun mviScope(diagnostics: MviDiagnostics): AutoCloseableCoroutineScope
```

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
- `DraftMviViewModel`, `DraftState`, `DraftUiState`, `FeatureSlot` and `combineStates` keep their
  packages even though they stay in `:shared`. Splitting one package across two modules is legal
  for non-`sealed` types, and it is what keeps the diff a module move instead of a 120-file import
  rewrite — the same reasoning `:core-base` records for `com.singularity.todo.core.platform`.

**Semantics that must not move.** Read-only `StateFlow` observation; the two `final` state
writers; `Channel`-backed, buffered, single-consumer events with suspend-on-full and
`tryEmit`-never-blocks; graceful close, drain-after-close, drop counting, and no crash report for
a discard; `CancellationException` never absorbed; `addCloseable` as the only teardown
registration; a fatal `Error` escaping to the scope's handler.

**Compatibility.** Source compatibility *inside* the repository is broken deliberately at three
points: the super-call gains `diagnostics`, `extraEventCapacity` disappears, and the bus takes a
`MviDiagnostics` instead of a `CrashReportingPort` + `Logger`. No publishable artifact exists, so
binary compatibility, coordinates and versioning are #3271's problem, and only if a second
consumer appears.

## Why no `@Stable` on the bus

`@Stable` is a Compose annotation and Compose is not in the module. It is removable without
consequence: `EventBus` has exactly one production construction site — `MviViewModel`'s private
field — and no Compose parameter anywhere in the tree receives it (verified: no reference to
`EventBus` outside the base class and the tests). Composition stability is therefore unaffected,
and the compose stability configuration never listed it.

## Gradle

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

`api` rather than `implementation` for both, because they appear in public signatures:
`ViewModel` is the supertype, and `StateFlow`/`Flow`/`Job`/`CoroutineScope` are parameter and
return types.

The catalog has **no** `androidx-lifecycle-viewmodel` alias today — only the `-compose` and
`-navigation3` variants — so #3270 adds it as one line in `gradle/libs.versions.toml`, consistent
with `scripts/build-version-catalog-gate.py`. The artifact resolves for both targets from the
version already pinned (`2.11.0`).

Not in the module: Compose, Kermit, Koin, Room, Navigation, kotlinx.collections.immutable,
serialization, Koog, any `feature.*` or `core.domain` type. `:adr-core` and `:core-base` are not
dependencies either — the module needs no clock, no platform facts and no ADR storage.

## Dependency graph

```
androidApp ──► pro
     └───────► shared ──► adr-core ──► core-base
desktopApp ──► shared
adr-cli ─────► adr-core ──► core-base

shared ──► mvi ──► (kotlinx-coroutines, androidx-lifecycle-viewmodel)
```

Acyclic, and `:mvi` has no edge back into anything of the application's. `pro` is unaffected: it
imports `CrashReportingPort`, which stays in `:shared`.

`easement.yml` must gain one component and one layer entry (`mvi` at the bottom of the order,
alone, since it depends on no project), plus `shared: may_depend_on: [mvi, adr-core, core-base]`.
That file is not edited here, and the easement skill forbids regenerating its baseline without a
human — #3270 needs that approval before the module can be built.

## Move map

Sources `:mvi` receives (package unchanged, `shared/` → `mvi/`):

```
core/ui/Markers.kt
core/ui/EventBus.kt                      (edited: -@Stable, -Kermit, -CrashReportingPort, +diagnostics)
core/ui/MviViewModel.kt                  (edited: -AppError/-helpers/-reporter/-extraEventCapacity, +diagnostics)
core/ui/IntentActions.kt
core/ui/MviDiagnostics.kt                (new)
core/coroutines/AutoCloseableCoroutineScope.kt
core/coroutines/BackgroundScope.kt       (split: two functions leave, loggingBackgroundFailureHandler stays)
core/coroutines/MviScope.kt              (new: mviScope(diagnostics))
core/coroutines/testScope.kt
```

Left behind in `:shared`, unchanged except for the adapter:

```
core/ui/DraftMviViewModel.kt   core/ui/DraftState.kt
core/observability/*           core/error/*
core/ui/featureSlot/*          core/coroutines/LoggingBackgroundFailureHandler.kt  (new home of the logging policy)
core/ui/AppMviDiagnostics.kt   (new: the app's MviDiagnostics)
```

Tests: `EventBusTest`, `EventBusContractTest`, `MviViewModelTest`, `IntentActionsTest` and
`AutoCloseableCoroutineScopeTest` move to `:mvi`'s `commonTest`. `MviViewModelTest`'s
`AppError`-specific cases move out to `:shared`, where the adapter lives; the library's own
recording double is a local `MviDiagnostics`, because the library must not depend on
`shared/…/test/fakes`. `CrashReportingTest` stays in `:shared` — it tests the port and the app
policy, not the runtime.

## Migration order for #3270

Each step compiles and passes gates on its own, except where noted; no step introduces a
compatibility facade, and none survives the change.

1. **Module skeleton.** Catalog alias, `settings.gradle.kts` include, `mvi/build.gradle.kts`,
   easement approval + entry, `scripts/changed-modules.py` mapping. Nothing moved yet.
2. **Pure moves.** `Markers.kt`, `IntentActions.kt`, `AutoCloseableCoroutineScope.kt`,
   `testScope.kt`, and the `backgroundFailureHandler`/`createBackgroundScope` half of
   `BackgroundScope.kt` (the Kermit half lands in `:shared` under its own name). Behaviour
   identical; only `shared/` → `mvi/` in the path.
3. **The seam, atomically.** Add `MviDiagnostics` + `NoOpMviDiagnostics` to `:mvi` and
   `AppMviDiagnostics` to `:shared`; move `EventBus` (drop `@Stable`/Kermit/reporter) and
   `MviViewModel` (error internals → `classify`, drop `extraEventCapacity`). Update the 33
   production subclasses + `DraftMviViewModel` + the test harnesses to pass
   `diagnostics = AppMviDiagnostics(crashReporter)`. One commit, because the seam and its only
   consumer cannot land apart.
4. **Contract tests move**, split as described above, and `:mvi`'s detekt/test tasks are wired
   into the gate suite (a module whose tests nobody runs is the "feature that works but is never
   called" defect, one layer down).
5. **Enforce the adapter.** Extend the existing `NoDivergentScopeAndReporter` rule — which
   already checks that a ViewModel's `scope` default derives from its `crashReporter` — to also
   require that a super-call passing `crashReporter` passes a `diagnostics` derived from it. This
   is the one real risk in the design (a new ViewModel that forgets the argument loses
   `AppError` grouping *and* user-facing messages, quietly), and it is the same defect shape that
   rule already exists to prevent.

**Verification that the boundary holds**, added to the arch tests: `:mvi`'s `commonMain` imports
nothing from `com.singularity.todo` outside its own packages, and does not name Kermit, Compose,
Koin, Room, Navigation, `AppError` or `CrashReportingPort`. That assertion is the whole
acceptance criterion, expressed as something a build can fail on. Note that Konsist's shared scope
(`CommonMainKonsistScope`) is built from `commonMain.root`, `coreDomain.root` and
`coreDatabase.root` — `:shared`, `:core-domain` and `:core-database`, not `:mvi` — so #3270 must
add a fourth root (and its `jvmTest` build property, which the object reads through
`System.getProperty`) or the boundary test is inert. That scope's own KDoc records why a
module missing from it is the failure mode to watch: it reports green while reading less code.

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
