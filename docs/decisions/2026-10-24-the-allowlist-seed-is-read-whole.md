---
title: "The allow-list seed is read whole, and it is not the server's table"
date: 2026-10-24
status: accepted
tags: [sync, protocol, tooling, database]
---

# The allow-list seed is read whole, and it is not the server's table

## Context

`infra/gen_sync_allowlist_sql.py` turns the field allow-lists of
`shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt`
into the rows of `supabase/migrations/sync_field_allowlist_seed.sql`. The
gate `scripts/check-sync-allowlist-regenerated.py` compares the two, so the
seed cannot drift away from the contract.

The reader was a regular expression: the body of a `setOf(...)` was taken up
to the first `)` in the file. In `SyncContract.kt` that `)` sits inside a
comment — the one that explains that the server stores `serverVersion` and
`hlc` without enforcing them:

```kotlin
    // Sync metadata — client-side fields the server stores but does not
    // enforce in sync_field_allowlist (serverVersion=0, hlc=null for local)
```

So the list of the task ended there, and the seed lost the three fields that
came after it: `serverVersion`, `hlc` and `userId`. Nothing said so. The
seed and the generator were cut in the same place, and the gate that compares
them saw agreement — a gate that certifies a file against a broken reader
certifies the breakage.

Two facts about the file surfaced while fixing the reader, and neither is
settled here.

**The seed is not the server's table.** The server's own seed is
`supabase/migrations/2026-10-07-sync_schema.sql`, and it holds 86 rows over
seven entity types. It does not list `userId`, `hlc` or `serverVersion` for
any type: the client-side sync metadata is stored by the server without being
enforced, which is what the contract says and what the table reflects. The
generated seed is the *client's* contract — the union of what the server
accepts and what the client sends — so it names those three fields, and it is
a statement about the client rather than about the database.

**The two files do not share a vocabulary for the entity types.** The server
writes `tag_group` and `time_entry`; the generator writes `taggroup` and
`timeentry`, because it lower-cases the name of the `DocType`. The server
also has a `profile` type, which the client's contract does not describe.

The vocabulary is not a question about the protocol: the client's own
`DocType` enum already carries the server's words — `TagGroup("tag_group")`,
`TimeEntry("time_entry")` — and the generator lower-cases the enum's *name*
instead of using the enum's `key`. That is a choice of the generator, and the
fix is small and local: the names of the types live in a map in the generator,
and the server's words are one line away. It is not fixed here because a
bug-fix commit that regenerates the seed should not also change what the seed
says about types it did not have to touch.

**The seed says more than the server accepts.** `id` (every type), `seriesId`
(task) and `closedAt` (project) are in the client's contract and in the
generated seed, and the server's table does not list them — while
`SyncContract.kt` says the server "must accept" `seriesId`, or a synced task
is rejected as a document with an unknown field. That is pre-existing: the old
seed says the same, and nothing applies the seed. It is named here because the
fact that the seed is the client's contract, and not the server's table, is
what makes it visible.

## Idea

A list of fields is read by counting the parentheses that close it, on a
source in which the comments and the strings are blanked out. The count is
taken on the blanked source and the fields are taken from the original one.

## Decision

The generator reads a list by counting parentheses, on a source in which the
comments and the string literals are blanked out. A parenthesis in a comment
is not a parenthesis of the code, and the count must not see it.

The facts above are recorded, not fixed. The vocabulary is a choice of the
generator — a small one, and a local one — and it is left open because a
commit that regenerates the seed should not also change what the seed says
about the types it did not have to touch. The `profile` type is a fact
about the protocol: the server has it and the client's contract does not
describe it, and only a change of the protocol can say what a profile is.

Nothing applies the generated seed automatically: the file has no timestamp,
the migration runner does not see it, and the gate that compares it with the
generator is its only reader, apart from the gate's own test.

## Rationale

The reader has to know where a list ends, and the file it reads is Kotlin:
the end of a list is a parenthesis, and a parenthesis can sit inside a
comment or inside a string. A reader that counts over the raw file counts
those too, and stops in the wrong place — at a place that looks like the end
of the list to everything that reads the file the same way.

The blanking is what separates the two. Every character that is not code
becomes a space, so the source keeps its length and the count is taken over
the code alone. The fields themselves are read from the original source,
because they are string literals, and the blanking has taken them away.

## Consequences

- The seed names the three client-side fields of the task again, and the gate
  that compares it with the contract now compares it with a reader that reads
  the whole list. The test `scripts/tests/test_gen_sync_allowlist_sql.py`
  holds the reader to that: a contract with an unbalanced parenthesis in a
  comment still yields every field after the comment.
- A reader that stops early is invisible to a gate that compares the file
  with the reader. The test is the part that is not the file.
- The vocabulary mismatch stays open — it is a small and local change of the
  generator, waiting for a commit that changes the generator on purpose. The
  `profile` entity type of the server stays open too, and that one is a fact
  about the protocol: the client's contract does not describe a profile, and
  nothing but a change of the protocol can say what a profile is.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt`
  — the contract the seed is generated from
- `supabase/migrations/2026-10-07-sync_schema.sql` — the server's own seed,
  and the fact the vocabulary question is decided against
- `scripts/check-sync-allowlist-regenerated.py` — the gate that compares the
  seed with the generator
- `scripts/tests/test_gen_sync_allowlist_sql.py` — the test that holds the
  reader to the whole list
- `docs/decisions/2026-10-07-three-write-rules-and-the-one-that-needs-a-server-fact.md`
  — the rule that reads the server's seed where it already lives
