# supabase-auth-and-sync

## What

Replace the two stubbed Supabase seams with working implementations, and fix the
four blocking defects in the existing sync core that a live server would turn into
irreversible data loss.

The two stubs:

- the sync transport, which currently returns empty results and reports success
- the authentication repository, which validates input and returns success without
  contacting any identity provider

The four defects, all verified by reading the code:

1. A pulled event whose type has no registered handler advances the download cursor
   and is never applied. The data is gone.
2. Two sync cycles can run concurrently, because the "is a sync running" test reads a
   status that the engine already reset between its push and its pull phase.
3. The download cursor is a single global value, not scoped to an owner and profile.
   Switching profile reuses a cursor that belongs to a different data set.
4. Failed patches record an attempt counter that nothing ever reads, so a patch that
   fails is retried forever with no delay and no deadline.

Plus two protocol gaps that make push a no-op regardless of transport: the patch
payload carries no entity content, and pulled events carry no profile dimension, so
one profile's events land in another profile's data.

## Why

Sync was designed against a Supabase backend that was never connected. The design is
sound — outbox, cursor-based catch-up, idempotent patch identity, split push/pull — but
it has never run against a server, and the parts that only matter under real
concurrency were never exercised. Connecting a backend first would surface B1, B3 and
B4 as silent data loss on the user's own data. They are cheaper to fix now, with no
backend attached, than to diagnose from a corrupted database afterwards.

The same argument applies to the two dead surfaces: an entire OAuth subsystem and an
auto-sync entry point are fully implemented, fully tested in places, and called by
nothing. Their tests produce green gates that protect nothing.

## Scope

### In scope

- **Identity.** Split the composite `user_id` into an owner id and a profile id at the
  network boundary only. The local database keeps its composite string, so no data
  migration is needed for existing data.
- **Authentication.** Email and password on both platforms, backed by the connected
  Supabase project. Anonymous sign-in yields a real provider-assigned id instead of a
  locally generated one.
- **Credential storage.** Session tokens move out of plain-text preferences into the
  existing secure storage port. On Android that means a hardware-backed keystore.
  An existing plaintext token is migrated on first read and erased from the old store.
- **Server-side sync.** Typed Postgres tables for the six synchronised document types,
  one SQL function that applies a whole batch atomically, and an append-only event log
  with a monotonic sequence number. Row-level access policies keyed on the session's
  owner id. No edge functions, no realtime.
- **Conflict policy: last-write-wins per field.** The server stores a per-field logical
  version, and a patch is applied field by field. Two devices of one client that edited
  different fields keep both edits, regardless of arrival order. This is the question
  the whole feature exists to answer, and row-level last-write-wins answers it with
  data loss.
- **Idempotency and crash safety.** A repeated patch identity returns the cached result
  instead of applying twice; a failed batch rolls back entirely and the client keeps
  its outbox.
- **Sync core refactor.** Explicit single-owner capture of the sync cycle, a dropped
  event counter with a stated cursor policy, exponential backoff with a dead-letter
  store, and per-owner-per-profile sync state moved from preferences into the database.
- **Data seeding.** Existing local data is uploaded once, on first sign-in, through the
  same push path as any other change, so seeding has no separate failure mode.
- **Cleanup.** Remove the unwired OAuth subsystem and the unwired auto-sync entry point,
  and fix the dead-symbol detector that failed to see the latter.

### Out of scope

- A real CRDT. Simultaneous edits to *the same* field still resolve to one value, by
  design; that loss is inherent, not a gap in this work.
- Realtime or push streaming. The current cursor poll is sufficient and has no
  websocket lifecycle to leak.
- Google or Apple sign-in.
- Projections for saved searches and agenda views. They are not synchronised today and
  this change does not extend the set of synchronised document types.
- Cleaning up the other known unwired surfaces outside sync, tracked separately.
- Multi-tenancy, billing, webhooks, an iOS target.

## New capabilities

- `offline-sync` — new. The project has no sync spec today.
- `user-authentication` — new. The project has no auth spec today.

## Impact

- Database schema version advances; two additive migrations plus one that moves sync
  state out of preferences.
- `SyncApiClient` loses its user-id parameter: row-level access policies already
  determine visibility, and a client-supplied identity must never be trusted.
- Two module-level source files are deleted, and the dead-symbol baseline shrinks.
- New server-side SQL is the only place that knows the vendor is Supabase on the sync
  path. The data schema itself is portable Postgres.

## References

- `docs/decisions/DIGEST.md`
- `docs/agents/domain.md`
- skills: `singularity-todo-sync`, `singularity-todo-room-migration`,
  `singularity-todo-secure-storage`, `singularity-todo-koin-dsl`,
  `singularity-todo-unwired-surface-audit`
