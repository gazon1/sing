# A turn that hangs has ended

Issue: — · Related: FR-040, US3 scenario 8,
`docs/decisions/2026-10-26-a-turn-that-hangs-has-ended.md`

## Why

FR-040 promises three things when an external agent crashes or hangs, and none of them held.

A turn had no clock: `AcpTurnRunner` collected the session's prompt flow and waited for ever.
A wedged agent left the screen spinning with a Stop control that asked an agent which was not
listening. And when the flow ended without the protocol's completion event, the turn ended
`Failed` — correctly — but the connection still said **connected**, so the reader kept a
Disconnect button and an input that could not work, and had no way back to a working
conversation.

## What Changes

- A turn that produces no event for 60 seconds ends as `Failed`, with a reason that names the
  silence. The bound is inactivity, not total duration, and it lives in the turn's clock.
- A turn that ends without the protocol's completion event, and was not stopped by the
  reader, means the session is gone: the connection is marked `Failed` with the reason, the
  session is closed, and the Connect control — already shown for a failed connection — is the
  way to reconnect.
- The partial answer stays, as it already did.

## Impact

- Affected specs: `ai/external-agent` — FR-040 is a requirement of the in-flight
  `acp-external-agent-desktop` change; this change delivers it and adds no requirement text
  (see `.openspec.yaml`).
- Affected code: `feature/ai/external` — the turn runner, the ViewModel, and their tests.
- What is explicitly **out of scope**: subscribing the screen to the client's lifecycle
  events (the turn's own ending is the signal); retrying a failed turn (forbidden by FR-041);
  the built-in chat's turn, which has its own cancellation and no external process.
- Supersedes nothing.
