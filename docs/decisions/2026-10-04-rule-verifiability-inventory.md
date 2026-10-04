---
title: "Rule verifiability inventory — six rules that did not do what their KDoc claimed"
date: 2026-10-04
status: accepted
tags: [detekt, quality-gates, testing, verifiability, ci]
---

# Rule verifiability inventory — six rules that did not do what their KDoc claimed

## Context

`2026-10-04-ci-test-tag-filter-and-vacuous-gates` established that the repository's
gates reported success without exercising the behaviour they described. That change
proved the claim for `NoDirectDispatchers`, `NoEmptyOnClickLambda` and
`ProhibitUserIdInObserve`, wired `:detekt-rules:test` into `check.sh` and `ci.yml`,
and recorded the rest as backlog.

This entry is the follow-through: the same method — read the rule, write a
positive control, run it, and believe the result over the KDoc — applied to the
remaining suspects named in the audit. **Five of the six were real defects.** One
was a false accusation worth recording too, because "the plan said so" was not
evidence.

## Findings

| # | Rule | KDoc claims | Reality | Status |
|---|---|---|---|---|
| 1 | `PassThroughUseCase` | bans pass-through use cases (AGENTS.md: "enforced by rule") | `resolveReceiverType` read only `KtClass.declarations.filterIsInstance<KtProperty>()`. A primary-constructor `val` is a `KtParameter`, so the lookup returned null and the rule bailed before reporting. | **fixed** |
| 2 | `NoStateIn` | exempts VMs annotated `@OptIn(CombineStateInReadThrough::class)` | that annotation exists nowhere in the repository. The exemption was unreachable, and the rule told agents to apply an annotation that would not compile. | **fixed** (hatch removed) |
| 3 | `NoRealDelayInTest` | bans `delay(N)` above 500 ms | `valueText.toLongOrNull()` cannot parse `1_000` or `1000L` — the spellings the codebase actually uses. `delay(1000L)` exists in this repo and bypassed the check. | **fixed** |
| 4 | `NoRealDelayInTest` | one finding per `Thread.sleep` | both `visitCallExpression` and `visitDotQualifiedExpression` reported it, so every `Thread.sleep` was counted twice. Found by a test asserting an exact count. | **fixed** |
| 5 | `KDocEnforcement*` | every ViewModel / repository interface has class KDoc | iterated `root.declarations`, which is **top-level only**. A class nested in an `object` or another class was never inspected. | **fixed** (full tree walk) |
| 6 | `ProhibitUserIdInObserve` | "also flags implementations" | guard was `endsWith("Repository")`, which cannot match `TaskRepositoryImpl`. The claim was the exact opposite of the behaviour. | **fixed** |

Plus one correction to the audit itself:

- **`NoRunCatchingInSuspend` was not one of the broken rules.** It is registered,
  configured, and has a passing test. It is simply `active: false`, deliberately,
  with 239 `runCatching` occurrences in `commonMain` to migrate first. The audit
  listed it among the vacuous rules; that was wrong, and acting on it would have
  meant flipping a rule on before the migration that makes flipping it safe.
- **The audit's "109 long delay sites" is wrong.** The repository has 23 `delay(`
  call sites in total. The 500 ms threshold is also a deliberate, documented escape
  hatch (for `stateIn(WhileSubscribed(5000))` VMs), not an accident — which is
  why it is now a named constant rather than a bare `500`.

## Decision

**1. Fix the rules; never weaken the test that caught them.** Each finding was
reproduced by a test first. Where a test and the rule disagreed, the rule was
changed — including `NoEmptyOnClickLambda`, where the first reading (flag the
empty default parameter) was wrong and the existing pair of tests made the real
distinction obvious: `onClick: () -> Unit = {}` is a normal optional default, while
`onClick ?: { }` is a dead fallback. Deleting or loosening the test to make the
build green would have reproduced the original defect.

**2. `:detekt-rules:test` is the load-bearing gate.** 56 tests had never been
executed by anything; 4 failed. 76 run and pass now, after adding
`PassThroughUseCaseRuleTest`, `NoRealDelayInTestRuleTest` and `NoStateInRuleTest`.
One rule class (`KDocEnforcementRules`) still has no test — tracked below.

**3. Prefer a visible `@Suppress` to an invisible hatch.** With
`CombineStateInReadThrough` gone, the sanctioned way out of `NoStateIn` is
`@Suppress("NoStateIn")` with a reason. A documented escape hatch that does not
exist is worse than none: it reads to an agent as permission, and the failure only
appears at compile time.

## Consequences

- The rules now reach code the old guards skipped. Whether that surfaces findings
  is a separate question, and the answer is the honest measure of how much the
  previous "0 findings" was worth — see the detekt baseline delta recorded in the
  2026-10-04 change.
- `KDocEnforcementRules` is the last rule class without a unit test. Its two rules
  are trivially testable (a class with and without a doc comment, nested and
  top-level) and the nesting fix is exactly the kind of change that needs one.
  It is recorded in `deferred-backlog.md`, not fixed here, because the remaining
  audit items in this cycle were the higher-severity ones.
- The generalisable rule: **a rule's KDoc is a claim, not evidence.** When the two
  disagree, the test run decides. Four of the six findings above were invisible in
  code review because the KDoc was plausible and the implementation was quietly
  narrower.

## Links

- `2026-10-04-ci-test-tag-filter-and-vacuous-gates.md` — the CI half of this work.
- `deferred-backlog.md` — `detekt-rules-test-was-never-run-by-any-gate` and
  `two-rulesets-were-vacuous-52-violations-were-invisible`.
- `singularity-todo-detekt-rules-authoring` skill — the positive-control method
  this inventory applies; it should be the first thing an agent reads before
  adding or trusting a rule here.
