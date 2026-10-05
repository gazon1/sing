# local-storage-failure-classification

**Status:** proposed · **Issues:** #161, #165 · **ADR:** `2026-10-05-sync-storage-failure-is-not-a-server-refusal`

## What

A failure to read or write the device's own state SHALL be reported as a local-storage
failure, and a user SHALL be able to tell it apart from a remote service that refused the
request.

The invariant is already established on the sync cycle: every local read and write there
is classified at the point of the call, names the operation it was performing, and lands
the reported state in a terminal condition. Two places are outside it, and they are the
subject of this change:

- The path that queues a local change for later upload classifies a storage failure as
  unclassified. This is the last such path in the sync layer.
- Two of the four preference wrappers do not classify at all.

## Why

A user whose database is closed and a user whose server refused are in different
situations with different remedies — one restarts the app, the other waits and does not
throw away their edits. Telling them the same words makes the first retry the wrong thing.

The failure is not only in the words. An unclassified local read that escapes the sync
cycle leaves the reported state advertising work in progress, and the state that reads
as "running" is the one callers check before starting the next cycle. So a single storage
failure at the wrong moment can make every later attempt decline itself.

## Scope

**In scope:** the classification, the wording, the terminal reported state, and the
preservation of the underlying failure — on every path that reads or writes local state.

**Out of scope, deliberately:**

- Whether the two preference wrappers should absorb a storage failure or propagate it.
  That is a product decision that has not been made; it is tracked in #165 and is not
  specifiable until it is. This change only requires that such a failure be
  *classified* when it is reported, not that it be swallowed.
- Which specific operations are wrapped. A spec that names them is a spec about code.
- Anything about the remote side, which already names its own failures.

## Why a new capability rather than a change to an existing spec

No spec covers local state or its failure behaviour. `core/sync-state` is listed as a
backfill candidate and explicitly marked droppable; authoring it here would capture ~800
lines of unrelated history with nothing forcing it to stay accurate. The behaviour this
change describes is narrower than that module and crosses into the settings layer, so it
gets its own capability id rather than being forced into either.
