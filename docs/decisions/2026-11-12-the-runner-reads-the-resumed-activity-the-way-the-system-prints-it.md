---
title: "The runner reads the resumed activity the way the system prints it"
date: 2026-11-12
status: accepted
tags: [testing, maestro, android, ci, ui-automation]
---

# The runner reads the resumed activity the way the system prints it

## Context

The runner gives every flow a clean device: it resets the app's content, forces
the app to stop and starts it again, so that the flow's first command runs
against a fresh process with fresh rows. The reset is asynchronous, so "the
reset is done" has to be *observed*, and the observation is the resumed
activity: the app is in front when the reset thread has returned.

The wait looked for one shape of that line:

```python
if f"topResumedActivity=.{APP_ID}/.MainActivity" in out:
```

Two things are wrong with that, and both were found by measuring on a phone
rather than by reading the code.

**The shape is not the one the system prints.** On a realme RMX3938 (API 35),
`dumpsys activity activities` prints

```
  ResumedActivity: ActivityRecord{5a1b95b u0 com.singularity.todo/.MainActivity t3273 d0}
```

and no `topResumedActivity=` at all. The old shape is what an older Android
version printed. So the matcher never matched, every wait ran to its full
timeout, and the wait that was introduced to replace a fixed sleep was itself
a fixed sleep — a slower one, and one that ended in "not resumed" every time.

**The rule had two owners.** The runner (`emulator.maestro._device`) and the
CI driver (`scripts/run-maestro-e2e.py`) each had their own copy of "what the
resumed activity looks like", and the copies had drifted: the CI one knew only
the old shape. Two copies of a rule that has to be right about the device's
output format is one copy too many.

There was a third problem in the CI copy, and it was not about the shape at
all: nothing had launched the app before its readiness check ran. The flows
launch the app themselves, and they run *after* the check. A wait that asks
whether the app is up cannot pass while the app is not running, so it timed
out on every shard, burned ninety seconds per shard, and printed "App not
confirmed ready" as though the device had failed to start the app.

## Idea

Read the line the way the device prints it, in one place, and give the check
something to wait for.

## Decision

**One matcher, one owner.** `emulator.maestro._device.resumed_activity_line`
reads the resumed-activity line out of a `dumpsys activity activities` dump.
Both shapes are read — `ResumedActivity:` and `topResumedActivity=` — and the
markers are anchored on the left, so a differently-named field
(`mResumedActivity:`, which `dumpsys activity` prints at the top level) cannot
be mistaken for the answer. The CI driver imports the matcher instead of
carrying a copy of the rule.

**Two waits, two questions.** "The app is in front" is a question about the
*package*: the debug seed activity, which the runner drives the content reset
through, belongs to the same app id, so a package-level waiter answers true
for it too. That answer is too weak to guard the reset, so the reset waits for
the *main* activity specifically. The two are separate functions because the
two questions are separate.

**A matcher that cannot match is a sleep that lies.** The rule is pinned by a
test against the dump the device actually printed, so the shape cannot drift
back without the test noticing.

**The CI driver starts the app before it waits for it.** The readiness check
now has an answer to give: the installed APK either comes up or it does not,
and the shard learns which before it spends minutes driving flows against a
dead app.

## Rationale

A wait that can never succeed is worse than no wait: it costs the timeout on
every run and reports a state ("not ready") that is really a state of the
*check*. The two shapes exist because the Android version changed, and the
version is not ours to choose.

The distinction between the two waits is not pedantry. The seed activity and
the main activity live in the same package, so a matcher that only asks about
the package answers true for both — and the reset is exactly the moment where
the difference matters, because the reset is over when the *main* activity is
in front.

The wait answers "the reset thread has ended", not "the reset worked": a
seeding thread that threw still brings the app up. The docstrings say so, and
a false answer warns instead of being discarded, because the flow that follows
would otherwise run against whatever the previous flow left behind.

## Consequences

- The runner's reset wait answers for the first time. On the phone, the agenda
  suite's eleven flows run without a single "the app did not come up"
  warning, and each flow starts against a reset that has finished.
- The CI driver spends seconds on the readiness check instead of ninety
  seconds, and its answer means something: the app is started, and then the
  app's presence is observed.
- A second copy of the rule is gone, so the shape of the device's output is
  pinned in one test rather than in two implementations that can drift apart.
- The wait is still a wait, not a proof. A seeding thread that threw brings
  the app up, and the check cannot tell the two apart — which is why the false
  answer warns rather than being swallowed.

## Links

- `docs/decisions/2026-10-04-maestro-flow-isolation.md` — the decision this amends
- `docs/decisions/2026-10-28-reset-content-reaches-the-whole-profile.md` — the reset this waits for
- `scripts/src/emulator/maestro/_device.py` — the matcher and the two waits
- `scripts/src/emulator/maestro/_fixtures.py` — the reset that waits
- `scripts/run-maestro-e2e.py` — the CI driver that imports the matcher
- `scripts/tests/test_maestro_resumed_activity.py` — the dump the matcher is pinned against
