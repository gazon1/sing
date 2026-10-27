---
title: "A turn that hangs has ended, and a session that died is not connected"
date: 2026-10-26
status: accepted
tags: [acp, external-agent, turn, timeout, connection]
---

## Context

FR-040 asks for three things when an external agent crashes or hangs, and none of them
held:

- a turn that produces no event for 60 seconds is treated as hung and ends as a failure
  with a visible error;
- a session whose process tree dies is marked **disconnected** rather than connected;
- the turn that was running keeps the answer produced so far.

The turn had no clock at all. `AcpTurnRunner` collected the session's prompt flow and
waited — for ever if the agent never spoke. A wedged agent left `isRunning = true`, a
spinner that never stopped, and a Stop control that asks an agent which is not listening.

And a dead session still read as connected. When the prompt flow ended without the
protocol's completion event, the turn ended `Failed` — correctly — but the connection
stayed `Connected`, so the screen kept offering **Disconnect** and an input that could not
work, and the reader had no way back to a working conversation. The lifecycle vocabulary
that already says a session died (`AcpSessionEvent.Disconnected`) was written by the client
only on an explicit close, and the ViewModel never subscribed to anything.

## Idea

Give the turn the clock it says it has, and let a turn that ended without a completion say
what that means about the session: the agent is gone, so the screen should say so and offer
to connect again.

## Decision

1. **A turn is bounded by inactivity, not by total duration.** No event for 60 seconds ends
   the turn as `Failed`, with a reason that names the silence. The bound lives in
   `AcpTurnRunner` — the turn's own clock — and not in the screen, which should not carry a
   rule about the protocol.

2. **The window does not count time the turn spends waiting on the reader.** A permission
   request is the turn talking to the reader, not the agent going quiet: the agent is silent
   because it asked a question, and the permission deadline is five minutes — longer than the
   idle window. So while a request is outstanding the window is re-armed rather than allowed
   to expire, and FR-036's "the turn continues afterwards" stays true.

3. **A turn that ends without the protocol's completion event, and was not stopped by the
   reader, means the session is gone.** The connection becomes `Failed(reason)` — which is
   how the screen already renders "not connected, and here is why" — the session is closed,
   and the Connect control, already shown for `Failed`, is the way to reconnect.

4. **A turn the reader stopped is not a session loss.** The reader's cancel records its own
   outcome first; a turn that ends after that is that turn's business and says nothing about
   the session.

5. **The partial answer stays.** It already did — the turn writes each delta into the message
   as it arrives, and nothing clears it on failure.

## Rationale

**Why inactivity and not a total budget.** An agent that is working legitimately may take
minutes: it emits a tool call, it streams text, it emits the next. What distinguishes
"working" from "hung" is *silence*, not elapsed time. A total budget would kill a long but
healthy turn; an inactivity window kills only a turn that has stopped saying anything.

**Why the permission wait is excluded.** The agent is silent during a permission request
because the reader has been asked a question — the silence is the turn working, not failing.
The permission deadline is five minutes, so a 60-second window that counted it would end every
turn that asked the reader anything and waited more than a minute: FR-036's "the turn continues
afterwards" would be false by construction. The window is therefore re-armed while a request
is outstanding.

**Why the connection state, and not only the outcome.** The outcome line and the header
answer different questions: the outcome says how the last turn ended, the header says whether
there is a conversation. A dead session reported as connected is worse than no answer — the
reader presses Send and nothing happens, with no reason on screen. `Failed(reason)` carries
both the reason and the Connect control, which is exactly FR-040's "an error and a way to
reconnect".

**Why the reader's cancel is excluded.** Cancelling is the reader saying "stop"; the agent
obeying (or being torn down after the grace) is not the session dying. Marking the connection
failed on every cancel would punish the reader for using the Stop control.

## Consequences

- A turn that goes quiet for 60 seconds ends as a failure that names the silence, and the
  connection is marked failed with a way to connect again.
- The screen no longer shows a spinner, or a Disconnect button, for a session that is gone.
- **The price, stated rather than hidden**: a single tool call that produces no protocol
  activity for more than 60 seconds — a long query, a slow download — would be read as a hung
  turn. The protocol reports progress per tool call and per streamed message, so silence that
  long means silence; but the threshold is a number, and a slower agent can cross it. It is
  the number FR-040 names.
- The new failure is not retried, by the same rule as every other failed turn.
- The lifecycle vocabulary is unchanged; the ViewModel does not subscribe to
  `AcpSessionEvent` — the turn's own ending is the signal, because it is the thing that knows
  the flow stopped.

## Links

- FR-040 and US3 scenario 8, `specs/014-acp-client-agent-activity/spec.md`
- ADR `2026-10-23-acp-client-and-agent-activity` — the feature this completes
- OpenSpec: `openspec/changes/a-turn-that-hangs-has-ended/`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpTurnRunner.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModel.kt`
