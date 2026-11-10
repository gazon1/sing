---
date: 2026-11-10
status: accepted
deciders: [maintainer]
---

# A rebase drops the resolutions a merge commit carried

## Context

The branch of PR #2177 was rebased onto `origin/main` (2026-11-10). The old
branch history contained a merge commit, `2c2c6d1f1` — *Merge origin/main into
agent/issue-1669* — whose tree carried two repairs that no commit in the
branch introduced:

- the auto-mirrored chevrons were swapped for the auto-mirrored keyboard
  arrows, because the pinned Material icons carry no auto-mirrored chevron
  (`2026-11-09-material-icons-have-no-auto-mirrored-chevron.md`);
- two desktop test files opted into `ExperimentalTestApi`, without which they
  do not compile.

Both repairs were made **in the merge**, by hand, on the way past a conflict.
A merge commit's tree is a *resolution*, not a diff that a later replay can
carry: `git rebase` replays the commits, and a merge commit is not one of
them. The rebase therefore finished cleanly and produced a tree that did not
compile — the icons were unresolved references, and the desktop test sources
were errors.

The rebase reported no conflict for either file. There was no conflict to
report: nothing in the branch's diff asked for the repair, so nothing asked
for it to be dropped.

## Idea

A rebase that ends without conflicts has still dropped whatever the dropped
merge commits carried. "The rebase finished" is not a verdict about the tree;
only compiling the tree is.

The repairs themselves are not the hard part — they are two lines and two
imports. The hard part is **knowing they are missing**, which means knowing
what the dropped merge carried.

## Decision

**A rebase that drops merge commits is finished when the tree compiles and the
dropped merge's resolutions are back, not when the replay ends.**

- **The dropped merge's resolutions are listed mechanically.** The files a
  merge resolved are the files that differ from *both* of its parents:
  `git diff --name-only M^1 M` ∩ `git diff --name-only M^2 M`. That
  intersection is the merge's own work — everything else in it is one
  parent's content that the other side agreed with.
- **Each of those files is checked against the rebased tree.** A file whose
  repair was a manual edit is re-applied by hand; a file whose repair was
  simply "the trunk's version wins" is already there.
- **The tree is compiled.** Both the production sources and the test sources:
  the second repair that was lost here was in a test file, and a compile of
  the production sources alone would have missed it.
- **A branch that merges the trunk is rebased onto it instead.** The merge is
  what made the repairs unreproducible. A branch that carries the trunk as a
  parent gets the trunk's content for free, and its own diff stays a diff.

## Rationale

- **The loss is silent.** Nothing in the replay mentions the dropped repair,
  and the compile error names a symbol rather than a history. Reading the
  error as "the branch wants a different icon" would have produced the
  wrong fix: the branch did not want a different icon, the merge had already
  answered that question.
- **The intersection is the merge's own work.** A file that one parent
  already agreed with is not a resolution; a file that differs from both is.
  The intersection is small and finite, which is what makes the check
  affordable.
- **The compile is the cheap oracle.** Every lost repair in this case was a
  compile error, so compiling the tree both finds the loss and proves the
  repair.

## Consequences

- **The rebased branch carries two extra commits** — one for the sources, one
  for the test sources — that exist only because the replay dropped what a
  merge had already resolved. They are repairs, not features, and the commit
  bodies say so.
- **A merge commit in a branch's history is a cost, not a convenience.**
  Every future rebase of that branch has to pay it again.
- **The check is mechanical and short**: one diff intersection, one compile
  of the production and the test sources. It is not a substitute for the
  gates, but it is what tells you the rebase did not quietly undo anything.

## Links

- `docs/decisions/2026-11-09-material-icons-have-no-auto-mirrored-chevron.md`
  — the repair the merge carried
- `docs/decisions/2026-11-09-the-pre-push-hook-judges-the-push-not-the-repository.md`
  — the hook that judges a push's scope, fixed in the same rebase
