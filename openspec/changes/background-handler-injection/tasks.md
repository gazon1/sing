# Tasks — background-handler-injection

## Harden first (each is independently shippable)

- [x] **Assert the test invariant, or remove the dependence on it.** Either add a check that
      fails when the JVM test task's `forkEvery != 1`, or extract the dispatch policy into a
      constructible class so tests do not need process-wide state at all. Do not leave a global
      whose test safety rests on an unstated Gradle flag. (#128)

      Shipped as `ForkEveryIsolationTest`: the `jvmTest` task now publishes `forkEvery` as a
      system property, and the test asserts it is `1` *and* that no class touching the global
      handler omits `@Execution(SAME_THREAD)`. Proven to fail by removing that pin.
- [x] **Move the shape decision to PSI.** One `NoUnreportedFailurePath` rule in `detekt-rules/`
      with a positive test per predicate, replacing the three regexes in
      `CrashReportingWiringTest`. Keep the corpus and funnel checks in the test; keep the rule
      registered per `check-detekt-registrations.sh`. (#129)

      Shipped as `detekt-rules/.../NoUnreportedFailurePathRule.kt` with 15 positive tests and a
      Policy object split out so each branch is testable without a detekt harness. Verified to
      fire on the real corpus by planting a violating ViewModel in `commonMain` and reading the
      detekt report — not merely by the tests passing. `CrashReportingWiringTest` keeps the two
      corpus checks (funnel, scope factory) and loses the three text predicates.
- [x] **Give the top-level install function a call site check.** The unwired-surface audit cannot
      see top-level functions, which is how `debugInfo` sat unused for its whole life; the check
      the follow-ups ADR scopes is a function documented as *call this at startup* that nothing
      calls. `installBackgroundCrashReporting` is now a second instance. (#127)

## Then migrate

- [x] Enumerate every consumer of the `createBackgroundScope()` default. The list above was
      wrong twice over: it said 15 ViewModels, and there are **28** declaring a scope default
      plus 30 subclasses reaching the base's. Re-derived from the source, not trusted.
- [x] ~~Make `scope` a required constructor parameter on the ViewModels.~~ **Superseded.**
      `MviViewModel.init` calls `addCloseable(scope)`, so a Koin-`single` scope would be
      cancelled by the first ViewModel cleared and take every other ViewModel's collectors with
      it. The compiler argument was right for the four components that hold no reporter and
      wrong for the 28 that do — see the ADR amendment for the derivation that replaced it.
- [x] Make `createBackgroundScope` require its `CoroutineExceptionHandler`, with no default. The
      `expect`/`actual` pair is gone: both actuals were byte-identical once the global left, and
      an `expect` with no platform difference is a lie about where the platform boundary is.
- [x] Give each owner its handler explicitly. Four components name theirs at their Koin binding
      (`CurrentUser`, `ProfileAwareCurrentUser`, `ProfileRepositoryImpl`,
      `AndroidPomodoroTaskListProvider`); every ViewModel derives it from the
      `CrashReportingPort` it already holds, via `reportingScope(crashReporter)`.
- [x] Delete `BackgroundFailureHandler` and `installBackgroundCrashReporting`. Keep
      `BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY` — the grouping key is a contract, not a mechanism.
- [x] Reduce `BackgroundFailureHandlerTest` to what does not depend on a global, and add the
      case the global made impossible: two handlers, two failures, neither captured by the
      other. The `@Execution(SAME_THREAD)` pin is gone with the convention it protected.
- [ ] Retire the `background.coroutine_failed` key only if nothing reports under it. Migrating the
      mechanism must not silently drop the group; check the dashboard before removing the key.
      **Still open — needs the dashboard, which is not reachable from here.**

## Close out

- [x] Amend `docs/decisions/2026-10-05-background-failure-handler-and-the-guard-it-behind.md`
      rather than writing a new record — it already named this refactor as the end state. The
      amendment also records that the migration's *shape* was not the one the plan guessed at,
      and why the compiler argument does not transfer from the four components to the 28.
- [x] `./scripts/refresh-decisions-digest.sh`
- [ ] If `openspec validate` is run, note that #72 records it red on `main` with two pre-existing
      failures; establish the new baseline before attributing a failure to this change.

## At archive time, not before

- [ ] Move the module's row in `openspec/specs/MODULE-INDEX.md` from **Not covered**
      to **Covered**. Not now: the spec does not exist in `openspec/specs/` until the
      change is archived, and the index is supposed to stay honest about what is not
      yet covered.
