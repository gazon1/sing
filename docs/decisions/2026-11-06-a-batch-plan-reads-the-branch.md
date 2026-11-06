---
title: "A batch plan reads the pull request's branch, not main"
date: 2026-11-06
status: accepted
tags: ["process", "planning", "pull-requests"]
---

# A batch plan reads the pull request's branch, not main

## Context

A pull request carried the whole migration of the JVM tests onto
`DatabaseTestFixture`, but the branch was based on an older `main` and had been
open for a while. Planning the remaining work from `main` produced seven batch
issues (#2651–#2657), one per package, plus a statement that "43 hand-rolled DB
tests remain". The branch already contained all of them.

The mistake announced itself late: a rebase onto current `main` was expected to be
a rewrite and turned out to be **one** real conflict — `AppDatabaseFactory.kt` had
moved to `:core-database`. The 49 other files applied cleanly. The estimate was
wrong because nothing in the plan read the branch.

## Idea

- When a pull request already exists for a change, the follow-up plan starts from
  `git diff origin/main...<branch>`, not from `main`'s tree.
- A batch issue is created only for work the branch does **not** contain.
- The plan records the branch tip it was derived from, so a later rebase can tell
  whether the plan is still true.

## Decision

Follow-up plans and batch issues name the branch and the base they were read
from, and their content is derived from that diff. If `main` is the only source
(a change with no branch yet), the plan says so.

## Rationale

`main` answers "what is left if the pull request is dropped". The branch answers
"what is left if it lands". The second is the question a follow-up plan is asking,
and the two answers differed by the entire pull request in this case. Seven issues
and a re-implementation plan were spent on a diff that already existed.

## Consequences

- Planning takes one `git diff` that it did not take before.
- A stale branch is still a real risk: if the branch is far behind `main`, the plan
  must also check whether a rebase changes the shape. In this case it did not, but
  that is a fact to verify, not to assume.
- Closed batch issues keep a pointer to the pull request that satisfied them, so a
  reader can see the work rather than a "completed" label with no diff.

## Links

- #2503 (the pull request), #2651–#2657 (the batches it already contained)
- `docs/agents/issue-tracker.md`
