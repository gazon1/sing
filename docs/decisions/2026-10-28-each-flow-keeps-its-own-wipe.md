---
title: "Each flow keeps its own wipe, and the session reset is not free either"
date: 2026-10-28
status: accepted
tags: ["e2e", "maestro", "performance", "tooling"]
---

## Context

`2026-10-21-each-flow-gets-a-clean-device.md` decided that the runner wipes
the active profile before **every** flow. The cost of that decision was named
in the same ADR ("about a second per flow"), and a review measured it again
and proposed removing it:

> Every one of 117 flows pays `reset_session` (force-stop + cold start +
> sleep 1) and `cleanup_run_fixtures` (`am start reset=content` + sleep 1).
> That is 2–4 s × 117 = 4–8 minutes of pure setup, and 96 flows additionally
> pay a launch-clean cold start with a 30 s shell wait.
>
> **Fix:** Batch the fixture reset per shard instead of per flow, or drive
> several assertions from one seeded app state.

## Idea

- **A.** Wipe once per shard, and let the flows share the device's content.
- **B.** Keep the wipe per flow, and record what it actually costs.
- **C.** Keep the wipe, and remove the waste around it.

## Decision

**B**, with the cost measured and the waste named.

**The wipe stays per flow.** The ADR that introduced it measured the
alternative: before it, 51 of 94 flows failed; after it, 39. Thirteen flows
changed from failing to passing, and they were exactly the ones the earlier run
had polluted. A per-shard wipe trades 4–8 minutes of setup for those thirteen
failures coming back, and the nightly of 2026-10-20 failed 43 flows across all
four shards in exactly that shape. The session reset is not the wipe: it
restores the *session* a flow leaves behind (a flow that ends signed out must
not decide the session of the flow after it), and it also gives the next flow
a process that is on the agenda shell rather than wherever the last flow left
it.

**What it actually costs.** Measured on the device this repository develops
against:

| | cost |
|---|---|
| `cleanup_run_fixtures` (the content wipe, plus its sleep) | ~1.5 s |
| `reset_session` (force-stop, sleep, start, wait for resumed) | ~1.5 s + the cold start |
| the app's cold start (`am start -W`) | 1.1 s (`TotalTime: 1135`) |
| the whole per-flow fixture tax | ~3 s |

That is 6–8 minutes over 117 flows, which is what the review said. The
**"30 s shell wait" is not real**: `launch-clean.yaml`'s
`extendedWaitUntil` is a bounded poll that returns as soon as `nav_tab_today`
is on screen; 30 s is the ceiling it waits *up to*, not a sleep. On a device
where the element never appears the wait costs the full 30 s — which is how a
flow that asserts a screen the build does not have burns half a minute.

**The waste around it is named, not removed.** The runner launches the app
twice per flow: the content wipe is delivered by `am start` on the seed deep
link, which starts the app, and `reset_session` then force-stops it and
starts it again. Sending the wipe *after* the cold start would cost one cold
start instead of two, and would leave the wipe running against the process the
flow is about to use — which is more correct as well as cheaper. It is not
done here because the seed activity has to be seen to finish on the device
before the flow touches the app, and this change cannot be exercised from a
checkout without an E2E build and an emulator. It is the next thing to try,
and it is worth about a minute a run.

## Rationale

- **The isolation is measured; the saving is estimated.** The ADR has the
  before-and-after numbers for the wipe. The saving has a number too, but it
  is 6–8 minutes against a run that is 150 minutes, paid for in the signal the
  suite exists to produce.
- **A batched reset makes the order a variable again.** A flow that asserts an
  empty list would fail on the previous flow's row, and the failure would name
  the app instead of the run. That is the failure mode the ADR was written
  against.
- **The claim about the 30 s wait was worth checking** rather than accepting:
  it was the largest number in the finding, and it is the one number that
  would have made the whole finding worth acting on (96 flows × 30 s ≈ 48
  minutes). It is a poll.

## Consequences

- **The fixture reset is per flow, and the ADR that decided it is not
  reopened** by a performance review that did not measure it.
- **The per-flow tax is about three seconds**, and the runner's own code says
  so where a reader will look.
- **One waste is left on the table on purpose**, with the reason written down
  where the next person will find it: the double launch per flow.
- **A finding that names a number is worth measuring before it is acted on.**
  Half of this finding's number was a timeout, not a cost.

## Links

- `scripts/src/emulator/maestro/_fixtures.py` — the wipe and the session reset
- `scripts/src/emulator/maestro/_cli.py` — where the wipe runs, before every flow
- `Maestro/helpers/launch-clean.yaml` — the bounded wait
- `2026-10-21-each-flow-gets-a-clean-device.md` — the decision this one holds
- `2026-10-28-reset-content-reaches-the-whole-profile.md` — the amendment that
  closed the two kinds of content the wipe used to miss
- `2026-10-28-a-flow-is-one-scenario.md` — the other half of the same review:
  the flows are not merged either
