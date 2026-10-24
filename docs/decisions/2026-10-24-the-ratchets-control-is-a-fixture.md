---
title: "The ratchet's control is a fixture, because a sabotage cannot grow the baseline"
date: 2026-10-24
status: accepted
slug: the-ratchets-control-is-a-fixture
---

# The ratchet's control is a fixture, because a sabotage cannot grow the baseline

## Context

The detekt baseline ratchet measures the branch's own growth: it compares the baseline in the working
tree with the baseline at the merge-base with the base branch (`2026-10-24-the-ratchet-measures-the-branch-growth.md`).
Its control in the gate suite was a **sabotage**: the suite edited the real tree — it appended one
`<ID>` to `config/detekt/baseline-shared.xml` — and asserted that the gate then failed.

That control could not fail, and it failed for a reason that had nothing to do with the gate.

The branch that introduced the merge-base comparison had *shrunk* the baseline: renaming the
calendar-sync package removed 170 suppressed findings. A sabotage adds one entry to a baseline that
is already 170 entries smaller than what it inherited, and the comparison is a net one — "the
working tree's baseline is 169 entries smaller than the merge-base's". The gate correctly said
**OK**, and the control reported a gate that cannot fail.

The control was measuring the wrong thing. It asked "does the gate notice one added entry?" — and
the honest answer was no, because the gate measures a *difference*, and a difference that is
negative is not a violation. The defect the control was meant to catch is a branch whose baseline
grows **relative to what it inherited**, and a sabotage in a tree whose branch shrank the baseline
cannot produce that.

## Idea

A control has to ask the question the gate exists to answer. Here that question needs a history: a
branch that forks from `main` and grows the baseline by one entry. The real tree cannot provide it,
because the real tree's history is the branch's own history — and that branch shrank the baseline.

A fixture can: a repository of its own, with a `main`, a branch, and one commit that grows the
baseline.

## Decision

**The ratchet's control is a fixture: a synthetic repository whose branch really grew the baseline.**

- The fixture builds `repo/` with a detekt config, three baseline files and a git history.
- `main` holds a baseline of two entries; the branch commits a baseline of three.
- The gate is pointed at that repository with `--root`, so it reads the fixture's
  baseline, the fixture's detekt config and the fixture's git. The checkout the gate
  lives in has the wrong history — the question needs a branch, and that branch has to
  have really grown.
- The control asserts the gate **fails** on that repository. A gate that passed it would be a gate
  that cannot see growth, and the fixture says so.

The fixture needs a git environment of its own: git exports `GIT_DIR` and `GIT_WORK_TREE` for the
hooks it runs, and a fixture that inherits them writes its commits into somebody else's repository.
The sandbox environment clears them and sets an author, so the fixture's commits are the fixture's.

The `--root` option exists because of this decision, and it follows the precedent of
`scripts/check-slow-tests-ran.py --root`: a gate that reads a synthesised input has to be
pointable at that input. The gate's own identity in the registry does not change — the control
still runs `scripts/check-baseline-ratchet.py`, and only adds the root it measures.

## Rationale

**A control that cannot fail is worse than no control, because it is believed.** The sabotage said
"the gate catches a grown baseline", and the suite believed it for the whole life of the merge-base
comparison — while the gate was, in the real tree, comparing a shrunk baseline against a bigger one
and correctly passing. The fixture says what the sabotage could not.

**The control and the gate must ask the same question.** The gate's question is "did this branch
make the debt worse?". In a tree whose branch shrank the debt, the honest answer to "did one entry
get added?" is "no, and 169 got removed". The fixture puts the branch in the state where the
question has the answer the gate is supposed to find.

**A fixture that did not change anything would prove nothing.** The edge-function fixture raises if
its sabotage does not modify the copy — a control that cannot even damage its subject is not a
control. The ratchet fixture builds a branch that genuinely grows, and the assertion is that the
gate genuinely fails.

## Consequences

- The ratchet gate leaves `SCRIPT_GATES` and appears in `FIXTURE_GATES`. It is still a blocking gate
  in the lint suite; what changed is how the suite proves it can fail.
- The control runs the gate against a temporary repository, so it needs `git` and no network.
- The sabotage that the old control performed is gone: nothing edits `config/detekt/baseline-shared.xml`
  in the real tree any more, so a failing control can no longer leave a modified baseline behind.
- The gate itself is untouched — it compares against the merge-base and prints the revision it
  compared against, as `2026-10-24-the-ratchet-measures-the-branch-growth.md` decided.

## Links

- `scripts/check-baseline-ratchet.py` — the comparison the fixture exercises.
- `scripts/src/sgate/gate_controls.py` — `_setup_baseline_ratchet`, the fixture that builds the
  repository whose branch grew.
- `docs/decisions/2026-10-24-the-ratchet-measures-the-branch-growth.md` — the comparison itself.
- `docs/decisions/2026-10-04-measurement-integrity.md` — a baseline that ratchets in one direction
  only.
