---
title: "The seeds run in arrival order"
date: 2026-11-17
status: accepted
tags: [seed, android, testing, e2e]
supersedes: 2026-11-17-the-seeds-run-one-at-a-time
---

# The seeds run in arrival order

## Context

`2026-11-17-the-seeds-run-one-at-a-time` decided that the seed links are
sequenced, and stated the order the seeds run in: "One seed at a time, in the
order the links arrived; the newest link still owns the screen." The mechanism
it chose was "a single lock around the seed — `SeedSequencer.run`".

A lock orders the seeds by *the order its waiters reach it*, and that is not
the order the links arrived in. The activity gave every link its own thread:

```kotlin
Thread {
    runBlocking { sequencer.run { seed(params) } }
}.start()
```

Nothing tied that thread to the arrival that started it, so the scheduler was
free to let the thread of a *later* link reach the lock first. The link that
arrived first could therefore be written last:

1. link A arrives, with `reset=content`;
2. thread A is created, and the scheduler does not run it yet;
3. link B arrives with a different dataset;
4. thread B takes the lock and writes B;
5. thread A takes the lock and writes A over B — while `arrive()` has already
   given the screen to B.

The screen then showed B and the data was A. The comment in `SeedSequencer`
promised the opposite, and the decision record repeated the promise.

The lock was not wrong about *serialising* the seeds — one seed at a time held.
It was wrong about *which* seed came next.

## Idea

Order the seeds by the arrival, not by the lock. The arrival is the event the
whole contract is written in: the newest arrival owns the screen because it is
the one that will write last. The write order has to be the same event.

## Decision

**The seed of an arrival runs after the seed of the arrival before it has
finished.** Not "after whatever seed held the lock before" — after the seed of
the link that arrived before it.

Each arrival carries a **gate**: a `CompletableDeferred` that the arrival
before it opens when its seed is done, and that this arrival opens when its own
seed is done. `SeedSequencer.run(arrival)` waits for its gate and opens it in
a `finally`, so a failed seed opens the gate too and does not block the links
behind it.

The gate is created at the arrival, so the order cannot drift: a worker that
starts late, or a thread that reaches the lock early, waits for the gate of the
link that arrived before it. There is no lock left whose waiters are ordered by
their own arrival at it.

**The arrival number and the gates travel together.** `arrive()` returns a
`SeedArrival` — the number that decides who owns the screen, and the gates that
decide who writes first — so a caller cannot order the seeds by one and decide
the screen by the other.

**The link the activity holds is bookkeeping of its own.** `SeedLinkLifecycle`
holds the link the activity is holding now and the arrival it got, so the
activity's three callbacks (`onCreate`, `onNewIntent`, `onResume`) are three
lines each and the sequence they produce is held by a test in the shared
module, without an Android harness.

## Rationale

A single-threaded executor would order the seeds correctly too, and it was the
alternative considered. The gate was chosen over it for three reasons.

**The executor's order is a submission order, not an arrival order.** The
executor runs what it was given first; the submission happens after `arrive()`
returns, so the two orders agree only while the submission happens on the same
thread as the arrival. The gate cannot drift that way: it is created by the
arrival itself, and the arrival is the event the contract is written in.

**The executor has a lifecycle the arrival does not.** A queue belongs to
something: the activity, or a singleton. The activity is `singleTask` and
finishes itself after a successful seed, so a queue owned by it can be shut
down with a seed still queued — the link that arrived second would be dropped
exactly when the first one finishes. The gate has no owner and nothing to shut
down.

**The order has to be testable where the rules live.** The ordering is a rule
of `SeedSequencer`, in `commonMain`, and a test holds it without an Android
harness. A queue in the activity would put the rule in `androidApp`, where the
only test is on a device.

The gate keeps the property the lock had: a worker that never finishes blocks
the links behind it. A seed that hangs still hangs the ones behind it, exactly
as before — the difference is only *which* link waits for *which*.

## Consequences

- The profile ends up in the state the **last link that arrived** describes,
  and the screen belongs to that link. The two can no longer disagree.
- A `reset=content` that arrives first wipes first; a reset that arrives second
  wipes after the older seed wrote its rows. The order of the arrivals is the
  order of the effects.
- `SeedSequencer.run` takes the arrival instead of the block alone, and
  `arrive` returns a `SeedArrival` instead of a number. Both are in
  `commonMain` and are used by the debug activity and by the tests.
- The activity keeps one thread per link. The threads wait at their gate
  instead of at a lock; a link that arrives while a seed runs still gets its own
  worker, and the workers are not interchangeable.
- The order is held by a test that starts the *second* link's worker first: it
  must not write before the first link's seed has finished.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedSequencer.kt` —
  the arrivals, the gates and the ownership rule
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedArrival.kt` —
  the arrival number and its gates
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedLinkLifecycle.kt` —
  the link the activity holds, through its three callbacks
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedSequencerTest.kt` —
  the order, held
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedLinkLifecycleTest.kt` —
  the create / new intent / resume sequence, held
- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt` —
  where the link is handled and the seed is run
- `2026-11-17-the-seeds-run-one-at-a-time` — the decision this one completes
- `2026-11-17-a-seed-link-is-handled-where-it-arrives` — the arrival rule the
  whole contract is written in
