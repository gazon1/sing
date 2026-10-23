---
title: "The id is known before the row is written"
date: 2026-10-23
status: accepted
tags: [proposals, dispatch, coroutines, compensation]
---

# The id is known before the row is written

## Context

The write path writes rows one at a time and takes them back when a later row
fails or when the scope is cancelled. The compensation was the subject of
`2026-10-23-a-cancelled-write-takes-back-what-it-wrote`, and it works — on
every path it was written for.

It could not work on the path it exists for. The compensation addresses a row
by its id, and the id was the *result* of the write:

```kotlin
writeAllOrNone(
    items = plan.texts,
    write = { text -> checklist.addItem(plan.taskId.value, text).getOrThrow() },
    takeBack = { id -> checklist.delete(id) },
)
```

`addItem` generated the id inside the repository and returned it. So the id of a
row was known only after the row was written — and the write that is cut short
after the database has committed is exactly the write whose id never arrives.
The compensation ran, and it had nothing to compensate: the row stayed.

The row that stayed is a row the user never saw, and the change went back to
waiting — so the next attempt, which is the user's, wrote it a second time. The
user pressed "apply" twice and got two rows.

The same hole was in the claim. The claim is a transaction, and what it decided
was known by a flag in the caller:

```kotlin
val claimedItem = proposals.claim(itemId, Confirmed, DecidedActor.User, null, userId)
claimed = true
```

A cancellation that arrived between the commit and the return left the flag
false. The `finally` therefore did not give the claim back, and the change stood
`Confirmed` — with nothing written. The user could not press "apply" again,
because the change was no longer waiting.

## Idea

Name the id of every row before the row is written, and let the flag about the
claim be set where the claim is.

## Decision

**The row carries the id.** The write path builds a row — its id, its write, and
its take-back — before it writes anything:

```kotlin
class ProposedRow<out Id>(
    val id: Id,
    val write: suspend () -> Unit,
    val takeBack: (suspend () -> Result<*>)?,
    val isGone: (suspend () -> Boolean)?,
)
```

The id is remembered *before* the write, so a write that is cut short after the
commit leaves a row the take-back can address.

**The caller names the id.** `ChecklistRepository.addItem` and
`TimeTrackingRepository.createManualEntry` take the id, because the repository
generated it and the repository cannot know that the caller is going to need it.
Every other caller leaves it out and gets a fresh id, as before.

**The take-back tolerates a row that is not there.** A write that failed before
it created the row leaves nothing to take back — and "the row is not there" is
what the take-back wanted. So the take-back asks the row whether it is gone and
treats "gone" as success. Without this, every failed write would be reported as
a row that stayed: a row the user would be told about that is not there.

**The claim is decided where it is made.** The flag is set inside the
non-cancellable block that holds the claim:

```kotlin
val claimedItem = withContext(NonCancellable) {
    val item = proposals.claim(itemId, Confirmed, DecidedActor.User, null, userId)
    if (item != null) claimed = true
    item
}
```

A cancellation that arrives while the claim is in flight no longer interrupts
it: the claim completes, the flag is set, and the `finally` gives the claim
back — which is right, because nothing was written.

**One table of writers.** `rowsOf(plan, userId)` is the only `when` over the
plans, and every plan goes through the same application point. The subtasks and
the tasks a note's actions describe share one writer, because they are the same
rows.

**The update plans have no take-back.** Setting a field again is the same
setting, so a retry of an update needs no undo. A take-back that wrote the
previous state back would overwrite whatever the user did in the meantime, which
is a change nobody decided about. The creation plans have one — their id is
known — and the collection plans have one per row.

## Rationale

The id is the only thing that makes a row addressable, and a row that cannot be
addressed cannot be taken back. Putting the id in the result of the write makes
the write's *success* a precondition for undoing it — and success is exactly
what a cut-short write does not have.

The take-back tolerating absence is the other half of the same argument. If the
id is known before the write, the take-back is asked about rows that may never
have existed, and a take-back that fails on such a row would report a row that
stayed — a lie about the data, in a message the user reads.

The claim's flag was the same defect in a different place: a fact about a
transaction was known by a flag that a cancellation could skip. Setting the
flag where the transaction is makes the fact and the flag the same moment.

## Consequences

- **A write that is cut short leaves nothing behind.** There is a test for it:
  the write commits, the screen goes away before the write returns, and the row
  is gone afterwards.
- **A cancellation while the claim is in flight gives the claim back.** There is
  a test for it: the change waits again, and nothing was written.
- **The repositories take an id.** Two interfaces, their implementations and
  their fakes. The parameter has a default, so no other caller changed.
- **The dispatch is one application point.** A new plan is one branch of
  `rowsOf`, and the compensation is the same code for every plan.
- **The flat reader of the codec is still there.** The stored shape did not
  change in this decision — see
  `2026-10-23-the-stored-change-is-the-models-own-shape`.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalDispatch.kt`
  — `ProposedRow`, `rowsOf`, `writeAllOrNone`, `takeBackSafely`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ApplyProposalItemUseCase.kt`
  — the claim.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/proposals/ApplyProposalItemUseCaseTest.kt`
  — the three tests.
- `docs/decisions/2026-10-23-a-cancelled-write-takes-back-what-it-wrote.md`
  — the compensation this decision makes addressable.
