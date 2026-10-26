# Design — a-turn-that-hangs-has-ended

ADR: `docs/decisions/2026-10-26-a-turn-that-hangs-has-ended.md`

## The turn's clock

`AcpTurnRunner` already documents itself as the owner of "a turn has a clock of its own".
Today it owns the *identity* of a turn (which turn may write) but not its *bounds*. This
change gives it the bound too, which is why the runner's input changes from an opaque
suspend lambda to the turn's event stream:

```kotlin
// before — the runner cannot see the flow, so it cannot bound it
fun start(assistantId: String, run: suspend (sink: suspend (AcpTurnEvent) -> Unit) -> Unit)

// after — the runner owns the clock and builds the flow inside the turn
fun start(assistantId: String, events: () -> Flow<AcpTurnEvent>)
```

The events are drained into a channel by one producer and consumed one at a time under a
deadline (`withTimeoutOrNull(TURN_IDLE_TIMEOUT) { received.receiveCatching() }`), re-armed
per event. This is deliberately *not* `Flow.timeout`, and not `withTimeout`:

- `withTimeout` would bound the **total** duration and kill a long but healthy turn;
- a single `Flow.timeout` cannot be paused, and the window must **not** count time the turn
  spends waiting on the reader. A permission request is silence by design: the agent asked
  the reader a question, and the permission deadline is five minutes. So when the window
  expires and `awaitingReader()` is true, the loop re-arms instead of ending the turn.

The channel is bounded (64): it is the transport's backpressure, and a full buffer can never
starve the deadline, because a full buffer means the consumer has something to take. The turn
stops as soon as the completion arrives, and an event that lands exactly as a window closes is
taken (`tryReceive`) rather than calling a turn that just answered hung.

Without that exception the idle bound would contradict FR-036 ("the permission request is
declined, and the turn continues afterwards") — every turn that waited more than a minute for
an answer would die.

## What a turn's ending means

The runner already distinguishes the endings it must: a completion (`Finished`), a
cancellation the caller did not ask for (the transport dying), a thrown failure, and a
stream that simply stopped. It now also knows whether the reader stopped the turn — the
ViewModel's cancel records an outcome first, and `outcomeRecorded` is the flag that says so.

```
completed = a Finished arrived
readerStopped = an outcome was already recorded when the body returned
sessionLost = !completed && !readerStopped
```

`sessionLost` is reported to the ViewModel through a new callback, once, for the current
turn only (the same turn-identity guard the activity and the outcome already use — a turn
that was stopped keeps running for a moment and must not write onto the next one).

## The ViewModel's response

On `sessionLost(reason)`:

- the connection becomes `Failed(reason)`. `Failed` is the existing state that renders the
  reason and the Connect control — the "error and a way to reconnect" FR-040 asks for;
- the session is closed (bounded, graceful; the process may already be gone, and closing an
  already-dead session is safe — FR-039);
- a permission request that was on screen is refused and taken off it: a live sheet under a
  "connection failed" banner is a question the reader can answer but nothing will hear;
- the tool-access state becomes `Unavailable`, because a session that is gone cannot reach
  the user's tasks and a stale `Granted` is a false claim (the same reasoning FR-032 applies
  to a failed handshake);
- the partial answer is left alone.

The turn's stream is built **inside** the turn, not on the intent that starts it: the client
builds the flow eagerly and throws when it is no longer connected, and that throw must become
a turn outcome rather than an exception escaping the screen's intent.

## Rollback risk

Low. The change adds a bound and a state correction; it removes nothing. Reverting restores
the unbounded wait and the stale-connected state — both worse, neither destructive. There is
no data migration and no persisted state.

## What this does not do

- It does not subscribe the screen to `AcpSessionEvent`. The turn's own ending is the signal,
  and it is the thing that knows the flow stopped; a second subscription would be a second
  path to the same fact.
- It does not kill a hung *agent process* beyond closing the session. `close()` is bounded and
  graceful; a process that ignores it is a separate concern already owned by the cancel path's
  `terminate()` and by FR-038.
