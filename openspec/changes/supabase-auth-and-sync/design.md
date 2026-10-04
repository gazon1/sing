# Design — supabase-auth-and-sync

## Architecture decision inputs

Two ADRs own the decisions this design implements; their rationale is not repeated here.

- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md` — why the merge is
  per-field, why the clock is logical, why `baseVersion` stops being a write gate.
- `docs/decisions/2026-10-04-sync-server-side-sql-not-edge-functions.md` — why the
  server side is SQL over typed tables, why the allowlist is a table, why the escape
  hatch is one function body.

## Data flow

```
Feature VM → Repository → Room (source of truth, offline)
                 └─► enqueue → outbox row
                          │
                    SyncCoordinator  (single owner of the cycle)
                          │
                    SyncEngine ──► SyncApiClient   ← the only sync-side vendor seam
                          │              │
                          │              ├─ rpc(sync_batch_apply)
                          │              └─ rpc(sync_events_since)
                          │
                     pull handlers → Repository writes → Room
```

Auth is a separate seam with the same shape: one interface, one vendor implementation,
nothing above it that knows the provider.

## Server schema

Six synchronised document types, one table each, plus a profile table and an
append-only event log. Every synchronised row carries:

- `owner_id uuid` — the authenticated account
- `profile_id text` — the local profile the row belongs to. **Text, not uuid**: the
  client's `ProfileId` is a value class over a `String` whose default is literally
  `"default"` and whose other values are ULIDs, so a uuid column would reject every
  profile the app can produce.
- `doc jsonb` — the entity's client-serialised field values. **One column, not one
  native column per field**: the serialisation authority is the client's `toJson()`,
  for six Kotlin data classes containing enums, `Instant`s and value classes, and a
  native type per field is a mapping that has to be kept in step with six Kotlin
  classes forever. The *row* is typed — identity columns, per-field clocks, version
  counter, timestamps — and the merge is still one statement per table.
- `field_versions jsonb` — per-field logical clock readings, stored as the parsed
  triple `{"p": …, "c": …, "n": …}` so the comparison is numeric rather than
  lexicographic
- `server_version bigint` — a change counter, kept for diagnostics
- `created_at` / `updated_at` / `deleted_at` as `bigint` epoch millis, parsed from the
  client's ISO-8601 string by `sync_to_millis`. The parse returns NULL rather than
  raising: a bad timestamp costs one column, not the whole batch.

`id` is `text`, not `uuid`. Client ids are ULIDs, and the client is the id authority;
making the server mint ids would be a second source of truth for no gain.

Indexes: `(owner_id, profile_id, updated_at)`, `(owner_id, profile_id, deleted_at)`,
and a GIN index on `field_versions`. The event log is indexed on `(owner_id, lsn)`.

The event log is written **only** by the batch function. A trigger would be more
automatic and wrong: seeding and any future bulk import would flood the log with events
no client asked for. Direct client access to the log is revoked; reads go through a
function.

## Batch application

One call, one transaction, all-or-nothing.

For each patch: skip if its identity was already applied (return the cached result),
then `UPDATE … WHERE id = ? AND owner_id = auth.uid() AND profile_id = ?`, with each
column assigned by a per-field comparison against the stored readings. Zero rows
affected means the row is gone — the outcome is non-retryable, and the client drops the
patch rather than retrying it forever.

The comparison and the write are the same statement. That is the whole safety argument
for putting the merge in the database, and it is developed in the edge-function ADR.

Rowcount zero for a *whole* batch is a protocol error, not a data condition; the
function raises, the transaction rolls back, and the client keeps its outbox.

## Identity mapping

The local `user_id` column holds `"{profileId}/{userId}"` for non-default profiles and a
bare id otherwise. That composite string is a local storage detail and stays exactly as
it is — which means **no data migration of existing rows**.

The split into `(owner_id, profile_id)` happens in one place, at the network boundary.
The owner id comes from the signed-in session. The profile id comes from resolving the
local profile. An unparseable composite string is an error, never a silent default:
defaulting would write a user's data under the wrong profile, which is the one failure
this whole split exists to prevent.

## Sync state in the database, not preferences

The download cursor must be per `(owner, profile)`. A flat preferences file gives that
only by string-concatenating keys, and the cursor write is not transactional with the
entity writes it describes. The database already provides composite primary keys and
transactions, so sync state becomes a row: `last_lsn`, `last_successful_sync_at`,
`device_id`, `auto_sync_enabled`, `scheduled_interval`, `enabled_triggers`, keyed by
`(owner_id, profile_id)`.

Existing preference values are migrated on first read. Losing the cursor is harmless —
it re-downloads; losing the device id is not, so it is preserved explicitly.

## Single owner for the sync cycle

The current coalescing test reads a public status field that the engine resets between
its push and pull phases, so a second caller can start a parallel cycle in that window.
Two parallel pulls then both write the cursor.

Replaced by a conflated channel with exactly one consumer. Ten concurrent requests
collapse into one cycle plus at most one follow-up — guaranteed structurally, because a
single consumer cannot be in the loop twice. No lock, no flag, no dependence on
observable status. A lock would give mutual exclusion but not coalescing; building
coalescing on top of it is how the current defect exists. One mechanism instead of two.

## Batching, backoff, dead letters

Pushes are capped per call; pulling continues from the last applied position.

A failed patch gets a delay of `min(2^attempts, cap)` and a deadline. Past the maximum
attempt count it moves to a dead-letter table, visible in the UI with discard and retry
actions. The count that already exists in the outbox is what drives this — it is
currently written and never read, so a failing patch is retried forever, immediately,
with no cap. That is the worst possible retry policy against a server that is already
struggling.

## One driver per platform

Today three mechanisms drive sync on Android: a work-scheduled push, an
alarm-scheduled periodic sync, and a delay loop. Two of them react to the same session
change with different periods.

Target: one driver — the work scheduler on Android (it has backoff, persistence and
battery/network constraints), a delay loop on the JVM. The alarm-based scheduler is
removed. Session-change reaction lives in one place.

The JVM delay loop also gets a restart: an exception escaping the cycle currently kills
the loop permanently, so auto-sync dies silently until the process restarts.

## Client diff instead of snapshot

`buildPatch` compares the current state against the last successfully uploaded state
and emits one field operation per change, plus a logical clock reading. This is what
makes the patch carry data at all — today the patch contains an id, a version, a
checksum and a timestamp, and the snapshot it claims to carry has nowhere to ride.

The last-uploaded state is a local table keyed by entity. It is a diff input only; it
is not part of the conflict protocol and is not sent anywhere.

The row checksum is removed, along with the code that computed it. It was a fast-reject
for a row-level decision that is no longer made.

## Security notes

- Session tokens move to the platform secure store. A plaintext token found there is
  migrated on first read and deleted from the old store.
- The server derives the acting owner from the session at every level, including the
  internal helpers. They originally took an owner id as a parameter, which made them a
  write path into any account for any signed-in client. Both the grant and the
  signature were changed; the signature is the one that holds.
- A patch the server refuses — a field outside the allowlist, a row owned by someone
  else — is reported as a refusal and is *not* recorded in the idempotency ledger.
  Reporting it as a success would let the client advance its shadow past a value the
  server never took, and the next diff would be computed against that fiction.
- Four security-definer functions remain executable by `authenticated`, and the
  platform's security advisor reports each one. That is the design: they exist to write
  rows the policies hide. Switching them to invoker to silence the linter would break
  sync, so the warnings are accepted and recorded in the ADR.
- Leaked-password protection is disabled on the project. It is an auth setting rather
  than a migration, so it needs the dashboard; it must be on before sign-up ships.
- The Supabase URL and anonymous key are not hardcoded. They come from the secure store,
  falling back to build-time properties for development builds. The service-role key
  never reaches the client; every server entry point runs as the authenticated role
  under row-level policies.
- Nothing logs tokens, passwords or full email addresses.

## Rollback risk

**High** — this is the main risk of the change, and it is stated rather than managed.

- **Schema.** Server-side, the migration is additive: new tables, new columns. Rolling
  back means dropping tables, which discards server-side sync state. No local data is
  affected, because the local database is the source of truth.
- **Local database.** Version 32 → 33 is additive (new tables) plus a move of sync state
  out of preferences. Room migrations are tested. Rolling back the app to an older build
  leaves the added tables unused; the sync-state move is the only irreversible part and
  is limited to a cursor and a device id.
- **Protocol.** Old clients send patches with no logical clock and no field operations.
  The server must accept them and fall back to row-level behaviour, or an old build
  installed on a second device corrupts a row. This fallback is a requirement, not a
  nicety.
- **Dead lines.** There is no migration rollback for data already deleted. The dead
  letter store exists so that a patch is never *discarded* — it is set aside and
  visible.

## Testing strategy

- Unit: the transport's response parsing, the authentication repository's state
  transitions, the secure store round trip and its migration, the identity mapper's
  parsing including its failure mode, and the diff that produces field operations.
- Architecture (static analysis): vendor SDK imports confined to the two seams; no
  token port writes to plain-text preferences.
- Sync core: ten concurrent requests produce one cycle plus at most one follow-up; a
  failed patch backs off and lands in the dead letter store; an unappliable event does
  not advance the cursor; an event for another profile is not applied.
- Integration against a real database, tagged slow and gated on configuration: batch
  application, repeated delivery, cross-user isolation, and the merge scenario — two
  devices, two different fields, both edits survive.
