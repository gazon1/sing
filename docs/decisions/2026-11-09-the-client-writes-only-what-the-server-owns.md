---
title: "The client writes only what the server owns, and reads the server's row back as a document"
date: 2026-11-09
status: accepted
tags: ["sync", "protocol"]
---

# The client writes only what the server owns, and reads the server's row back as a document

## Context

The server merges a patch field by field. It applies the field allowlist to the
patch **as a whole**, before it writes a single field: a patch that names one
field the server does not own is refused whole, with
`field_not_writable`, and nothing lands. The fields the server owns are the
row's key (`id`), its owner (`userId`), its clock (`hlc`) and its version
(`serverVersion`).

The client did not know that. A patch is built by diffing the model's whole
JSON against the shadow's confirmed state, and the model is put on the wire
whole — so the patch of a fresh task named all four of those fields along with
the fields the server does accept. Every such patch was refused, the change
never reached the server, and no other device ever heard of it.

The other half of the same contract: the server stores a **row**, not a
document. The row's key and its owner are columns of the row, not fields of the
document it keeps, so a row that comes back carries neither. Every model
requires both — `id` and `userId` have no defaults — so a pulled row could not
be decoded into a model at all, and the pull skipped the event as a document
that does not decode.

Both halves were invisible for as long as the fixture was lenient: it stored
the document it was given, and it accepted a patch that named anything.

## Idea

The split of ownership between the client and the server is a fact about the
protocol, and the client is the side that knows it: the server says which
fields it owns, and the client decides what to put in a patch and how to read a
row back.

## Decision

**A patch names no field the server owns.** `SyncPatchBuilder` drops the four
server-owned fields from the diff it builds. The rest of the patch is
unchanged: it is still a list of field operations, diffed against the confirmed
state.

**A pulled row is completed into a document before it is decoded.**
`SyncDocumentWriter.documentFromRow` fills in the row's key from the event
that names the row, and the row's owner from the session. A document that
carries either of them keeps what it says: the owner of a document is never
overwritten from the session when the document states one.

## Rationale

**The strictness is the point.** The allowlist is applied to the patch as a
whole on purpose: a patch the server cannot take in full writes nothing, so a
device cannot half-apply a change and leave the server holding a row nobody
knows the shape of. Relaxing the fixture to accept a patch with a foreign field
in it would have removed the guarantee and hidden the defect — the client's
patch was wrong, and a lenient server would have hidden it until the day the
real server refused it.

**The client is where the knowledge lives.** The server cannot tell a device
which fields it must not write: the device knows its own model's fields, and
the contract's own list says which four of them the server owns. A client that
writes one of them is a client that has to be told no.

**The id comes from the event, the owner comes from the session.** The event
names the row it is about, and the server serves only the rows of the account
that asks for them, so the two are the row's identity. Neither is a claim the
row's document makes, which is exactly why the document does not carry them.

**The owner's rule stays one-sided.** A document that states an owner keeps it.
The session fills in an owner only where the document states none — the
question "whose document is this" has one answer, and the document's answer is
the one that wins.

## Consequences

- A patch that names a server-owned field no longer reaches the server, so the
  server's refusal stays the last line of defence rather than the first.
- A pulled row decodes: the models' two required fields are supplied by the
  event and the session.
- The server's own behaviour is unchanged — the fixture still refuses a patch
  that names a field it does not own, and the SQL still applies the allowlist
  to the patch as a whole.
- A document that arrives with an explicit `null` for a field the model does
  not allow is still a document that does not decode, and the pull still skips
  it. That is a separate question from the one this decision answers.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncPatchBuilder.kt` — the patch
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt` — the row read back as a document
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt` — the fields the server owns
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/SharedSyncServer.kt` — the server's own merge
- `infra/supabase/migrations/2026-10-07-sync_schema.sql` — the server's SQL
