---
title: Sync server side is SQL functions over typed tables, not edge functions
date: 2026-10-04
status: accepted
---

# Sync server side is SQL functions over typed tables, not edge functions

## Context

The sync client talks to a four-method transport interface. It has to be implemented
somewhere. Three options existed:

1. Postgres tables plus SQL functions, called over the RPC endpoint.
2. Edge functions (Deno/TypeScript) with a database connection.
3. A separate backend service.

The existing project context matters here: the protocol is a document patch with a base
version and a row checksum, i.e. optimistic locking over a row, plus an append-only
event log with a monotonic sequence number. The client already owns the outbox, the
cursor, the retry classification and the patch identity.

Option 2 or 3 means re-implementing, server-side, the things the client already does
correctly — idempotency on patch identity, atomic batch application, the event log.
That is a second sync engine, in a second language, with its own deployment and its own
retry semantics. It is also where a bug stops being a rollback.

Option 1 makes the server the thinnest possible thing: a transaction that takes a lock,
compares, writes, appends to a log, and returns. The schema is readable next to the
data. There is no cold start, no runtime to keep patched, no second connection pool.

Against it: the merge logic has to be written in PL/pgSQL, which is verbose for
branching over six entity types, has no unit tests in the usual sense, fails at runtime
rather than at compile time, and is debugged with `raise notice`.

## Decision

**Typed Postgres tables plus one SQL function that applies a batch atomically. No edge
functions for the sync path.**

The batch function is security-definer with a pinned search path, so it can write rows
that row-level policies otherwise hide, while still deriving the acting user from the
session rather than from a parameter. The client never sends an owner id: visibility is
the database's answer to "who is signed in", not the client's.

The field allowlist is a **table**, not code. Adding a synchronised field is one insert.
A patch naming a field outside the allowlist is rejected server-side, which turns "can
a client write this column?" from a code-review question into a runtime check.

**Escape hatch, kept cheap on purpose:** the same merge logic can be lifted verbatim
into an edge function that calls a single RPC. The client does not change, because the
seam is four methods wide and none of them mention Postgres. If PL/pgSQL proves
unworkable, that is a change to one function body, not a rewrite of the client.

## Rationale

The deciding factor is where a defect lands. In SQL, a merge bug is a wrong value in a
column, and the correct row is one `UPDATE` away. In an edge function, the same bug is a
second implementation of a protocol whose client half is already deployed, and the two
halves can disagree about what `ok = true` means.

The PL/pgSQL cost is real and the plan does not pretend otherwise. It is paid for by
one property: the merge stays inside the same transaction as the data, so the compare
and the write are a single atomic expression. A read-then-write arbiter outside the
database has a window between the two statements that no amount of care closes
completely — it needs row locks, a retryable serialization policy, and conflict
handling for conflicts of its own making. Inside the update there is no window to
close.

## Consequences

- No unit-test story for the merge logic in the usual sense. It is covered by
  integration tests against a real database, tagged as slow. That is a real reduction
  in feedback speed and it is the main argument for the escape hatch.
- A syntax error in the function body surfaces at call time, not deploy time. Mitigation
  is a health-check entry point called under a signed-in session.
- Exiting the vendor stays cheap: the schema is portable Postgres, the row-level
  policies are the only place the provider's identity function appears, and the client
  knows the vendor in exactly two implementations.
- The event log duplicates the row. It is deliberate denormalisation — it is what makes
  catch-up a single indexed read in the same transaction as the write.

## Links

- `openspec/changes/supabase-auth-and-sync/design.md`
- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncApi.kt`
