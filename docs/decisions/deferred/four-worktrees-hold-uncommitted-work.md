---
title: "Four Worktrees Hold Uncommitted Work"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** 2026-10-05, while assessing branch and worktree hygiene before
re-deriving the publication history. The proposed cleanup was "prune dead
worktrees and consolidate 84 branches"; measuring first showed that framing
was wrong.

**Status: OPEN**

**Tracked as:** #202

**Symptom:** 17 worktree directories exist, and four of them carry work that
exists nowhere else:

- `singularity-todo-business-01` — **an interactive rebase stopped mid-flight**
  (`UU docs/testing/coverage-matrix.md`, 7 changed files, 2 completed picks of 7
  pending). Its branch content is not reachable from `origin/main`.
- `singularity-todo-notes-body` — 50 changed files on
  `refactor/notes-canonical-body`.
- `singularity-todo-pallete` — 35 changed files on `feature/pallete`.
- `singularity-todo-test-scences` — 52 changed files on `feat/test-scences`.

Also: 42 local branches are unmerged into `origin/main`; 21 of those are checked
out in a worktree and 21 are not. A `git branch -D` sweep or a `git worktree
remove` without reading `git status` in each would destroy the four blocks above
and 21 unmerged branch tips.

**Already ruled out:** none of this is garbage. The rebase in `business-01` is
interrupted mid-sequence, not failed — the completed picks are recoverable with
`git rebase --continue`. The other three are ordinary in-progress work on
branches that were never merged.

**Try next:** decide per worktree, not per rule. `business-01` first: finish or
abort the rebase deliberately, because an interrupted rebase is also a trap for
the *next* person — `git status` in that directory reports a conflict rather than
the feature it looks like it is working on. Then triage the three dirty branches
by whether their work is superseded by `origin/main` or still wanted. Only after
that, prune: the 21 unmerged branches without worktrees are the cheap case, and
even they should be listed for a human first.

The generalisable lesson: "clean up the repository" is not a safe instruction to
hand to an agent, and neither is "there are 84 branches and 46 worktrees". Both
numbers invite a bulk delete, and the interesting content is in the four
directories that are not clean.

---

### Two things the conversion found that this entry did not

**1. Priority was three scales, not one.** `PriorityPalette` always documented
two coexisting palettes, and the divergence between them is deliberate — the
editor is a form, the list is a list, and the reds are tuned differently on
purpose. But `PriorityChip.kt` held a **third** set of four values
(`4CAF50` / `FF9800` / `F44336` / `E91E63`) that was never registered in the enum
and never documented, in a function also named `priorityColor` — shadowing the
canonical one in a sibling package with different values. The compiler will not
flag that and a reviewer will not notice; the unit test covers the list one only.
The chip's values are now preserved exactly as a documented third palette, and
its function is renamed `priorityChipColor`.

**2. The two palettes were near-duplicates.** `TaskColors.Surface` and
`TaskListColors.Surface` were both `0xFF161A22`; their backgrounds differed
(`0xFF0F1115` vs `0xFF0B0E14`) and their text primaries differed
(`0xFFE2E4E9` vs `0xFFF2F3F5`). Two hand-maintained copies of one palette, which
is why they had drifted. There was no need to unify them by hand — both now
resolve from the same scheme, so they are identical by construction.

---
