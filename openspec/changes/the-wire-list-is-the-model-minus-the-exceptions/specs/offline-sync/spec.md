# offline-sync

## ADDED Requirements

### Requirement: REQ-OS-029 The wire's field list is derived from the model

The set of field names a device may put on the wire SHALL be derived from the
model the document is an instance of, not written out by hand beside it.

`SyncContract` SHALL derive the wire's field list for each document type from
the element names of that model's serial descriptor. The hand-written part of
the contract SHALL be the exception — the fields the server owns — and not the
list.

The set of field names the server accepts in a patch SHALL be derived from the same
model, minus the fields the server owns: the server's version counter, the
clock the patch carries separately, the owner the session carries, and the key
of the row.

The checks SHALL say:

- the names the wire carries and the wire's derived list are **equal** — the
  wire is the model, serialised, and what the equality pins is the encoder: a
  field the encoder stops writing is a field the device sends no more, and the
  server never hears of it;
- every subtracted name is a name the model has — a subtraction of a name that
  does not exist would leave the model's own bookkeeping writable;
- the server's field table and the derived server list agree, apart from the
  fields the server has not been migrated for — a field the server accepts in a patch
  that is outside the derived server list is a name nobody ever sends, and a
  type the client syncs and the server has no row for is a type the server
  refuses.

The derivation SHALL reach the tools through a committed projection rather than
through a parser of the Kotlin source: the seed generator reads the projection,
and a check says the projection is what the models declare.

**Rationale:** the list was a copy of the models, written out by hand, and a
copy has to be kept in step by somebody's memory. Three lists existed where one
should have been, and they did not agree: the copy, the server's live table and
a generated seed written from the copy — the client's contract, in the
directory the deploy tooling reads, spelling two entity types the way the
client spells them rather than the way the server reads them. The check was one
sided as well: it could say that every field the wire carries is in the list,
and not that the list has no name the model does not have. Deriving the list
removes the copy, and the derivation reproduces the server's live table name
for name for four of the six client types — the two differences are the fields
the server has not been migrated for yet.

#### Scenario: A model gains a field

- **Given** a task model that gains a field
- **When** the contract is read
- **Then** the wire's field list for tasks contains the new field's name
- **And** the server's field list for tasks contains it too, unless it is one
  of the fields the server owns
- **And** no list had to be edited by hand for that to happen

#### Scenario: The encoder stops writing a field

- **Given** a model field the encoder does not write any more
- **When** the wire and the wire's list are compared
- **Then** the check fails
- **And** the failure names the field, because the device sends a document
  without it and the list says it is there

#### Scenario: The subtraction names a field the model does not have

- **Given** a subtraction of a name the model does not have
- **When** the contract is checked
- **Then** the check fails
- **And** the failure names the field, because the subtraction subtracts
  nothing, so the field stays in the server's list and the model's own
  bookkeeping becomes writable by the server

#### Scenario: The server accepts a field outside the derived server list

- **Given** a server's field table that holds a name outside the derived
  server list
- **When** every row the migrations hold for the table — read the way the
  statement writes it, so that a row is a row however it was written — and the
  derived server list are compared
- **Then** the check fails
- **And** the failure names the entity type and the field

#### Scenario: The server does not know a type the client syncs

- **Given** a server's field table with no row for a type the client syncs
- **When** every row the migrations hold for the table and the derived
  server list are compared
- **Then** the check fails
- **And** the failure names the type, because the server looks a type up by
  the name the client sends it under

#### Scenario: The reader cannot read a row

- **Given** a migration that holds a row of the table the reader cannot make
  sense of — a row whose values come from a query, a row without a column
  list, a row whose values do not match its columns
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file and what the reader could not read,
  because a row the reader did not see is a field nobody sends

#### Scenario: The migration writes a row the reader cannot place

- **Given** a migration that writes through a target the reader cannot tell —
  a placeholder, an expression, a name spelled through a call — in a scope
  where the table's name is used as a name
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because a write the reader cannot place
  is a row it cannot rule out — and the table's name, used as a name in the
  same scope, is what makes it a row of this table

#### Scenario: The migration writes through a variable

- **Given** a migration that writes through a name the reader can read — a
  variable of the migration's own language, `insert into v_table`
- **When** the migrations are read
- **Then** the check does not report the write
- **And** the limit is written down in the change's documents, because the
  reader cannot tell a variable from a table: a name that is not the field
  table's name is another table's business — and a migration that writes the
  field table through a variable is refused in review, not by this check

#### Scenario: The migration runs a write text it carries in a variable

- **Given** a migration that carries the text of a write into the table in a
  variable and runs it later — `v_sql := 'insert into sync_field_allowlist …';
  execute v_sql;`
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because a scope that spells a write into
  the table is a scope where the table's name is in play — the text may be the
  text that is run, and the reader cannot follow it there

#### Scenario: The migration assembles the text it runs from pieces

- **Given** a migration that runs text assembled from pieces — the table's
  name sharing its literal with the rest of the statement, or handed to a
  call that builds it
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file and the field, because a row the file
  holds is a row the file holds however it was written

#### Scenario: The migration assembles the name from a piece it cannot fold

- **Given** a migration that writes into a PIECE of the table's name, where the
  scope holds what the piece may be completed with — another piece of the name,
  or the name itself — so that the text the migration runs is assembled at run
  time
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because a write the reader cannot rule out
  is a row it cannot vouch for

#### Scenario: The name is computed at run time

- **Given** a migration that writes into the table through a name the write
  takes from the database at run time — out of a row, a setting, a function's
  return — so that the name is nowhere in the SQL of that statement
- **When** the migrations are read
- **Then** the check does not report it, because the reader reads SQL and a
  name that is not in the SQL is not a name it can see
- **And** the rule refuses it: the table is a contract, not data — it is
  written by a list of names, and a migration that writes it through a
  computed identifier is refused in review
- **And** the limit is written down in the change's documents

#### Scenario: The migration renames the table

- **Given** a migration that renames the field table, or moves it out of the
  schema the server looks it up in
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because a table the server cannot find
  is a table no field is writable through

#### Scenario: The migration puts rows into the table itself

- **Given** a migration that drops the table, or creates it out of a query
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because a statement that makes the
  table writes rows no model has, and names none at all

#### Scenario: The migration writes the row as text

- **Given** a migration that writes a row of the table as text inside a
  string — dynamic SQL, run when the migration applies
- **When** the migrations are read
- **Then** the check reads the string and finds the row
- **And** the failure names the entity type and the field, because a row the
  migration runs is a row the file holds

#### Scenario: The name is in play

- **Given** a migration whose scope holds the table's name as a name — the
  name carried in a variable, read into one, or spelled in the scope the
  write is in — or holds it as a literal of its own, as data the file hands
  around
- **When** the migrations are read
- **Then** the check reports a write whose target it cannot tell
- **And** the failure names the file, because a name in play is a name a
  write may point at
- **And** a write whose target is a name the reader can read is not reported
  for that reason alone — the reader cannot tell what a variable holds, and
  a migration that writes the field table through a variable is refused in
  review

#### Scenario: The name is read out of the table

- **Given** a migration that reads the table — `select ... from
  sync_field_allowlist` — and writes through it
- **When** the migrations are read
- **Then** the check does not report the read
- **And** the limit is written down in the change's documents, because a read
  of the table says nothing about where a write points: a read of the table
  cannot spell its own name

#### Scenario: The migration makes a view over the table

- **Given** a migration that makes a view over the table — a second door into
  it, which PostgreSQL writes through
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because the reader does not follow
  views, and an automatically updatable view writes through to the table
  (a view PostgreSQL does not update — over an aggregate, or over a join —
  is no door, and is not reported)

#### Scenario: The migration makes a child of the table

- **Given** a migration that makes a table that inherits from the field table
  — `create table … inherits (sync_field_allowlist)`, `create table …
  partition of sync_field_allowlist`, or `alter table … inherit
  sync_field_allowlist`
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because the reader does not follow
  children: PostgreSQL returns the rows of a child when the parent is
  queried, so a row written into the child is a row the server's own lookup
  finds, written under a name the reader never sees
- **And** a table shaped like the field table — `create table … (like
  sync_field_allowlist)` — is not reported, because it copies the shape of a
  table and not its rows

#### Scenario: The migration writes the table's name as a string

- **Given** a migration that writes the field table with its name written as
  a string — `insert into 'sync_field_allowlist'`, in any spelling of a
  string: `'…'`, `E'…'`, `B'…'`, `N'…'`, `X'…'`, `U&'…'`, `$$…$$`,
  `$tag$…$tag$`
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because the database does not accept a
  string where a table's name stands — the migration does not apply, and the
  rows it holds are not rows the server will have
- **And** the text inside the string is read the way the database reads it:
  an E-string's escapes are decoded, and a `U&` string's unicode escapes are
  decoded
- **And** a bit string and a hex string are read as the BITS they hold, for
  that is what a text column stores — and as the BYTES they spell as well, for
  that is how a name is written in hex and in bits
- **And** a constant that continues across a newline is one constant, its
  parts joined the way the server joins them — the whitespace between them is
  dropped — so a name spelled in two parts is read as the one name it is
- **And** a COMMENT between the parts is not whitespace the server drops: a
  block comment between them is a syntax error, so the reader does not join
  the parts and reports the row it cannot read
- **And** a name assembled out of pieces is read as the name the pieces spell,
  and a bit string or a hex string spells bytes — so `'sync_field_' ||
  B'0110…'` spells the table's name and is reported as the write it is
- **And** a row holds a VALUE, and the value a bit string or a hex string
  holds is the bits it holds — the text the database stores — so the value of
  a row is read as the bits and not as the bytes the same literal spells
- **And** the text a statement the migration RUNS is the text the server
  runs — the bits — so a statement written as a bit string is reported as a
  migration that does not apply, rather than read as a statement out of the
  bytes the same literal spells
- **And** a string that HOLDS the words of a write is not reported — the
  verb is inside the string there, and a column holds words

#### Scenario: The migration hands a write to a call

- **Given** a migration that hands a string spelling a write into the table to
  a call — `dblink_exec('conn', 'insert into …')`
- **When** the migrations are read
- **Then** the check fails
- **And** the failure names the file, because the reader cannot know whether
  the call runs the text it is handed

#### Scenario: The migration stores the words of a write

- **Given** a migration that stores the words of a write into the table in a
  column — `values ('insert into sync_field_allowlist …')`
- **When** the migrations are read
- **Then** the check does not report it
- **And** the check does not read a row of it, because a column holds words

#### Scenario: A row of a type nobody has

- **Given** a migration that holds a row of a type neither the client nor the
  server is known to have
- **When** the rows the migrations hold are compared with the derived lists
- **Then** the check fails
- **And** the failure names the type, because the server looks a type up by
  name and never reads that row — and a type one spelling away from the
  client's is a row that was meant for the client and missed
