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

- [ ] Enumerate every consumer of the `createBackgroundScope()` default: the 15 ViewModels wired
      in the AppTracer follow-up, plus `CurrentUser`, `ProfileAwareCurrentUser`,
      `ProfileRepositoryImpl` and `AndroidPomodoroTaskListProvider`. Re-derive the list — do not
      trust this one, it was written before the last refactor.
- [ ] Make `scope` a required constructor parameter, in that order, one module at a time. Compile
      errors are the work list; that is the entire benefit of doing it this way.
- [ ] Give each migrated owner its handler explicitly. Where the owner is a ViewModel, that is the
      reporter it already holds.
- [ ] Delete `BackgroundFailureHandler` and the `install` entry point. Keep
      `BACKGROUND_COROUTINE_FAILURE_ISSUE_KEY` — the grouping key is a contract, not a mechanism.
- [ ] Delete `BackgroundFailureHandlerTest`, keeping the parts that do not depend on the global:
      the cancellation rule, the never-rethrow rule, and the issue key.
- [ ] Retire the `background.coroutine_failed` key only if nothing reports under it. Migrating the
      mechanism must not silently drop the group; check the dashboard before removing the key.

## Close out

- [ ] Amend `docs/decisions/2026-10-05-background-failure-handler-and-the-guard-it-behind.md`
      rather than writing a new record — it already names this refactor as the end state, and the
      amendment should say it shipped.
- [ ] `./scripts/refresh-decisions-digest.sh`
- [ ] If `openspec validate` is run, note that #72 records it red on `main` with two pre-existing
      failures; establish the new baseline before attributing a failure to this change.

## At archive time, not before

- [ ] Move the module's row in `openspec/specs/MODULE-INDEX.md` from **Not covered**
      to **Covered**. Not now: the spec does not exist in `openspec/specs/` until the
      change is archived, and the index is supposed to stay honest about what is not
      yet covered.
