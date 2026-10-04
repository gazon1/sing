# Tasks — scope-reporter-agreement

## Decide

- [ ] **Pick the shape, and record why.** Two options, both in #143. (a) Make the disagreement
      unrepresentable: the two affected ViewModels derive their work context from the reporting
      port they already hold, as the coordinator, the search ViewModel's secondary constructor and
      every `slot/` class already do. (b) Keep the override and add a check that compares the two
      resolved destinations across the graph. (a) is the smaller surface and matches the majority
      shape; (b) generalises if a component ever genuinely needs a context from elsewhere. This is
      a decision, not an implementation detail, and it belongs in the ADR either way.
- [ ] **shared/ — derive the scope from the reporter in `SyncViewModel` and
      `CalendarSyncViewModel`.** Both currently take `scope` as a required parameter positioned
      after a defaulted reporter. The two bindings in `CoreDiModule.kt:219-220` and
      `CalendarSyncDiModule.kt:64-65` then lose their `scope =` argument. Under option (a) this
      task is the whole change.
- [ ] **shared/ — update the two bindings** and delete the now-unused `scope = reportingScope(get())`
      lines. Check whether the `factory { reportingScope(get()) }` in `CoreDiModule.kt:78-81` still
      has a consumer afterwards; if it does not, it is a `factory` with no consumers and should go
      in the same commit rather than becoming the next unwired surface.

## Verify

- [ ] **Test: a background failure and a handled failure from the same component reach the same
      destination.** Resolve the component from a real graph, trigger a `catchTo` failure and an
      escaped background failure, and assert both landed in one recording port. This is the test
      that makes REQ-7 checkable rather than asserted — and it is the shape `KoinGraphValidationTest`
      already uses for the calendar-sync definitions, so no new harness.
- [ ] **shared/ — prove the check can fail.** Plant a work context built from a second port,
      confirm the test goes red, restore. A test that reports "one destination" against a component
      written the other way round is the artefact, not the guarantee — this is the same discipline
      `just honesty` and `NoUnwiredReporterInBinding` were built with.
- [ ] **shared/ — `ForkEveryIsolationTest` and the ViewModel graph tests still pass.** Removing two
      constructor parameters changes two bindings; `CrashReportingWiringTest` and
      `NoUnreportedFailurePathTest` are the checks that would notice a binding quietly losing its
      reporter, and they should stay green without modification. If one of them needs a change, that
      is a finding, not a chore.

## Close out

- [ ] **Amend `2026-10-05-background-failure-handler-and-the-guard-it-behind.md`** with whichever
      option was taken. That record states the four components holding no reporting port name their
      handler at their binding — this change adds two more sites to the same question, and the
      record's REQ-6 discussion of what the two rules can and cannot see is where the "a rule
      cannot correlate two sibling arguments" finding belongs.
- [ ] Do **not** move REQ-7/REQ-8 into `openspec/specs/crash-reporting/` until the check exists.
      REQ-8's second scenario is a requirement that the check can be shown to fail; archiving
      before that lands puts a guarantee in the spec that nothing verifies, which is the condition
      `failure-visibility` is still open for.
- [ ] Note the interaction with #135 in whichever lands first. #135 is "a rule with no positive
      control can be added"; this is "a rule cannot see the disagreement that matters here". A
      future attempt to close #143 with a rule rather than a test should re-read #135's correction
      about checks that match less than intended.

## Already consistent — do not re-audit

Named so the next person does not re-count them. The `slot/` classes hold no reporting port and
take the coordinator's work context; the search ViewModel's secondary constructor derives its
context from the reporter it was handed; the coordinator defaults to the derived context. None of
these can disagree, and the requirement above is written to be satisfied by them already.
