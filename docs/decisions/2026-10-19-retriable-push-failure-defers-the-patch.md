---
title: "A retriable push failure defers the patch, and a discarded response is not a processed one"
date: 2026-10-19
status: accepted
tags: ["sync", "bugfix", "contract", "testing"]
---

## Context

Seven tests in the sync push suite were red on a clean checkout: four in
`SyncEnginePushTest`, one in `PushPhaseTest`, one in `SyncEnginePushIdentityTest`
and one in `SyncEngineLostRaceTest`'s neighbourhood. The red was the only
evidence — every one of them was a test written to state a rule, and every one
of them was failing.

Two separate defects were behind them.

**Defect 1 — the patch was destroyed instead of deferred.**
`PushPhase.processResponses` handled a retriable failure like this:

```
result.isRetriable -> {
    outboxDao.delete(result.patchId)      // ← the row is gone
    deferOrDeadLetter(...)                // ← which then UPDATEs it
}
```

`deferOrDeadLetter` records the failure with `outboxDao.markFailed(...)`, and
`markFailed` is `UPDATE sync_outbox SET attempts = attempts + 1, … WHERE patch_id = :id`
— an UPDATE, not an upsert. Updating a row that was just deleted updates
nothing. The patch was therefore **removed from the outbox with no retry and no
dead letter**: a user's edit, destroyed because the server said "busy".

The delete had been added by PR #717, a *style* commit titled "fix(detekt):
resolve 46 style/complexity violations across 9 files", which restructured an
`if/else` into a `when` and copied the delete from the neighbouring "server
confirmed it" branch. Before that commit the retriable branch did not delete
anything. A compliance refactor introduced silent data loss, and the tests that
would have caught it had been red ever since.

**Defect 2 — a discarded response counted as a processed one.**
`PushSummary.processed` was set to "the number of results the server sent".
A response that arrived after a sign-out is *discarded* — the server answered,
and this device declined to act on the answer — and it was counted as
processed. The summary therefore said "the server answered and we handled it"
for the one case where nothing was handled. The contract the other tests
already state is the opposite: `SyncEngineLostRaceTest` asserts
`processed == succeeded + failed + lost`, and `PushPhaseTest` asserts
`processed == 2` for a response of two results, one of them superseded.

## Idea

Fix the delete, and make `processed` mean what the rest of the suite already
says it means: the results this client acted upon. A discarded result is in
neither.

## Decision

**A retriable failure defers the patch; only an exhausted retry dead-letters
it. And `processed` counts what was acted upon, not what arrived.**

- `PushPhase.processResponses` — the retriable branch no longer deletes the
  row. `deferOrDeadLetter` updates it in place (`attempts + 1`,
  `nextAttemptAt`), and the dead-letter path deletes it only after the payload
  has been shelved in the dead-letter store.
- `PushCounts.toSummary()` — `processed` is now `succeeded + failed + lost +
  superseded`. The `totalResults` argument is gone, because a number that
  counted discarded results was the defect.
- `PushSummary`'s KDoc states the invariant, so the next reader does not have
  to derive it from three test files.

**The non-retriable branch was left alone.** A permanent error still deletes
the row and settles the shadow. Whether that should dead-letter instead is a
policy question about what the server's terminal errors mean, and it is not
what the red tests were about.

## Rationale

- **"Deferred" and "deleted" are different operations.** The suite's own KDoc
  says the policy is "moved, not deleted — a user's edit should not be
  destroyed because the system could not deliver it". The code deleted the row
  and then tried to update it, which is the one combination that achieves
  neither.
- **A style refactor is not a safe place to change behaviour.** The delete was
  not a design decision; it was a line that moved next to another line. The
  test suite was the only thing that noticed, and it noticed six months later.
- **`processed` had two meanings in one repository.** The lost-race test and
  the push-phase test already stated the "acted upon" reading; the
  implementation used "received". One of them had to change, and the tests were
  the ones that were right: a summary that counts what you declined to do as
  work you did is the same shape of lie as a counter that cannot become
  non-zero.
- **The discard case is not a failure.** It is a fourth thing, and it has its
  own field precisely so that it cannot be counted as one of the others by
  accident. Counting it in `processed` was that accident.

## Consequences

- **Seven red tests are green**, and the push suite as a whole passes.
- **A patch survives a transient server error.** It is retried with the
  backoff the policy already defines, and it reaches the dead-letter store when
  the attempts run out.
- **`PushSummary.processed` means "acted upon".** A consumer that wanted
  "results received" now has to add `discarded` to it — which is the sum of the
  five fields, and is one line.
- **The dead-letter path now deletes a row that exists.** Before this change it
  deleted a row that the retriable branch had already deleted, so the delete
  was a no-op and the row was gone either way. Now the row survives until the
  dead letter is written, and the delete is the last step.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/PushPhase.kt` — the fix
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt` — the `PushSummary` contract
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncOutbox.kt` — `markFailed` is an UPDATE
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncEnginePushTest.kt` — the retry policy
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncEnginePushIdentityTest.kt` — the discard case
