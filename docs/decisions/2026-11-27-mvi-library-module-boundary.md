---
title: "The MVI library boundary: one module, one diagnostics seam, Draft stays app-side"
date: 2026-11-27
status: accepted
tags: [mvi, architecture, module-boundary, lifecycle]
epic: "#3268"
issue: "#3269"
---

# The MVI library boundary

> Decision for #3269, stage 1 of epic #3268. Design and inventory only: **no module exists and no
> file has moved.** The full classification table and the move map are in
> `docs/architecture/mvi-library-boundary.md`.

## Context

The MVI runtime lives in `:shared`, mixed with application concerns. `MviViewModel` reaches
`AppError`, `CrashReportingPort`, Kermit and two project coroutine helpers; `EventBus` carries a
Compose `@Stable` annotation and a (never-called) crash reporter. Before any file could move, the
boundary had to be decided, or the extraction would produce a module that is separate and still
not reusable.

The audit found the coupling is concentrated in exactly one place — the failure path — and that
everything else is either already generic or is app policy wearing MVI's clothes.

Measured facts, against the tree at the time of writing:

- 36 files extend `MviViewModel` — 33 production classes (31 under `feature/`, `RefreshViewModel`,
  and the JVM-only `AcpExternalAgentViewModel`), plus `DraftMviViewModel`, the base itself and one
  test harness. 43 files in `commonMain` declare a `crashReporter` parameter.
- `EventBus` has exactly **one** production construction site: `MviViewModel`'s private field. No
  Compose parameter anywhere receives a bus, which is why its `@Stable` is removable.
- `EventBus`'s `crashReporter` is **never read** in production. It exists only so a test can prove
  the absence of a report after a close.
- `MviViewModel.extraEventCapacity` has **zero** callers.
- `:shared` declares no `lifecycle-viewmodel` dependency at all: `androidx.lifecycle.ViewModel`
  arrives transitively through `lifecycle-viewmodel-compose`.
- `DraftMviViewModel` (545 lines) is used by two editors and couples Kermit, `AppError`, `Either`,
  `toMessage`, and two hard-coded issue keys.
- The four `AppError`-dependent behaviours of the base class are pinned by tests: the original
  throwable is reported, an `AppError` is grouped by its own code, an unclassified error falls
  back to the call-site label, and a cause-bearing error is reported by its cause while grouped by
  its code.

## Idea

Three placements were considered.

1. **One module, lifecycle included, one app-supplied diagnostics seam.**
2. **One module with the error semantics moved into an extended crash-reporting port**, so the base
   class never names an app type: `CrashReportingPort extends MviErrorClassifier`.
3. **Two modules now** — `:mvi` core plus `:mvi-draft` for the editor protocol — on the theory that
   splitting early is cheaper than splitting later.

## Decision

**Option 1.** One KMP module `:mvi`, depending on kotlinx-coroutines and AndroidX
`lifecycle-viewmodel` and nothing else. It owns the intent/event markers, `MviViewModel`,
`EventBus`, `IntentActions` and the one diagnostics seam below — and **no coroutine
infrastructure**.

Coroutine work is split by ownership, not by convenience:

- **MVI owns the scope contract**: a required `scope: CoroutineScope` parameter and its
  cancellation at teardown, expressed as `addCloseable { scope.coroutineContext[Job]?.cancel() }`.
  Both are `kotlinx.coroutines` types, so the base cancels a scope without naming a closeable type
  of its own.
- **The application owns the scope implementations**: `AutoCloseableCoroutineScope`,
  `createBackgroundScope`, `backgroundFailureHandler`, `loggingBackgroundFailureHandler` and
  `reportingScope` all stay in `:shared`. `AutoCloseableCoroutineScope` alone has 63 production
  importers, 15 of them not ViewModels (sync, auth, the task collectors) and 4 calling `.close()`.
- **Test helpers stay test helpers**: `testScope` is not exported from `:mvi` production
  `commonMain`. The `kotlinx-coroutines-test` `runTest`/`TestScope` pair is the documented
  mechanism; the child-`Job` wrapper remains a `:shared` helper, and #3271's fixture decides
  whether a public one is ever needed.

The consequence is a rule: **a non-MVI component must not depend on `:mvi` to obtain coroutine
utilities.** `:mvi` publishes none, so the arrow can only point the right way.

The failure path crosses the boundary through a single library-owned object, `MviDiagnostics`,
with three members: `classify(error, errorLabel) -> MviFailure`, `report(error, issueKey)`, and
`log(level, message)`. The library owns the *shape* of a classified failure; the app owns the
knowledge, in one `AppMviDiagnostics` adapter that reproduces today's semantics exactly. The
default, `NoOpMviDiagnostics`, is silent and reports the raw throwable grouped by the call-site
label.

`androidx.lifecycle.ViewModel` stays, as the **sole** source of ViewModel lifecycle. It is one
multiplatform type covering Android and JVM Desktop, it is the lifecycle authority `ViewModelStore`
and Navigation 3 already drive, and its `addCloseable` teardown is the contract every ViewModel in
the tree uses.

`DraftMviViewModel`, `DraftState`/`DraftUiState`, `FeatureSlot` and `combineStates` **stay
app-side**. `extraEventCapacity` is deleted. Package names do not change.

## Rationale

**Options 2 and 3 were rejected on the same principle: do not spread one decision across N sites
when one site can hold it.**

Option 2's problem is that a default implementation is silently wrong for whoever forgets to
override it: a library-provided `classify` cannot know an app's error codes, so the grouping key
would collapse distinct defects into the call-site label with nothing failing. It also forces a
`classify` member onto nine `CrashReportingPort` implementations (four production — free Android,
pro Android, JVM, no-op — and five test doubles), most of which have no business classifying
anything. And it would make the app's port the library's interface: the dependency arrow would
run from the port to MVI instead of from the adapter to both. One explicit adapter line per
ViewModel is the cheaper trade, because it is wrong-loud.

Option 3 was rejected because there is no evidence for the split. `DraftMviViewModel`'s generic
mechanics are not generic: "checkpoint only after an explicit save, never after autosave", "a
clear must forget the pending write that would resurrect it", "a keystroke during a suspension is
not part of what the save stored" are *editor policy*, each with its own regression in the record
(`#2724`, `#3256`, `2026-11-09-the-saved-draft-is-the-one-the-save-left`). Extracting them means
inventing a draft-policy port for one application and two editors. The trust-boundary rule for a
reusable module — and the issue's own instruction — is that a capability splits only when a real
consumer justifies it. `DraftState` is `internal` today; making it public would change no
behaviour and serve no caller.

**Lifecycle: keeping AndroidX is the decision, not the default.** The Essenty comparison is in
the architecture note. A platform-neutral lifecycle would be a *second* lifecycle authority next
to the one Navigation 3's `ViewModelStore` drives, and two authorities is a worse defect than one
dependency. `InstanceKeeper` is recorded as not needed: retention is already entry-scoped.

**Kermit leaves; one log member stays.** The module must not depend on a logging library, but the
bus's refusal warning is a contract — a refused `tryEmit` is real event loss, and every call site
ignores the returned flag. So the *message and the severity decision* stay in the library
(`MviDiagnostics.log`), and the *sink* becomes the app's. The test that pins "warn, not debug,
because release filters below `Warn`" keeps testing the library's decision through a recording
implementation.

## Consequences

- `:mvi`'s `commonMain` has no import from `com.singularity.todo.*` outside its own packages, and
  names no Kermit, Compose, Koin, Room, Navigation, `AppError` or `CrashReportingPort` type — and no
  `core.coroutines` type either. That is the acceptance criterion, expressed as an assertion a build
  can fail on.
- **`scope` becomes required, and the base loses its default.** The current default composes a
  scope from `crashReporter`, which is application policy. All 35 subclasses already declare
  `scope: AutoCloseableCoroutineScope = reportingScope(crashReporter)` and pass it explicitly
  (verified: none relies on the base default), so requiring it changes no call site.
- **`vmScope` narrows to `CoroutineScope`.** 24 test sites use `vmScope.close()` or
  `vmScope.job?.cancel()`; each already holds the scope it constructed, so they call it there.
  Production uses neither (verified). This is the cost of the base not owning a closeable type, and
  it is confined to tests.
- **One new risk, and it is a real one:** 43 files declare `crashReporter` and pass it to the base
  class. Forgetting the new `diagnostics` argument on a future ViewModel compiles and silently
  degrades the error path to raw throwables with call-site grouping. The mitigation is mechanical,
  not documentary: extend `NoDivergentScopeAndReporter`, which already enforces "the scope is
  derived from the reporter", to also require "the diagnostics are derived from the reporter".
- Source compatibility inside the repository breaks at three named points (the required `scope` plus
  the `diagnostics` parameter, the removed `extraEventCapacity`, the bus's changed second and third
  parameters). No artifact is published, so binary compatibility is not yet a question; #3271 owns
  it if a second consumer appears.
- `easement.yml` needs one new component, one new layer, and `shared: may_depend_on: [mvi, …]`.
  That edit requires human approval — it is not part of this issue, and it blocks #3270.
- The Konsist governance test reads `:shared`, `:core-domain` and `:core-database` through
  `CommonMainKonsistScope`, which is built from three build properties. It cannot fail on a
  `:mvi` violation until a fourth root is added. #3270 must extend it; otherwise the boundary is
  documented and unenforced, and the scope's own KDoc names this exact failure mode — a scan of
  less code reports green like a scan of all of it.
- `testScope` stays in `:shared` and is **not** exported from `:mvi` production `commonMain`.
  Kotlin has no cross-module test-fixture source set, and 33 files use it — including `desktopApp`
  tests, which cannot see `:shared`'s test sources. `kotlinx-coroutines-test` covers the consumer
  case; #3271 decides whether a public child-`Job` wrapper is ever justified by its fixture.
- No compatibility facade is introduced at any step, so none has to be removed later.

## Links

- Epic #3268; this stage #3269; extraction #3270; consumer proof #3271; state restoration #3273 and
  back-handler arbitration #3274, both deliberately outside this module.
- `docs/architecture/mvi-library-boundary.md` — classification table, public API, Gradle block,
  dependency graph, move map, migration order.
- `docs/decisions/2026-11-27-mvi-architecture-direction.md` — no framework, no plugin layer.
- `docs/decisions/2026-11-26-mvi-state-is-observed-read-only.md` — both state names are pinned.
- `docs/decisions/2026-11-27-mvi-instrumentation-is-not-a-new-seam.md` — no observer API exists to
  carry into the module.
- `docs/decisions/2026-10-22-an-event-bus-has-one-consumer.md` — the bus contract this preserves.
- `docs/architecture/mvi-ownership.md` — the ownership inventory this satisfies.
