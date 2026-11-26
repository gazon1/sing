# FlowMVI compatibility assessment (2026-11-27)

## Decision

**GO for the isolated #3234 spike; no production adoption decision yet.** Use the latest stable Maven Central release, **3.2.1**, as the comparison baseline. Do not use the unreleased master snapshot or silently substitute a newer prerelease.

All prerequisite contract work is complete: #3226–#3232 and #3256 are closed. In particular, the Draft comparison now starts from the corrected save/autosave/discard behavior rather than the known resurrection defect.

## Version and compatibility matrix

| Area | Singularity Todo baseline | FlowMVI evidence | Assessment |
|---|---|---|---|
| Kotlin | 2.4.10 | Newest published prerelease inspected: 3.3.0-alpha03 POM declares Kotlin stdlib 2.2.21. Current upstream master declares Kotlin 2.5.0-RC and library version 3.4.0. | 3.2.1 is a plausible binary-compatible candidate; exact Gradle resolution/build must be proven in #3234. Do not infer compatibility from master. |
| Coroutines | 1.11.0 | Published 3.3.0-alpha03 POM declares coroutines 1.10.2; current upstream source catalog uses 1.11.0. | Likely compatible via Gradle conflict resolution, but inspect the resolved graph and run concurrency tests. |
| Android + JVM Desktop | Android and JVM Desktop only | FlowMVI publishes KMP core JVM/Android variants; its Compose module configures both jvm and Android. | Supported targets exist; validate both actual project targets. |
| Compose Multiplatform | 1.12.2 | Dedicated pro.respawn.flowmvi:compose artifact; current upstream source is built against Compose 1.13.0-rc01. | API integration exists, but Compose artifact/runtime alignment is a material spike risk. Verify resolved versions and both target builds. |
| Lifecycle | AndroidX Lifecycle 2.11.0 | Compose integration uses lifecycle-aware subscription; store lifetime is controlled by start(scope)/stop. Docs recommend ViewModel scope on Android and rememberCoroutineScope() on Desktop. | Feasible, but not a drop-in replacement for current ViewModel addCloseable(AutoCloseableCoroutineScope) ownership. |
| Koin | 4.2.2 | Official DI page documents Koin 4.1.x helpers based on ContainerViewModel / koinViewModel. | Integration pattern exists; 4.2.2 compatibility and project-specific scope semantics need compile/runtime verification. |
| Navigation 3 | Navigation 3 1.2.0 | Upstream README claims Nav3 integration; DI docs explain destination-scoped ViewModel ownership but do not provide a dedicated Nav3 adapter contract. | Treat as manual integration, not proven turnkey compatibility. The spike must preserve destination ownership and back-stack behavior. |
| License | — | Apache-2.0 in upstream README and Maven metadata. | Compatible. |

**Version distinction:** Maven Central metadata identifies 3.3.0-alpha03 as the newest published artifact in the inspected release line, while 3.2.1 is the latest stable release. Current upstream source declares 3.4.0 and Kotlin 2.5.0-RC; that is not evidence that 3.4.0 is a published release. Pin 3.2.1 for a stable-baseline comparison. Use 3.3.0-alpha03 only as a non-baseline reference if stable API lacks a required capability.

## Semantic comparison against protected in-repo contracts

### State

- Current MviViewModel exposes read-only StateFlow; writes are restricted to updateState (CAS-based MutableStateFlow.update) and setState.
- FlowMVI exposes immutable MVIState through its Store and uses StateFlow internally. Its default StateStrategy.Atomic(reentrant = true) serializes suspendable state transactions in FIFO order; Immediate explicitly removes consistency/thread-safety guarantees.
- These are not identical transaction models. The spike should use default Atomic and test concurrent updates; do not compare against Immediate.

### Intent processing

- Current project dispatches through onIntent; feature work is launched on the ViewModel-owned scope. The contract suite covers scope cancellation.
- FlowMVI defaults to sequential intent processing (parallelIntents = false), with an unlimited intent queue and DROP_OLDEST as the configured overflow policy. For a fair comparison, explicitly choose sequential processing and Channel.UNLIMITED. If the spike uses a bounded queue, set BufferOverflow.SUSPEND to avoid silently dropping user intents.

### One-shot Actions / EventBus

- Current EventBus is a single-consumer Channel.receiveAsFlow(): multiple collectors split events, emit suspends under buffer pressure, tryEmit reports failure, close drains buffered events, and a send already suspended at close remains pending until it can send or is cancelled. New sends after close are absorbed and counted.
- FlowMVI defaults to ActionShareBehavior.Distribute() with a bounded buffer (64) and suspending overflow. Each action is delivered to one subscriber; with multiple subscribers the recipient is unspecified. With exactly one subscriber, this is close to a single-consumer bus.
- FlowMVI docs explicitly say Actions are fire-and-forget and are not guaranteed to be received; do not use Actions for critical business state. The FAQ warns that Share can lose actions when there are no subscribers. Keep Distribute, use one action subscriber in the spike, and test emission before subscription, subscriber cancellation, stop/restart, and buffer pressure. The existing post-close/drop counter contract has no obvious direct equivalent and may require an adapter/plugin.

### Cancellation and lifecycle

- Current ViewModel owns an injected AutoCloseableCoroutineScope registered through addCloseable; clearing the ViewModel cancels child work. DraftMviViewModel additionally owns restore/autosave/save/discard sequencing and keyed effect cancellation.
- FlowMVI stores are explicitly started with a caller-owned scope; start returns a lifecycle handle and stores can stop/restart while retaining state. Compose integration subscribes in a lifecycle-aware way. This is a different ownership boundary, not an automatic equivalent to ViewModel.clear().
- Saved state is an optional separate artifact/plugin; it is not automatic merely because the core store is used. Task List does not need saved-state persistence, but must test leave/return and distinguish same-store restart from recreation/new-store restoration.

### Errors, tests, diagnostics, dependencies

- FlowMVI has a plugin/recovery pipeline, a test artifact (pro.respawn.flowmvi:test), Compose integration, and debug logging/validation (debuggable). Its test DSL builds on coroutine testing/Turbine/Kotest. Upstream claims 350+ tests; that is a project claim, not independent evidence.
- Current project has an explicit catchTo/emitError policy: cancellation propagates, ordinary failures are reported and mapped, and fatal-error behavior is bounded/documented. The spike must check that FlowMVI recovery does not swallow cancellation or fatal JVM Error; do not accept a broad catch as equivalent.
- Upstream says core has only coroutines as a dependency. Current upstream source uses atomicfu internally; the published 3.3.0-alpha03 POM also declares atomicfu. Inspect the actual resolved graph for the pinned version rather than relying on the README slogan.
- Apache-2.0; Maven Central publication and active upstream source/docs were verified. Release freshness is a concern: stable 3.2.1 is older than the current upstream tree, whose Kotlin/Compose toolchain is ahead of this app.

## Go/no-go for #3234

**GO, narrowly, for a disposable spike pinned to 3.2.1.** FlowMVI is a credible KMP candidate: it has Android/JVM artifacts, Compose integration, sequential intents, atomic state updates, a single-subscriber-compatible Action mode, a test harness, and Koin integration guidance. The evidence does not justify production adoption.

The spike must make these gates pass before any positive recommendation:
1. Resolve/build core + compose on the repository's Kotlin 2.4.10 / Compose 1.12.2 / coroutines 1.11.0 toolchain without forcing unrelated platform upgrades.
2. Build Android and JVM Desktop targets and verify Koin 4.2.2 + current Navigation 3 ownership.
3. Match the existing Task List behavior matrix, including rapid intents, Room Flow, cancellation, leave/return, and no duplicate/lost state.
4. Test Action delivery with exactly one subscriber and document the difference in close/no-subscriber semantics.
5. Verify cancellation and fatal-error behavior, and compare resolved dependencies and maintainability—not LOC alone.

If the stable artifact cannot pass the first two gates without upgrading the app's Kotlin/Compose baseline, stop the spike and close #3234 as not planned. Do not add FlowMVI to production modules.

## Sources

- FlowMVI official README: https://github.com/respawn-llc/FlowMVI — supported targets, artifacts, integrations, tests, license.
- FlowMVI Quickstart: https://opensource.respawn.pro/FlowMVI/quickstart — intent queue, state strategy, Action delivery, lifecycle and subscription defaults.
- Compose integration: https://opensource.respawn.pro/FlowMVI/integrations/compose — lifecycle-aware Compose integration and stability configuration.
- Koin DI integration: https://opensource.respawn.pro/FlowMVI/integrations/di — documented Koin 4.1.x setup.
- Maven Central artifact metadata: https://central.sonatype.com/artifact/pro.respawn.flowmvi/core — published version and transitive dependency metadata.
- Maven Central version history: https://mvnrepository.com/artifact/pro.respawn.flowmvi/core/versions — stable/prerelease release history.
- Singularity Todo MviViewModel.kt, EventBus.kt, DraftMviViewModel.kt, gradle/libs.versions.toml, and contract tests on main.

## Explicit limitations

This is a source/documentation compatibility assessment, not a successful dependency-resolution or Android/Desktop build. Those are intentionally delegated to #3234, which is the executable compatibility proof.
