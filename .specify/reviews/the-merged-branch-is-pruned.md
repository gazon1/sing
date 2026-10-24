---
change: the-merged-branch-is-pruned
verdict: pass
date: 2026-10-24
pr: https://github.com/gazon1/sing/pull/1051
reviewed: ff488ee4333b4fe8f0d0fbcbca21c98e2dd77b5a
documents: sha256:0058c62b46482bdb6106a11d0e0e9ae2d2ccc0134cd5086c6bd60156c636fc62
---

## What was reviewed

The pruning of the local branches a squash-merge leaves behind, on the branch
`chore/the-merged-branch-is-pruned`:

- `scripts/prune-merged-branches.py` — the plan, the exclusions and the deletion.
- `scripts/tests/test_prune_merged_branches.py` — the checks the plan rests on.
- `docs/decisions/2026-10-23-the-merged-branch-is-pruned.md` — the decision record.
- `CONTRIBUTING.md` — what a reader of the repository is told.
- The change's own proposal and spec delta.

The review was a second agent's, given the diff and the decision record rather than
the session that wrote it. It ran twice: the first pass returned `fail` with three
blockers and one mechanical mismatch, the second pass found no blockers.

## What the first review found, and what closed them

**The document was stricter than the code.** The decision record and
`CONTRIBUTING.md` said that a branch is deleted only when its tip is the commit the
pull request was merged from. The code also deleted a branch whose tip is an
*ancestor* of the merged head — a branch behind the merge, whose content is
already in `main`. The live plan showed one such branch in 116, so the mismatch was
observable, not theoretical. The decision record now states both rules, explains why
the second one is safe, and the plan says which of the two matched.

**The request to GitHub was covered by no test.** The stand-in for `gh` returned a
canned list and ignored the request, so four mutations of the request itself — the
state, the merged commit, the repository and the page size — left the suite green.
The stand-in now answers the way `gh` answers, and each of the four mutations turns
the suite red.

**The branch was thirteen commits behind the trunk.** The pull request would have
reverted other people's work. The rebase is the session's job, not the reviewer's.

**The docstrings were in Russian.** The convention of the repository is English in
the code and Russian in what a person reads. The docstrings and the comments are
now English; the tool's own output stayed Russian.

## What the second review found, and what closed them

No blockers. The second pass confirmed by mutation that every rule of the plan has
a test that fails when the rule is broken, and it found three small things, which
were closed in the same round:

- a pull request whose fork had been **deleted** was dropped from the comparison
  and its branch was reported as having no merged pull request — a false
  statement, because the pull request exists and belongs to someone else. The
  branch now gets the reason that says so.
- the list of commits printed for a branch that was rewritten showed the whole
  history of the branch, while the number next to it counted only the commits
  that exist nowhere else. The list is now the set the number counts.
- the fallback repository — used when the origin remote cannot be read — was
  taken silently. The tool now says which repository it used.

## The verdict

`pass`. The plan is printed before anything is deleted, the deletion needs an
explicit flag, and every branch that is left alone has a reason that is true. The
decision record matches the code, and the numbers in it are reproducible from the
tool's own output: 163 branches with a merged pull request, 29 of them moved on
since the merge, 19 held by another worktree.

The rebase onto the trunk is the one thing that is left, and it belongs to the
session that writes the commit.
