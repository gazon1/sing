---
title: "The MVI runtime lives in its own module"
date: 2026-11-28
status: accepted
tags: [mvi, architecture, module-boundary, lifecycle, detekt]
epic: "#3268"
issue: "#3270"
---

# The MVI runtime lives in its own module

> Execution record for #3270, stage 2 of epic #3268. The boundary itself was decided in #3269
> (ADR `2026-11-27-mvi-library-module-boundary.md`, reference
> `docs/architecture/mvi-library-boundary.md`). This entry records what the extraction changed
> that the plan did not fully predict, because those changes are now load-bearing.

## Context

The plan said the move was mechanical: five files, one new module, one diagnostics seam. It very
nearly was. What needed a decision was not *which* files move — #3269 settled that — but three
consequences of the seam that only appear once the code is real:

1. `MviViewModel` no longer has a crash reporter to derive a scope from, so the scope it receives
   must carry its own `Job`, and the type it hands its subclasses changes.
2. The `AppError` knowledge the base class carried has to live somewhere, and the detekt rule that
   guarded the old shape now guards a different one.
3. A new module is a new hole in every inventory that names modules — coverage, Konsist, detekt,
   the affected-test selector.

## Idea

Keep the planned boundary exactly, and let the consequences resolve in the direction the boundary
already points:

- The base takes `scope: CoroutineScope` and **requires a `Job`**, cancelling that `Job` at
  teardown. It never owns a scope factory, and it never names a closeable-scope type.
- `AppError`, `toMessage`, `original()`, `CrashReportingPort` and Kermit meet the library in one
  adapter, `AppMviDiagnostics`, which lives in `:shared`.
- Every inventory gains `:mvi` explicitly, and a failing gate is preferred to a silently narrower
  scan.

## Decision

1. **`scope: CoroutineScope` is required, and a missing `Job` is a construction error.** The check
   is `requireNotNull(scope.coroutineContext[Job])`, not a nullable field. A Job-less context would
   make teardown a silent no-op and leak every coroutine the ViewModel launched — the one failure
   mode a base class must never have quietly.
2. **The task-detail slots and `CountdownStateMachine` take a plain `CoroutineScope`.** They only
   call `scope.launch`; none of them closes the scope, and the base is the owner that cancels. All
   other production consumers (`NoteRewriteWatcher`, `AcpAgentConversation`, `CurrentUser`,
   `AcpTurnRunner`) already accepted a `CoroutineScope`, so nothing else changed shape.
3. **`AppMviDiagnostics` is the only place the two vocabularies meet.** `:mvi` publishes
   `MviDiagnostics`/`MviFailure`/`MviLogLevel` and a generic `NoOpMviDiagnostics`; `:shared`
   publishes the adapter. The `AppError`-specific behaviour is asserted in
   `AppMviDiagnosticsTest`, next to the class that implements it.
4. **`NoDivergentScopeAndReporter` grew a third finding, scoped to direct `MviViewModel`
   super-calls.** A ViewModel that declares `crashReporter` must pass a `diagnostics` derived from
   it; a subclass of `DraftMviViewModel` must not be asked, because that base builds the adapter
   once for every editor. The detekt run proved the need for that exemption twice over: the
   un-narrowed rule flagged `NoteEditor` and `TaskCreateViewModel`, which are correct.
5. **`VmCloseable` skips `MviViewModel` itself.** The base *is* the registration; the rule asks
   subclasses to register a scope they own, and it reads for `addCloseable(scope)` by argument
   text. The base cancels a `Job` through a lambda, so the text it looks for cannot exist there.
6. **`:mvi` is registered everywhere a module can be forgotten**: `koverReport`,
   `scripts/changed-modules.py`, `scripts/test-impact-map.py`, `CommonMainKonsistScope`,
   `SourceScan`, the `jvmTest` `mvi.root` system property, and `easement.yml`.

## Rationale

The alternative to (1) — defaulting the scope — reintroduces exactly the application policy the
boundary documents reject, and it makes "teardown silently does nothing" reachable. A construction
error is loud at the only moment it can be fixed.

The alternative to (4) — making the adapter implicit, e.g. having `CrashReportingPort` extend a
library interface — was rejected in #3269 for the same reason it is rejected now: it forces a
`classify` member onto nine implementations and gives every forgetful one a silently wrong
grouping key. One line per ViewModel, wrong-loud instead of wrong-quiet.

The alternative to (6) — deriving roots from Gradle — is #3285's job and would have blocked this
extraction. Registering `:mvi` explicitly is the smallest correct thing, and the duplication is
recorded as #3285's problem rather than hidden.

## Consequences

- `:shared` depends on `:mvi` (`api`, because every ViewModel extends a type from it); `:mvi`
  depends on nothing of the application's. Two checks hold that, and neither subsumes the other:
  `MviBoundaryTest` scans imports in `:mvi`'s `commonMain` against an explicit list of the
  runtime's own type names (a package prefix would trust `AppMviDiagnostics`, which shares the
  package), and `:mvi:verifyDependencyBoundary` resolves the production graph of **every**
  production source set — `commonMain`, `androidMain`, `jvmMain` — failing on any coordinate
  outside an exact allow-list. The import scan sees an import that arrives through an `api`
  dependency; the graph check sees a declared dependency nothing uses yet, including one added
  only to `androidMain`.
- A ViewModel that forgets `diagnostics` now fails detekt instead of losing its error grouping.
- The contract tests that pin `EventBus` and `MviViewModel` behaviour live in `:mvi`'s `commonTest`
  and run in the coverage job; `MviViewModelTest` additionally pins "no `Job` is rejected at
  construction" and "clearing the ViewModel does not cancel its parent".
- A `CancellationException` that arrives *inside* a `Result` — what `runCatching` produces when
  it captures a cancellation instead of propagating it — is now re-thrown alongside the thrown
  form. Without that, the second shape was classified, reported as a defect and shown to the
  user, which is the one thing the cancellation contract forbids. Found in review of the
  extraction, with two tests pinning it.
- The scope type a ViewModel exposes to its own subclasses narrowed from
  `AutoCloseableCoroutineScope` to `CoroutineScope`. That is source-compatible for every consumer
  in the tree (all of them took a supertype already) but it is a real narrowing: a future subclass
  that wanted `.close()` on `vmScope` no longer can, which is the point — the base owns teardown.
- `testScope()` did not move. It stays in `:shared`'s `commonMain` as test-support code, per the
  #3269 decision; `:mvi`'s own tests build a child `Job` explicitly from `backgroundScope`.

## Links

- Epic #3268; boundary #3269; extraction #3270; consumer proof #3271; source-root inventory #3285.
- ADR `2026-11-27-mvi-library-module-boundary.md` — the boundary this executes.
- ADR `2026-10-22-an-event-bus-has-one-consumer.md` — the bus contract, unchanged.
- ADR `2026-11-27-mvi-instrumentation-is-not-a-new-seam.md` — why no observer API crossed over.
- `docs/architecture/mvi-library-boundary.md` — the reference, now describing the real module.
- `mvi/src/commonMain/kotlin/com/singularity/todo/core/ui/` — the runtime.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/AppMviDiagnostics.kt` — the adapter.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/MviBoundaryTest.kt` — the boundary, as a gate.
