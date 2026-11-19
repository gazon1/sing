---
title: The release gate checks that the tag is contained in main
date: 2026-11-19
status: accepted
tags: [release, ci, workflow]
---

# The release gate checks that the tag is contained in main

## Context

`release.yml`'s `meta` job refuses to release a tag that is not on `main`. The
check was written as:

```bash
git merge-base --is-ancestor origin/main "$GITHUB_SHA"
```

`--is-ancestor A B` answers "is A an ancestor of B?". The check therefore asked
whether `main`'s current tip is an ancestor of the tagged commit — which is
true only for a commit `main` has **not yet reached**. A tag on a commit that
is already merged (the normal case, and the case the step's own comment
"Tag must be on main" describes) failed the gate with
`$GITHUB_SHA is not on main`.

The same inversion made the gate fragile against concurrent merges: because it
compared against `origin/main`'s *tip*, any merge that landed while the
release was running moved the tip past the tagged commit and failed the gate
for a tag that had been valid moments earlier.

## Decision

Ask the direction the comment describes — is the tagged commit contained in
`main`?

```bash
git merge-base --is-ancestor "$GITHUB_SHA" origin/main
```

## Rationale

- It matches the intent of the step: a release may be cut from a commit that
  `main` already contains, and only from such a commit.
- It is stable under concurrent merges. Once the tagged commit is on `main`,
  further merges do not change the answer — the check no longer depends on
  `main`'s tip at the moment the release runs.
- The other conditions of the step are unaffected: the tagged commit still
  needs its own successful `ci.yml` run, and a tag that already has a release
  is still refused.

## Consequences

- `v0.2.2` can be tagged on an already-merged commit, which is what the
  release of the first active RuStore version required.
- The gate no longer accepts a tag on a commit that only exists on a branch:
  such a commit is not contained in `main`, so the check still fails.
- The check reads `origin/main`, which the `actions/checkout` step provides
  through `fetch-depth: 0`.

## Links

- `.github/workflows/release.yml` — the `meta` job, "Tag must be on main and
  have a green CI run".
- Issue #3007 — release the first active version for RuStore.
