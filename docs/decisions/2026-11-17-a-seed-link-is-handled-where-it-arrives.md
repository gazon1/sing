---
title: "A seed link is handled where it arrives"
date: 2026-11-17
status: accepted
tags: [seed, android, testing, e2e]
---

# A seed link is handled where it arrives

## Context

The debug seed activity is declared `singleTask`: a seed link that reaches a
running instance is delivered to `onNewIntent` instead of starting a second
activity. The implementation seeded only in `onResume` and used `onNewIntent`
to store the new intent and to clear a "seeding" flag.

Measured on the device (realme RMX3938, Android 15), 2026-11-17, before the
change: a second seed link that arrived while the first seed was still
running left no trace at all — no log line, no row — and the first seed's
completion brought the app up on its own terms. The reason is the Android
lifecycle, not the flag: a link that reaches an instance which is *already
resumed* does not come with another `onResume` — the activity is not
restarted for it — so a link handled only in `onResume` was handled by a
callback that never ran.

The same measurement showed the second half of the problem: the first seed's
completion started MainActivity and finished the activity while a newer seed
was still running, so the app came up before the newer seed's rows existed,
and the newer seed's failure could never be shown.

A later measurement (2026-11-17, the serialisation work) found the mirror
image of the same gap. The activity was started by a link, and a second link
arrived while the app was still starting — the app takes about four seconds
to come up on this device. The second link arrived through `onNewIntent`,
which replaced the link the activity had been started with; the resume that
would have seeded the launch link then saw the *second* link instead. The
first link was never seeded, without a word in the log:

```
21:28:00.071  START u0 {… dat=todo-debug://seed/… cmp=…/.debug.DebugSeedActivity}
21:28:03.267  START u0 {… dat=todo-debug://seed/… cmp=…/.debug.DebugSeedActivity}   ← the second link
21:28:03.850  Displayed com.singularity.todo/.debug.DebugSeedActivity
21:28:04.304  I DebugSeedActivity: task 'SweepSecond' was Created
```

The 300 rows the first link asked for are nowhere in that log: only the
second link's single task was seeded.

## Idea

Handle the link where it arrives, and let the newest run own the screen.

A link is handled once, and each seed that starts takes a number; the newest
number is the one that owns the screen. An older run keeps writing its own
rows and says nothing.

## Decision

**The link is seeded in `onCreate`, in `onNewIntent` and in `onResume`.**
Each of them is a place where a link arrives: the launch link in `onCreate`,
a link that reaches a live instance in `onNewIntent`, and a link that arrives
with the activity itself in `onResume`. The same Intent instance is handled
once — the instance is the key — so the `onResume` that follows an
`onNewIntent` does not seed a second time, and the launch link is seeded
before a second link can replace it.

**The newest run owns the screen.** Every run takes a number from
`SeedSequencer`; only the newest one brings the app up and only the newest one
shows its failure. An older run that finishes late writes its rows and stays
silent.

**The seeds run one at a time.** A link that arrives while a seed is running
waits for it: two seeds writing at once leave a profile that is neither one's
state. The rule and the lock are in `2026-11-17-the-seeds-run-one-at-a-time`.

## Rationale

Measured on the device after the change, with two seed links 3 seconds
apart, the first one still running when the second arrived:

| | before | after |
|---|---|---|
| the second link's seed | never ran | ran, with its own parameters |
| the first seed's completion | brought the app up | stayed silent |
| the app came up | once, by the first seed | once, by the newest seed |
| a repeated identical link | dropped | seeded again |

The log of the run that measured this:

```
21:05:05.238  Displayed com.singularity.todo/.debug.DebugSeedActivity
21:05:14.014  I DebugSeedActivity: seeded 300 tasks with base title 'SweepFirst'
21:05:23.786  I DebugSeedActivity: seeded 300 tasks with base title 'SweepSecond'
21:05:23.799  START u0 {flg=0x34008000 cmp=com.singularity.todo/.MainActivity}
21:05:23.962  Displayed com.singularity.todo/.MainActivity
```

The first seed's rows were written and nothing else happened; the second
seed's rows were written and the app came up — once, and from the run that
owns the screen.

The *order* in this log changed with `2026-11-17-the-seeds-run-one-at-a-time`:
the second seed now waits for the first one to finish instead of writing at
the same time. What this log shows is what this decision is about — both links
were handled, each with its own parameters, and only the newest run owned the
screen.

After the launch link was taken in `onCreate` as well, the same two links seed
both their fixtures — the measurement is in
`2026-11-17-the-seeds-run-one-at-a-time`:

```
21:31:50.578  I DebugSeedActivity: seeded 300 tasks with base title 'SweepFirst'
21:31:51.008  I DebugSeedActivity: task 'SweepSecond' was Updated
```

A failed seed behaves the same way: the reason is on the screen and in the
logcat, the app does not come up, and the next link seeds normally
afterwards — the activity is not left in a state where the next link is lost.

## Consequences

- A link that arrives while a seed is running is seeded, with its own
  parameters. This is what the deep link is for: a fixture the caller asked
  for exists.
- The app comes up when the *newest* run finishes. A slow first run no longer
  brings the app up while a newer run is still writing.
- The older run's rows land before the newer run's, and its failure is in the
  logcat but not on the screen — the screen belongs to the newer launch.
- A link is still handled once per Intent instance. The same link sent again
  is a new instance and is seeded again, which is what a fixture that defines
  state rather than appending to it means.

## Links

- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt` —
  where the link is handled
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedSequencer.kt` —
  which run owns the screen, and which seeds run at a time
- `shared/src/commonTest/kotlin/com/singularity/todo/core/seed/SeedSequencerTest.kt` —
  the rule, held
- `2026-11-17-the-seeds-run-one-at-a-time` — the order the seeds run in
- `Maestro/helpers/seed.yaml` — the flow-side contract: the seed is followed
  by a wait for the app
