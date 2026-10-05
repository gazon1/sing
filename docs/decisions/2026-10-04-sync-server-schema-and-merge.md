---
title: The sync server: per-field merge in one statement, identity from the session
date: 2026-10-04
status: accepted
---

# The sync server: per-field merge in one statement, identity from the session

## Context

Phase 3 of `supabase-auth-and-sync`. The server side had to exist before the client
transport could be written, and the schema was specified as "typed Postgres tables
plus one SQL function that applies a batch atomically"
(`2026-10-04-sync-server-side-sql-not-edge-functions.md`).

Three things in that specification turned out to be wrong when they met the actual
client, and each was found by running the SQL rather than by reading it.

**`profile_id` is text, not `uuid`.** The client's `ProfileId` is a value class over a
`String` whose default is literally `"default"` and whose other values are ULIDs. A
uuid column rejects every profile the app can produce, and the rejection surfaces as
"profile not found" on a user's real data — the worst place to find out.

**Timestamps are ISO-8601 strings on the wire, not epoch millis.** The design assumed
`kotlin.time.Instant` serialises as a number. It does not: `toJson()` produces
`"2025-10-09T08:53:20Z"`. A `(doc->>'updatedAt')::bigint` throws, and a throw inside
the batch rolls back the whole batch because one field of one patch was odd. Hence
`sync_to_millis`, which parses and returns NULL on failure rather than raising.

**Client field values live in one `doc jsonb` column, not one native column per
field.** This is the real deviation from the design, so it is stated plainly: the
serialisation authority is the client's `toJson()`, for six Kotlin data classes
containing enums, `Instant`s and value classes. A native Postgres type per field means
a cast per field and a mapping to keep in step with six Kotlin classes forever — drift
that fails at runtime, on the user's data, in a direction nobody can debug from the
schema. The row is still typed where typing matters: identity columns, per-field
clocks, the version counter and the timestamps are real columns with real
constraints, and the merge is still one statement per table.

The merge is generic across the six types even though SQL has no generic UPDATE. The
per-field selection is a correlated subquery on the row being updated, so the six
`UPDATE`s differ only in a table name, and that name comes from a `case` over a
literal set — never from the request.

## Decision

Six document tables keyed `(id)` with `owner_id uuid`, `profile_id text`, `doc jsonb`,
`field_versions jsonb`, `server_version bigint`, and `created_at`/`updated_at`/
`deleted_at` as bigint epoch millis parsed from the ISO string. Plus `sync_profiles`,
an append-only `sync_events` log, a `sync_applied_patches` idempotency ledger, and a
`sync_field_allowlist` table seeded with every client-serialised field.

Four functions. All security-definer with a pinned search path. All take the acting
owner from `auth.uid()`.

`field_versions` stores `{"<field>": {"p": …, "c": …, "n": …}}` — the parsed clock
triple, compared numerically field by field. Comparing the encoded string would order
by node name once physical and counter tie, and arbitrary-but-stable is exactly what
should not be deciding which of two devices wins a field.

## Rationale

**Identity from the session, structurally.** The helpers originally took
`p_owner uuid` as a parameter and were security-definer: a signed-in client could call
`sync_apply_ops` directly with someone else's owner id and write to their rows, because
the function never compared the argument with the session. This is the same defect
phase 2.8 removed from the client, reintroduced server-side. The Supabase security
advisor found it by listing which functions `authenticated` can execute — which is the
argument for running that check rather than reading the code.

The fix is structural rather than a revoked grant: the helpers derive the owner and
have no owner parameter to lie about. A wrong grant is then a performance problem, not
an authorisation bypass. Both fixes are applied; the second is the one that holds.

**Refusals are reported, not absorbed.** Three cases return `ok: false` and are *not*
recorded in the idempotency ledger:

- `field_not_writable` — every field in the patch is outside the allowlist. The
  allowlist did hold, but reporting success would tell the client to advance its shadow
  past a value the server never took, and the next diff would be computed against that
  fiction.
- `row_unavailable` — the id is taken by another identity, or a concurrent insert won.
  Same reasoning.
- A patch whose row exists and whose every field is older reports `ok: true, lost:
  true`. A lost race is a legitimate outcome under per-field LWW, and the client needs
  to hear it as "your write lost", not as "your write landed".

None of them touch the ledger, because the ledger answers "has this patch been
applied" and these were not. Recording them would make a refusal permanent for a client
that later sends a corrected patch under the same id.

**A patch that changes nothing changes nothing.** The first version's `UPDATE` had no
guard, so a stale patch — one whose clock is older for every field it names — still
incremented `server_version` and still appended an event every other client then had
to download and apply in order to discover nothing. A `count(*) … > 0` guard, the same
expression as the one in the `SET` list, fixed both.

**The legacy fallback takes the same path.** An old client sends a full snapshot with
no clock and no operations. It is expanded into operations here, so the allowlist, the
per-field merge and the create-if-absent branch all apply unchanged. The first version
guarded the create branch on `not v_legacy` on the assumption that a legacy patch would
always find a row to update; the first legacy patch for any entity does not, so it
updated nothing, fell through every branch, and reported success.

**Direct table access is revoked.** The clients call four functions and nothing else.
A grant with a policy the caller can satisfy by lying about a column is not a policy.

## Consequences

- Four advisor warnings remain and are **accepted**: `sync_batch_apply`,
  `sync_events_since`, `sync_health` and `sync_transfer_ownership` are security-definer
  and executable by `authenticated`. That is the design — they exist to write rows the
  policies hide — and switching them to SECURITY INVOKER to silence a linter would
  break sync. Recorded here so the next reader does not "fix" it.
- `sync_transfer_ownership` accepts only the caller's own identity and reports that
  there is nothing to transfer. REQ-UA-005 asks whether another user's data is
  claimable; the answer is that the function refuses any target that is not the caller.
  A transfer to a *different* account — the anonymous-to-account case — is not
  implemented, because moving data between accounts needs a source-side decision this
  function is not the place to make. It is a follow-up, not a bug.
- Leaked-password protection is disabled on the project. It is a GoTrue setting, not a
  migration, so it needs the dashboard; it is off before the sign-up screen ships and
  should be on before it does.
- The event log stores `p->'doc'`, which for a field-op patch is empty. The pull
  handler applies an event's `data` as a full entity, so a field-op patch produces an
  event that cannot be applied. Phase 5 changes the event to carry the merged row
  rather than the patch's payload; until then, a pull from a server fed by this code
  would apply nothing. That ordering is deliberate — the transport is not written yet —
  and it is a real gap, not an oversight.
- `sync_hlc_newer`, `sync_to_millis`, `sync_table_for` and `sync_writable_op_count` are
  executable by nobody. They exist to be called by the batch function.

## Links

- `docs/sync-server-integration-test.sql` — the nine-scenario run that found the four
  defects above
- `openspec/changes/supabase-auth-and-sync/specs/offline-sync/spec.md` (REQ-OS-003, REQ-OS-005, REQ-OS-010)
- `openspec/changes/supabase-auth-and-sync/specs/user-authentication/spec.md` (REQ-UA-005)
- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md`
- `docs/decisions/2026-10-04-sync-server-side-sql-not-edge-functions.md`
- `docs/decisions/2026-10-04-patch-as-field-diff-with-logical-clock.md`
