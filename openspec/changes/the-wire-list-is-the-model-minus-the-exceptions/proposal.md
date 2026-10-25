# The wire's field list is the model's own list

## Why

The client keeps a list of the field names the server accepts in a patch, and it keeps
it by hand. `SyncContract.FIELD_ALLOWLIST` is six sets of names, one per
document type, written out in Kotlin next to the contract they belong to.

A list written by hand next to the code it describes is a copy, and a copy has
to be kept in step by somebody's memory. Three lists exist where one should
be, and the three do not agree:

- the client's hand-written list — 106 names, including the client's own
  bookkeeping (`serverVersion`, `hlc`, `userId`);
- the server's live table — 86 rows over seven entity types, seeded by the
  migration: the work's fields, without the bookkeeping and without the key;
- a generated seed file — 106 rows, written by a script that reads the Kotlin
  source.

The seed is the client's contract, and it sits in `supabase/migrations/` — where
the deploy tooling looks for migrations. It names the client's bookkeeping and
the key, which the server's table does not hold, so applying it would put rows
into the table that nobody needs to write. The file also spells two entity types
the way the client spells them (`taggroup`, `timeentry`), while the server
reads `tag_group` and `time_entry` — the `DocType` keys. The gate that checks
the file compares it with the list the file was generated from, so the first
defect is invisible.

And the check the client makes is one-sided: every field the wire carries is in
the list. The other half — the list has no name the model does not have — is
not checked, because the list is written by hand and the model is code. A name
in the list that the model dropped is a name nobody ever sends, and it looks
like a rule.

## What changes

- The client's list is derived from the model it speaks about: the element
  names of the model's serial descriptor. The hand-written part becomes the
  exception — the fields the server owns — and not the whole list.
- Two lists come out of the derivation, and each means one thing: the names a
  device puts on the wire (the model's own names — every field of every model
  travels, and a field that does not travel is a field the model does not
  have), and the names the server accepts in a patch (the wire's names minus the
  fields the server owns: the version, the clock, the owner and the key).
- The seed file is generated from the derived server list, with the entity
  types spelled the way the server spells them. It moves out of
  `supabase/migrations/` — that directory is where the deploy tooling looks
  for migrations, and this one describes the table rather than changing it.
- The checks are two-sided now: the wire and the wire's list are *equal* (which
  pins the encoder), every subtracted name is a name the model has, and the
  server's table and the derived list are compared — every row every migration
  seeded into that table, not only the first statement of the first migration —
  so a field the server accepts in a patch that is outside the derived server list is
  caught, and so is a type the client syncs and the server has no row for.

## Non-goals

- The server's deployment. The live table was seeded before the series and the
  closed-project fields existed, and a migration — not this change — is what
  adds them to it (#1050).
- The receiving side of the same rule: a document that arrives without a field
  still resets that field of the row (#1026), and the shadow does not remember
  what the device knew (#1032).
- What a device sends does not change. The wire's list is the model's names,
  and that is what the hand-written list already said.
