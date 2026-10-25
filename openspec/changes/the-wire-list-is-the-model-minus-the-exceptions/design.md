# The wire's field list is the model's own list — design

## The three lists, and what each is

This is the state BEFORE the change — what the design answers.

| List | Where | What it is |
|---|---|---|
| The client's list | `SyncContract.FIELD_ALLOWLIST` | hand-written, 106 names: what a device may put on the wire, plus the client's own bookkeeping |
| The server's live table | `sync_field_allowlist`, seeded by `2026-10-07-sync_schema.sql` | 86 rows over seven entity types: the fields the server accepts in a patch |
| The generated seed | `supabase/migrations/sync_field_allowlist_seed.sql` | 106 rows, written by `infra/gen_sync_allowlist_sql.py` from the client's list — the client's contract, in the directory the deploy tooling reads |

The first is a copy of the second, plus the client's own three names, and the
third is a copy of the first. Only the second is a deployment; the other two
are statements, and they disagree.

After the change there are two lists, both derived: `SyncContract.WIRE_FIELDS`
— the model's own names, which is what the encoder writes — and
`SyncContract.SERVER_FIELDS`, which is that minus the four names the server
owns. The hand-written part is those four names. The generated seed becomes a
DESCRIPTION of the table the server reads: 82 rows, written from the derived
server list, and it moves out of `supabase/migrations/` — the migrations directory is
where the deploy tooling looks for migrations, and a file that says what the
table holds is not one. The live table is unchanged: this change does not
deploy anything.

## The derivation

The compiler knows what a model's descriptor says, and only the compiler. The
derivation therefore lives in Kotlin and reads the serial descriptor of each
model:

```kotlin
private fun wireNames(descriptor: SerialDescriptor): List<String> =
    (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
```

`getElementName` returns the *serial* name, so `@SerialName("dependsOn")` on
the task's `blockedBy` and `@SerialName(TAGS_WIRE_NAME)` on its `tags` come
out as the wire names — the names the server's table holds.

### The wire's list

```
wire(docType) = wireNames(model(docType))
```

There is nothing subtracted from it, and that is a fact rather than an
omission: every field of every model has a name on the wire, and the device
puts every one of them there — the three that live outside the row travel as
parts of the document — so a field that does not travel is a field the model
does not have. Subtracting a name from this list would have no state in which
it is correct: the wire would still carry the field, and the equality below
would fail.


### The server's list

```
serverWritable(docType) = wire(docType) − SERVER_OWNED
```

`SERVER_OWNED` is the same set for every type: `serverVersion` (the server's
own counter), `hlc` (the clock the patch carries separately), `userId` (the
owner comes from the session, not from the document) and `id` (the key of the
row, not a field of the document).

This list is the seed's content, and the server's live table already holds it,
apart from the two fields a migration is owed. The derivation reproduces the
live table name for name for four of the six client types:

| Type | Derived | Live table | Difference |
|---|---|---|---|
| `note` | 18 | 18 | — |
| `tag` | 7 | 7 | — |
| `tag_group` | 5 | 5 | — |
| `time_entry` | 9 | 9 | — |
| `task` | 26 | 25 | `seriesId` — a migration is owed |
| `project` | 17 | 16 | `closedAt` — a migration is owed |

The live table also holds `profile` (6 rows), a type the client does not sync:
the profile is the server's own.

The two differences are the server's deployment lagging the models. They are
not a mistake in the derivation and not a mistake in the migration — they are
the sequence in which features reach the server. A field the models have and
the server has not been migrated for is a patch the server counts as carrying
no writable operation, which is why the lag is filed (#1050) rather than
ignored.

## What the checks say

The old check was one-sided: every field the wire carries is in the list. That
is true by construction once the list is derived, so it says nothing. The new
checks say:

1. **The wire and the wire's list are equal.** The wire is the model,
   serialised, and the list is derived from the same model, so the equality
   holds by construction. What it pins is the *encoder*: a field the encoder
   stops writing (`encodeDefaults` turned off, a custom serializer leaving one
   out) is a field the device sends no more, and this is where that is said.
2. **Every subtracted name is a name the model has.** `SERVER_OWNED` names the
   four fields the server owns, and every model has them. A subtraction of a
   name that does not exist would leave the model's own bookkeeping writable.
3. **The server's table and the derived server list agree**, apart from the
   fields the server has not been migrated for. The gate reads the rows as
   NAMES: a row of the table is a triple of a type, a field and a flag, and
   the gate looks for those triples wherever a migration holds them — every
   migration, not only the first. It compares them with the derived list; a
   type the client syncs and the server has no row for is caught too.

   The reader works statement by statement: it takes the column list the
   statement names and maps the values onto it, so a row is read the same way
   however it was written — the columns in any order, the flag as `true`, as
   `'t'`, as `'yes'`, as `'tr'`, as `TRUE::boolean`, the name as `'task'`, as
   `'task'::text`, as `$$task$$`, in extra parentheses, with a comment in the
   middle of it.

   It tells three things apart. A block of text is a BODY — `as $fn$ ... $fn$`,
   `do $$ ... $$`, `do '...'`, `do [language plpgsql] $$ ... $$` — and the reader
   reads it; a block of text written
   where a statement may be written — `execute '...'`, `execute $q$...$q$`,
   `execute format('...')`, `prepare x as '...'` — is a statement the migration
   runs, and the reader reads that too; a block of text in a value position is a
   value, whatever it says. The spelling of the literal does not change what
   the block is: `execute E'…'` and `do E'…'` are statements the migration
   runs, exactly as their plain-quoted twins are, and the escapes an E-string
   holds are decoded, so a row written by one is a row the reader reads. Names
   assembled from pieces
   are folded into the
   names they spell — `'sync_' || 'field_allowlist'` and
   `format('%s%s', 'sync_field_', 'allowlist')` alike, in every spelling of a
   literal: `'…'`, `E'…'`, `U&'…'`, `B'…'` and `$$…$$`, with a cast between two
   pieces or without one — because a name nobody can see is a write nobody can
   place. The folding is applied to a statement BEFORE it is read, so the text
   the migration runs is the text the pieces spell.

   A name the reader CANNOT fold it reports: the pieces it cannot fold are the
   pieces where the name goes missing. `'sync_' || v_suffix`, where the suffix
   is a variable, is a write into a PIECE of the name, and where the scope
   holds what the piece may be completed with — another piece, or the name
   itself — that write is reported: the text the migration runs is assembled
   at run time, and the reader cannot rule out that it reaches the field
   table. A piece on its own is not the name: `'field'` is a word, and a file
   may hold it without meaning the table.

   A body is a scope of its own AND a scope inside another: a nested body
   sees the scope it is nested in, so a write text the enclosing body holds
   is a write text the nested body may run.

   What the reader cannot read, it REPORTS — a row whose values come from a
   query, a row without a column list, a row whose values do not match its
   columns, a value it does not know, a write it cannot place. It does not
   pass over them: twenty-four review rounds found such a hole in every one of
   them, and every hole was a row the reader dropped in silence — a field
   nobody sends going past the check unnoticed, which is the direction this
   check exists for. What the reader does not model — a statement that
   renames a row, empties the table, merges into it or deletes from it, a copy
   into the table, a drop of the table or of its schema, a table made out of a
   query, an ALTER of the column's default, a VIEW over the table (a second
   door into it, which PostgreSQL writes through — a view it does not update,
   over an aggregate or a join, is no door), a CHILD of the table (a third
   door into it — PostgreSQL returns the rows of a child when the parent is
   queried, so a row written into the child is a row the server's own lookup
   finds), a string that spells a
   write into the table and is handed to a CALL (which may run it) — is
   reported as such rather than passed over, and so is a row of a type
   neither the client nor the server is known to have: the server looks a
   type up by name, so such a row is a row it never reads.

   The list of what it reports is not a guess. PostgreSQL writes rows into an
   existing table in six ways — insert, update, delete, truncate, merge,
   copy — and the reader reads the inserts and reports the other five. The
   table itself can be made in three ways — a drop, a `create table ... as
   select`, a `select ... into` — and all three are reported, as is a table
   declared with another default for its flag.

   A write the reader cannot place — its target is not a name the reader can
   read: a placeholder, an expression, a name spelled through a call — is
   reported when the table's name is used as a name in the same scope. A name
   that is read says nothing about where a write points; a name that is
   carried says it might. A write whose target IS a name is a write into the
   table that name stands for, and the reader cannot tell a variable from a
   table: `insert into v_table` is a write into a table named `v_table`, and a
   migration that writes the field table through a variable is refused in
   review, not by this gate. The scope is the dollar-quoted body the write is
   in, or the statement itself. A statement a migration runs through `execute`
   is a write of that kind whatever its text says.

   The limit is stated, not implied. The reader sees the SQL the file spells,
   and one case is beyond it: a name the write takes from the DATABASE at run
   time — out of a row, a setting, a function's return — so that the name is
   nowhere in the SQL of the statement that writes. That is the limit of
   static reading itself: a reader that reads SQL cannot see a name that is
   not in it. The file may spell the name as data it inserts, and the reader
   still cannot follow it: the name the write uses is data, not text. That
   case is closed by a rule and not by the gate: the field table is a
   contract, not data, it is written by a list of names, and a migration that
   writes it through a computed identifier is refused in review. What the
   reader takes for a read is a read OUT of the table; a name in the scope
   the write is in — carried in a variable, or read into one — is a name in
   play, and so is a name the file holds as a literal of its own; a write
   whose target the reader cannot tell, is reported.

   What a row is written as is not what it is: an insert written as text inside
   a string — dynamic SQL, run by the migration when it applies — is a row the
   file holds, so the reader reads the string to find it.
4. **The generated seed is current** with the derived server list, and the
   committed export is what the models declare.

## How the derivation reaches the tools

The tools are Python and shell; the derivation is Kotlin. The projection is
committed:

```
config/docs/sync-field-allowlist.json
```

written by `./gw :shared:exportSyncFieldAllowlist`, which runs the derivation
in the compiled code and writes the JSON. The seed generator reads that file.
The JVM test says the committed export is current - the arrangement
`config/docs/tool-surface.json` already uses - and
`./gw :shared:verifySyncFieldAllowlist` is the fast half of it, in the local
loop: the export task writes the file and so cannot report that it is wrong,
and the check asks the exporter whether the committed file says what the models
declare - the same comparison the JVM test makes, through the same function. The fast half matters
here more than it does for the tool surface: the seed and the gate both read
the export, so an export left stale by a model field would leave the seed and
the export agreeing with each other and with nothing.

The gate `check-sync-allowlist-regenerated.py` keeps its job and its speed: it
runs the generator and compares the file. What changes is where the generator
reads from: a JSON file instead of a Kotlin source parsed with a regular
expression. The generator can no longer stop at a parenthesis in a comment.

The generated file also moves out of `supabase/migrations/`, to
`supabase/sync_field_allowlist_seed.sql`. The migrations directory is where
the deploy tooling looks for migrations, and a file that describes what the
table holds is not one: applying it over a database that already carries rows
would leave them in place, and its rows for the two fields a migration is
still owed would close a gap that a real migration has to close.

The reader of Kotlin goes away entirely: `infra/gen_sync_allowlist_sql.py` no
longer reads the source, so it can neither cut a list short nor spell an
entity type the way the client spells it.

## The seed's entity types

The generated file spelled two entity types the way the client spells them.
The server spells them the way `DocType` does — `tag_group`, `time_entry` —
and those are the names `sync_table_for` and `sync_apply_ops` look up. The
generator now takes the entity type from the derived list, whose key is the
`DocType` key, so the file and the server speak the same names.

## What does not change

- What a device puts on the wire. The wire's list is the model's names, and
  that is what the hand-written list already said.
- The server's live table. This change does not apply a migration; it states
  what the table is and compares it with the derivation.
- The documents' shape, the protocol, the client's behaviour on the wire.
