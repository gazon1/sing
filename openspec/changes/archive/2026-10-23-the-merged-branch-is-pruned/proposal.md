# The merged branch is pruned

Status: **open** · Issues: — · ADRs: `docs/decisions/2026-10-23-the-merged-branch-is-pruned.md`

## What

The local branches of merged pull requests stop accumulating.

This repository merges pull requests by squash, and a squash-merge does not make the
branch an ancestor of `main`: the branch's history is rewritten into one new commit
on `main`, and the branch keeps its own, unrelated, history. Git says so plainly —
`git branch --merged` does not list such a branch and `git branch -d` refuses to
delete it with "not fully merged". The branch is not stale; it is invisible to the
two commands that are meant to clean up after a merge.

The checkout where this was written held 163 branches whose pull request had been
merged, and the count grows with every pull request. Nothing warns about it: the
tree is not dirty, no gate fails, the worktree works. The cost is paid later — in
`git branch` listings nobody reads, in a stale worktree whose branch was merged three
weeks ago, and in the moments where an agent picks a branch that looks alive and
builds on it.

`scripts/prune-merged-branches.py` asks GitHub which pull requests are merged, takes
the local branches from git, and builds a plan. By default it only prints the plan;
deleting takes an explicit `--yes`.

## Why

Git can only tell that a branch was *not* merged by ancestry. GitHub knows that the
pull request closed and which branch it came from. The merged pull request is the
only evidence that is both cheap to read and sufficient — and `gh` is already on the
machine and authorised for this repository, so the question costs one command.

The decision to delete stays with the person who owns the branch. The fact that a
pull request was merged says that its content is in `main`; it does not say that
nobody still needs the branch. Its author may be comparing the old head with the new
one, another session may be standing on it, and the branch may carry local commits
that were never pushed. Printing the plan keeps the decision where it belongs and
still removes the part that is pure labour: finding out *which* branches are the
leftovers.

Seven things are never deleted, and each of them has a reason that is written down
rather than inferred: the branch the checkout stands on, a branch held by another
worktree, `main`, a branch without a merged pull request, a branch of a fork, a
branch whose merged pull request cannot be confirmed, and a branch that moved on
after the merge — the last one carries commits that exist in no `origin/*` and in no
`main`, and deleting it would destroy work that exists nowhere else.

## How it is checked

- `scripts/tests/test_prune_merged_branches.py` drives the script with a stand-in
  for `gh` that reads the request the way `gh` answers it, and a stand-in for `git`
  over a real history — so the network is never touched and the expectations are
  the ones git gives.
- The contract of the request to GitHub is itself under test: `--state merged`, the
  merged commit, the owner, the repository and the page size.
- Every rule the plan applies has a test that fails when the rule is broken, and
  the tests were checked by mutation: taking out the "behind" rule, taking out the
  `headRefOid` from the request, changing the repository or the page size, and
  blanking the "only in the local copy" indicator all turn the suite red.

## Out of scope

- The tool is not a gate. It changes the working tree of a person, on their
  machine, at their request — that is not what a gate in CI is, and it does not
  belong in the gate registry.
- Branches of other repositories are not touched: a fork's branch lives in another
  repository, and the local branch of the same name is not the same thing.
