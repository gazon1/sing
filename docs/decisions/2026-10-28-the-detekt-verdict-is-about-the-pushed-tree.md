---
date: 2026-10-28
status: accepted
deciders: [maintainer]
---

# The detekt verdict is about the tree that is being pushed

## Context

The pre-push hook had two verdicts about two different things, and neither of
them was about the tree being pushed.

- The structural gates (version catalog, Maestro selectors, UI wait budgets)
  ran in a throwaway worktree checked out at the pushed sha. That part was
  right.
- The detekt step ran in the **current worktree**, and its verdict was
  "did auto-correct change any file?" — compared with
  `git status --porcelain` before and after.

Two failures follow from that, and both are silent.

1. **The tree that was judged was not the tree that was pushed.** A push of
   `HEAD:other-branch`, a force-push of a rebuilt branch, or a push from a
   worktree whose content differs from the pushed commit was judged against
   something else. PR #962 is the instance that cost 35 minutes of CI: the
   pushed tree carried a detekt violation, and the hook that was supposed to
   catch it was looking at a different tree.
2. **The verdict could not see a correction.** `git status --porcelain` prints
   ` M path` for a file that was modified before the run *and* for the same
   file after detekt rewrote it. The status lines are equal, so a run that
   corrected a violation in a file that was already dirty reported "changed
   nothing" — and the pushed tree went out with the violation in it.

There was also no blocking detekt verdict at all. The exit code of a
`--auto-correct` run is not a verdict: a correcting run that fixed a planted
violation ended BUILD SUCCESSFUL / exit 0 (ADR
`2026-10-17-detekt-auto-correct-works-use-it.md`). So a violation that detekt
cannot correct — or one that lives in the pushed tree while the worktree is
clean — had no path to a red hook at all.

## Idea

The hook should ask one question — *does the tree that is being pushed hold a
detekt violation?* — and answer it about that tree, with a signal that cannot
be fooled by a correction.

Two signals are available and both are needed:

- the **exit code** of a run without auto-correct: detekt's own verdict on the
  findings it could not fix;
- the **content digest** before and after an auto-correcting run: the findings
  it did fix. The digest is a hash over the content of every Kotlin file, not
  a status line, because a status line cannot distinguish "modified before"
  from "modified by the correction".

## Decision

The detekt step becomes a **verdict about the pushed tree**, taken in one of
two places:

- **the current worktree**, when it holds byte-identical content for
  everything detekt can read (the Kotlin sources, the build scripts that
  decide which source sets are scanned, the detekt config and baseline, the
  custom rules, the version catalog, and `.editorconfig` — ktlint reads it
  from inside detekt, so it decides which rules are applied). Then the
  worktree *is* the pushed tree, and judging it there is warm: the Gradle
  daemon and the build directories are the ones this checkout has been using;
- **a throwaway worktree at the pushed sha**, in every other case. The
  structural gates already check one out for the same sha, and it is reused.

The verdict fails the push when either signal fires: a non-zero exit code, or a
content digest that changed across the run.

The comparison of the worktree with the sha covers *every* path detekt can
read, not only the Kotlin files. A worktree whose `shared/build.gradle.kts`
differs is a different tree: the source set detekt scans is a property of the
build script, so judging the worktree would judge a different set of files.

The hook no longer judges Kotlin that is only in the worktree. It never did
have anything to say about it — the tree being pushed does not contain it —
and treating it as a reason to block a push is how a Python-only push got
held hostage by someone else's finding.

## Rationale

The property that matters is: **the verdict is about the tree that is being
pushed**. Everything else follows from it.

- Judging the worktree when it is byte-identical is not a compromise — it is
  the same tree, and it is the difference between a two-minute hook and a
  five-minute one. The cold path is a fresh Gradle build in a fresh
  worktree, and it is paid only when the worktree is genuinely a different
  tree.
- The content digest is what makes the correction visible. A correction in a
  file that was already dirty is invisible to `git status` and visible to a
  hash.
- The exit code is what makes an uncorrectable finding visible. Without it, a
  finding detekt cannot fix is invisible to a correcting run.

The alternative — keep judging the worktree and report the diff — is what the
hook did, and it is what let a broken tree out.

## Consequences

- A push whose worktree differs from the pushed tree pays a cold Gradle run
  (a fresh build directory and a fresh build-logic compile). The common case
  — commit, then push from the same worktree — stays warm.
- A violation in the pushed tree fails the push even when the author's
  worktree already holds the fix. That is the point: the fix is not in the
  tree being pushed.
- A violation that is only in the worktree does not fail the push. The author
  learns about it from CI, which judges the whole tree anyway.
- The correction is still made where it can be kept: in the fast path the
  corrected files are in the author's worktree, and in the cold path the diff
  is printed from the temporary worktree.
- `scripts/tests/test_pre_push_hook.py` drives the real hook through a real
  `git push` into a local bare remote, with a stand-in for the Gradle
  wrapper. It holds the property in both directions: a violation in the
  pushed tree fails the push, and a violation only in the worktree does not.
- The verdict takes a multi-minute Gradle build, and git opens its SSH
  connection to the remote *before* the hook runs. The connection therefore
  idles for the length of the analysis, GitHub closes it, and the push fails
  with 141 — after the hook printed its own verdict and said the tree was
  clean. Measured on the first push of this change: the hook reported
  `=== Pre-push OK ===` and the push still failed with 141.
  The remedy is a client-side keepalive
  (`git config core.sshCommand "ssh -o ServerAliveInterval=30"`), not a
  faster hook: the analysis is whole-module, and the analysis is the point.

## Links

- `.githooks/pre-push` — the hook this decision is about
- `scripts/tests/test_pre_push_hook.py` — the positive control
- `docs/decisions/2026-10-17-detekt-auto-correct-works-use-it.md` — why the
  exit code of a correcting run is not a verdict
- `docs/decisions/2026-09-25-git-hooks-worktree-isolation.md` — the hook runs
  in every worktree
