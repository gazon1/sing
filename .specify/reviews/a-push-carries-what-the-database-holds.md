---
change: a-push-carries-what-the-database-holds
verdict: pass
date: 2026-10-23
pr: 1043
reviewed: 6da0efbf319d8fb0f78ba18bd5f6f3c680e60168
documents: sha256:6a626459e49c454e6c549bec5e802456b60f227041c5efafe82ceb30847d67fe
---

## What was reviewed

The whole change: `openspec/changes/archive/2026-10-25-a-push-carries-what-the-database-holds/` —
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

### Round three — the pull request

One blocker and four should-fix, and the blocker was the same one the CI
found on its own: the ADR had no frontmatter block, so `singularity-adr
validate` failed and the branch could not go green. The substance was found
to be right — every task push now carries the database's task, the direction
of the dependencies agrees with `toTask` and `withExtras`, the read happens
inside the transaction that wrote it, and the tag edit goes through the path
that owns the tags. What was open:

1. **Blocker — the ADR had no frontmatter.** It was written in the older
   shape (`**Date:**` and a `Status` section), and the corpus reads a
   `---` block. The CI job said so before the review did.

2. **Should-fix — the verdict overstated the transaction test.** It said a
   read taken *outside* the transaction leaves the pushed task without the
   tag. That is false: a read taken after the commit would see the tag too.
   The test pins that the claim sees the write, not where the read happens.

3. **Should-fix — the verdict said the domain reads `sharedFieldOf`.** It
   does not: the function is internal to the presentation layer and has
   three readers, all of them slots. The rule it states is real; the reader
   of it was named wrongly.

4. **Should-fix — the ADR's sentence about a batch was wrong.** It said a
   batch built from a read without the tags "no longer sends an emptiness as
   a claim". The batch *writes* the tags it was given before it pushes, so a
   batch built from such a read takes the tags off the task and the push
   says so — an emptiness, but one the batch wrote. The push is honest
   either way; the sentence was not.

Nits: the verdict claimed the ADR says the profile id is read once, and the
ADR says no such thing; the tag edit now repairs the series, which was not
documented; removing a tag the task does not carry rewrote every cross-ref
and re-pushed; `freshTasks` reads the cross-refs of the whole profile; a
create pushes a task without the edges the caller carried.

### Round four — PASS, with one should-fix and two nits

The fourth reviewer read the change against the contracts it states, at the
tip of the branch, and confirmed the substance: the assembly reads the row
and the cross-refs, the direction of the edges agrees with `toTask` and
`withExtras`, the read happens inside the transaction that wrote it — checked
against Room 3.0.0's own bytecode rather than assumed — and the tag edit
goes through the path that owns the tags. No blocker. What was open:

1. **Should-fix — the verdict's test count was stale.** It said "three of
   the six tests fail". The class had seven tests by then, and the
   reverted-fix experiment had been run against six of them. The experiment
   was re-run against the seven-test class: three of the seven fail — the
   tags of an edit, the dependencies of an edit and the dependencies of a
   batch write.

2. **Nit — the transaction test's name claimed more than the test pins.**
   It said the claim is read *inside the transaction*; the assertion holds
   for a read taken after the commit too, which is what the verdict itself
   said a round earlier. The name is now the claim: the task the write left.

3. **Nit — a write that changes nothing does not repair the series, and
   the ADR did not say so.** The early return in `setTags` skips the repair
   as well as the write and the push. A series another writer left
   inconsistent therefore stays that way until a write that moves something
   touches it. The code was right; the ADR now says it.


## What was done about it

- The ADR carries a frontmatter block now — `title`, `date`, `status`,
  `tags` — and `scripts/check_adr_status.py` is green on it.
- The verdict no longer claims that a read outside the transaction fails the
  test. It says what the test pins: the claim sees the write.
- `sharedFieldOf`'s KDoc no longer says the domain reads it, and the
  verdict does not either. The mapping stays — it is what states the rule.
- The ADR's batch sentence now says what the batch does: it writes the tags
  it was given and then pushes what it wrote.
- The ADR says what a create pushes: the task as the database holds it,
  which waits for nothing, because the create writes the tags and the row
  and not the dependencies.
- The ADR says what the tag edit does besides writing the tags: a removed
  tag is recorded as suppressed, and the edit repairs the series.
- The ADR says what the bulk archive's single pass reads: the cross-refs of
  the whole profile, not only of the set it assembles.
- `setTags` writes nothing and pushes nothing when the tags the row carries
  are the tags the caller asked for — removing a tag the task does not carry
  is a no-op, and a no-op is not a claim.
- The spec's scenario is named for what it pins — *the claim is the task the
  write left* — and the requirement says where the read happens.
- The test is named for the same thing: *the claim is the task the write
  left*. The name it had said the claim was read inside the transaction,
  which the assertion alone cannot tell: a read taken after the commit
  would see the tag too.
- The reverted-fix check was re-run against the seven-test class, and it is
  three of the seven that fail — the tags of an edit, the dependencies of an
  edit and the dependencies of a batch write.
- The ADR says that a write which changes nothing does not repair the
  series either, because it repairs nothing.

### The fix itself

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
  it asserts on is the tag the same transaction wrote. A read taken *before*
  that write leaves the pushed task without the tag and the test fails — the
  test pins that the claim sees the write, not where the read happens: a read
  taken after the commit would see the tag too.
- The test was checked against a reverted fix: with `update()` and
  `updateAll()` pushing the caller's copy again, three of the seven tests
  fail — the tags of an edit, the dependencies of an edit and the
  dependencies of a batch write. The tests pin the defect, not the code.
- The ADR no longer promises a re-stamp of `updatedAt`: it says the stamp
  does not change and that what changes is that the push no longer carries
  the caller's copy.
- The ADR says what a batch costs: a read per member, and the bulk archive
  as the exception.
- `sharedFieldOf` keeps the mapping — the rule is unchanged and the mapping
  documents it — and its KDoc now says where the tags are written and why.

## Links

- ADR `docs/decisions/2026-10-23-a-push-carries-what-the-database-holds.md`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskPushClaimTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/archive/TaskDaoArchiveRepositoryImplTest.kt`
- Issue #1034 — the project write takes the inherited groups with it
