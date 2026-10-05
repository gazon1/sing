---
date: 2026-10-05
status: accepted
---

# A hybrid clock is merged, and a clock that disagrees by more than five minutes stops the write

## Context

The sync engine stamps every patch with a hybrid logical clock
(`core/sync/HlcFactory.kt`, `Hlc.tick`). The server orders fields by that clock, so the
clock is what decides whose edit survives a conflict.

The audit of the auth/sync test plan found the other half of a hybrid clock is not wired:
`HlcFactory.tock` — the merge performed when a message is *received* — has no caller in
production. `grep -rn "tock(" shared/src` returns the two definitions and two tests.
Confirmed independently in the plan's row BP-06 and rows CV-09/CV-10.

Nothing merges a foreign clock, so `Hlc.tick`'s physical component is
`max(device wall clock, last local tick)` and stays there. Two failures follow, both
silent:

- A device whose clock is a day behind emits patches the server ranks below everything
  the other device wrote, forever. `lastPhysical` never catches up, because only local
  events advance it.
- A device that receives a far-future clock never learns it, so its own subsequent
  patches stay behind and keep losing.

The wire format compounds it: `SyncEvent` (`SyncProtocol.kt:96-116`) carries
`serverLsn`, `createdAt`, `protocolVersion` and `profileId` — **no clock at all**. Even
with a caller there is nothing to merge. The test plan listed this as defect S4 and
recorded it as fixed; it is half fixed.

## Idea

Three candidate policies for a received clock, in increasing strictness:

1. **Adopt it.** Take the remote physical time as the local one. Cheapest, and wrong: a
   single bad event — or a server bug, or a client whose own clock is far ahead —
   rewrites this device's clock permanently. There is no evidence to distinguish "the
   server is right" from "this value is wrong", so adoption is trusting the last writer.
2. **Clamp.** Move the local clock toward the remote one by at most *D* per merge.
   Bounded damage from one bad value, and no single event can rewrite history. But *D*
   becomes a tuning constant with no principled value, and a device that is consistently
   a day behind still drifts, just slower.
3. **Refuse past a bound.** Merge normally while the disagreement is within *D*; beyond
   it, report and stop writing.

## Decision

**Merge is mandatory, and drift past five minutes stops the write with a visible,
handleable error.**

- `tock` runs on every received event. Without it a hybrid clock degenerates into a wall
  clock, which is what it is currently.
- The physical component is `max(local, remote, system)`, per the standard receive rule:
  an effect must be ordered after the cause that produced it.
- When the remote reading disagrees with this device's by more than **five minutes**, the
  cycle does not write. It reports a distinct condition naming the drift, and local work
  continues — the user can still create and edit, and the queue holds until the clock is
  right.
- The error is never silent. A refusal the caller cannot distinguish from success is the
  same defect as no check at all.

Five minutes matches the de-facto default of the `dldc/hybrid-logical-clock`
implementation, and the rationale generalises: **NTP keeps a well-behaved host within
roughly that of true time, so a larger disagreement is a fault rather than latency.**

The load-bearing property is that a hybrid clock is *not* a clock synchronisation
mechanism. It reads the physical clock to order events and tolerates its anomalies; it
does not correct it. Attempting to repair the system clock from `tock` is the
architectural error here, and it is what policy 1 amounts to.

## Rationale

Policy 3 was chosen over clamping because the failure this guards against is not gradual.
A device whose clock jumped forward by a year is a device whose user set it by hand, and
a per-merge clamp would still move it thousands of times in the wrong direction before
anybody noticed. Refusing is the only option whose damage is bounded by one event.

It was chosen over adopting because there is no evidence available at the point of merge
that would distinguish a correct remote clock from a wrong one. A rule that cannot tell
them apart must be the conservative one.

A named error rather than a silent skip follows from the same place every other
classification in this codebase does: a condition the user can act on, under a code that
survives a reworded message, is worth acting on. "Your device clock looks wrong" is a
better message than edits that quietly lose.

## Consequences

- The client must be able to tell a drift refusal apart from a server refusal. It is a
  distinct reported state, not a retryable network error — retrying does not move a
  clock.
- **The merge has nothing to read.** `SyncEvent` carries no clock, so this decision
  cannot be implemented on the client alone. Either the server echoes the merged clock on
  the event row, or the client measures drift against a server-supplied *current* time.
  Using a single event's `createdAt` as that reading is a confound and is not acceptable:
  it conflates "the server's clock is wrong" with "this account has been quiet for a
  while", and an account idle for a day would be refused as drifted.
- Until the wire format changes, drift is undetectable and this decision is inert. The
  `tock` merge alone is still worth doing and is independent of it: merging monotonic
  logical time is correct whether or not the physical component can be trusted.
- The test plan's rows CV-09 and CV-10 become implementable tests rather than
  characterisation, once a server clock reading exists.

## Links

- Issue #179.
- Test plan `/home/max/Downloads/Тест-план_ авторизация и синхронизация.md` §6.2 BP-06,
  §6.8 CV-09 and CV-10, §12 Q7.
- `2026-10-04-sync-server-schema-and-merge.md` — where `sync_to_millis` gives the server
  a millisecond reading to hand out.
