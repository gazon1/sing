---
title: "The seeds wait in a queue"
date: 2026-11-18
status: accepted
tags: [seed, android, testing, e2e]
supersedes: 2026-11-17-the-seeds-run-in-arrival-order
---

# The seeds wait in a queue

## Context

`2026-11-17-the-seeds-run-in-arrival-order` fixed the *order* of the seeds: the
seed of an arrival runs after the seed of the arrival before it has finished.
The mechanism it chose was a gate per arrival — a `CompletableDeferred` the
arrival before it opens — plus one thread per link, started from the activity:

```kotlin
Thread {
    runBlocking { sequencer.run(arrival) { seed(params) } }
}.start()
```

The order was right, and the cost was paid in three places:

- **A thread per link.** Every arrival started an OS thread that did nothing but
  wait at its gate. The links that arrive in a burst are the links that get a
  thread each, and the threads are not interchangeable: each one waits for its
  own gate.
- **A `runBlocking` in the lifecycle callback.** The thread blocks until the
  seed is done, so the arrival's own thread is held for the whole seed.
- **Two classes that hold one fact between them.** `SeedArrival` carried the
  number *and* the gates, `SeedLinkLifecycle` carried the link and its arrival,
  and `SeedSequencer` held the rule. The arrival was a number with gates
  attached, and the rule was split over three files.

`kotlinx.coroutines` is already a dependency, and a `Channel` with one consumer
is the queue this contract was describing all along: the arrival order *is* the
order the requests are written into the channel, and the consumer takes them one
at a time.

## Idea

Put the requests in a channel and let one consumer take them in order. The
arrival number stays what it was — the fact that decides who owns the screen —
and it stops being anything else.

## Decision

**The requests wait in a `Channel`, and one consumer runs them.** `SeedQueue`
owns an unlimited channel of `SeedRequest(arrival, params)` and a `consume`
loop that runs the seeds one at a time, in the order they were written into the
channel. The channel is the order, and it cannot drift from it: the request is
written by the arrival that produced it, on the main thread, in the lifecycle
callback.

**`SeedSequencer` is only the screen.** It counts the arrivals and says which
one owns the screen (`arrive`, `ownsScreen`). The serialisation is gone from it
— the `Mutex`, the `run { ... }`, the gates — because the queue is the only
mechanism that sequences the seeds, and a second one would be a second answer to
the same question.

**The queue lives in `shared/core/seed`, not in the activity.** The order is a
rule of the seed contract, and `androidApp` has no JVM test source set: a queue
in the activity could only be held by a device test. In `commonMain` it is held
by `SeedQueueTest`, on the real channel.

**The two callbacks are not symmetric.** `onSuccess` is called for the arrival
that still owns the screen — a superseded run does not bring the app up, and the
check lives in the queue, so the rule is the production rule and not a copy of
it in the activity. `onFailure` is called for every failure: the diagnosis
belongs to the seed that failed, whoever owns the screen, and only the *showing*
of the failure is the screen owner's business.

**A failed seed does not stop the consumer.** The failure of one seed is caught
around that seed alone; the consumer goes on with the next request. The
cancellation of the consumer itself is rethrown — it is not a failed seed, and
swallowing it would leave the queue half-alive.

**One scope, owned by the activity.** The consumer runs in a scope that belongs to
the activity — not `lifecycleScope`, because the destruction of the activity must
not cut a seed in the middle of its write, and not a scope per link, because the
consumer is one coroutine. The scope is the project's own
`createBackgroundScope(loggingBackgroundFailureHandler())`, so the dispatcher and
the failure policy are the ones the rest of the app uses, and the rule that keeps
a dispatcher out of the feature code stays in force. The activity closes the queue
when it is destroyed: the requests that are already in it still run, and the
consumer stops when the queue is empty.

**The parameters are read before the arrival is recorded.** A link that carries
nothing to seed is not an arrival, and it must not take the screen away from a
link that does carry something.

## Rationale

A channel is the queue the contract described, and it is cheaper than what it
replaces: no thread per link, no `runBlocking` in the lifecycle callback, and
one class fewer to hold the arrival.

The order it gives is stronger than the gate's. The gate ordered the seeds by
*the arrival each worker belonged to* — a worker that started late still waited
for its own gate, but the arrival and the worker were two things that had to
agree. The channel has no second thing: the request is written by the arrival
itself, on the main thread, and the consumer takes the channel's order.

The channel is also the one place where "one at a time" is written down once:
`consume` runs the seeds in a `for` loop, and the loop is the serialisation.

The order is held by a test on the real queue: `SeedQueueTest` puts a request in,
holds it with a `CompletableDeferred`, puts the next one in, and asserts that
the second one has not started when the first is released — the exact sequence
the contract is written in, without an Android harness.

## Consequences

- The activity has no thread per link and no `runBlocking`: the link is read
  and queued on the main thread, and the consumer runs the seeds.
- `SeedSequencer` is two functions and two fields. `SeedArrival` and
  `SeedLinkLifecycle` are gone.
- The seeds are ordered by arrival, so the profile ends up in the state the
  *last* link that arrived describes, and the screen belongs to that link.
- A slow seed delays the next one, and a seed that hangs hangs the ones behind
  it — the price of a defined state, paid where it is visible: in the time
  between the two links.
- The queue is closed when the activity is destroyed. A link that arrives after
  that is dropped, and it is logged: the activity is gone, and there is nobody
  left to seed for.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedQueue.kt` —
  the channel and the consumer
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedRequest.kt` —
  the arrival number and the parameters, together
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedSequencer.kt` —
  the arrivals and the ownership rule
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedQueueTest.kt` —
  the order, held
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedSequencerTest.kt` —
  the screen ownership, held
- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt` —
  where the link is handled and the request is queued
- `2026-11-17-the-seeds-run-in-arrival-order` — the decision this one completes
- `2026-11-17-a-seed-link-is-handled-where-it-arrives` — the arrival rule the
  whole contract is written in
