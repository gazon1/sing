---
title: "The error funnel had no floor, and the guard that should have seen it only recognised its own shape"
date: 2026-10-05
status: accepted
tags: [observability, coroutines, testing, architecture]
---

## Context

The AppTracer work (`2026-10-04-apptracer-integration`) put a reporter at the bottom of the
MVI error funnel. Two follow-up problems remained, both found by reading the code rather than
by running it.

**The floor was missing.** `createBackgroundScope()` returned
`CoroutineScope(SupervisorJob() + Dispatchers.Default)` — no `CoroutineExceptionHandler`. That
scope is the default for every ViewModel and the backing scope for `CurrentUser`,
`ProfileAwareCurrentUser`, `ProfileRepositoryImpl` and `AndroidPomodoroTaskListProvider`. A
`launch` whose body throws in such a scope is routed by kotlinx.coroutines to the thread's
default uncaught-exception handler, which on Android is the platform's `KillApplicationHandler`:
**the process dies.** Measured at the time: 9 ViewModels, 26 unguarded `launch` calls between
them, `AgendaViewModel` alone accounting for 10.

**The guard was blind to its own blind spot.** `CrashReportingWiringTest` fired only on
`emitError`/`catchTo` call sites. A ViewModel that launched work and handled failures entirely
by hand passed vacuously. Widening the trigger found six more ViewModels than the original
hand-count had listed, and confirmed the shape of the problem: `ArchiveViewModel`,
`TagGroupsViewModel`, `ProjectEditorViewModel`, `SavedAgendaViewModel`, `StatisticsViewModel`,
`CalendarSyncViewModel`, `SyncViewModel` and `BackupViewModel` all converted failures into UI
state or UI events with their own `try`/`catch`/`fold`, and **not one of them reported
anything**. `StatisticsViewModel` was the clearest: a failed query was caught and replaced with
an all-zero snapshot, so the user saw a plausible empty statistics screen and the failure
existed nowhere.

## Idea

Two options for the missing floor.

**A narrow global failure handler** on every background scope, installed at process start from
the Koin-resolved `CrashReportingPort`. Keeps the reporter injectable; adds one piece of
bootstrap state, in the same category as the installed uncaught-exception handler.

**Remove the unmanaged scopes** — require every default-scope consumer to receive an explicit
scope, so the compiler finds each one. Strictly better as a design, and a multi-week refactor
across repositories, workers and 31 ViewModels, during which the hole stays open.

The second is the right end state. It is not the right change to make inside a reliability fix,
and shipping the fix is what makes the refactor safe to schedule.

## Decision

Take the narrow handler now.

- `BackgroundFailureHandler` is a `CoroutineExceptionHandler` object that every
  `createBackgroundScope()` actual adds to its context. It delegates to a replaceable
  `(Throwable) -> Unit` target, or logs through Kermit when nothing is installed.
- `installBackgroundCrashReporting(port)` in `core.observability` builds the target from a
  `CrashReportingPort`. `SingularityApp.onCreate` calls it right after `startKoin`.
- The handler never rethrows, so a background defect cannot be re-escalated into the process
  death it exists to prevent, and skips `CancellationException`.

The **reporter stays injected; the handler is bootstrap state.** That is the whole exception to
"everything is injected", and it is worth being precise about why. `createBackgroundScope()` is
a top-level `expect fun` with no receiver and no Koin handle, and it runs *inside* Koin graph
construction — it cannot take a parameter. Routing it through a global would have been the
alternative, and that is exactly what `NoStaticProfileAwareCurrentUserRule` exists to prevent: a
reporter *is* a dependency, and a mutable global holding one is a service locator that tests
cannot reset. The handler is not a dependency; it is a policy for a failure that has nowhere
else to go, and it is stable-identity so a scope created before the install still reports to
the target installed after it.

Two consequences accepted deliberately:

- **The uninstalled default is a Kermit log line, not a no-op.** A silent default would
  reintroduce the same failure mode one layer down. On desktop the Kermit file log is the only
  sink there is, `JvmCrashReportingPort` is a no-op, and nothing installs a target on that
  platform — so the default *is* the desktop behaviour, and that is why desktop deliberately
  does not call `installBackgroundCrashReporting`.
- **`forkEvery = 1` is what makes the global safe in tests.** Every test class gets a fresh JVM,
  so no other class can receive a failure this class's target captures. Methods within a class
  still run concurrently, so `BackgroundFailureHandlerTest` pins
  `@Execution(SAME_THREAD)`.

For the guard: the trigger became "can this class fail at all" — it uses the funnel, launches a
coroutine, or catches something by hand — rather than "does it use the funnel". Being strict
about the shape is the point, because a ViewModel that routes around the funnel is invisible to
a guard that only recognises the funnel. A second check asserts that no ViewModel constructs a
scope directly (`GlobalScope`, `MainScope(`, `CoroutineScope(`), which is the one way to
reopen the floor silently.

Fixing the trigger exposed the real work: 15 ViewModels now pass a `CrashReportingPort`, in
three shapes. Five had their hand-rolled failure handling **replaced with the funnel**
(`TagGroupsViewModel`, `SavedAgendaViewModel`, `ArchiveViewModel`, `AppVersionGateViewModel`,
`ProjectEditorViewModel`) — `catchTo` reports by construction, so those call sites gained
reporting by deletion. Six **kept** their existing structure and gained an explicit
`crashReporter.report` at the point they were already swallowing (`AgendaViewModel`,
`StatisticsViewModel`, `BackupViewModel`, `ChatViewModel`, `CalendarSyncViewModel`,
`SyncViewModel`) — for a `Flow.catch`, or a hand-written `fold` whose UI behaviour is
deliberate, rewriting it would have been a larger change than the defect warranted. Four are
wired with no new call site at all (`AiUsageViewModel`, `AuthViewModel`, `CalendarViewModel`,
`TaskCreateViewModel`); their funnel is live but currently unused.

- `TagGroupsViewModel` launched `createTagGroup`/`deleteTagGroup` and **discarded the `Result`**,
  so a failed create or delete left the screen unchanged with no message and nothing in the
  report. Both now route through `catchTo`.
- `AppVersionGateViewModel` let a throwing remote-config read escape, so a broken config server
  could brick every user behind a version gate. It now fails open — report, then evaluate the
  default snapshot.

## Rationale

- **Report and survive beats report and die.** A collector that crashed is a defect to triage,
  not a reason to lose the user's unsaved state.
- **A guard must recognise failure, not the house style for handling it.** The original trigger
  was a check that the funnel was used, dressed up as a check that failures were reported. Those
  are different properties, and only the second one is the invariant worth protecting.
- **Enabling wiring is not dead wiring.** For four of the fifteen there is no bespoke failure
  handling to convert, so the parameter is passed to the base class and currently unused. That
  is the honest state: the funnel is live if a call site is added. Manufacturing a `catchTo` to
  justify a parameter would have been the wrong direction of travel.
- **`AuthViewModel` is wired but deliberately does not report sign-in failures.** A wrong
  password is user error, not a defect, and reporting it would flood the dashboard. The
  distinction between "has somewhere to report" and "reports everything" is per-site judgement;
  the guard asserts only the former.

## Consequences

- A failed `launch` in any background scope is reported under `background.coroutine_failed` and
  no longer kills the process.
- 15 ViewModels pass a `CrashReportingPort`: five converted to the funnel, six gained an
  explicit report at an existing swallow point, four wired with no new call site.
  `BackupViewModel`'s settings *decode* outcomes (`SchemaTooOld`, `ParseError`) stay unreported
  on purpose — the file's own KDoc already classifies those as an ordinary message for a user
  who picked a file the app cannot read.
- `CrashReportingWiringTest` fails on a ViewModel that can fail without a reporter, and on a
  ViewModel that builds a scope bypassing the handler.
- The narrow handler is explicitly interim. The refactor in *Idea* removes the need for it; this
  ADR is the record of what to remove.
- Grouping for background failures is one key for the whole class, not per call site. A
  per-call-site key would need a per-call-site handler, which is the global-state plumbing being
  avoided; the stack trace in the report is what distinguishes them.

## Links

- `core/coroutines/BackgroundFailureHandler.kt` — the handler
- `core/observability/BackgroundCrashReporting.kt` — the install entry point and issue key
- `core/coroutines/BackgroundScope.android.kt`, `BackgroundScope.jvm.kt` — the actuals
- `arch/CrashReportingWiringTest.kt` — the widened guard
- `core/observability/CrashReportingTest.kt` — `BackgroundFailureHandlerTest`
- `2026-10-04-apptracer-integration.md` — the integration these two gaps sit inside
- `2026-10-04-observability-followups.md` — items 4 and 6 there (silent `runCatching`, the
  deliberate global in `LogBootstrap`) are the same class of finding

---

# Amendment (2026-10-05, later the same day)

The *Idea* section above proposed removing the unmanaged scopes so the compiler would find each
one, and called that a multi-week refactor across "31 ViewModels". **It shipped, and the shape
is not the one this record guessed at.**

## What changed

`BackgroundFailureHandler` (an `object` with a swappable target), `installBackgroundCrashReporting`,
and the `expect`/`actual` pair are gone. `createBackgroundScope` is now an ordinary
`fun` with a **required** `CoroutineExceptionHandler` argument — not an `expect fun`, because
both actuals were byte-identical once the global left, and an `expect` with no platform
difference is a lie about where the platform boundary is.

The reason the factory could not take a dependency, as this record states above, was that it ran
inside Koin graph construction with no handle. That is still true — and it is still solvable
without a global, because **the caller of the factory is the thing that has the dependency.** The
Koin module has the `CrashReportingPort`; the ViewModel has the `CrashReportingPort`; the app
entry point has the Koin graph. `createBackgroundScope(crashReportingFailureHandler(get()))` is
one line at each of those sites.

## The migration did not make `scope` required on the ViewModels

The obvious reading of the plan was "make `scope` a required constructor parameter on all 28
ViewModels, so the compiler finds every site". That is wrong, and for a reason worth recording:

`MviViewModel.init` calls `addCloseable(scope)`, so a scope injected from Koin as a `single`
would be **cancelled by the first ViewModel cleared**, taking every other ViewModel's
collectors with it. The binding would have to be a `factory`, which is correct but expensive:
~30 signature changes, every `viewModelOf(::Vm)` converted to `viewModel { }` — giving up the
constructor-arity checking the project deliberately uses — and every test construction site
updated, for no reduction in risk.

After `NoUnreportedFailurePath` landed, every ViewModel that can fail already holds a
`CrashReportingPort` **as a required constructor parameter**. So the default scope is composed
from it:

```kotlin
scope: AutoCloseableCoroutineScope = reportingScope(crashReporter)
```

The default is not a tolerated defect any more; it is the right answer, derived from a
dependency the component is already required to hold. The compiler no longer needs to find these
sites, because the rule does, and a rule can be given a positive test.

The four components that hold **no** reporter — `CurrentUser`, `ProfileAwareCurrentUser`,
`ProfileRepositoryImpl`, `AndroidPomodoroTaskListProvider` — have nothing to derive from, so
their handlers are named at their Koin bindings. That is the case the compiler argument was
actually for.

## The test-safety consequences evaporated

This record listed two accepted consequences of the global: `forkEvery = 1` made it safe across
classes, and `@Execution(SAME_THREAD)` made it safe across methods within one. Both are gone,
because a handler is a value — a test builds its own and nothing running beside it can capture
its failures. `ForkEveryIsolationTest` keeps asserting `forkEvery = 1` for Koin's graph, which
is still process-wide state, and its KDoc now says so. The `@Execution` pin is gone with the
convention it was protecting.

## The guard's remaining half

`CrashReportingWiringTest` keeps the two *corpus* checks and lost the three text predicates,
which are now the `NoUnreportedFailurePath` PSI rule. The split is deliberate: the rule asks
"does this ViewModel report?", the corpus checks ask "is there still something for it to report
into?" A rule over individual ViewModels passes happily when every one of them routes into a
base class that silently does nothing. That is not a per-file property, so it is not a
per-file gate.

The scope-factory check was rewritten in the same pass, and **its first version could not
fail**: it asserted the parameter was *a* `CoroutineExceptionHandler`, which passed just as
happily against `= loggingBackgroundFailureHandler()` — a default, which is the exact
process-wide policy this migration removed. It now asserts the *absence* of a default value,
and that was proven by reintroducing one. This is the third time in this repository's recent
history that a shape check passed for the wrong reason; the pattern is consistent enough to be
worth naming as its own failure mode.

## Amended 2026-10-05 — the guard needed a third rule, and the audit needed correcting

Two more rules landed against this invariant over the following day, and the reason is worth
recording because it is a limit on what the first two can do, not a new idea.

`NoUnwiredReporterInBinding` was written because the class-level rule passed for two production
bindings that shipped a no-op reporter. It asks whether the *binding* passes a reporter. It still
cannot ask whether the scope on the adjacent line points at the **same** reporter — and comparing
two `get()` calls for identity is a type-resolution question, which a detekt rule does not have.

`NoDivergentScopeAndReporter` therefore does not compare. It removes the possibility: the scope is
derived from the reporter in the constructor's default, so there is no second argument to
correlate. What is left to check is whether that is still true, which is two findings — the
constructor's default, and a binding that passes both arguments anyway.

**The second finding is not redundant, and that is the point.** `SettingsViewModel` had a correct
constructor and a binding that replaced the derived scope with a graph-supplied one. A
constructor-only check passes it forever. The rule reported it on its first run against the real
tree.

**The audit was wrong by a factor of two.** The site count recorded when this was filed said two
and was four. The two extra ones — `SearchViewModel` (a derived *secondary* constructor that left
the *primary* one requiring a scope) and `SettingsViewModel` above — are both shapes that look
correct when you read the binding, which is what the audit had done. This is the same failure mode
as the two above it: a check that covers the case you happened to look at is indistinguishable from
one that covers the case you did not. The count is left in the change's tasks file rather than
rewritten, because "do not add `SearchViewModel` to the consistent list" is the instruction the
next person needs.

## Links

- `2026-10-05-positive-tests-for-every-detekt-rule.md` — why the guard is a rule and not a regex
- `2026-10-05-no-direct-dispatchers-rule-was-a-no-op.md` — the same failure mode, recorded once already
