---
title: "The merged branch is pruned"
date: 2026-10-23
status: accepted
tags: [git, workflow, tooling]
---

# The merged branch is pruned

## Context

This repository merges pull requests by **squash**. That was a deliberate
choice: `main` gets one commit per pull request, the history stays linear and
readable, and a half-finished branch never leaks into the trunk as a set of
merge commits. The process records say so —
`docs/decisions/2026-09-26-code-review-process.md` ("squash-merge preferred
for feature branches") and `docs/decisions/2026-09-26-four-phases-gate.md`
("Author squash-merges to main"). The price of that choice is paid
somewhere else, and it is paid silently.

A squash-merge does not make the branch an ancestor of `main`. The branch's
history is rewritten into a single new commit on `main`, and the branch
itself keeps its own, unrelated, history. Git sees the truth plainly: the
branch is not merged, so `git branch --merged` does not list it and
`git branch -d` refuses to delete it with "not fully merged". The branch is
not stale — it is simply invisible to the two commands that are supposed to
clean up after a merge.

The result is that local branches accumulate. The pull requests #1019,
#1020, #1021, #1022, #1023, #1024, #1030, #1031 and #1035 were merged by
squash, and seven of their branches were still in the checkout afterwards —
the branches of #1030 and #1035 had already been deleted by hand. Those nine
pull requests are a sample, not the scale.

The scale was measured on **2026-10-24**, in the checkout this ADR was
written in, with `python3 scripts/prune-merged-branches.py --json`:

| what | how many |
|---|---|
| merged pull requests in the repository | 311 |
| local branches in that checkout | 282 |
| branches whose pull request had been merged | 164 |
| — of those, in the plan (tip is the merged head) | 115 |
| — of those, in the plan (tip is *behind* the merged head) | 1 |
| — of those, held by another worktree | 19 |
| — of those, moved on after the merge | 29 |
| branches with no merged pull request of ours | 116 |

Nothing warns about any of it: the tree is not dirty, no gate fails, and
the worktree works. The cost is paid later — in `git branch` listings nobody
reads, in a stale worktree whose branch was merged three weeks ago, and in
the moments where an agent picks a branch that looks alive and builds on it.

The cleanup that git itself offers cannot be used here. `git branch -d` is
refusing for the right reason: from git's point of view the branch really
was not merged. The knowledge that it *was* merged — as a squash — lives
in one place only: GitHub, which knows that the pull request closed, which
branch it came from, and which commit it merged.

## Decision

1. **`scripts/prune-merged-branches.py` exists.** It asks GitHub which pull
   requests are merged (`gh pr list --state merged`), takes the local
   branches from git, and builds a plan: the local branches whose tip is the
   commit that was merged, or an ancestor of it.

2. **By default it only prints the plan.** Deleting requires the explicit
   `--yes` flag, and with `--yes` the script prints what it deleted.
   `--dry-run` is the same as the default, `--json` is the same plan for a
   machine. The plan reports, for every branch it would delete, whether the
   local tip *is* the merged head or is *behind* it — so that the rule the
   tool follows can be read off its output.

3. **Seven things are never deleted**, each with its own reason in the
   output:

   1. the branch the current checkout stands on;
   2. a branch held by another worktree (`git worktree list --porcelain` —
      git refuses to delete such a branch anyway);
   3. `main` (and `master`);
   4. a branch with no merged pull request of our own repository — it may
      have an open pull request, and that pull request is still work in
      progress;
   5. a branch whose name matches a merged pull request of a **fork** — that
      pull request is somebody else's, and the local branch of the same name
      is not the same thing; the output says so, rather than claiming that
      there is no merged pull request at all;
   6. a branch that could not be **verified** — `gh` reported no commit for
      the pull request, or the local tip could not be read, so there is
      nothing to compare the branch with and the name alone is not evidence;
   7. a branch that **moved on after the merge** — see the next point.

4. **A branch is deleted when its tip is the commit that was merged, or an
   ancestor of it.** The plan compares the local tip with the pull request's
   `headRefOid`:

   - the tip **is** the merged commit — the pull request merged exactly this
     branch, and the branch has not moved since;
   - the tip is an **ancestor** of the merged commit — the branch is behind
     the pull request. Everything it holds is inside the merged pull
     request, and the merged pull request is in `main`. There is nothing in
     the branch that is not already in `main`, so deleting it loses nothing.

   A branch whose tip is *neither* — that moved on after the merge, or that
   was rewritten — is reported in its own category ("moved on after the
   merge") together with the commits it would lose and whether those commits
   exist anywhere else. It is never deleted. The name of a branch is not
   evidence: names are reused, and a branch can carry a whole day of work
   under the name of a pull request that was merged a week ago.

5. **The script is a tool, not a gate.** It changes the working tree of a
   person, on their machine, at their request — that is not what a gate in
   CI is, and it does not belong in the gate registry. It exits 0 when the
   plan was built (and, with `--yes`, when everything was deleted), 1 when a
   tool failed (`gh` is not installed, a branch could not be deleted) and 2
   on a usage error (`--yes` together with `--dry-run`, an empty `--owner`).

6. **The repository and the owner come from the checkout.** The pull
   requests are read from the repository the `origin` remote points at, and
   only the branches of that repository's owner are candidates. A clone of a
   fork must not compare its own branches with the pull requests of the
   upstream. When `origin` cannot be read at all, the tool falls back to
   the repository it knows (`gazon1/sing`) and says which one it used — a
   tool that compares a fork's branches with the upstream's pull requests
   must not do it silently.

7. **The request to GitHub is part of the contract.** The tool asks for
   `--state merged`, for the merged commit (`headRefOid`), for the owner of
   the head, for the repository it is reading, and for more pull requests
   than one page of `gh` holds — 311 merged pull requests exist, and the
   default page of `gh` is 30. The test suite holds this contract: the
   stand-in for `gh` answers the way `gh` answers, so a query for closed
   pull requests, a query without the merged commit, a query for another
   repository and a query with the default page size all fail the suite.

## Rationale

- **The default is "print", because "delete" is a decision, not a
  consequence.** The fact that a pull request was merged says that its
  content is in `main`. It does not say that nobody still needs the branch:
  its author may be comparing the old head with the new one, another
  session may be standing on it, and the branch may carry local commits
  that were never pushed and never will be. A tool that deletes on the
  strength of a merged pull request alone takes that decision away from the
  person who owns the branch. Printing the plan keeps the decision where it
  belongs and still removes the part that is pure labour: finding out
  *which* branches are the leftovers.

- **The branch of the current checkout is not deleted.** Two reasons, and
  they are not the same reason. Git refuses to delete a branch that is
  checked out, so the attempt would fail anyway — a tool that fails where
  it could have said "this one is in use" teaches its reader that its
  output is noise. And the current branch is, by definition, the branch
  somebody is working on right now; the fact that its pull request was
  merged says that the *remote* work is done, not that the local branch is
  finished.

- **A branch held by another worktree is not deleted.** Git refuses such a
  branch, so the plan that promised to delete it would turn into a failed
  run with a non-zero exit code — the tool would report an error for a
  branch it had just listed as safe. In the checkout this ADR was written
  in, nineteen of the branches with a merged pull request were held by
  another worktree.

- **A branch behind the merged head is deleted, because there is nothing in
  it that is not already in `main`.** Its tip is an ancestor of the commit
  the pull request merged, so every commit it holds is inside that pull
  request — and that pull request is in `main`. Deleting the branch
  therefore destroys no work; it only removes a name that points at
  history `main` already carries. The alternative — refusing to delete
  such a branch — would leave behind exactly the leftovers the script
  exists to remove, and the "moved on" category would have to be able to
  tell a harmless branch from a dangerous one by something other than the
  commit. One of the 164 branches in that checkout was behind its merged
  head; it is in the plan, and the plan says why: "tip behind the head of
  the pull request — the content is already in `main`".

- **A branch that moved on after the merge is not deleted.** The merged
  pull request proves that *its* content is in `main`; it says nothing
  about the commits that arrived afterwards. `git branch -D` deletes the
  branch and those commits with it, and the reflog is not a place to keep
  work. Of the 164 branches with a merged pull request in that checkout, 29
  had moved on since the merge, and **nine** of them carried commits that
  exist in no `origin/*` and in no `main` — that is the criterion the tool
  itself computes, and it is the number the tool prints: for each moved
  branch it says how many of its commits are "only in the local copy". The
  largest such branch held 19 such commits. Deleting those branches would
  have destroyed that work, silently, from a plan that looked like a list
  of safe leftovers. The script prints those branches in their own
  category, with the commits and the answer to the only question that
  matters there: is there another copy of them? The list it prints is the
  list that answers that question — the commits that exist in no `origin/*`
  and in no `main` — and not the whole history of a branch that happens to
  carry them somewhere in the middle. When the list is longer than the
  printing cap, it says so.

- **`main` is protected by name, not by ancestry.** The rule "never delete
  the trunk" is stated where it can be read, rather than inferred from a
  property (`is it merged?`) that a squash-merge breaks. `main` has no
  pull request of its own, so the plan would not contain it in practice —
  but a rule that only holds by accident is a rule that is one refactor
  away from not holding.

- **A branch without a merged pull request is left alone.** It may have an
  open pull request, it may belong to another session, and it may be a
  branch whose work was never pushed. The script does not guess; it
  reports the branch as untouched and lets the reader decide.

- **A branch of a fork is left alone, and is named as such.** The pull
  request exists and it is merged — of the fork. Saying "there is no merged
  pull request" about such a branch would be false, and a reader who
  trusts the output would take the reason for the exclusion to be the
  wrong one.

- **A branch that cannot be verified is left alone.** Without the merged
  commit there is nothing to compare the local tip with, and the name of a
  branch is not evidence: `gazon1/noddy` has five merged pull requests
  under one name. "Not verified" is a reason of its own, and it is
  different from "there is no merged pull request".

- **`-D`, not `-d`.** After a squash-merge the branch is not an ancestor of
  `main`, so the careful `-d` would refuse exactly the branches this script
  exists for. The safety is not in the flag but in the plan: the plan holds
  only branches whose tip is the commit that was merged **or an ancestor of
  it**, and that no worktree holds. `-D` then says what it means — the
  decision was already made, by the person, and the plan is the record of
  it.

- **The script asks GitHub rather than guessing from git.** Git can only
  tell that a branch was *not* merged by ancestry. GitHub knows that the
  pull request was closed, that the branch was its head, and which commit
  it merged. The merged pull request is the only evidence that is both
  cheap to read and sufficient — and `gh` is already on the machine and
  authorised for this repository, so the question costs one command.

## Consequences

- The owner of the repository can clean the leftovers with one command, and
  can see what the command would do before it does it:

  ```bash
  python3 scripts/prune-merged-branches.py        # the plan
  python3 scripts/prune-merged-branches.py --yes  # the plan, acted on
  ```

- The plan is honest about the branches it refuses: each of them carries
  the reason it was left alone, and the branches that moved on after the
  merge carry the commits that would be lost.

- The numbers in this ADR can be re-checked with
  `python3 scripts/prune-merged-branches.py --json`: the plan says how many
  branches it holds and whether each tip is at the merged head or behind it,
  and the moved branches say how many of their commits exist in no
  `origin/*` and in no `main`.

- The script is idempotent: a branch that has already been deleted is not
  in the plan, so a second run finds nothing to do.

- The number of leftover branches is no longer a fact about the
  repository's discipline; it is a fact about who ran the script.

- The cost of this decision is that the cleanup is not automatic. Squash-
  merge still leaves a branch behind after every pull request, and the
  script is something a person has to remember to run. An automatic
  cleanup — a hook, or a CI job that deletes the branch of a merged pull
  request — would remove the leftovers without anyone thinking about it,
  and it would also remove the branch of a person who still wants it,
  without asking. The price of the manual step is accepted in exchange
  for the deletion staying a decision.

- A branch whose pull request was merged by a merge commit rather than by
  a squash is found by the plan as well: `gh pr list --state merged`
  reports it, and if the local branch still points at the merged head, the
  plan lists it and `--yes` deletes it. That is harmless — such a branch
  is an ancestor of `main`, and `git branch -d` would have deleted it
  without help. The script exists for the case git cannot see; it does not
  refuse to touch the case git can.

- A pull request that was merged and then reverted still counts as merged:
  `gh pr list --state merged` does not distinguish a reverted merge. The
  branch of such a pull request appears in the plan like any other, and
  the human is the one who reads it.

- A branch whose name was reused after its pull request was merged is
  judged by the commit, not by the name: the plan looks at every merged
  pull request that carried that name, and deletes the branch only if one
  of them merged the very commit the branch points at (or an ancestor of
  it).

## Links

- `scripts/prune-merged-branches.py` — the tool.
- `scripts/tests/test_prune_merged_branches.py` — its tests; the calls to
  `gh` and `git` are injected, so the suite never touches the network.
- `CONTRIBUTING.md` — where the script is mentioned for contributors.
- `docs/decisions/2026-09-26-code-review-process.md` — the review process
  that says how a pull request is merged.
- `docs/decisions/2026-09-26-four-phases-gate.md` — the phases of a pull
  request, and the squash-merge in the last one.
