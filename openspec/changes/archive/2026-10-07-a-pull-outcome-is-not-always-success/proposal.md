# a-pull-outcome-is-not-always-success

**Status:** proposed · **Issues:** #175, #177

## What

Two of the three places where a received change was not applied were reported as applied,
and the third reported a stalled cycle as a successful one. Requirements REQ-OS-020 …
REQ-OS-023.

- A change with an absent or non-document payload is now **skipped**: not applied, the
  position moves past it, and it is counted as dropped.
- A delete that did not happen is now **failed**: not applied, and the position stays put
  so the delete is delivered again.
- A cycle that stopped early is now a **failure**, not a partial page reported as success.

And the invariant behind #177: **a type this device uploads is a type it can apply.**

## Why

`ApplyOutcome` had two arms, `Applied` and `Conflict`, and three situations were folded
into `Applied`. That single value is what made each loss silent:

- **A payload-less event.** The server considered it delivered, the client considered it
  done, and the row was never written again. The pull summary said it had arrived.
- **A failed delete.** The server's delete is the only event that would ever remove the
  row, so consuming it left the local copy with nothing left to say so.
- **A stalled cycle.** The loop broke before the end of the page and then fell through to
  the success path, stamping a "last synced" time on a device that was stuck behind an
  event it would re-receive on every cycle and never apply.

The last one is why the stall is reported as a failure rather than merely logged. A
device that is permanently stuck and a device that is synchronised produced identical
screens, which is the same reason the earlier `_isLoading` defect mattered.

Time entries are the concrete instance of the invariant. The seed uploaded them; the
dispatch table could delete one and not create one, because `TimeTrackingRepository`
models tracking as a state machine and has no upsert. So a second device hit a change it
could not apply, and an unappliable change stops the position — for everything, forever.
The seed no longer uploads them, which is the smaller change: today a second device
receives nothing *and* its sync breaks, so nobody is worse off. Whether tracked time
should sync is a product question and stays in #177.

## Why the two non-applied cases are not the same outcome

They need opposite cursor treatment, and conflating them is the failure mode.

A payload that is absent will be absent again, so waiting cannot help — the position must
move or the account stops. A delete that failed might succeed next time, so the position
must stay or the row is stranded. One outcome cannot do both, which is why
`ApplyOutcome` grew `Skipped` and `Failed` rather than reusing the existing `Skipped`
arm: that arm already meant "another profile's event", and borrowing it would have
propagated the wrong cursor behaviour into a new case.

## Scope

**In scope:** the three false successes; the two new outcomes; the stalled-cycle
reporting; the seeded-types invariant and the seed change that satisfies it.

**Out of scope, deliberately:**

- **The clock.** `SyncEngine` now reads an injected clock, which is what made the backoff
  tests possible, but the clock *merge* is in `account-switch-and-clock-drift` and needs
  a wire-format change.
- **The wire format** REQ-OS-020's payload-less case is detected from what already
  arrives; nothing here changes the protocol.
- **Whether time entries sync.** Filed in #177. What lands here is that the two halves
  cannot disagree again.
- **Transactions.** The pull page is still applied row by row with no transaction around
  it, and an entity write plus its outbox write is still two calls. Untouched here.
