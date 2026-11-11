---
title: "The bot pull request needs no review; the merge acts only on a verified commit"
date: 2026-11-11
status: accepted
tags: [ci, dependabot, review]
---

# Context

The dependabot path could not merge a single pull request, and each of the
three reasons was a rule that could only ever fail.

1. **The review gate could not be satisfied on a bot pull request.** The `CI
   gate` job waits for the `Agent review` job, and that job requires a comment
   on the pull request that opens with `<!-- agent-review -->` and carries a
   verdict — the review of a *separate* agent. Nobody writes that comment on a
   dependabot pull request: there is no separate agent to write it, and the
   pull request carries no review. So the job was red on every bot pull
   request, and the gate that waits for it was red too. All eight open
   dependabot pull requests were stuck the same way — no comments at all,
   `Agent review` = failure, `CI gate` = failure. The auto-merge waits for
   `CI gate`, so it was waiting for a green that could never come.

2. **A conflicted pull request was handed to `gh pr merge`.** The poll step
   skips a pull request whose `mergeable_state` is `dirty`, but it left the
   pull request in `mergeable.txt` — the file the merge step reads. `gh pr
   merge` answers 405 for a pull request with conflicts, `set -euo pipefail`
   turned that into an aborted step, and every pull request after it was never
   merged. `dirty` also never set `waiting`, so the poll did not wait for it
   either: it moved on, and the merge step then tried to merge it.

3. **The merge was not pinned to the commit that was verified.** The green
   check-run is read from the head commit at the moment of the poll, but the
   merge happens later, with no constraint on which commit is merged. The head
   can move between the check and the merge, and then the commit that lands is
   not the one that was green.

# Idea

Three rules, each of them closing one of the defects:

- **The review gate does not apply to a pull request nobody can review.** The
  `Agent review` job is skipped on a bot pull request, and the gate counts a
  skipped job as a valid outcome.
- **The merge acts on what was verified.** The poll step collects the green
  pull requests, each with the head commit that was green, and the merge step
  reads that collection — not the list of candidates — and pins the commit.
- **One pull request that cannot be merged does not stop the others.** The
  merge loop records the failure and reports it at the end of the step.

# Decision

**A bot pull request is exempt from the review gate.** The `Agent review` job
is skipped when the pull request's author is `dependabot[bot]`. The job is
skipped, not removed from the gate's `needs:` list: the gate keeps waiting for
the review on a human pull request, and it already accepts a skipped job as a
valid outcome. Writing the review on the bot's behalf would not work — the
review is the check that a *separate* agent looked at the change, and a
comment on a bot pull request would be the same agent's own signature.

**The merge acts only on what was verified, and it is pinned to the commit.**
The poll step writes the green pull requests of the round into a second file,
each with the head commit that was green. The merge step reads that file. A
conflicted pull request is not in it: it cannot be merged at all, and it is
not going to un-conflict itself. The merge passes the commit it was given:
`gh pr merge <number> --match-head-commit <sha>`. A head that moved between
the check and the merge is then refused by the API, and the refusal is
reported instead of landing an unverified commit.

**One unmergeable pull request does not stop the others.** The merge loop
records a failed merge and reports it at the end of the step, instead of
aborting on the first failure. Pinning the head introduces a new way for one
pull request to block all the later ones — a moved head is refused — and the
refusal must not be the end of the loop.

# Rationale

**Why the job is skipped rather than the review written on the bot's
behalf.** The gate's meaning is "a second agent looked at this change". A bot
pull request is a dependency bump: the change was written by dependabot, and
the review that matters is the one that checks the bump against the
constraints — which is what the `CI gate` and the catalog gate already do.
Writing an `<!-- agent-review -->` comment on a bot pull request would keep
the job green while emptying it of meaning.

**Why a second file rather than a flag on the first.** The list of candidates
is derived in an earlier step, from the description of each pull request. The
list of what may be merged is derived here, from a check-run that is green at
this moment. Two different moments, two different facts: a pull request that
was green and is no longer must not be merged, and the file is rebuilt every
round so that it always says what the last round saw.

**Why the head is pinned.** The check-run belongs to a commit. The green
verdict is about that commit and no other. Merging without naming the commit
means the merge may land a different one — the check-run of a commit that was
never checked is not a green light, it is a light that was on somewhere else.

**Why the loop keeps going.** A batch merge that stops at the first refusal
turns one pull request's problem into every later pull request's problem. The
step reports which pull requests failed and exits non-zero, so the job is
still red — but the pull requests that could be merged were merged.

# Consequences

- A bot pull request's `CI gate` is decided by the checks alone. A human pull
  request's is decided by the checks *and* the review.
- The rule "a pull request carries the review of a separate agent" now has an
  exception, and the exception is written down where the rule lives:
  `docs/architecture/verification.md` and `docs/architecture/afk-runner.md`.
- A conflicted dependabot pull request is reported as an error and left for a
  human — it is never handed to `gh pr merge`, and it never blocks the pull
  requests behind it.
- A pull request whose head moved between the check and the merge is refused
  and reported; the other pull requests are still merged.
- The merge is pinned to the commit that was verified. A pull request that was
  updated after it went green waits for the next round.

# Links

- Files: `.github/workflows/ci.yml`,
  `.github/workflows/dependabot-auto-merge.yml`,
  `docs/architecture/verification.md`, `docs/architecture/afk-runner.md`
- Related: `2026-11-10-the-catalog-owns-the-version.md`,
  `2026-10-23-the-verdicts-share-one-registry.md`
