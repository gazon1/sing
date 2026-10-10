---
title: "Detekt Rule Branch Coverage Owed"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "detekt-rule-coverage-floor"]
---

**Found in:** 2026-10-05 rule-audit. `RuleFiresSmokeTest` gives all 17 custom rules at
least one positive test (two were confirmed no-ops and fixed: see
`2026-10-05-no-direct-dispatchers-rule-was-a-no-op` and
`2026-10-05-positive-tests-for-every-detekt-rule`). That is the minimum, not the job.

**Tracked as:** #98
**OpenSpec change:** `openspec/changes/detekt-rule-coverage-floor/`

**Status:** CLOSED — tracked GitHub issue is closed (2026-10-05, later the same day). Kover is now on
:detekt-rules (`just tkr`), so this is a number rather than prose: **92.0% line
coverage, 102 tests, 0 failures.** Coverage went 66.2% -> 92.0% when the four
MviViewModel rules — the ones guarding the canonical VM shape, previously 0% and
entirely unverified — got a positive and a negative test each. All four turned out
to work, so no third no-op; the point was that nobody knew until they were measured.

One question is still answered per rule — "can it fire?" Branch coverage remains
uneven, and the gaps are not spread evenly:

- `PassThroughUseCaseRule` has the most untested logic and the least obvious guards:
  `operator`, `private`, block bodies, the `LlmUseCase` exemption, and the `clock` /
  `tool` receiver exemptions. None is exercised. A guard that is wrong here is a
  false-positive machine aimed at legitimate use cases.
- `NoStateInRule`'s `@OptIn(CombineStateInReadThrough::class)` exemption is the one path
  that decides whether the rule is usable on read-through VMs, and it is untested.
- `MviViewModelRulesProvider`'s other four rules (`IntentMethodName`, `VmScopePosition`,
  `VmCloseable`, `ShadowedState`) have no tests at all — the smoke test covers only
  `MviViewModelExt`.
- `NoEmptyOnClickLambdaRule`'s "file name contains preview" exemption is untestable via
  the PSI harness (see the ADR); the repo's real `core/ui/preview/` package is the only
  thing exercising it.

**Try next:** start with `PassThroughUseCaseRule`, because its exemptions are the ones
that decide whether the rule is tolerable in a codebase. Extract each guard to a policy
object in the style of `NoDirectDispatchersPolicy` and cover it directly, which also
removes the PSI-shape coupling that made the original bug possible.

**Try next (cheap alternative):** a differential test. Run each rule over a fixture
containing a known violation and assert the *count*, not just non-emptiness, so a rule
that starts double-reporting fails. Cheaper than full branch coverage and catches the
regression that matters most in practice.

---

---

---
