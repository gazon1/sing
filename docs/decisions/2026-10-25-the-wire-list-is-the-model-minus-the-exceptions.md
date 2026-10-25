---
title: "The wire's field list is the model's own list"
date: 2026-10-25
status: accepted
supersedes: 2026-10-24-the-allowlist-seed-is-read-whole
tags: [sync, contract, tooling]
---

## Context

The device tells the server which fields of a document it may write. The server
keeps that permission in a table, `sync_field_allowlist`, and a field that is
absent from the table cannot be written by any patch, whatever the client sends
(`2026-10-04-sync-server-schema-and-merge.md`). The client has to agree with
that table, so the client keeps a list of the field names.

The client keeps it by hand. `SyncContract.FIELD_ALLOWLIST` holds six sets of
names — one per document type — written out in Kotlin, and the file says where
they came from: the server-side table, plus the client's own sync metadata
(`serverVersion`, `hlc`, `userId`), which the server stores without enforcing.

There are three lists where there should be one, and the three do not agree.

**The client's list** — 106 names, written by hand, one name per line.

**The server's live table** — 86 rows across seven entity types, seeded by
`2026-10-07-sync_schema.sql`. It holds the *work's* fields: no `serverVersion`,
no `hlc`, no `userId`, and no `id` — the id is the row's key, not a field of the
document. And it lags the models in two places, `task.seriesId` and
`project.closedAt`, which arrived after the migration that seeded it.

**A generated seed file** — `supabase/migrations/sync_field_allowlist_seed.sql`,
106 rows, produced by `infra/gen_sync_allowlist_sql.py`, which reads the
Kotlin source and writes the names it finds. The fast gate
`check-sync-allowlist-regenerated.py` compares that file with the client's
list, and they agree.

Two defects live in that arrangement.

The seed is the client's contract, and it sits where the deploy tooling looks
for migrations. It names what the client sends — the client's own sync
metadata (`serverVersion`, `hlc`, `userId`) and the key (`id`) — and the
server's table holds none of them: the server stores that metadata without
enforcing it, and the id is the row's key, not a field the client writes. A
file in `supabase/migrations/` is a migration to apply, so applying this one
would put rows into the server's table that the server's own lookup honours
and that nobody needs to write. The file also spells two entity types the way
the client spells them: `taggroup` and `timeentry`. The server spells them
`tag_group` and `time_entry` — those are the `DocType` keys, and they are
what `sync_table_for` and `sync_apply_ops` look up — so the rows of a seeded
file would stand under names the server never reads.

And the client's list is a copy of the model's own field names, kept by hand.
The check the client makes is one-sided: `SyncContractIsTheSourceOfTruthTest`
asserts that every field the wire carries is in the list. It cannot assert the
other half — that the list has no name the model does not have — because the
list is written by hand and the model is code: a name in the list that the model
dropped is a name nobody ever sends, and it looks like a rule.

(The reader that used to cut that list short — the first `)` in the Kotlin
source — was fixed the day before, in
`2026-10-24-the-allowlist-seed-is-read-whole.md`. What is left is the copy
itself, where the seed lives, and the vocabulary it speaks.)

## Decision

**The wire's field list is the model's own list. The hand-written part is the
exception, not the whole.**

`SyncContract` derives every list from the model it speaks about. The
derivation reads the serial descriptor of the model — the same descriptor the
encoder writes from — and takes its element names. A model field that the
device puts on the wire is in the list because the model has it, not because
somebody wrote it down twice.

Two lists come out of that derivation, and they mean different things.

**The wire's list** — the names a device puts on the wire. It is the model's
own names, and there is nothing to subtract from it: every field of every
model has a name here, and the device puts every one of them on the wire —
the three that live outside the row (`tags`, `dependsOn` and
`inheritedTagGroupIds`) travel as parts of the document rather than as
columns of it. A field that does not travel is a field the model does not
have.

**The server's list** — the names the server accepts in a patch. It is the wire's list
minus the fields the server owns: `serverVersion` (the server's own counter),
`hlc` (the clock the patch carries separately), `userId` (the owner comes from
the session, not from the document) and `id` (the key of the row). That
subtraction is the one hand-written part of the contract, and it is four names
long.

The derivation is not a guess about the contract; it reproduces it. The live
table has 86 rows over seven entity types, of which six are the client's. For
four of the six the derived list matches it name for name — `note` (18),
`tag` (7), `tag_group` (5), `time_entry` (9). For the other two it differs by
exactly the fields that arrived after the migration that seeded the table:
`task.seriesId` and `project.closedAt`. Those two are the server's deployment
lagging the models, and a migration is what closes it — filed as #1050.

The seed file is generated from the derived server list, and its entity types
are the `DocType` keys, so the file and the server speak the same names. The
file is a *description* of the table, not a migration to apply: it is
insert-only, and applying it over a database that carries older rows would
leave those rows in place. The server's table is seeded by the migrations, and
the file does not live in the migrations directory: that directory is where the
deploy tooling looks for migrations, and a file that says what the table holds
is not one. It sits one directory up, next to the migrations, and the gate
reads it as the description it is.

### What the checks say now

- **The wire and the wire's list are equal.** Not "the wire's names are in the
  list" — equal. The wire is the model, serialised, and the list is derived
  from the same model, so the equality holds by construction; what it pins is
  the *encoder*. A field the encoder stops writing — because `encodeDefaults`
  was turned off, or a custom serializer left one out — is a field the device
  sends no more and the server never hears of, and this is where that is said.
- **Every subtracted name is a name the model has.** A subtraction of a name
  that does not exist subtracts nothing: the field stays in the server's list,
  the server is told it may write it, and the model's own bookkeeping becomes
  writable.
- **The server's table and the derived server list agree**, apart from the
  fields the server has not been migrated for yet. The gate compares the
  derived list with the rows the migrations hold for the table — so a field
  the server accepts in a patch that is outside the derived server list, the other half
  of the old check, is caught, and a type the client syncs and the server has
  no row for is caught too. A row that REFUSES a write is compared the other
  way round: a field inside the derived list that the table refuses is a
  mismatch the gate names, because the client's write of that field would be
  dropped. A row that refuses a field OUTSIDE the derived list grants nothing
  — it denies a write of a name nobody declares — and the gate leaves it
  alone.

  **The rows are read out of the migrations, statement by statement.** The reader
  takes the column list the statement names and maps the values onto it, so a
  row is read the same way however it was written: the columns in any order, the
  flag as `true`, as `'t'`, as `'yes'`, as `'tr'`, as `TRUE::boolean`, the name as
  `'task'`, as `'task'::text`, as `$$task$$`, in extra parentheses, with a
  comment in the middle of it.

  **It tells three things apart.** A block of text is a BODY — `as $fn$ ... $fn$`,
  `do $$ ... $$`, `do '...'`, `do [language plpgsql] $$ ... $$` — and the reader
  reads it; a block of text written
  where a statement may be written — `execute '...'`, `execute $q$...$q$`,
  `execute format('...')`, `prepare x as '...'` — is a statement the migration
  runs, and the reader reads that too; a block of text in a value position is a
  value, whatever it says: a text column that happens to hold the words
  `insert into sync_field_allowlist` is a text column, and a `format` call that
  nothing runs is a value too. The spelling of the literal does not change what
  the block is: `execute E'…'`, `do E'…'` and `prepare x as E'…'` are statements
  the migration runs, exactly as their plain-quoted twins are — the `E` belongs
  to the literal, not to the text around it, and the escapes it holds are
  decoded, so a row written by an E-string is a row the reader reads. Names
  assembled from pieces are
  folded into the names they spell — `'sync_' || 'field_allowlist'` and
  `format('%s%s', 'sync_field_', 'allowlist')` alike, in every spelling of a
  literal: `'…'`, `E'…'`, `U&'…'`, `B'…'` and `$$…$$`, with a cast between two
  pieces or without one — because a name nobody can see is a write nobody can
  place. The folding is applied to a statement BEFORE it is read, and the text
  it folds a NAME into is the name the pieces spell; the text it folds a
  VALUE into is the value the pieces hold, and the text the migration runs is
  the value the pieces hold — the server runs the bits.

  **A name the reader cannot fold it reports.** The pieces it cannot fold are
  the pieces where the name goes missing: `'sync_' || v_suffix`, where the
  suffix is a variable, is a write into a PIECE of the name. Where the scope
  holds what the piece may be completed with — another piece of the name, or
  the name itself — that write is reported: the text the migration runs is
  assembled at run time, and the reader cannot rule out that the write reaches
  the field table. A piece on its own is not the name — `'field'` is a word,
  and a file may hold it without meaning the table — so a write into a piece
  is reported only together with what the piece may be completed with.

  **What the reader cannot read, it reports.** It is not a SQL parser and does
  not pretend to be one: a row whose values come from a query, a row without a
  column list, a row whose values do not match its columns, a value it does not
  know — each is a failure with a name on it, not a row that quietly
  disappears. Twenty-four review rounds found such a hole in every one of them, and
  every one of them was a row the reader dropped in silence — a field nobody
  sends going past the gate unnoticed, which is the direction the gate exists
  for. The rule is therefore the other way round: what the reader does not
  understand is a failure, and the gate has to be taught before it merges.

  A statement that renames a row, empties the table, merges into it or deletes
  from it changes what the server accepts in a patch in a way a comparison of names
  cannot see, and it is reported for the same reason — and so is a copy into
  the table, which writes rows without naming them one by one, a drop of the
  table or of its schema, a table made out of a query, and an ALTER that
  changes the column's default. An ALTER that renames the table or moves it
  out of the schema the server looks it up in is reported too: a table the
  server cannot find is a table no field is writable through. Three more
  doors are reported rather than followed: a view over the table, which
  PostgreSQL writes through; a child of the table, whose rows PostgreSQL
  returns when the parent is queried; and a string that spells a write into
  the table and is handed to a call, which may run it.

  A write whose TARGET is written as a string is reported too:
  `insert into 'sync_field_allowlist'` cannot run, because the database does
  not accept a string where a table's name stands — and the file spells the
  name all the same, so the statement is a migration that does not apply and
  the rows it holds are not rows the server will have. The spelling of the
  string does not change that: `E'…'`, `B'…'`, `N'…'`, `X'…'`, `U&'…'`,
  `$$…$$` and `$tag$…$tag$` are all strings where the database wants a name,
  and all of them are reported. The text inside them is the text the database
  reads — an E-string's escapes are decoded, a `U&` string's unicode escapes
  are decoded — because a name read any other way is a name the database
  never sees. A string that HOLDS the words of a write is not reported: the
  verb is inside the string there, and a column holds words.

  **The reader reads the text the deployed server reads.** Four rules of
  PostgreSQL's lexer are the ones a name can hide behind, and the reader
  follows all of them: a constant that continues across a newline, a newline
  inside one pair of quotes, the bits a bit string and a hex string hold, and
  the escapes of the version the deployment runs.

  Two constants
  separated by whitespace with a newline in it are ONE constant, and the
  whitespace is dropped — `'ti'` on the next line `'tle'` is `title` — so a
  name spelled in two parts is one name, and the reader joins the parts the
  way the server joins them. A newline inside one pair of quotes is another
  thing: the server keeps it, and so does the reader, because a name that
  holds one is a name the server holds too. And a bit string and a hex
  string hold BITS, which is what a text column stores: `X'41'` is
  `01000001` in the server's table, and not the letter A. A bit string and a
  hex string spell bytes as well, and a file that writes the table's name in
  hex writes those bytes — so the reader reads both, and a name spelled in
  hex is a name the reader sees.

  A COMMENT between the parts is not whitespace the server drops: a block
  comment between them is a syntax error there, and a line comment is not —
  so the reader joins the parts a line comment stands between, and does not
  join the parts a block comment stands between. A constant the reader
  cannot join is a row the reader cannot vouch for, and the reader says so
  rather than reads it as a name.

  **The reader reads a NAME, a VALUE and a STATEMENT apart, because the
  server stores them apart.** A name assembled out of pieces is the name the
  pieces spell, and a bit string or a hex string spells BYTES — so
  `'sync_field_' || B'0110…'` spells the table's name, and the write the
  file spells is the write the reader sees. A row holds a value, and the
  value a bit string holds is the bits it holds — the text the server
  stores — so the reader reads the value of a row as the bits and not as the
  bytes the same literal spells. And the text a statement the migration RUNS
  is the text the server runs: the bits, and not the bytes. A statement
  written as a bit string is a migration that does not apply — the server
  runs the bits, and the bits are not a statement — so the reader says so
  rather than read a statement out of the bytes the same literal spells. A
  reader that read the bytes of a row or of a statement would read a text
  the server never holds, and a reader that read the bits of a name would
  miss a name the file spells.

  And the reader reads the escapes of the version the deployment runs:
  PostgreSQL 15, which is what `supabase/postgres:15.8.1.085` is. `\v` is not
  one of its escapes — `E'\v'` holds `v` there, and the vertical tab arrived
  with PostgreSQL 17 — so a name that hides behind `\v` hides behind a
  letter, and the reader reads it as the letter. The reader follows the
  server it reads for, and not a newer one: a name read the way a newer
  server would read it is a name the deployed server never sees.

  **The list of what it reports is not a guess.** PostgreSQL writes rows into
  an existing table in six ways — insert, update, delete, truncate, merge,
  copy — and the reader reads the inserts and reports the other five. The
  table itself can be made in three ways — a drop, a `create table ... as
  select`, a `select ... into` — and all three are reported, as is a table
  declared with another default for its flag.

  A write the reader cannot place — its target is not a name the reader can
  read: a placeholder, an expression, a name spelled through a call — is
  reported when the table's name is used as a name in the same scope. A name
  that is read (`select ... from sync_field_allowlist`) says nothing about
  where a write points; a name that is carried says it might. A scope that
  SPELLS a write into the table is a scope where the name is in play: the
  text of that write may be carried into a variable and run later, and the
  reader cannot follow it there — so `v_sql := 'insert into sync_field_allowlist …';
  execute v_sql;` is reported. A scope that only READS the table spells no
  write and stays quiet, which is the shape the server's own migrations use:
  the text of the check the server runs is held in a variable, and the table is
  only read there.

  A write whose target IS a name is a write into the table that name stands
  for, and the reader tells the names apart by the name alone. It cannot tell
  a variable from a table: `insert into v_table` is a write into a table named
  `v_table` and not a write into the field table, and what a variable holds is
  data the reader cannot follow. A migration that writes the field table
  through a variable is therefore not caught by this gate — it is caught by
  the rule that the field table is a contract, not data, and is refused in
  review. That limit is a limit of static reading, not a hole in it.

  And the same holds at the level of the file. A file that holds the TEXT of a
  write into the table — as a literal, in a row, in the return of a function —
  is a file where the table's name is in play: a statement in another
  statement of that file may be running that text, and the reader cannot
  follow it there. A call may run what it is handed, so a call handed a name
  — a variable the reader cannot follow — in a scope that holds such a text
  is reported too: the reader cannot rule out that the call runs it. The
  spelling of the expression does not change what the statement runs:
  `execute v_sql` and `execute (v_sql)` are the same statement.

  The scope is the
  dollar-quoted body the write is in, or the statement itself, so a name
  declared in one statement of a body is seen from the next. A statement a
  migration runs through `execute` is a write of that kind whatever its text
  says: what it runs is text, and the text may name any table.

  **The limit is stated, not implied.** The reader sees the SQL the file
  spells, and one case is beyond it: a name the write takes from the DATABASE
  at run time — out of a row, a setting, a function's return — so that the
  name is nowhere in the SQL of the statement that writes. That is the limit
  of static reading itself: a reader that reads SQL cannot see a name that is
  not in it, and no static reading can. The file may well spell the name — as
  a value it inserts, as a value another migration inserts — and the reader
  still cannot follow it: the name the write uses is data, not text.
  Everything else is open. A name in a body is seen from every statement of
  that body, and a nested body sees the scope it is nested in — a name the
  enclosing body holds is a name the nested body can write with, and a write
  text the enclosing body holds is a write text the nested body may run; a
  name assembled from pieces is folded into the name it spells; a
  name spelled in the scope the write is in — carried in a variable, or read
  into one — is a name in play, and so is a name the file holds as a literal
  of its own, because a write in another statement of that file may take it
  from that data. What the reader takes for a read is a read OUT of the
  table: `select ... from sync_field_allowlist` says nothing about where a
  write points, because a read of the table cannot spell its own name.

  The view and the call are two of the three doors, and both are reported
  rather than followed. A VIEW over the table is
  a second door into it — PostgreSQL writes through an automatically updatable
  view — and the reader does not model which views are updatable, so a
  migration that makes one is reported. A view that carries one of the
  marks of a view PostgreSQL does not update — an aggregate, a join, a set
  operation — is no door, and is not reported; the marks are the reader's
  own, and a view it cannot place is reported rather than passed over. And a string that spells a write into
  the table, handed to a CALL, is reported: the reader cannot know whether the
  call runs it, and `dblink_exec('conn', 'insert into …')` does. A string
  that is stored is not reported — a column holds words, and `values
  ('insert into …')` puts a word in a column.

  A CHILD of the table is a third door, and it is the quietest of the three:
  PostgreSQL returns the rows of a child when the parent is queried, so a
  row written into `sync_field_allowlist_child` is a row the server's own
  lookup finds — the server reads `sync_field_allowlist`, and the child's
  rows stand in it. The row is written under the child's name, so the reader
  never sees it. The shapes are three — `create table … inherits (parent)`,
  `create table … partition of parent`, `alter table … inherit parent` — and
  all three are reported. A `like` is not one of them: it copies the shape of
  a table and not its rows, and the server looks the table up by its own
  name.

  The one case beyond the reader is therefore closed by a rule and not by the
  gate. The field table is a contract, not data: it is written by a list of
  names, and a migration that writes it through a computed identifier is
  refused in review — see `CODING_STANDARDS.md`. The gate says so rather
  than pretend otherwise: it reports a write whose target it cannot place
  whenever the table's name is in the same scope, and a name that is not in
  the file is not a name it can report.

  What a row is written as is not what it is: an insert written as text inside
  a string — dynamic SQL, run by the migration when it applies — is a row the
  file holds, so the reader reads the string to find it.

  A row of a type neither the client nor the server is known to have is
  reported too: the server looks a type up by name, so such a row is a row the
  server never reads, and a type one spelling away from the client's ('Task',
  'time-entry') is a row that was meant for the client and missed. The table
  itself is one table too: a table of the same name in another schema, or
  quoted in another case, is another table's business.
- **The generated file is current** with the derived list, and the committed
  export is what the models declare.

### How the derivation reaches the tools

The derivation lives in Kotlin, because only the compiler knows what a model's
descriptor says. The tools are Python and shell, so the derivation is exported:
`config/docs/sync-field-allowlist.json` is the projection of the derived lists,
written by `./gw :shared:exportSyncFieldAllowlist`, and the seed generator reads
that file instead of parsing the Kotlin source. The JVM test says the committed
export is current — the same arrangement `config/docs/tool-surface.json` uses —
and `./gw :shared:verifySyncFieldAllowlist` is the fast half of it, in the
local loop, where a model field added without re-exporting is found at once
rather than in the twenty-minute suite.

The seed generator keeps its name and its gate; what changes is where it reads
from. It no longer parses a Kotlin source with a regular expression, so it can
no longer stop at a parenthesis in a comment.

## Consequences

- **The client's list stops being a second copy.** A field added to a model is
  in the wire's list the moment the model changes, and the server's list
  follows it. Nothing has to be remembered in a second place, which is the
  whole point: the old list was a copy that had to be kept in step, and the
  step was a person's memory.
- **The seed file changes.** It loses the client's own bookkeeping and the key
  — 82 rows instead of 106 — and it spells the entity types the way the server
  spells them. It moves out of `supabase/migrations/`, because that directory
  is where the deploy tooling looks for migrations and this one describes the
  table rather than changing it. It is a local artifact; the live table is not touched by this
  change, and the file now describes the table the server reads, apart from
  the server's own six rows — the `profile` fields the client never sends —
  and the two fields a migration is still owed.
- **The client sends the same fields as before.** The wire's list is the
  model's names, and that is what the hand-written list already said. No
  document changes shape, and no server behaviour changes.
- **The server's writable set is now stated once, in code, and checked against
  the deployment.** Before, the client's list and the server's table were
  compared to nobody. Now the derivation says what the server accepts in a patch, the
  gate compares that with the migrations that seeded the live table, and the
  difference — the two fields a migration is owed — is named instead of being
  invisible.
- **The wire's list has nothing subtracted from it, and that is a fact, not an
  omission.** Every field of every model has a name on the wire, and the
  device puts every one of them there — the three that live outside the row
  travel as parts of the document; a field that does not travel
  is a field the model does not have. The one subtraction there is — the four
  fields the server owns — is about the server's list, not the wire's.
- **The server's lag is a deployment matter, and it is now visible.** A field
  the models have and the server has not been migrated for is a patch the
  server counts as carrying no writable operation. That was true before this
  change; it was not written down anywhere, and now it is.
- **The gate cannot see a name that is not in the file.** A migration that
  writes the field table through a name it computes at run time is a row the
  gate does not report — the reader reads SQL, and the name is nowhere in the
  SQL. The rule closes it, and the rule is the review: the field table is a
  contract, not data, and it is written by a list of names.

## Alternatives

- **Keep the list hand-written and check it against the model.** A gate would
  catch the drift, but the copy would stay a copy, and the check would have to
  parse the models to know what they have — which is the parser this change
  removes.
- **Derive the list and keep the seed as it is.** The seed would then describe
  a table the server does not have, and the two would drift in the other
  direction — which is how the three lists came to disagree in the first place.
- **Subtract a field from the wire's list to say it does not travel.** The
  device puts the whole model on the wire — the fields that live outside the
  row travel as parts of the document — so the wire would still carry the
  field and the equality would fail — the mechanism would have no state in
  which it is correct. A field that does not travel belongs in a model that
  does not have it, and then it is not in the list to begin with.
- **Parse the migrations with a real SQL parser.** A parser knows every
  spelling, so a reader built on one would not have to learn them. The
  repository has no SQL parser and the CI installs no Python packages, so the
  parser would either be a dependency the gate cannot have, or a hand-written
  one — and a hand-written one is what twenty-four review rounds found holes in,
  one per round. The reader this change settles on is narrow instead: it reads
  the shapes it knows, and it reports every shape it does not.
- **Make every dynamic write a failure.** The reader could report any write
  whose target it cannot tell — `execute format('insert into %1$I …', v_table)`
  — and then it would see every computed name. It cannot be done: the
  server's own migrations write through computed identifiers, so the rule
  would fail the repository's own history over a shape the server uses on
  purpose. The rule is narrower and says the same thing: the field table is
  written by a list of names, and nothing writes it through a computed
  identifier.

## What this answers

`2026-10-24-the-allowlist-seed-is-read-whole.md` decided that the seed is
read whole — the reader that cut the list at the first `)` in a comment is
gone — and recorded two facts it did not settle. This decision answers all
three.

**The reader is gone.** There is no reader of the Kotlin source any more.
The names are derived from the models by the Kotlin exporter and written to
`config/docs/sync-field-allowlist.json`, and the seed generator reads that
file — a file of names, not of source. A reader that misreads a source can no
longer be the reason a name is missing, and a gate that compares a file with
the same broken reader cannot certify the breakage.

**The seed describes the server's table.** That decision recorded that the
seed was the *client's* contract — the union of what the server accepts and
what the client sends — and therefore not the server's table. The seed here
describes the server's table: the names the server accepts in a patch, which
is what the table holds and what the gate compares the migrations against.
The client's own bookkeeping did not disappear with it: the projection
carries both lists — `wire` (106 names, the ones the client sends) and
`serverWritable` (82 names, the ones the server may write) — and the four
server-owned names, `serverVersion`, `hlc`, `userId` and `id`, are in the
first list and not in the second — for each of the six types, which is the
whole difference between the two lists — and the server's table holds none
of them.

**The vocabulary question is answered.** That decision noted that the two
files did not share a vocabulary for the entity types — the server writes
`tag_group` and `time_entry`, the generator wrote `taggroup` and
`timeentry` — and left it open as a question about the generator. There is no
generator-side spelling any more: the projection carries the entity type as
the model spells it, and the model's spelling is the `DocType` key, which is
the server's word.

## Links

- Issue #1033 — the allow-list on the wire is a second list
- Issue #1050 — the server's field table is owed the fields the models added
- `2026-10-04-sync-server-schema-and-merge.md` — the server's schema and the
  table that says which field may be written
- `2026-10-23-a-document-says-only-what-it-carries.md` — the receiving side of
  the same rule: a document says only what it carries
- `2026-10-24-the-allowlist-seed-is-read-whole.md` — the decision this one
  replaces: the seed is read whole, and it is not the server's table
