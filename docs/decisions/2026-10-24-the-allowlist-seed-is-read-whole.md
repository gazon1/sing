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

## Decision

The generator reads a list by counting parentheses, on a source in which the
comments and the string literals are blanked out. A parenthesis in a comment
is not a parenthesis of the code, and the count must not see it.

The two facts above are recorded, not fixed. The vocabulary is a question
about the protocol — which names the server's table uses — and it belongs to a
change of the protocol, with the server's own migration as the fact it is
decided against. Nothing applies the generated seed automatically: the file
has no timestamp, the migration runner does not see it, and the gate that
compares it with the generator is its only reader.

## Consequences

- The seed names the three client-side fields of the task again, and the gate
  that compares it with the contract now compares it with a reader that reads
  the whole list. The test `scripts/tests/test_gen_sync_allowlist_sql.py`
  holds the reader to that: a contract with a parenthesis in a comment still
  yields every field after the comment.
- A reader that stops early is invisible to a gate that compares the file
  with the reader. The test is the part that is not the file.
- The vocabulary mismatch stays open, and the `profile` entity type of the
  server has no counterpart in the client's contract. Both are facts about
  the protocol, not about the generator.

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
