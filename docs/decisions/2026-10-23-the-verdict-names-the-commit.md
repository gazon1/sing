---
title: The verdict names the commit, and the claim names the run
date: 2026-10-23
status: accepted
tags: [process, gates, review]
---

# The verdict names the commit, and the claim names the run

## Context

Two claims ended a session on the wrong note, and both were the same kind of
mistake: a claim was made from something that looked like evidence and was not.

**A green CI was reported for a red branch.** The branch's CI run was `failure`
(`39888579980`), and the run the session quoted as green — `39888582872` — was
the *cache cleanup* workflow. `gh run list --branch main` answers with every
workflow that touched the branch, and the first successful line in that list
was not the build. The correction cost the reader a second pass over the logs.

**A review verdict that no longer covered its change.** The verdict gate was
written a week earlier (2026-10-22) so that a change carries the record of its
review. The record it required was a file with a verdict, a date and a pull
request: a claim that *a review happened*. It said nothing about *which* change
was reviewed, and a change's contract can be edited after the review — so a
"pass" could sit there describing text that was no longer in the tree.

Both claims are the ones a later session cannot check on its own, and both are
cheap to make checkable. The repository already has the idiom: a rule without a
gate is a rule the review holds on to, and a message says what happened.

## Decision

**A review verdict names what it reviewed.** The verdict file carries two more
fields:

- `reviewed:` — the commit the review was made against. A pointer for a reader
  who has the history, and shape-checked by the gate — a commit sha and not a
  sentence. It is not checked for existence: a squash-merge takes the reviewed
  commit out of `main`'s history, and a shallow clone does not have it either,
  so an existence check would be a check that fails for reasons that have
  nothing to do with the verdict.
- `documents:` — the digest of the change's *contract* documents: its proposal
  and its spec deltas, as the verdict speaks for them now. The gate recomputes
  the digest and compares, so a change edited after the review leaves a verdict
  that the gate reports, naming both digests. A contract that moves is
  re-issued, not re-read: the change that edits the contract re-issues the
  verdict with the new digest, in the same diff, where a reviewer sees both
  (see Consequences).

**The digest is over the contract, not over the whole change directory.**
The contract is the proposal and the spec deltas — what the change requires of
the code. `tasks.md` is a checklist that moves while the work is done; ticking
a box is not a change of what the change requires. A digest over the whole
directory would change every time the work moved, and a verdict that has to be
rewritten every time the work moves is a verdict nobody keeps. A change may
also carry a root-level `design.md`; it is outside the digest, because the
contract is what the change requires and the spec delta is where that is
written.

**The digest is not the commit.** The alternative — "the verdict names a commit,
and the commit is an ancestor of the branch head" — was rejected on a fact, not
on a preference: PR #978 landed by squash, so its reviewed commit
(`1cb18e39`) is *not* an ancestor of `main`. A rule that fails for every change
that landed by squash is a rule about the merge strategy, not about the verdict.
The digest says the same thing — "the verdict is about these documents" — and
survives the merge.

**A CI claim is a line.** `scripts/ci-verdict.py` prints the workflow that
decided, the run, and the conclusion, and its exit code *is* the conclusion:
0 for `success`, 1 for anything else, 2 when there is no run, 3 when the run
list could not be read at all. A missing run is not a pass, and a list that
could not be read is not a run.

The runs are filtered by the workflow that decides — matched on the display
name `gh run list` reports, "CI" for `ci.yml` — because a successful cache
cleanup is not a claim about the build.

**Two rules the reviewer applies** moved into `CODING_STANDARDS.md`, next to the
rule they sharpen:

- *A gate that derives a set from the tree proves the set is complete* — the
  set is compared with an independent enumeration, and the comparison is part of
  the gate. The slow-class rule is the shape of this: `unmapped_slow_classes`
  walks the test roots itself and names the slow classes that live in a root
  it does not map, instead of trusting a test to notice.
- *A number that the tree can change is generated, not written* — with the
  distinction that an ADR is history and a living document is the present. A
  count written by hand in a living document starts true and quietly stops
  being true: `docs/engineering/script-tooling-audit.md` says the corpus is
  1676 Kotlin files and 447 test classes, and the tree is now 1915 files and
  572 test classes. Nothing failed — the number was true when it was written.

## Rationale

The alternative to both decisions was to leave them to the review: a session
that reports CI should name the run, and a session that reviews a change should
say so. That is the practice the repository already has, and it is the practice
that produced the two failures above — not from carelessness, but because the
evidence was in front of the reader and the claim did not follow from it.

The decisions here are cheap, and the cheapness is the point: the digest costs a
hash of two files, the tool costs one `gh` call, and both make a claim that a
later session can check without trusting the session that made it.

The digest is deliberately *not* a hash of the diff. The diff grows with every
commit, and a digest that changed with every commit would say "this verdict is
stale" every time the work moved — a verdict that is always stale is a verdict
that is always rewritten, and rewritten verdicts are verdicts nobody checks.

## Consequences

- A change that is not in the baseline and has a verdict now needs two more
  fields. The one verdict that already existed on `main`
  (`ci-fast-only-pr-full-on-schedule`) was given them — and the first attempt
  was wrong, which is the finding that shaped this paragraph.

  The claim behind it was that the change's documents are byte-identical at
  the reviewed commit and in the tree today, and that
  `git diff 1cb18e39 origin/main --
  openspec/changes/ci-fast-only-pr-full-on-schedule/` is empty. It is not
  empty: PR #986 (`745b3afa`) added REQ-25 to the contract spec and rewrote
  the task list — 115 insertions — and the digest of the contract as it
  stands is `93c85260…`, not the `fb500744…` of the contract at the reviewed
  commit. The gate said so on the tree that carried the wrong digest, which is
  the mechanism working: it caught a claim about a contract that had moved.

  So the rule is not "the verdict carries the digest of what the reviewer
  saw" — that digest is a number nobody can look up later, and a contract that
  moves would leave the verdict red forever. The rule is that **a verdict
  speaks for the contract it carries**, and a contract that moves is
  re-issued: the change that edits the contract re-issues the verdict with
  the new digest, in the same diff, where a reviewer sees both. The gate
  catches the drift; whether the re-issue was earned is the reviewer's
  question, and a re-issue is a claim in a diff like any other.

  The verdict file for `ci-fast-only-pr-full-on-schedule` now carries the
  digest of the contract as it stands, and its prose says plainly that the
  review was of PR #978's diff and that REQ-25 arrived later, in #986.
- The documents of a change are written **before** the review. Editing the
  contract afterwards leaves a verdict the gate reports, which is the intended
  outcome and not a nuisance: the review has to be redone, because the thing it
  judged has changed.
- The gate's own digest is the one the verdict records
  (`--digest <slug>`), so the recorded value and the checked value cannot
  disagree about how a digest is computed.
- Nothing here depends on the run history. The digest is over the working tree,
  so the check is the same in CI, in the pre-push hook and on a laptop — no
  `fetch-depth: 0`, no shallow-clone failure mode.
- The review's *quality* is still the reviewer's judgement. The verdict says a
  review happened and what it covered; it does not say the review was good.

## Links

- `openspec/changes/2026-10-23-the-verdict-names-the-commit` — the change and
  the capability `change-review-integrity`
- `scripts/check-review-verdicts.py`, `scripts/ci-verdict.py`
- `docs/decisions/2026-10-22-the-rules-that-guard-the-rules.md` — the rule
  this one makes checkable
