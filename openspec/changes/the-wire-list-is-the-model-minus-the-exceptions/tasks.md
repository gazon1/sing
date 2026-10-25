# The wire's field list is the model's own list — tasks

## The derivation

- [x] `SyncContract` derives the wire's list from each model's serial
      descriptor instead of holding six hand-written sets.
- [x] The one hand-written part is the subtraction: the fields the server owns
      (`serverVersion`, `hlc`, `userId`, `id`), declared once and shared by
      every type.
- [x] Every subtracted name is checked to be a name the model has.

## The checks

- [x] The conformance test says the wire and the wire's list are equal, not
      that the wire's names are a subset of the list — which is what pins the
      encoder.
- [x] The positive controls run the check under test against a doctored list
      and a doctored wire, instead of re-implementing it.
- [x] The other half of the old check — the list has no name the model does
      not have — is stated and holds by construction.
- [x] A gate compares the server's seeded table with the derived server list,
      so a field the server accepts in a patch that is outside the derived server list
      is caught,
      and a type the client syncs and the server has no row for is caught too.
      The fields the server has not been migrated for are named.
- [x] The rows are read out of the migrations statement by statement: the reader
      takes the column list the statement names and maps the values onto it, so
      a row is read the same way however it was written — the columns in any
      order, the flag as `true`, as `'t'`, as `'yes'`, as `'tr'`, as
      `TRUE::boolean`, the name as `'task'`, as `'task'::text`, as `$$task$$`,
      in extra parentheses, with a comment in the middle of it.
- [x] A row the reader CANNOT read is a FAILURE, not a row that disappears: a
      row whose values come from a query, a row without a column list, a row
      whose values do not match its columns, a value it does not know. Twenty-four
      review rounds found such a hole in every one of them, and each was a field
      nobody sends going past the gate unnoticed.
- [x] EVERY migration is read, not only the first one — a migration may hold
      the table's rows in more than one file, and reading only the first would
      drop the rows the later ones added.
- [x] A statement the reader does not model is a FAILURE, not a row-less
      statement: an update, a delete, a truncate, a merge, an upsert that
      renames a row, a copy into the table, a drop of the table or of its
      schema, a table made out of a query, an ALTER of the column's default.
      Each changes what the server accepts in a patch in a way a comparison of names
      cannot see — and each is scoped to the table, so a statement about
      another table is another table's business. The list is not a guess:
      PostgreSQL writes rows into an existing table in six ways, and the
      reader reads the inserts and reports the other five; the table itself
      can be made in three ways, and all three are reported.
- [x] An insert written as text inside a string — dynamic SQL, run by the
      migration when it applies — is a row the file holds: the reader reads the
      string to find it. And the converse: a string in a value position is a
      value, and the reader does not read a row the database never holds.
- [x] A write the reader cannot place — a target that is not a name the reader
      can tell, a placeholder or an expression, or a PIECE of the name the
      scope may complete — is reported whenever the table's name is used
      as a name in the same scope, which is the body the write is in or the
      statement itself. A write whose target IS a name is a write into the
      table that name stands for, and the reader cannot tell a variable from a
      table: `insert into v_table` is a write into a table named `v_table`,
      and a migration that writes the field table through a variable is
      refused in review, not by this gate. A scope that SPELLS a write into
      the table is a scope
      where the name is in play — the text of that write may be carried into a
      variable and run later, and the reader cannot follow it there. A scope
      that only READS the table spells no write, and stays quiet: that is the
      shape the server's own migrations use. The same holds at the level of the
      file: a file that holds the TEXT of a write into the table is a file
      where the table's name is in play — a write the migration runs may be
      written as text and carried in a variable, in a row, in the return of a
      function. A call may run what it is handed, so a call handed a name — a
      variable the reader cannot follow — in a scope that holds such a text is
      reported too. The spelling of the expression does not change what the
      statement runs: `execute v_sql` and `execute (v_sql)` are the same
      statement. A body is a scope of its own AND
      a scope inside another: a nested body sees the scope it is nested in, so
      a write text the enclosing body holds is a write text the nested body
      may run. A name assembled from pieces is
      folded into the name it
      spells, in every spelling of a literal — `'…'`, `E'…'`, `U&'…'`, `B'…'`,
      `X'…'`, `N'…'` and `$$…$$`, with a cast between two pieces or without
      one — whether the
      pieces are put together with `||` or handed to `format` or `concat`, and
      a statement a migration runs through `execute` is a write of that kind
      whatever its text says. The spelling of the literal does not change what
      the block is: `execute E'…'` and `do E'…'` are statements the migration
      runs, exactly as their plain-quoted twins are, and the escapes an
      E-string holds are decoded, so a row written by one is a row the reader
      reads. `do [language plpgsql] $$ … $$` is a body, too — the language
      clause stands between the keyword and the code, and the code runs.
- [x] A body whose block was never closed is reported, like a statement that
      ends in the middle of an expression: the migration does not apply, so
      the rows it holds are not rows the server will have.
- [x] A name the reader CANNOT fold is reported: the pieces it cannot fold are
      the pieces where the name goes missing. A write into a PIECE of the
      name, where the scope holds what the piece may be completed with —
      another piece of the name, or the name itself — is a write the reader
      cannot rule out, and it is reported. A piece on its own is not the name:
      `'field'` is a word, and a file may hold it without meaning the table.
- [x] A NAME and a VALUE are read differently, because the server stores them
      differently. A bit string and a hex string hold BITS — the text a text
      column stores — and they spell BYTES as well, and a file that writes the
      table's name in hex writes those bytes. The pieces of a name are folded
      into the name they SPELL — `'sync_field_' || B'0110…'` spells the table's
      name — and the value of a row is read as the value the pieces HOLD: the
      bits.
- [x] The cases the reader does not see are written down, not left to be
      discovered. A name in the scope the write is in — carried in a variable,
      or read into one — is a name in play, and so is a name the file holds
      as a literal of its own, because a write in another statement of that
      file may take it from that data; what the reader takes for a read is a
      read OUT of the table. A name the write takes from the DATABASE at run
      time — out of a row, a setting, a function's return — is the one case
      beyond the reader: it is the limit of static reading itself, since a
      reader that reads SQL cannot see a name that is not in the SQL, and the
      name the write uses is data, not text. It is closed by a rule rather
      than by the gate: the field table is a contract, not data, it is written
      by a list of names, and a migration that writes it through a computed
      identifier is refused in review.
- [x] A write whose TARGET is written as a string is reported — the database
      does not accept a string where a table's name stands, so the migration
      does not apply. Every spelling of a string is reported, and the text
      inside it is read the way the database reads it. A string that HOLDS the
      words of a write is not reported: a column holds words.
- [x] The doors the reader does not follow are reported: a VIEW over the
      table, which PostgreSQL writes through; a CHILD of the table, whose
      rows PostgreSQL returns when the parent is queried, so a row written
      into the child is a row the server's own lookup finds; and a string
      that spells a write
      into the table and is handed to a CALL, which may run it. A string that
      is STORED is not reported — a column holds words.
- [x] A table RENAMED, or moved out of the schema the server looks it up in,
      is reported: a table the server cannot find is a table no field is
      writable through.
- [x] A row of a type neither the client nor the server is known to have is a
      failure, and so is a type one spelling away from the client's: the server
      looks a type up by name, so a row it never reads is a row that was meant
      for the client and missed.
- [x] A migration file whose name carries no date is a failure: the deploy
      tooling reads the name to order the files, and a gate that skipped such
      a file would say nothing about the rows it holds.
- [x] The gate's own `--self-test` can fail: the probe it writes has to make
      the seed stale, and the self-test says so when it does not.

## The tools

- [x] `config/docs/sync-field-allowlist.json` — the committed projection of
      the derived lists.
- [x] `./gw :shared:exportSyncFieldAllowlist` writes it.
- [x] A JVM test says the committed export is current.
- [x] `./gw :shared:verifySyncFieldAllowlist` is the fast half of that check,
      in the local loop — the seed and the gate both read the export, so an
      export left stale would leave them agreeing with each other and with
      nothing.
- [x] `infra/gen_sync_allowlist_sql.py` reads the export instead of parsing
      the Kotlin source.
- [x] The seed file is regenerated from the derived server list, with the
      entity types spelled the way the server spells them, and says in its
      header that it is a description of the table rather than a migration to
      apply.
- [x] The seed file moves out of `supabase/migrations/` to `supabase/` — that
      directory is where the deploy tooling looks for migrations, and this file
      describes what the table holds.
- [x] `check-sync-allowlist-regenerated.py` keeps its job and its speed, and
      its unit tests are rewritten for the new generator.

## The record

- [x] The ADR (`docs/decisions/2026-10-25-the-wire-list-is-the-model-minus-the-exceptions.md`)
      with its frontmatter.
- [x] The issue record (`.specify/issues/the-wire-list-is-the-model-minus-the-exceptions.yaml`).
- [x] The rule in `CODING_STANDARDS.md`: a field the model has is a field the
      wire carries; the list is derived, not written.
- [x] Issue #1050 — the server's field table is owed `task.seriesId` and
      `project.closedAt`.

## The gates

- [x] `openspec validate --all --json --strict`.
- [x] `./gw :shared:jvmTest` — the whole suite, fast and slow.
- [x] `./gw :shared:detekt :desktopApp:detekt :androidApp:detekt`.
- [x] `bash scripts/ci/static-gates.sh`.
- [x] `python3 scripts/check-review-verdicts.py`, `check_adr_status.py`,
      `check-issue-registry.py`.
- [x] A review by an agent that did not write the change, and the verdict
      file.
