# scenario-results-are-authoritative-in-ci

Issues: #149, #150 · Backlog entry: `scenario-result-missing-for-a-claiming-commit-is-not-a-failure`

## What

Make a scenario's result **authoritative in CI**, in two parts:

1. a scenario whose claimed target has no result at the build's own commit **fails
   the build**;
2. a target whose flows run somewhere CI cannot reach is **stated as such** in the
   matrix, rather than rendered as an outcome nobody filled in.

## Why

`test-execution-integrity` exists because a build was green for months while
selecting 16 of 218 test classes. Three more faces of the same defect are already
pinned. This is a fifth.

The scenario layer renders four outcomes and distinguishes the one that matters:
a target that is *claimed* but produced no result at this commit. It reports it,
and **nothing acts on it**. A reader scanning the matrix sees a cell and cannot
tell whether it means "verified" or "nobody looked" — which is the same
ambiguity the class-count floor was created to remove.

Part 2 exists because the honest fix for part 1 is not free. The Android flows run
in a different CI job from the one that builds the matrix, and their output is
discarded after the run. So closing part 1 naively would turn every build whose
Android job did not run into a red build for the wrong reason. The requirement is
therefore written so that *not running a target* and *running it and getting
nothing* stay distinguishable — and so that a target the build genuinely did not
cover has to say so out loud instead of looking empty.

## What this does NOT do

- It does not fail a build for a target that was never claimed by a scenario.
- It does not require the Android flows to run in the main job. That is a cost
  decision, not a correctness one, and is deliberately left open.
- It does not change what a scenario is, how one is written, or how a result is
  produced. No product behaviour changes; this is verification posture only.
- It does not retire the per-class never-run floor. The two measure different
  things with opposite polarity (see #157).

## Rollback risk

Low. Nothing here changes the product, and each requirement is a check that can be
removed independently. The one caveat is part 1: shipping it before part 2's
"state what you did not cover" mechanism exists would produce false reds for any
build that legitimately does not run a target.
