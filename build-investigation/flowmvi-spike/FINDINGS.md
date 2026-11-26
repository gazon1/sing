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

## Gate 2 — Koin 4.2.2 and Navigation 3 1.2.0 ownership: **passed, with friction**

`resolveProof/.../Ownership.kt` compiles `koinViewModel`, `viewModel` from
`org.koin.core.module.dsl`, and a Koin graph that provides a store-owning ViewModel.
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

`spike/src/jvmTest: 13 tests, 0 failures`.

Variant A is not a re-implementation: the app's own `MviViewModel`, `EventBus`,
`Markers`, `AppError`, `RunCatching`, `AutoCloseableCoroutineScope` and
`CrashReportingPort` are staged into the probe by a `Sync` task whose **inputs are those
files**, so editing production invalidates the staging. Variant B is FlowMVI configured
the way the assessment said is fair, not at its defaults: `parallelIntents = false`,
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

### 2. Atomic state — both correct

200 increments issued back-to-back are all applied by both: in-repo via
`MutableStateFlow.update` (CAS), FlowMVI via FIFO-serialized transactions under the
default `Atomic` strategy. Neither loses a write.

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

### 5. Action delivery with no subscriber — no loss, in the pinned mode

`an action emitted before a subscriber is delivered once one subscribes` passes. The
assessment warned that `Share` loses actions with no subscriber; `Distribute` — the mode
pinned here and the one closest to the app's single-consumer bus — is a channel, so it
buffers. The app's post-close/drop counter (`droppedAfterClose`) still has no equivalent,
but for the delivery question the two agree.

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
../../gw -p . :spike:jvmTest                                         # 13 tests, 0 failures
../../gw -p . :resolveProof:compileKotlinJvm :resolveProof:compileAndroidMain
```

## What this does not prove

- No Compose UI test: the Compose integration is proven to **compile** and to deliver
  states/actions to a subscriber, not to render. No screen was driven.
- No Room. The repository is a `FakeTaskSource`; a real Room `Flow` would add threading
  the fake does not reproduce.
- No instrumentation on Android: the Android side is a compile proof only.
- Performance was not measured; the issue did not ask for it.
