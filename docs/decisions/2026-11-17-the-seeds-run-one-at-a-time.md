---
title: "The seeds run one at a time"
date: 2026-11-17
status: accepted
tags: [seed, android, testing, e2e]
---

# The seeds run one at a time

## Context

The debug seed activity is declared `singleTask`: a seed link that reaches a
running instance is delivered to `onNewIntent` instead of starting a second
activity. The first implementation of the arrival handling seeded every link
that arrived, each on its own thread, and used a run number to decide which of
them owns the screen.

That decided *who speaks*, not *who writes*. Two seeds wrote at the same time:
the second link's seed began while the first one was still writing its rows,
and both wrote to the same profile. A seed is a definition of the state — a
link says "the profile holds this" — and two definitions written at once leave
a profile that is neither of them: rows written over each other, entities one
seed never saw, and a repeat run that lands somewhere else again.

`reset=content` is the case where this is not a detail. A reset wipes the
active profile's content; a wipe that runs while another seed is writing
deletes the rows that seed is halfway through producing, and the flow that
asked for them finds a profile with a hole in it.

The seed contract says a dataset brings the profile to a defined state. Two
seeds at once break that promise, and the PR said so in as many words: the
older run keeps writing its rows while the newer one owns the screen.

## Idea

Sequence the seeds instead of racing them. One seed at a time, in the order
the links arrived; the newest link still owns the screen.

## Decision

**The seeds run one at a time.** A link that arrives while a seed is running
waits for it to finish before it starts. The wait is a single lock around the
seed — `SeedSequencer.run` — not a queue of pending links: the links that
arrive while a seed runs are the links that run next, in arrival order, and a
link that never arrives is not a link that has to be remembered.

**The new run does not start its reset before the previous one finished.** The
lock is held across the whole seed — the profile switch, the reset and the
writes — so a `reset=content` is never inside another seed's writes.

**Ownership is decided by arrival, not by completion.** The newest arrival
owns the screen, even while the older seed is still running: it is the one that
will write last. An older run that finishes late writes its own rows and stays
silent — it does not bring the app up and it does not show its failure.

**A failed seed does not block the next link.** The lock is released when the
seed throws, so a link that names a priority nobody knows fails on its own
screen and the next link seeds normally.

**The rules live in one class.** `SeedSequencer` holds the arrival number, the
ownership rule and the lock, so the sequence of two links can be tested
without an Android harness — the activity is the entry point, not the logic.

## Rationale

The rule that a link is handled where it arrives was measured on the device
(realme RMX3938, Android 15) in
`2026-11-17-a-seed-link-is-handled-where-it-arrives`; this decision changes
what happens *after* the link arrives.

Measured on the device after this change, with two seed links three seconds
apart, the first one still running when the second arrived (realme RMX3938,
Android 15, 2026-11-17):

```
21:31:37.108  START u0 {… dat=todo-debug://seed/… cmp=…/.debug.DebugSeedActivity}
21:31:39.188  Displayed com.singularity.todo/.debug.DebugSeedActivity
21:31:40.215  START u0 {… dat=todo-debug://seed/… cmp=…/.debug.DebugSeedActivity}   ← the second link
21:31:50.578  I DebugSeedActivity: seeded 300 tasks with base title 'SweepFirst'
21:31:51.008  I DebugSeedActivity: task 'SweepSecond' was Updated
21:31:51.019  START u0 {flg=0x34008000 cmp=com.singularity.todo/.MainActivity}
21:31:51.201  Displayed com.singularity.todo/.MainActivity
```

The second link arrived at 21:31:40.215, while the first seed was still
writing its 300 rows. Its own seed started at 21:31:51.008 — 430ms *after*
the first seed's rows were written, not when the link arrived. Without the
lock the second seed would have written at 21:31:40.3, into the middle of the
first seed's rows.

The first run stayed silent when it finished: the app came up once, at
21:31:51.019, from the run that owns the screen.

The order is held by a test as well: `SeedSequencerTest` runs the real
executor — the same `run` the activity calls — with two links, and holds that
the second one does not write anything until the first one has finished. A
test that only compared run numbers would have passed while the seeds raced;
the sequence is the thing that matters.

## Consequences

- A link that arrives while a seed is running is seeded, with its own
  parameters — it waits its turn. The fixture the caller asked for exists.
- The seeds are ordered by arrival, so the profile ends up in the state the
  *last* link describes. That is the state a repeat run of the same link
  produces as well.
- A slow seed delays the next one. That is the price of a defined state, and
  it is paid where it is visible: in the time between the two links.
- The app comes up when the *newest* run finishes. An older run no longer
  brings the app up while a newer run is still waiting to write.
- The older run's rows land before the newer run's, not after the app is up.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedSequencer.kt` —
  the lock, the arrival number and the ownership rule
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedSequencerTest.kt` —
  the sequence of two links, held
- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt` —
  where the link is handled and the seed is run
- `2026-11-17-a-seed-link-is-handled-where-it-arrives` — the arrival rule this
  one completes
