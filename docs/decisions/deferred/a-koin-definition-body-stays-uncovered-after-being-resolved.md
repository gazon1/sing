---
title: "A Koin Definition Body Stays Uncovered After Being Resolved"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "detekt-tooling-honesty"]
---

**Found in:** 2026-10-05, when the coverage ratchet charged this work a 0.40pp drop in
`feature/calendar_sync` and the obvious fix did not fix it.

**Status: CLOSED**

**Tracked as:** #138
**OpenSpec change:** `openspec/changes/detekt-tooling-honesty/`

**Symptom.** `CalendarSyncDiModuleKt` reads 4/18 lines covered. The four covered lines are the
`module { }` block; the fourteen uncovered ones are the bodies of the `single { … }` and
`viewModel { … }` definitions. `KoinGraphValidationTest` now resolves both definitions for real,
the test passes, and the number does not move.

**Three hypotheses, in order of how cheap they are to rule out.** (a) `jvmTest` was served
`FROM-CACHE` or `UP-TO-DATE` after `just cr`'s kover wipe, so the class contributed no fresh
coverage data — which would make the number a property of the measurement rather than of the code.
(b) Line attribution: the lambda's lines may not reach the file-level LINE counter. (c) The
resolution is satisfied without executing the body.

**Why it is here.** A coverage number that does not describe execution is worse than no number,
because somebody will make a decision on it. In this case the decision was whether a floor drop was
acceptable, and the floor was adopted to the measured value with a `note` in
`config/coverage-ratchet.json` recording that the movement is unexplained. **That note should not
outlive the explanation** — whoever closes this should delete the note in the same commit.

**Related, and older.** #59 records the Gradle test cache silently skipping the whole suite, one
level up and with worse consequences. `gradle-test-cache-silently-skips-the-suite` in this file is
the same finding from the previous session.

**Try next.** Rule out (a) first, with `--rerun-tasks` on the single class, because it invalidates
the other two. If it is genuinely 4/18 after a forced re-run, plant a side effect inside the
`single { }` body and assert it happened when the definition is resolved.

---
