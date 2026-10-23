---
change: a-push-carries-what-the-database-holds
verdict: pass
date: 2026-10-23
pr: 1043
reviewed: f6b0f3162e8ff4e9b09bf5c0813d7d2a498215e0
documents: sha256:f80d8037d4e29ad3a242658e5979da49a4d8c4c1b138d8d36c39a0e2332ff400
---

## What was reviewed

The whole change: `openspec/changes/a-push-carries-what-the-database-holds/` —
the proposal, the design, the tasks and the `tasks` spec — against the code it
describes and against
`docs/decisions/2026-10-23-a-push-carries-what-the-database-holds.md`, plus the
rule added to `CODING_STANDARDS.md`.

The reviewers were separate agents given the delta, the ADR, the OpenSpec
change and the test files, and nothing else: no history of writing the code, no
pressure to find nothing wrong. They were asked to judge the change against the
contracts it states. Both were told not to run Gradle — a build was running in
the same worktree, and a second one corrupts the shared build directory.
Neither ran one.

The first round was one agent over the whole delta. The second round was
another, given the findings of the first after they had been fixed.

## The reviews

### Round one — FAIL

One blocker, four should-fix and four nits were found.

**Blocker — the tag edit on the screen went through `update(copy(tags = …))`.**
The local rule says `update()` does not write the tags, and it says why: a task
read through `get()` holds none, and writing that emptiness would strip the
tags off the task. The rule was about the *local* write and it was right, but
the same call was the one that pushed to the server — so the tags reached the
server only through the push, and came back on the next pull. Locally they
never appeared at all. The blocker was not that the rule was wrong: it was
that the path that owns the tags (`setTags`) was not the path the screen used.

**Should-fix — the requirement was not universal.** `create` and `createSeries`
pushed the task the caller held, and so did the batch write. The rule was
stated as "a push carries what the database holds" and applied to one path.

**Should-fix — the projects have the same hole.** A project write carries the
groups its tags are inherited through, and a write that does not know them
says the project has no inherited groups. The hole was real and it was the
same family, but the answer to it was a separate question — what a write is
supposed to do with an inherited group at all — and it was filed as #1034
rather than folded into this change.

**Should-fix — no test for the batch write.** The batch path was changed and
nothing pinned it.

**Should-fix — the test did not use a real transaction.** The claim is read
back inside the transaction that wrote it, and a fake unit of work cannot
show that: the read would still see the write, but for the wrong reason.

Nits: the test tag was `fast` for a test that opens a real database; the batch
path read the cross-refs once per task; the `updatedAt` on the wire was not
the row's stamp; the profile id was read twice in the archive path.

### Round two — PASS, with two should-fix and three nits

The mechanism was found to be as specified: the assembly reads the row and its
cross-refs, the wire names match, the direction of the dependencies agrees
with `toTask` and `withExtras`, the series repair carries the tags of the
source, the tag edit goes through `setTags`, and no `update(copy(tags = …))`
is left in production. What was still open:

1. **Should-fix — the ADR promised a re-stamp of `updatedAt` that no code
   does.** It said the write re-stamps the row with the local clock and the
   push says the same thing the row says. The repository does not re-stamp:
   the update use case stamps the caller's copy before the write, and the
   row keeps that stamp. The ADR described a change to the stamp that this
   change does not make.

2. **Should-fix — the transaction test did not test the transaction.** The
   tag it asserted on was written by an earlier, already committed create, so
   the assertion held even if the in-transaction read had been stale — which
   is the half of the rule the test was named after.

Nits: `freshTasks` reads the cross-refs of the whole profile rather than of
the set it assembles; `setTags` records a removed tag as suppressed, which
the change did not document; `sharedFieldOf` still maps the tag intents to a
shared field although the slot no longer routes them through it.

## What was done about it

- The tag edit on the screen goes through `core.taskRepo.setTags(…)`. The
  path that owns the cross-ref table the tags live in is the path that writes
  them, and the series repair follows from there.
- `create`, `createSeries`, `update` and `updateAll` push the task the
  database holds. The assembly lives in one place — `TaskDao.freshTask` for a
  single task, `TaskDao.freshTasks` for the paths that push many at once.
- The bulk archive assembles the batch in one pass: the cross-refs are read
  once for the whole set rather than once per task.
- The test class runs against a real database and a real write transaction
  (`RoomUnitOfWork`), and the transaction test was rewritten so that the tag
  it asserts on is the tag the same transaction wrote. A read taken before
  that write, or outside that transaction, leaves the pushed task without the
  tag and the test fails.
- The test was checked against a reverted fix: with `update()` and
  `updateAll()` pushing the caller's copy again, three of the six tests fail
  — the tags of an edit, the dependencies of an edit and the dependencies of
  a batch write. The tests pin the defect, not the code.
- The ADR no longer promises a re-stamp of `updatedAt`: it says the stamp
  does not change and that what changes is that the push no longer carries
  the caller's copy.
- The ADR now says what the tag edit does besides writing the tags — a tag
  the user removed is recorded as suppressed — and says that the profile id
  is read once.
- The ADR says what a batch costs: a read per member, and the bulk archive
  as the exception.
- `sharedFieldOf` keeps the mapping — the rule is unchanged and the domain
  reads it — and its KDoc now says where the tags are written and why.

## Links

- ADR `docs/decisions/2026-10-23-a-push-carries-what-the-database-holds.md`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskPushClaimTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/archive/TaskDaoArchiveRepositoryImplTest.kt`
- Issue #1034 — the project write takes the inherited groups with it
