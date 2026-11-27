# #3234 — FlowMVI versus the in-repo MVI: measured findings

Date: 2026-11-27. Baseline: FlowMVI **3.2.1** (latest stable, per the assessment),
this repository at `6e783d70` (Kotlin 2.4.10, Compose Multiplatform 1.12.2,
coroutines 1.11.0, Koin 4.2.2, Navigation 3 1.2.0).

## Verdict

**No-go for adoption.** FlowMVI 3.2.1 does build here and does reproduce the Task List
behavior — but it costs a Compose/runtime downgrade that has to be pinned back by hand,
its lifecycle does not fit the app's ViewModel-scoped ownership, and its own startup
semantics silently swallowed every intent in the first working version of the spike.
None of those is fatal on its own; together they buy nothing the app does not already
have, since the behavior comparison found **no user-visible difference**.

The spike is kept as evidence and deleted when the issue is closed.

## Gate 1 — resolution and build (the issue's stop/go gate): **passed, with a caveat**

FlowMVI 3.2.1 core + compose resolve and compile on the app's toolchain for **both**
targets, with no unrelated toolchain upgrade:

| Target | Task | Result |
|---|---|---|
| JVM Desktop | `:resolveProof:compileKotlinJvm` | pass |
| Android | `:resolveProof:compileAndroidMain` | pass |

The caveat is the one the assessment predicted. FlowMVI's `compose` artifact declares
Compose **1.10.0-beta01** and lifecycle **2.9.5**; on a tree that pins Compose
**1.12.2** and lifecycle **2.11.0** it silently wins the version conflict:

```
w: Compose Multiplatform runtime dependencies' versions don't match with plugin version.
    expected: 'org.jetbrains.compose.foundation:foundation:1.12.2'
    actual:   'org.jetbrains.compose.foundation:foundation:1.10.0-beta01'
    expected: 'org.jetbrains.compose.ui:ui:1.12.2'
    actual:   'org.jetbrains.compose.ui:ui:1.10.0-beta01'
```

Forcing the app's pin back (`resolutionStrategy.eachDependency { useVersion("1.12.2") }`)
compiles and tests fine, so the versions are not binary-incompatible — but every consumer
has to know to write that block. A dependency that downgrades the app's UI runtime unless
you defend against it is a standing tax, and Gradle reports it as a warning, not an error.

Resolved graph, measured rather than read from the README:

```
pro.respawn.flowmvi:core:3.2.1     -> core-jvm:3.2.1
  +- kotlinx-coroutines-core 1.10.2 -> 1.11.0   (conflict, resolved up)
  +- kotlin-stdlib 2.2.21 -> 2.4.10             (conflict, resolved up)
  +- kotlinx-atomicfu 0.30.0-beta               (new transitive)
pro.respawn.flowmvi:compose:3.2.1  -> compose-jvm:3.2.1
  +- lifecycle-runtime 2.9.5, lifecycle-runtime-compose 2.9.5
  +- compose runtime/foundation 1.10.0-beta01
```

So the README's "core has only coroutines as a dependency" is false for the published
artifact: `atomicfu` comes with it, and it drags the app's Compose *and* lifecycle
versions backwards. `atomicfu` 0.28.0 (already in the app via Compose) is also forced to
0.30.0-beta.

## Gate 2 — Koin 4.2.2 and Navigation 3 1.2.0 ownership: **passed, measured**

`Ownership.kt` compiles `koinViewModel`, `viewModel` from `org.koin.core.module.dsl`, and
a Koin graph that provides a store-owning ViewModel. `ProofStoreHolder` **starts** the
store in `init` and **closes** it in `onCleared`: a holder that only holds proves nothing,
because an unstarted store reduces no intents and an unclosed one outlives its destination.

`DestinationOwnershipTest` drives the real thing — a `NavDisplay` with
`rememberViewModelStoreNavEntryDecorator` (the decorator the app's nested graphs apply),
the holder resolved through `koinViewModel`, and three claims asserted in order:

| Claim | Assertion | Mutating it |
|---|---|---|
| the entry owns its ViewModel store | entry store ≠ composition store | — |
| the holder starts the store | an intent from the entry reaches the published state | removing `start` fails the test |
| popping the destination stops the store | `store.isActive == false` after the pop | removing `onCleared`'s teardown fails the test |

The residue is structural, not a compile error:

- **A FlowMVI store is not a ViewModel.** The app scopes a destination with
  `rememberViewModelStoreNavEntryDecorator`, which clears the ViewModels that destination
  owns when its entry leaves the back stack. A store created outside a ViewModel is
  outside that scope, so the app needs a wrapper ViewModel per destination — the one
  `ProofStoreHolder` in the proof — or the store outlives the destination.
- Koin's documented FlowMVI helpers are 4.1.x-based; at 4.2.2 they compile, and
  `koinViewModel { parametersOf(...) }` takes the store's configure lambda as a runtime
  parameter. That works, and it is one more layer than `viewModelOf(::Vm)`.

## Behavior: the six comparable scenarios are **identical**

One contract (`spike/scenario/Scenario.kt`), two implementations, one suite. The suite
runs every case against both and asserts the *observable* results are equal. Measured
outputs, taken from a run that printed both sides:

| Scenario | in-repo `MviViewModel` | FlowMVI 3.2.1 |
|---|---|---|
| initial load | `[Write the spike, Read the assessment]` | same |
| filter changes | `[]` | same |
| task completion | `[Write the spike, Read the assessment]` | same |
| Room Flow updates | `[…, Inserted elsewhere]` | same |
| loading/error | `[Write the spike, Read the assessment]` | same |
| rapid intents (20) | `filter=Completed, rows=0` | same |

`spike`: 18 tests, 0 failures (11 semantic-gap + 6 comparison + 1 fatal-`Error` contract,
the last in its own JVM fork). `resolveProof`: 1 test, 0 failures. 19 in total.

Variant A is not a re-implementation: the app's own `MviViewModel`, `EventBus`,
`Markers`, `AppError`, `RunCatching`, `AutoCloseableCoroutineScope` and
`CrashReportingPort` are staged into the probe by a `Sync` task whose **inputs are those
files**, so editing production invalidates the staging. Variant B — the store the
scenario suite drives — is FlowMVI configured the way the assessment said is fair, not at
its defaults: `parallelIntents = false`,
`intentCapacity = Channel.UNLIMITED`, `onOverflow = SUSPEND`, default
`StateStrategy.Atomic(reentrant = true)`, `ActionShareBehavior.Distribute`. The defaults
drop intents (`DROP_OLDEST`) — that difference is a configuration choice, not
equivalent behavior, so it was not used.

## The semantic gaps, measured

### 1. Cancellation — FlowMVI is correct

`FlowMVI does not offer cancellation to recovery` passes: a `CancellationException` thrown
from a reducer is **not** passed to the `recover` hook, so the plausible
`catch (e: Exception)` an application would write there cannot swallow it. An ordinary
`IllegalStateException` *is* offered (separate test). This matches
`runCatchingCancellable` in the app, which re-throws cancellation and catches
`Exception`. No gap.

### 1b. Fatal JVM errors — FlowMVI is *worse*, and silently

`runCatchingCancellable` catches `Exception` only, so an `Error` propagates out of the
in-repo loop. `FlowMVI does not offer a fatal JVM error to recovery` confirms `recover`
never sees it either — but the rest of the measurement is the finding:

- the pipeline stops reducing: every intent sent afterwards is dropped;
- **nothing reports it** — not `recover`, not the store's own
  `CoroutineExceptionHandler`, not the owner scope's;
- `store.isActive` still answers `true`, so the store looks alive while doing nothing;
- `close()` does not throw.

That is the same failure mode as the `init` trap below: a dead store that reports no
error. The in-repo loop at least lets the error reach the scope's uncaught-exception
handler. This test lives in `FatalErrorContractTest`, in **its own JVM fork** — the error
resurfaces on an unrelated thread and `runTest` otherwise fails whichever test runs next
with `UncaughtExceptionsBeforeTest`.

### 2. Atomic state — both correct

200 increments from 200 genuinely concurrent writers are all applied by both: in-repo via
`MutableStateFlow.update` (CAS), FlowMVI via its atomic state strategy. That test builds
its own store with `parallelIntents = true` — the scenario store is sequential, which
would test FlowMVI's queue rather than its concurrent writes. Neither loses a write.

The first version of this test measured `mirrored.get()` — a counter the reducer
incremented beside `updateState` — so it passed even when the store applied nothing.
`neither framework loses a concurrent increment` now reads the state each side actually
published: the in-repo `StateFlow.value`, and the FlowMVI state delivered to a subscriber.
Mutating the reducer to drop the `updateState` call fails the test; it passed before.

### 3. Store start/stop/restart — FlowMVI has a capability the app has no equivalent for

A FlowMVI store survives `close()` and a later `start()` **with its state intact**; the
in-repo loop cannot, because `addCloseable(scope)` makes teardown final and a new
ViewModel instance is the only way back. This is a genuine difference, and it is the
reason the two ownership models are not interchangeable — not a FlowMVI advantage for
this app, which wants teardown to be final.

A related, subtler difference: a **new** FlowMVI subscriber receives the retained state
asynchronously, whereas a new collector of a `StateFlow` reads `.value` immediately. The
first version of the restart test failed for exactly this reason.

### 4. Screen leave/return — no gap

The store keeps running while the subscriber is away, and the returning subscriber sees
the change it missed (`a returning subscriber sees what changed while it was away`
passes). The in-repo loop behaves the same through `stateFlow`, which is a
`StateFlow`.

### 5. Action delivery — three of the four cases match; the counter does not exist

Four focused tests, all discriminating (switching `Distribute` to `Share` fails every one
of them):

| Case | in-repo `EventBus` | FlowMVI `Distribute` |
|---|---|---|
| action emitted before any subscriber | buffered, delivered | same |
| subscriber cancelled, action arrives | next subscriber gets it | same |
| close with actions buffered | delivered after close | same |
| buffer saturation | `emit` suspends, never drops | `DROP_OLDEST` drops silently; `SUSPEND` does not |
| count of discards | `droppedAfterClose: Int` | **no equivalent, in any mode** |

So the assessment's one open question — the missing `droppedAfterClose` equivalent — is
answered: it is genuinely missing. A discarded action is indistinguishable from one that
was never emitted, and the only way to avoid discards is to configure `SUSPEND`.

### 6. The finding that is not on the assessment's list: `init` silently eats intents

The first FlowMVI variant used `init { source.watch { … } }` — the obvious reading, and
the one the docs' "run when the store starts" suggests. It compiled, ran, and applied
**no intent at all**: `init` blocks the store's startup until its block returns, and
`watch` never returns. The store sits in `starting` forever, so nothing is ever
dequeued, and there is no error, no warning and no exception. Measured before the fix:

```
B loaded:      Ready(rows=[2 rows], filter=All)
B after filter: Ready(rows=[2 rows], filter=All)   <- intent ignored
B after complete: Ready(rows=[2 rows], filter=All) <- intent ignored
```

`asyncInit` is the correct pairing, and after the switch the same three lines show the
filter and the completion applied. This is a real hazard for exactly the migration the
app would be making — a repository subscription is *always* long-lived — and it is
silent.

## Cost of the trade

| | in-repo | FlowMVI |
|---|---|---|
| Framework LOC in the probe | 106 (`VariantA.kt`) | 139 (`VariantB.kt`) |
| Infrastructure the app owns | 477 (`MviViewModel` + `EventBus` + `Markers`) | 0 (library) |
| New transitive dependencies | none | `atomicfu` 0.30.0-beta; Compose and lifecycle held back unless pinned |

**LOC was not used as the deciding metric**, per the issue. The numbers above are here
because the issue asks for them, and they argue in FlowMVI's favour on paper: 139 lines
of feature code against 477 lines of infrastructure the app has to own. What they do not
show is that the 477 lines are already written, tested (`EventBusContractTest`,
`MviViewModelTest`, `DraftMviViewModelTest`) and encode contracts this app depends on —
cancellation that propagates, a drop counter, a graceful close — some of which FlowMVI
only matches by coincidence of configuration and none of which it documents.

## Recommendation

**Close #3234 as not planned for adoption.** Concretely:

1. The spike proves FlowMVI 3.2.1 is *usable* here, so the question the issue asked is
   answered rather than blocked.
2. It also proves the migration would buy no user-visible behavior, while adding a
   Compose/lifecycle downgrade to defend against, an ownership wrapper per destination,
   and a silent-startup hazard.
3. Nothing in the evidence justifies the risk on the app's core loop. The in-repo MVI is
   not the thing to replace first.

If FlowMVI is ever revisited, the questions that would change the answer are:
a stable release built against Compose 1.12+ (so the pin fight goes away), and docs or a
plugin that makes a long-lived repository subscription impossible to get wrong.

## Reproducing

The probe has no wrapper of its own (a `gradle-wrapper.jar` is gitignored), so it runs
through the repository's wrapper with `-p`:

```bash
cd build-investigation/flowmvi-spike
../../gw -p . :spike:jvmTest :spike:jvmTestFatalErrors                # 18 tests, 0 failures
../../gw -p . :resolveProof:jvmTest                                  # 1 test, 0 failures
../../gw -p . :resolveProof:compileKotlinJvm :resolveProof:compileAndroidMain
```

`:spike:jvmTestFatalErrors` is a separate fork on purpose: `FatalErrorContractTest`
provokes a JVM-fatal `Error`, which FlowMVI swallows but which resurfaces on an unrelated
thread. Sharing a JVM with the other tests turns that into an
`UncaughtExceptionsBeforeTest` in whichever test runs next.

## What this does not prove

- No app screen was driven: the Compose integration is proven to compile, to deliver
  states/actions to a subscriber, and (in `DestinationOwnershipTest`) to start and stop a
  store under a real `NavDisplay`. Nothing renders a production screen.
- No Room. The repository is a `FakeTaskSource`; a real Room `Flow` would add threading
  the fake does not reproduce.
- No instrumentation on Android: the Android side is a compile proof only.
- Performance was not measured; the issue did not ask for it.
