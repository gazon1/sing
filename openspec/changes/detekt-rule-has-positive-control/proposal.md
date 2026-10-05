# detekt-rule-has-positive-control

Issues: #135, #139 · Backlog entry: `detekt-rules-test-was-never-run-by-any-gate`

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

## The same question applies to gates, and only the gate half was asked

This change scopes the requirement to custom **detekt rules**, which is the right
scope for a change in `detekt-rules/`. It is worth recording that the question
generalises and that the general case has been half-answered, because the same
defect was found in a gate during the audit this change belongs to.

`docs/decisions/2026-10-05-gate-audit-text-shape-vs-fact` asked every gate that
decides whether a test run counts as evidence two questions: *what input passes
silently*, and *is there a synthetic negative control*. Thirteen gates were
audited. One had a live defect — `flow_has_tag` compared a tag for string
equality, so a tag with one trailing space was dropped from every run — and
three more surfaced only while writing the negative controls for the repair.

The pattern is identical to the rule case: a gate that matches nothing is
indistinguishable from a gate that is working, and nothing in a green report
distinguishes "there is nothing wrong" from "nothing was looked at".

**The two are not the same work, though, and conflating them would be a
mistake.** A detekt rule's positive control is a test that makes the rule fire.
A gate's negative control is a *sabotaged input that must fail the gate* — the
inverse. The useful property is shared, the artefact is not, and a check written
for rules will not serve gates. So this change stays where it is, and the gate
half is recorded as its own follow-up with the audit as its input:

- `scripts/check-gate-wiring.py` already sabotage-tests registered gates, which
  is the gate-side equivalent of a positive control and already exists.
- What is missing is a gate that asks *which gates have never been sabotaged* —
  the same derivation-from-registration requirement the second task below states
  for rules.
- The audit table is the input: for each gate, the silent input found (or "none
  found") and whether a synthetic control exists.

**Measured, 2026-10-05, over the thirteen audited gates:** one live defect
found, three more found by the repair's own negative controls, and nine gates
with a documented input that passes them silently. The two gates added since
(`check-test-runs.py`'s by-results half and the class-body scanner guard) each
ship with a negative control that was verified to fail first, which is the
arrangement the follow-up should hold every gate to.

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
