# a-lost-race-resolves-to-the-server

**Status:** proposed · **Issues:** #203 · **Spec:** REQ-OS-026

## What

A per-field race that the server resolves against this device SHALL resolve to the
**server's** state, not to the local one. The local edit is abandoned, the row returns
to what the server holds, and the device says so rather than reporting a success.

## Why

ADR `2026-10-04-sync-server-schema-and-merge.md` already draws the distinction: a patch
whose every field is older answers `ok: true, lost: true`, and "the client needs to hear
it as *your write lost*, not as *your write landed*". The server draws it — verified
against the live project on 2026-10-05, where two writes to one field with the second
clock behind produce exactly:

```json
{"lost": 1, "applied": 0, "created": 0,
 "results": [{"ok": true, "lost": true, "cached": false, "legacy": false,
              "patchId": "calib-2", "newVersion": 1}]}
```

The client does not hear it. `PatchResult` has no `lost` field and `StableJson` sets
`ignoreUnknownKeys = true`, so the signal is dropped at the parse boundary. `SyncEngine`
branches on `ok`, and `ok` is `true`, so it deletes the outbox row and settles the
shadow as confirmed — promoting a state the server never took, recording a version the
server never reached, and leaving the next diff to be computed against that fiction.

The user's edit is then gone from everywhere: not on the server, not queued, and the
device shows it as synced.

## The decision, and one correction to it

Decided: **the local edit is abandoned and the row returns to the server's state.**

Correction worth recording, because the decision as first stated was not executable.
It was "overwrite the local row with `serverState`". `PatchResult` has a `serverState`
field — and the server never sends it. `sync_batch_apply` returns
`{patchId, ok, cached, lost, legacy, newVersion}` and nothing else; neither
`serverState` nor `newState` appears in its source, and `newVersion` is the literal `1`
(#207).

So there is no server-sent document to adopt. The document the server does hold is one
the client already has: `sync_shadow.confirmed_json`, documented as "serialised entity
state the server is known to hold". A lost patch changed nothing on the server, so that
row is still true — and reverting the local entity to it lands exactly on the server's
state without a round trip and without a server change.

That is not a smaller version of the decision. It is the same decision with the source
of truth found rather than assumed, and it avoids a wire-format change the decision does
not need.

## The alternative that was rejected

Keeping the local edit and re-sending it with a fresher clock was rejected because it
breaks what the hybrid clock is for. The clock is the device's ordering of events; a
freshly minted clock for an edit that already lost means the device deciding its own
edit outranks the one it lost to. Once that is allowed, ordering stops being ordering.
Accepting the loss is the option that keeps the clock meaning what the ADR says it
means, and the user is told rather than silently overwritten.

The cost is real and belongs in the open questions: a user who was offline and comes back
can lose an edit to a device that was online the whole time. That is what per-field LWW
is, and the alternative was worse — an invisible loop of edits that can never win.

## Scope

**In scope:** `PatchResult.lost`; the engine's handling of a lost result; the revert of
the local row to the confirmed shadow; reporting the outcome rather than reporting a
success.

**Out of scope, deliberately:**

- **A server change to send `serverState`.** Not needed, given the shadow is the same
  fact. Recorded here because the first form of this decision assumed it.
- **#207**, the `newVersion` literal. Separate: it is about a value nobody consumes
  today, not about a lost write.
- **Whether the user is warned in the UI.** The sync layer reports the outcome; the
  surface that shows it is not specified here, and inventing one here would freeze a
  choice the UI has not made.

## Open questions

- Should a lost edit be surfaced to the user at all, or only recorded? Silence here is
  the same defect in miniature, and the ADR's own standard is that a refusal is reported
  precisely so the shadow is not advanced past a fiction.
