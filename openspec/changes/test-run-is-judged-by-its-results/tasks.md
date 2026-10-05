# Tasks — test-run-is-judged-by-its-results

The implementation is on `main` (`9e1923c1`). These tasks are what the change still
owes: the requirement text, and the coverage that keeps each requirement from being
an assertion nobody checks.

Each task that changes behaviour names a test that verifies it.

- [x] Move the runnable-test predicate into one place and bind the two language
      implementations to a shared fixture table, so "is this class runnable" stops
      having an implementation per consumer.
      `config/test-fixtures/runnable-test-members.txt`, read by
      `RunnableTestFixtureTest` (Kotlin) and
      `scripts/tests/test_runnable_test_members.py` (Python).
- [x] Add the fully-qualified annotation form to both predicates.
      `@org.junit.jupiter.api.Test` is used at `RruleGeneratorTest.kt:49` and matched
      neither; a class whose only test used it read as test-free on both sides.
      Fixture `junit5_test_fully_qualified` fails before the fix.
- [x] REQ-13 — compare declared `fast` classes against the classes that produced a
      report, and fail on a mismatch.
      `scripts/check-test-runs.py`; covered by
      `ByResultsEndToEndTest.test_counts_meeting_the_floor_still_fail_on_a_missing_class`,
      which holds the counts exactly at their floor so only this check can fail.
- [x] REQ-13 — normalise the two suite-name shapes Gradle writes, and pin the
      normalisation, because the first version reported all 30 `desktopApp` classes as
      missing on a genuinely green tree.
      `SuiteNameNormalisationTest` and
      `ByResultsEndToEndTest.test_fully_qualified_report_name_satisfies_a_source_class_name`.
- [x] REQ-14 — make an unavailable scanner a failure rather than a skip.
      `ScannerUnavailable`; covered by
      `ScannerFailureIsLoudTest`, after the first version was caught reporting
      "floors met" with the check not running.
- [x] REQ-15 — preserve floor entries for source sets that did not run, and report a
      rise together with the configuration a floor requires.
      `UpdateBaselineTest`.
- [x] Raise the `shared:jvmTest` floor in the same commit that changed it, 178 → 200.
      That is a correction, not test growth: the old value was recorded while the tag
      gate could not see `@ParameterizedTest` classes.
- [x] Record the audit that found the `flow_has_tag` silent exclusion, and the fact
      that the first deferral of that fix was reasoned wrongly.
      `docs/decisions/2026-10-05-gate-audit-text-shape-vs-fact.md`; #148 closed.
- [ ] **Decide whether REQ-13 should also assert the reverse direction** — a report
      whose class name is in the results but absent from the sources. The Kiwi
      traceability work joins reports to carriers by display name, so a report that
      joins nothing is already visible there, and duplicating it in this gate may be
      the wrong layer. Not decided; it is the one open design question this change
      leaves behind.
- [ ] Once this change is archived, confirm the delta requirements are readable
      against the implementation by someone who did not write it — the structural
      validator proves the spec has the right shape, not that it matches the code.
