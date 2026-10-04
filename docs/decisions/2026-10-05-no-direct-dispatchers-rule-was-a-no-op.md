---
title: A gate that cannot fail is worse than no gate — the NoDirectDispatchers no-op
date: 2026-10-05
status: accepted
tags: [detekt, tooling, process, tests]
---

## Context

Follow-up to `2026-10-05-doc-gates-must-parse-structure`, which hardened the
documentation gates. That work left one question open: the 58 tests written to protect
those gates were themselves runnable only by hand, and `:detekt-rules:test` was not run
by any job. Wiring them into CI surfaced 4 pre-existing failures in
`:detekt-rules:test`, dating from before that work.

Investigating those 4 found something the earlier audit had reported as clean.

`NoDirectDispatchersRule` bans `Dispatchers.IO`, `Dispatchers.Default` and
`Dispatchers.Main` in commonMain. It had:

- a `RuleSetProvider` declaring `ruleSetId = "no-direct-dispatchers"`
- a correct entry in `META-INF/services/dev.detekt.api.RuleSetProvider`
- a `detekt.yml` block (added earlier the same day, when it was found dormant)
- a backlog entry describing its whitelist and its intended scope
- a KDoc explaining the policy in detail

And it could not report a single finding for any input.

`checkExpression` required both of:

```kotlin
val selector = expr.selectorExpression as? KtCallExpression ?: return
val receiver = expr.receiverExpression as? KtNameReferenceExpression ?: return
```

Confirmed against the PSI (by printing the actual tree, not by reading the code):

| Expression | receiver | selector |
|---|---|---|
| `Dispatchers.Default` | `KtNameReferenceExpression` | `KtNameReferenceExpression` |
| `Dispatchers.IO.limitedParallelism(1)` | `KtDotQualifiedExpression` | `KtCallExpression` |

`IO` is a *property*, so its selector is never a call. And in the chained form the
receiver is dot-qualified, never a bare name. Every possible input hit one of the two
early `return`s. The two tests asserting the rule *flags* something had been failing
since they were written; the two asserting it *stays quiet* were passing, because a rule
that never fires trivially satisfies every "does not report" assertion.

## Idea

1. Fix the tests to match the rule's current behaviour.
2. Delete the rule as unusable.
3. Fix the rule, and treat the two failing tests as the specification it should have had.

Option 1 makes the green build permanent and is indistinguishable from option 2 in
effect: a rule that reports nothing and a rule that does not exist behave the same on
every future code change. Option 3 is the only one that leaves the codebase with
something working.

## Decision

Fix the rule, and scope it to what its KDoc already claimed.

The scope question was the real design decision, and the tree answered it. Counting
occurrences of `Dispatchers.{IO,Default,Main}` in code (not comments) per source set:

- **commonMain: 1** — `core/log/FileLogWriter.kt:50`, the whitelisted line.
- **jvmMain: 8** — `FileRevealer.jvm`, `JvmNotificationPort`, `JvmSecureStorage`,
  `createBackgroundScope`.
- **androidMain: 11** — `AndroidSecureStorage`, `AndroidCalendarProvider`,
  `createBackgroundScope`.

All 19 platform occurrences are inside port implementations. This project's KMP
convention puts the interface in `commonMain` and the implementation in the platform
source sets, and picking the dispatcher is *the implementation's job*. A rule that
flagged those would ban the ports from implementing their ports. So the rule stops at
the commonMain boundary, which is what "bans direct references in commonMain production
code" always meant.

The decision logic is extracted into `NoDirectDispatchersPolicy`, a pure function,
because the rule's branches are path-based and `compileContentForTest(content, Path)`
**discards the directory component** of the path you hand it — `virtualFilePath` comes
back as `/X.kt` for any input. A path-scoped rule is therefore untestable through the
PSI layer. `NoDirectClockSystemRule` has the same limitation, and its `FileLogWriter`
whitelist has no test for exactly this reason.

An unrecognised path is treated as commonMain rather than exempt. Failing closed
matters: a path shape we did not anticipate should cost an explicit whitelist entry, not
silently disable the ban.

`:detekt-rules:test` and the gate-script tests are now blocking CI steps.

## Rationale

The failure mode here is the same one this whole sweep was about, one level in: **a gate
reporting success because it cannot fail.** The earlier audit listed this rule as
correctly registered and correctly packaged, and it was — that was the whole point of
D4. Registration is not operation. The rule was inspectable in every dimension the
existing checks looked at, and inert in the one that mattered.

Two things made it invisible, and both are worth naming because they recur:

**A test suite nobody runs is not a test suite.** `NoDirectDispatchersRuleTest` had the
right assertions. It asserted `Dispatchers_IO is flagged`, the rule did not flag it, and
the test failed — for as long as the test existed. Nothing read that result, so the
correct expectation and the broken implementation sat in the repository together,
unreconciled. Fixing the gates and then adding unwired tests would have reproduced the
original defect one level up; wiring both into CI is the part that actually closes it.

**Silence is not evidence.** The earlier work concluded from "`:shared:detekt` passes
with 0 findings" that `FileLogWriter.kt` was the only call site. That inference was
invalid, and it was written down in the backlog and in an ADR as a verified fact. A rule
that cannot report produces 0 findings under every possible input, so its silence is
uninformative. The claim is corrected in both places, because a false verification
recorded as fact is worse than no verification — it stops the next person looking.

The second failing test, `NoEmptyOnClickLambdaRuleTest`, was the opposite case: the test
was wrong, not the rule. It asserted that a parameter *defaulting* to `{}` plus a
`param ?: fallback` elvis would be flagged, but the rule's documented contract is an
empty lambda as a *named call argument*, and that shape is covered by
`scripts/find-unwired-surfaces.py` detector 2 (`default-noop`) — a different tool doing a
different job. The test was rewritten to assert the real contract, plus an explicit test
recording the division of labour so the next reader does not re-derive it. Fixing that
test by extending the rule would have been scope creep with codebase-wide effect: it
would flag every `= {}` default in the project.

## Consequences

- `NoDirectDispatchersRule` reports correctly and is scoped to commonMain. Verified:
  `:shared:detekt` reports 0 findings, and that result is now produced by a rule that
  can fail — 17 tests, including `rule is not a no-op`, which exists because every other
  test in the file is a filtering assertion and a filter that never matches passes all
  of them.
- `:detekt-rules:test` goes from failing to 73 tests / 0 failures, and is a blocking CI
  step for the first time.
- The gate-script tests are a blocking CI step. That step also asserts each test file
  reports at least one test: a file missing `unittest.main()` defines its tests, runs
  none, and exits 0 — verified by probe, and it would otherwise pass while testing
  nothing.
- ci.yml's path filter is widened from 3 entries to 16. It previously matched
  `openspec/**`, the workflow file and one script, so the job was skipped for most PRs
  — including any PR touching a detekt rule or a gate script. Steps that run on 2 of 30
  PRs are decoration.
- `NoDirectClockSystemRule` still has an untested path-based whitelist for the same
  structural reason. The `NoDirectDispatchersPolicy` extraction is the pattern to
  follow; applying it there is a small, separate change.
- 7 custom detekt rules still have no dedicated tests. This incident is the argument for
  writing them: `NoDirectDispatchersRule` had a test file, and the failure mode was still
  invisible.

## Links

- `2026-10-05-doc-gates-must-parse-structure` — the governance sweep that found the rule
  dormant and, correctly, stopped at the registration gap
- `docs/decisions/deferred-backlog.md` — `no-direct-dispatchers-rule-one-whitelisted-case`,
  corrected; `no-empty-onclick-lambda-rule-findings-sweep-pending`
- `singularity-todo-detekt-rules-authoring` skill
- `singularity-todo-test-tag-strategy` — the module's test conventions
