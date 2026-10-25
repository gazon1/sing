# the-verdict-names-the-commit

**Status:** proposed · **Issues:** — · **ADRs:** `2026-10-23-the-verdict-names-the-commit`

## What

Two claims that a session makes about its own work become checkable.

A **review verdict** exists as a file — `.specify/reviews/<slug>.md` — but the file
says only that a review happened. It does not say the review was of *this* change:
the change's contract can be edited after the review, and the verdict keeps saying
"pass" about a contract that is no longer there. The verdict now names the commit
it was made against, and the digest of the change's contract documents; the gate
recomputes the digest and says when the contract moved under the verdict.

A **claim about CI** — "the branch is green" — is a run id and a conclusion, and
the run id is of a workflow: a repository runs several, and the one that answered
"success" may have been the cache cleanup. A session read the first successful
line of a run list and reported a green CI for a red branch. The claim is now a
whole line — workflow, run, conclusion — printed by one tool, whose exit code is
the conclusion.

Alongside them, two rules the review agent applies to what it reads: a number
that the tree can change is generated rather than written, and a gate that derives
a set from the tree proves the set is complete against an independent
enumeration.

## Why

The two claims are the ones a later session cannot check on its own. A verdict
file is evidence that a review happened; it is not evidence that the review
covers the change in the tree. A run id is evidence that a workflow ran; it is
not evidence that *the build* ran, or that it passed.

Both failures are silent and both cost a session a stop: the first was caught by
reading a red branch's logs after a report said it was green, the second by
finding that a change the reviewer had passed had been edited afterwards.

The shape of the fix follows the repository's own rule — a rule without a gate is
a rule the review holds on to. The verdict's identity is now a gate
(`check-review-verdicts.py` recomputes the digest), and the CI claim is a tool
whose exit code is the verdict (`scripts/ci-verdict.py`).

## How

- The verdict file gains two fields: `reviewed:` (the commit the review was made
  against) and `documents:` (the digest of the change's proposal and spec
  deltas). The gate computes the digest itself — `--digest <slug>` — so the
  verdict and the check cannot disagree about how it is computed.
- The digest covers the *contract*, not the whole change directory. `tasks.md`
  is a checklist that moves while the work is done, and a verdict that has to be
  rewritten every time the work moves is a verdict nobody keeps.
- The commit is recorded but not checked for ancestry: a pull request that lands
  by squash takes its reviewed commit out of `main`'s history, so "the verdict
  names a commit and the commit is still there" would fail for every change that
  landed that way. The digest says the same thing and survives the merge.
- `scripts/ci-verdict.py` reads the runs of the `CI` workflow for a commit, a
  branch or a pull request, prints the line, and exits with the conclusion:
  0 for `success`, 1 for anything else, 2 when there is no run, 3 when the run
  list could not be read. A missing run is not a pass, and a list that could
  not be read is not a run.
- The rules the review agent applies live in `CODING_STANDARDS.md`, next to the
  rule they sharpen: the completeness of a gate's set, the generation of a
  number, and the shape of a CI claim.

## What this does not do

- It does not check that the review was *good*. The verdict says a review
  happened and what it covered; the quality of the review is the reviewer's
  judgement, and the findings are the reviewer's text.
- It does not re-review a change whose code moved after the review. The digest
  covers the contract; the code is covered by the pull request's own CI and by
  the review that read the diff.
- It does not make the run history a dependency of the gate. The digest is over
  the documents in the working tree, so the check is the same in CI, in the
  pre-push hook and on a laptop.

## Links

- ADR `2026-10-23-the-verdict-names-the-commit`
- `scripts/check-review-verdicts.py`, `scripts/ci-verdict.py`
- `CODING_STANDARDS.md` — "A number that the tree can change is generated, not
  written", "A claim about CI names the workflow, the run and its conclusion"
