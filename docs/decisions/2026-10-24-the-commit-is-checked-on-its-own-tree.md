---
title: "The commit is checked on its own tree, and the working tree is held while it is"
date: 2026-10-24
status: accepted
slug: the-commit-is-checked-on-its-own-tree
supersedes: null
---

# The commit is checked on its own tree, and the working tree is held while it is

## Context

The pre-commit hook compiled the **working tree**. The commit carries the
**index**, and on a branch with one commit per idea the working tree is
usually a mix: the part that is about to be committed and the part that is not
ready yet.

The consequence is a commit refused for a reason that belongs to another
change. The compile sees the next idea too, and if that half does not
compile, this commit fails — on files the commit does not contain. The
person committing is left to work out which half broke it, and the commit
they wanted is blocked by work they have not finished.

This is not a hypothetical: splitting one change into six commits on a branch
meant exactly this. The unstaged half was broken in the middle of the
sequence, and every commit in the sequence was blocked by it.

The pre-push hook has the right shape already: it checks the tree that is
being pushed, in a throwaway worktree, and says so in its own header. The
commit had no such shape.

## Idea

The pre-push hook can afford a throwaway worktree. A commit cannot: a
worktree has its own build directory and its own Gradle home, so a compile
there starts cold. The price of every commit would be a full build, and the
check that exists to make committing cheap would make it expensive.

So the working tree is **held** instead of replaced. The unstaged changes are
put aside, the index's content is written over the working tree, the check
runs, and the working tree is put back exactly as it was. Same directory,
same warm build cache, same speed — and the check sees the tree the commit
will carry.

## Decision

**The pre-commit hook holds the working tree at the index while it checks, and
restores it on every exit path.**

- `scripts/staged-tree.py hold` puts the unstaged work aside: the tracked
  files whose working-tree content differs from the index are copied aside and
  the index's version is written over them; the untracked files — the ones git
  does not ignore — are moved aside, because a Kotlin file that was never
  added is exactly the kind of thing that breaks a commit it does not belong
  to.
- `scripts/staged-tree.py restore` puts everything back, byte for byte. Gitignored
  files are left alone: they are build output, not source.
- The hook runs the restore from a **copy of the helper kept outside the
  tree**. The helper may itself be an untracked file — the first commit that
  adds it is exactly that — and a held tree has no helper in it to restore the
  tree with.
- The restore runs on every exit path: a failure, an interrupt, a trap. The
  held bytes are somebody's uncommitted work, and a hook that keeps them is a
  hook that loses them.

The lint reads the same tree as the compile: `lint-changed.py` takes the
changed files from git and reads them from disk, so holding the tree holds
the lint too.

## Rationale

**A check that measures the wrong tree is not a weaker check; it is a
different one.** The pre-push hook says this in its own header: *a hook that
gates the worktree reports green for a tree nobody pushed*. The same sentence
applies to the commit, one step earlier in the same life of a change.

**The cost of the honest check has to stay bearable.** A throwaway worktree
per commit would price a five-second check at twenty minutes. Holding the
tree in place prices it at what it cost before — nothing — because the build
directory is the one that is already warm.

**The restore is the part that matters.** Holding somebody's work aside is a
promise that it comes back. The copy of the helper exists because that
promise was broken once, in this repository, during this change: the helper
was an untracked file, the hold moved it, and the restore had no helper to run.
The tree was left held. The test that says so is in
`scripts/tests/test_staged_tree.py`, and it failed before the copy existed.

## Consequences

- A commit is checked against the tree it carries. A broken working tree that
  the commit does not touch no longer blocks the commit.
- The working tree is touched during every commit that has unstaged changes,
  and put back afterwards. The restore prints what it does.
- `scripts/staged-tree.py` is a new script with its own tests, in the
  repository's usual style: a sandbox repository, and assertions about what the
  tree looks like afterwards rather than about an exit code.
- The pre-push hook is unchanged: a push is rare enough to afford a throwaway
  worktree, and the tree it judges is the tree that is leaving.

## Links

- `scripts/staged-tree.py` — the hold and the restore.
- `scripts/tests/test_staged_tree.py` — the tree's shape after each step.
- `.githooks/pre-commit` — the hook that holds, checks and restores.
- `.githooks/pre-push` — the same shape, one step later, with a worktree.
- `docs/decisions/2026-10-23-the-commit-compiles-what-the-change-reaches.md` —
  which modules the commit compiles.
