# detekt-rule-coverage-floor

Issue: #98 · Backlog entry: `detekt-rule-branch-coverage-owed`

## What

Turn the measured detekt-rule coverage from a report into a floor, and close the
branch gaps that the report has been naming since it was written.

No production behaviour changes. This is verification posture only.

## Why

`:detekt-rules` has carried the Kover plugin and a `just kover-rules` recipe
since this sweep began. `just kover-rules` prints a per-rule line-coverage
report; nothing compares it to anything. `koverVerify` is deliberately not wired
into `check`, and the comment says why — no floor exists yet, so a verification
task with no threshold would only produce noise.

That reasoning is correct right up to the point where the report becomes the
delivery mechanism. Line coverage at 92% is a number that appears in a terminal
when someone remembers to run the recipe, and the rules underneath it are the
rules that enforce every other rule in this repository. A rule with no test is a
rule that can stop matching without anything noticing.

There is a second problem, and it is the more interesting one. Every custom
detekt rule in this repository now has at least one positive test — added in
this sweep precisely so no rule can pass by never firing. Positive tests prove
the rule fires; they do not prove its branches are reachable. Several rules
guard multiple shapes, and a guard that is never exercised is indistinguishable
from a guard that is not needed. That distinction is what the coverage report
exposes and what a floor makes permanent.

## How

Set the floor below the current measurement, not at it, and record the gap
before setting it — so the floor is a ratchet and the uncovered rules are a
named list rather than a percentage nobody can act on. Then work the list.

`koverVerify` belongs in `:detekt-rules:check` once a threshold exists. It does
not belong in the root `check` yet: the root kover aggregation covers the
product modules and its floors are a separate concern with their own gate.
