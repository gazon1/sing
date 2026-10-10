---
title: "Koin Definition Bodies At Zero Coverage"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — known Kover limitation; no fix available**

**Tracked as:** #370 (CLOSED)

**Closed via:** `fix/subtask-ui-tagging-01`:commit:94f3ab73

**Found in:** coverage ratchet analysis, 2026-10-07.

**Symptom.** Koin `single { … }`, `factory { … }`, and `viewModel { … }` definition
*bodies* report 0% line coverage even when the graph is fully resolved by
`KoinGraphValidationTest`. Only the `module { }` block header registers.

**Already ruled out — Kover architecture.** Kover instruments JVM bytecode at the
line level. A Koin definition body is a lambda argument to `single {}` etc., and
the JVM captures it as a synthetic class (`KoinDefinitionBody$1.class`) whose
invocation site is inside Koin's internal resolution code, not inside the test
class. The test calls `koin.get<SomeType>()`; Koin resolves the lambda and calls
`KoinGraphValidationTest$1.invoke()`; Kover attributes the call to Koin internals,
not to `Modules.kt`. The lambda body executes but is not Kover-attributed.

**Try next.** Nothing in Kover's configuration can change this — it is a fundamental
aspect of how JVM coverage tooling works. The options are:

1. **Accept the gap (chosen).** `KoinGraphValidationTest` already validates graph resolution.
   The 0% body coverage is a Kover blind spot, not a graph defect.
2. **Alternative coverage tool.** Jacoco's `OFFSET` counter mode produces different
   attribution, but still not per-definition-body lines for lambdas captured in
   synthetic classes.
3. **Structural test instead.** A `KoinGraphValidationTest` that uses a recording port
   (like `RecordingCrashReportingPort`) to assert side effects inside definition
   bodies would give binary pass/fail coverage without line attribution.

**Related:** `a-koin-definition-body-stays-uncovered-after-being-resolved` (#138, CLOSED)
is the same finding from an earlier session, confirming this is a Kover/Jacoco
fundamental limitation, not a configuration error.
