---
date: 2026-10-04
status: accepted
deciders: [sync client]
---

# The event log carries the post-merge document, and the clock goes over the wire as an object

## Context

Phase 3 built the server and phase 6 built the client, from opposite ends and
against two documents written before either existed. Three things did not line
up, and none of them would have failed loudly.

**The event payload was the request, not the result.** `sync_batch_apply` wrote
`sync_events.data` as `coalesce(p -> 'doc', '{}')`. A legacy client sends `doc`,
so for that client the event was correct. A field-diff client — which is what
`SyncPatchBuilder` produces, and what every current build is — sends `ops` and
`hlc` and never sends `doc` at all, so its events recorded `{}`.

The pull side deserialises `data` as a whole entity: `SyncBootstrapper` calls
`StableJson.decodeFromString(serializer<Task>(), data.toString())`. So a second
device on a second client would have received `{}` and materialised a task with a
blank title, blank description and no dates. Nothing in the push path would have
noticed: the push succeeded, the row was written correctly, the shadow advanced.

**The clock had two encodings.** The client's `Hlc` is a value class over the
string `"physical:counter:node"`. The server compares clocks field by field —
`sync_hlc_newer` reads `-> 'p'`, `-> 'c'`, `-> 'n'` from a `jsonb`. Handing the
server the string would have made every comparison read `null` on both sides, so
`p_incoming > p_stored` false, `p_incoming = p_stored` false, and the function
false. No patch would ever have been newer than what was stored, and nothing would
have merged — silently, with `ok: true`.

**The profile was missing from the request.** The server requires `profileId` on
every patch and refuses a batch without it.

## Idea

Fix each at the place where the two sides disagree, and pin all three with tests
that name the failure rather than the fix.

## Decision

1. `sync_events.data` holds the row as it stands **after** the merge, read back
   through a new `sync_read_row` helper in the same statement as the merge. The
   event can therefore never describe a state the server did not store.

2. `Hlc` is converted at the wire boundary by `Hlc.toWire()`: `"p:c:n"` in the
   domain model, `{p, c, n}` on the wire. The model's own encoding stays a string
   because it is a value class used as a map key and compared in code, where a
   struct would cost an allocation for nothing.

3. `BatchPushRequest` carries `profileId`. It is a routing dimension *within* the
   authenticated account — the owner still comes from the session and no value on
   the wire can widen that — which is why it does not trip
   `SyncTransportIdentityTest`, a rule about owner identity only.

4. A patch with no clock is refused in the transport, before it is sent. Omitting
   the key is not neutral: the server reads an absent `hlc` as a legacy snapshot
   client, expands the absent `doc` into zero operations, and refuses the patch as
   `field_not_writable`. That refusal is correct, and the local cause — a builder
   that forgot a clock — is invisible from the server's side of it.

## Rationale

The general shape here is worth more than the three fixes. Each mismatch lived in
a seam between two components written from documents rather than from each other,
and every one of them was silent: the push reported success, the server reported
success, and the loss appeared later, somewhere else, attributed to something
else.

`docs/sync-server-integration-test.sql` gained a scenario (10) that sends the
patch a *current* client sends — operations and a clock, no `doc` — and a
scenario (11) that asserts the entry points' grants. Scenario 11 exists because of
a mistake made while writing these migrations: a `revoke … from public, anon,
authenticated` intended for the helpers was also applied to `sync_batch_apply`.
Nothing about the schema changed, the deploy succeeded, and the first push failed
with "permission denied for function". `create or replace` preserves an existing
ACL, so the grant survives every ordinary migration and does not survive that one
particular clause — which is the worst possible combination.

## Consequences

- The client and the server now agree, and the agreement is asserted from both
  sides rather than documented.
- `SupabaseSyncApiClient` reads `serverLsn` through `longOrNull`, which parses the
  JSON literal as text. The obvious `double.toLong()` is exact for every value a
  test is likely to use and wrong past 2^53, where two log positions collapse into
  one. `SyncApiClientTest` pins this with 2^53 + 1.
- An event of an unknown document type is refused rather than skipped. Skipping
  would move the cursor past data that was never applied — the defect phase 2.2
  removed from the pull loop. A genuine future schema is carried by
  `protocolVersion`, which is a declared and versioned break.

## Links

- `docs/sync-server-integration-test.sql` — scenarios 10 and 11
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncWire.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncApiClientTest.kt`
