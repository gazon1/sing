# detekt-rule-has-positive-control

Issue: #135 · Backlog entry: `detekt-rules-test-was-never-run-by-any-gate`

## What

Make it impossible to add a custom lint rule to this repository that nothing
tests, and prove the new check can fail.

No product behaviour changes. This is verification posture only.

## Why

The originating audit found a rule that shipped registered, packaged, given a
configuration block, and referenced by a backlog entry — and that could not
report a single finding for any input. It required the dot-qualified selector to
be a call expression, but in the real code the selector is a property
reference, so both possible parse shapes hit an early return. Two of its four
tests had been failing since they were written; the two that passed asserted the
rule *stays quiet*, which a rule that never fires satisfies trivially. Nobody
read the result, because no gate ran that module's tests.

Every rule has a positive control today. `RuleFiresSmokeTest.kt` supplies one
for every rule that has no dedicated test class, and the per-rule classes cover
the rest — 20 rules, 146 green tests. **That is the state, not the guarantee.**
Nothing in the repository requires the twenty-first to be the same, and
`scripts/` contains no check that a rule has a test at all. The existing
declaration gate answers a different question: it proves every rule that
*reports* is a written-down decision, which is satisfied just as well by a rule
that reports nothing, forever.

There is a second reason this needs writing down rather than remembering. The
first attempt at measuring the gap reported that five rules had no test, by
looking for a dedicated test file per rule. All five were covered by the smoke
test. The check that produced the wrong number is the same shape as the check
being proposed here, which is why the sabotage test below is part of the
deliverable and not a nicety.

## How to verify the gate itself is honest

Delete one positive control. The new check must go red and name the rule whose
control is gone. Restore it and the check returns to green. A gate that has only
ever been seen green has not been tested.

## Scope

Out of scope, and tracked elsewhere:

- branch coverage of the rules — #98, whose floor would catch a guard that never
  runs, which a positive control cannot
- the negative controls (a rule flagging correct code) — a separate and larger
  body of work
