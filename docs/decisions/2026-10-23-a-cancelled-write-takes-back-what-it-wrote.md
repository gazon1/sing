---
title: "A cancelled write takes back what it wrote"
date: 2026-10-23
status: accepted
tags: [proposals, dispatch, coroutines, compensation]
---

# A cancelled write takes back what it wrote

## Context

A proposal that writes several rows — three checklist items, two subtasks, a few
time entries — writes them one at a time. The decision says: **all of them, or
none of them**. If the second row fails, the first row is taken back, so the next
attempt — which is the user's — does not write it twice.

The compensation was written for the failure path, and the failure path is where
it was:

```kotlin
val result = runCatchingCancellable { for (item in items) written += write(item) }
if (result.isFailure) {
    val takenBack = written.map { takeBack(it) }
    throw failureWith(result, takenBack)
}
```

There is a second path, and it was not covered. A cancelled coroutine is not a
failed one:

- `runCatchingCancellable` re-throws `CancellationException` rather than putting
  it into a `Result` — correctly, because a cancellation has to abort the
  coroutine, and swallowing it makes a cancelled operation look successful;
- so the exception flies past `if (result.isFailure)`, and the compensation never
  runs;
- the claim is released in a `finally` under `NonCancellable`, so the change goes
  back to **waiting** — exactly as it does when a write fails;
- and the rows that were written before the cancellation stay written.

The user leaves the screen, comes back, presses "apply" again — and the rows
that stayed are there twice. The compensation was written to prevent exactly
that, and it did not prevent it on the path that is most common: the user
navigated away mid-write.

Before the proposal lifecycle, this was unreachable: a cancelled write left the
change `Confirmed`, so the second press was impossible. Making the change
repeatable — which is what the lifecycle is for — turned the missing
compensation into a real doubling.

The same defect lived four times over, in `addChecklistItems`, `addSubtasks`,
`addTimeEntries` and `extractNoteActions`, each with its own copy of the "all or
nothing" logic and its own place where the cancellation slipped through.

## Idea

One helper that writes a list of rows or none of them, and whose compensation
covers the cancellation as well as the failure.

## Decision

**One generic helper.** `writeAllOrNone(items, write, takeBack)` takes the rows,
the write and the take-back; the four call sites differ only in what they write
and what they take back. A fifth kind of multi-row write now costs one call, not
one copy of the compensation.

**The compensation lives in the `finally`, under `NonCancellable`.** Not in the
branch that handles a failure:

```kotlin
try {
    val result = runCatchingCancellable { for (item in items) written += write(item) }
    if (result.isFailure) { /* take back, then throw */ }
    settled = true
} finally {
    if (!settled) {
        val takenBack = withContext(NonCancellable) { written.map { takeBack(it) } }
        reportStuckRows(takenBack)
    }
}
```

The `finally` is the only place that runs on both paths. Under `NonCancellable`,
because the scope that was cancelled is still cancelled — a take-back that runs
in a cancelled scope does not run at all.

**The `settled` flag says whether the compensation has already happened.** The
failure path takes back and then throws; the `finally` sees `settled` and does
not take back twice.

**Rows that could not be taken back are reported to the crash reporter.** There
is no user to tell: the scope they were in is gone, and the row that stayed is
a fact about the data, not a message. It is reported under
`proposals.rows_stuck_after_cancellation` — a fixed machine identifier, not a
message that carries user content.

## Rationale

The compensation is a property of the write, not of one way of failing. Code
that handles "the write failed" and not "the write was cut short" is a write
that is correct on one path and wrong on the other, and the wrong path is the
one the user cannot see.

`finally` is the honest place for it. A `catch (CancellationException)` that
re-throws would need the compensation to run before the re-throw — which is the
same code, in a place that has to be remembered to be updated. The `finally`
runs whether the scope ended in an exception, a cancellation or a return.

**Why not let the cancellation take the rows with it?** Because the rows are in
the database, not in the coroutine. The scope is gone; the checklist item is
there. The compensation is the only thing that can make the two agree.

**Why is the take-back reported rather than shown?** The user is not on the
screen — that is what "cancelled" means here. A message nobody reads is not
worse than no message; a row in the database that the next attempt writes twice
is worse, and it has to be visible to somebody: the crash reporter, where it
becomes a fact about the data.

## Consequences

- **The write path has one compensation, and it is the same on every path.**
  The four copies are one helper.
- **The cancellation test exists.** A test cancels the scope in the middle of the
  loop and asserts that the row written before the cancellation is gone and the
  change is waiting again. Before this decision, no test covered that path.
- **The take-back itself can fail.** `takeBack` returns a `Result`, and a row
  that could not be taken back is named in the failure the user sees — because a
  row that stayed is not a detail: the change goes back to waiting and the user
  will try again.
- **`runCatchingCancellable` stays as it is.** It is right to re-throw the
  cancellation; the fix is not to swallow it, but to make sure the compensation
  does not depend on the failure path.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalDispatch.kt`
  — `writeAllOrNone`, the compensation and the report.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/error/RunCatching.kt`
  — why the cancellation is re-thrown rather than caught.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/proposals/ApplyProposalItemUseCaseTest.kt`
  — the test that cancels the scope mid-write.
