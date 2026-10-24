---
title: "The ratchet measures the branch's growth, so it compares against the merge-base"
date: 2026-10-24
status: accepted
slug: the-ratchet-measures-the-branch-growth
supersedes: null
---

# The ratchet measures the branch's growth, so it compares against the merge-base

## Context

`scripts/check-baseline-ratchet.py` watched `config/detekt/baseline-shared.xml` and failed when
the file grew. It compared the baseline **in the working tree** with the baseline **at `HEAD`**.

On a pull request those two are the same file. CI checks out a commit and reads the file that
commit carries, so the comparison was "the file against itself" — and a branch could add a
hundred suppressions without the gate noticing. The gate was green in the one place where the
answer mattered.

The defect was invisible locally too, but for the opposite reason: on a working copy the two sides
differ only by whatever is uncommitted, so the gate measured the *uncommitted* delta and said
nothing about the branch. Either way, the branch's own growth was outside the measurement.

The rest of the repository already had the right answer for this kind of question, and it was
spelled out in the `quick` job's checkout: *"The map compares against `origin/main`, so the base
reference has to exist. A shallow checkout has no such ref, and the tool refuses a base it cannot
resolve — a comparison that could not be made is not a comparison that found nothing."* The
baseline ratchet had no base at all.

## Idea

A ratchet answers one question: *did this branch make the debt worse?* The branch's work is
`merge-base(base, HEAD)..HEAD` — that is what GitHub's own pull-request diff is. The debt the
branch inherited is the baseline at the merge-base; the debt the branch leaves behind is the
baseline in the working tree. The difference is the branch's own growth, and it is what the gate
should measure.

## Decision

**The ratchet compares against the merge-base with the base branch, and names the revision it
compared against.**

- The base branch is `origin/main`, then `main`. `--base <ref>` and `SGATE_BASE_REF` override it.
- The revision compared against is `git merge-base <base> HEAD`. The gate prints it:
  `comparing against merge-base(origin/main, HEAD) = e553498147c9`.
- When no base branch exists — a shallow checkout, a repository with one branch — the comparison
  falls back to `HEAD` **and the gate says so in a warning**. The fallback is the blindness this
  change removes, so it is announced rather than taken quietly.
- The `static` CI job checks out with `fetch-depth: 0`, so `origin/main` exists and the comparison
  is the real one.

The rule the comparison expresses is unchanged: a baseline may shrink, never grow. What changed is
which side of the comparison answers the question.

## Rationale

**A gate that cannot fail is worse than no gate, because it is believed.** The phantom-invariant
tests in `scripts/tests/test_check_baseline_ratchet.py` exist for the same reason: a check that
finds nothing reads as a pass. The merge-base comparison has the same property — comparing a file
with itself always finds nothing — and the control test in the new suite asserts exactly that, so
the blindness is named rather than assumed away.

**The two tests are a pair, and that is the point.** One says the branch's growth is caught; the
other says the comparison it replaced (`HEAD`) would have missed it. A test that only proves the
gate fails would also pass against a gate that failed for the wrong reason — this repository has
been bitten by that shape more than once.

**Merge-base, not the base tip.** Comparing against `origin/main`'s tip instead would count the
base branch's own growth as the branch's: the base moves forward after the branch forks, and the
branch does not carry that movement. The merge-base is the common ancestor, so the delta is the
branch's and only the branch's — the same definition GitHub uses for the diff it shows.

## Consequences

- The gate now measures what it claims to measure. On the branch that renamed the calendar-sync
  package it reports `shrank by 170 entry(ies) (362 -> 192)`; against `HEAD` it reported
  `unchanged`, because the branch's work was already in `HEAD`.
- The `static` job fetches the full history. That costs checkout time and buys the base reference;
  the `quick` job already pays the same price for the same reason.
- A repository with no base branch gets a warning on every run. The warning is the honest outcome:
  the gate is weaker there, and now says so.
- The gate's behaviour on `main` itself is unchanged: the merge-base of `main` and `HEAD` is
  `HEAD`, so the comparison is the same as before.

## Links

- `scripts/check-baseline-ratchet.py` — the comparison and the resolution of the base branch.
- `scripts/tests/test_check_baseline_ratchet.py` — the pair of tests, and the control.
- `docs/decisions/2026-10-04-measurement-integrity.md` — a baseline that ratchets in one
  direction only.
- `docs/decisions/2026-10-16-ci-gradle-step-cannot-fail.md` — the same family of defect: a step
  that cannot fail, and what it costs.
