#!/usr/bin/env python3
"""
Gate: fails if the sync field lists and the server's field table disagree.

Two things are checked, and both are about the same contract — the names a
document may carry, and the names the server may write.

1. The generated seed (`supabase/sync_field_allowlist_seed.sql`) is what the
   generator produces from the derived lists. The generator reads
   `config/docs/sync-field-allowlist.json`, which is the projection of the
   models' own field names — see `:shared:exportSyncFieldAllowlist`.

2. The server's table may not name a field outside the derived server list.
   Such a name is a field the server may write and nothing ever writes, and it
   reads like a rule. And a type the client syncs must be a type the server
   has a row for: the server looks a type up by the name the client sends it
   under.

## How the rows are read

A row of the table is a triple of a type, a field and a flag —
`('task', 'title', true)` — and the gate reads those triples out of the
migration files and compares them with the derived list.

The reader works statement by statement. For a statement that inserts into
the table it takes the column list the statement names and maps the values
onto it, so a row is read the same way however it was written: the columns
in any order, a boolean as `true`, as `'t'`, as `'yes'`, as `'tr'`, as
`TRUE::boolean`; a name as `'task'`, as `'task'::text`, as `$$task$$`, in
extra parentheses; a comment in the middle of a value.

It tells three things apart. A block of text is a BODY — `as $fn$ ... $fn$`,
`do $$ ... $$`, `do '...'`, `do [language plpgsql] $$ ... $$` — and the reader
reads it; a block of text written
where a statement may be written — `execute '...'`, `execute $q$...$q$`,
`execute format('...')`, `prepare x as '...'` — is a statement the migration runs,
and the reader reads that too; a block of text in a value position is a value,
whatever it says: a text column that happens to hold the words `insert into
sync_field_allowlist` is a text column, and a `format` call that nothing runs
is a value too. The spelling of the literal does not change what the block is:
`execute E'…'`, `do E'…'`, `prepare x as E'…'` are statements the migration
runs, exactly as their plain-quoted twins are — the `E` belongs to the
literal, not to the text around it, and the escapes it holds are decoded, so
a row written by an E-string is a row the reader reads. Names assembled from pieces are folded
into the names they spell — `'sync_' || 'field_allowlist'` and
`format('%s%s', 'sync_field_', 'allowlist')` alike, in every spelling of a
literal: `'…'`, `E'…'`, `U&'…'`, `B'…'` and `$$…$$`, with a cast between two
pieces or without one — because a name nobody can see is a write nobody can
place. The folding is applied to a statement BEFORE it is read, so the text
the migration runs is the text the pieces spell: a row the file holds is a
row the file holds however it was written.

A name the reader CANNOT fold it reports. The pieces it cannot fold are the
pieces where the name goes missing: `'sync_' || v_suffix`, where the suffix is
a variable, is a write into a PIECE of the name. Where the scope holds what
the piece may be completed with — another piece of the name, or the name
itself — that write is reported: the text the migration runs is assembled at
run time, and the reader cannot rule out that the write reaches the field
table. A piece on its own is not the name — `'field'` is a word, and a file
may hold it without meaning the table.

## What the reader does not read, it says

The reader is not a SQL parser, and it does not pretend to be one. Everything
it cannot interpret is REPORTED, never passed over:

* a row whose values come from a query (`insert ... select`);
* a row whose values it cannot read (a value it does not know, a row whose
  arity does not match the column list, an insert without a column list);
* an operation that changes the table in a way the reader does not model — an
  update, a delete, a truncate, a merge, an upsert that renames a row on
  conflict, a copy into the table, which writes rows without naming them, a
  drop of the table or of its schema, a table made out of a query, and an
  ALTER that changes the column's default;
* a write the reader cannot place — its target is not a name the reader can
  read: a placeholder, an expression, a name spelled through a call — in a
  scope where the table's name is in play. That is where the dynamic SQL of a
  migration lands, written as text and run when the migration applies. A
  statement a migration runs through `execute` is a write of that kind
  whatever its text says: what it runs is text, and the text may name any
  table. A scope that SPELLS a write into the table is a scope where the name
  is in play — the text of that write may be carried into a variable and run
  later, and the reader cannot follow it there; a scope that only READS the
  table spells no write, and stays quiet;
* a write into a PIECE of the table's name, where the scope holds what the
  piece may be completed with — the text the migration runs is assembled at
  run time, and the piece may grow into the whole name;
* a statement that ends in the middle of an expression — the migration does
  not apply, so the rows it holds are not rows the server will have — and a
  body whose block was never closed, which is the same thing a level down;
* a name assembled in more steps than the reader folds, so the reader did not
  finish reading the statement;
* an ALTER that renames the table or moves it out of the schema the server
  looks it up in — a table the server cannot find is a table no field is
  writable through;
* a VIEW over the table — a second door into it, which PostgreSQL writes
  through — because the reader does not model which views are updatable. A
  view PostgreSQL does NOT update (one over an aggregate, or over a join) is
  no door, and is not reported;
* a CHILD of the table — a table that inherits from it, or is a partition of
  it, or is given it later — because PostgreSQL returns the rows of a child
  when the parent is queried, so a row written into the child is a row the
  server's own lookup finds;
* a string that spells a write into the table and is handed to a CALL, which
  may run it. A string that is stored is not reported: a column holds words;
* a write whose TARGET is written as a string — `insert into
  'sync_field_allowlist'` — which the database does not accept, in every
  spelling of a string, and with the text inside it read the way the database
  reads it. A string that holds the words of a write is not a target: the
  verb is inside the string there.

The list is not a guess. PostgreSQL writes rows into an existing table in six
ways — insert, update, delete, truncate, merge, copy — and the reader reads
the inserts and reports the other five. The table itself can be made in
three ways — a drop, a `create table ... as select`, a `select ... into` —
and all three are reported, as is a table declared with another default for
its flag. A write the reader cannot place is reported when the table's name
is used as a name in the same scope: a name that is read (`select ... from
sync_field_allowlist`) says nothing about where a write points, and a name
that is carried — in a variable, or in pieces — says it might.

A write whose target IS a name is a write into the table that name stands
for, and the reader tells the names apart by the name alone. It cannot tell
a variable from a table: `insert into v_table` is a write into a table named
`v_table` and not a write into the field table. What a variable holds is
data the reader cannot follow, and a migration that writes the field table
through a variable is refused in review, not here. See `CODING_STANDARDS.md`.

The statement the reader reads is the statement the migration RUNS, with its
values in place. A mask would blank every value, and an assignment whose
value is blanked — `using errcode = '42501'` — would look like an expression
nobody finished; the values are there, and the reader reads them.

## The one case the reader cannot see

A name the reader cannot see is a name the write takes from the DATABASE at
run time — out of a row, a setting, a function's return — so that the name
is nowhere in the SQL of the statement that writes. The file may well spell
the name — as a value it inserts, as a value another migration inserts — and
the reader still cannot follow it: the name the write uses is data, not text.
That is the limit of static reading, not a hole in it, and it is closed by a
rule rather than by this gate: the field table is a contract, not data, it is
written by a list of names, and a migration that writes it through a computed
identifier is refused in review. See `CODING_STANDARDS.md`.

A name the reader CAN see is a different case, and it is reported: carried in
a variable, read into one, assembled from pieces, or held in the file as a
literal the file hands around, it is a name in play. A write whose target
the reader cannot tell is reported beside it, because such a write may point
at the name — and a write whose target IS the name is a row the reader reads.
So is the TEXT of a write into the table, held
as a literal anywhere in the file: a write the migration runs may be written
as text and carried in a variable, in a row, in the return of a function, and
a statement that runs something the reader cannot see may be running that
text. A body is a scope of its own AND a scope inside another: a nested body
sees the scope it is nested in, so a write text the enclosing body holds is a
write text the nested body may run. A call may run what it is handed, so a
call that is handed a name — a variable the reader cannot follow — in a scope
that holds the text of a write into the table is reported too: the reader
cannot rule out that the call runs it. What the reader takes for a read is a
read OUT of the table — `select ... from sync_field_allowlist` — because a
read of the table cannot spell its own name.

What a row is written as is not what it is: an insert written as text inside
a string — dynamic SQL, run by the migration when it applies — is a row the
file holds, and the reader reads the string to find it.

Twenty-four review rounds found such a hole in every one of them, and every hole
was a row the reader dropped in silence. A dropped row is a field nobody
sends, which is the direction this gate exists for. So the rule is the other
way round: what the reader does not understand is a failure with a name on
it, not a row that quietly disappears.

A row of a type neither the client nor the server is known to have is
reported too: the server looks a type up by name, so such a row is a row the
server never reads, and a near-miss of a client's type ('Task', 'time-entry')
is a row that was meant for the client and missed.

The other direction is reported but does not fail: a field the models have and
the server has not been migrated for is a deployment gap, not a lie in the
contract. It is tracked by issue #1050.

## Migration note
Migrated 2026-10-17 from argparse to Click (R2.1 of check-script-conventions.py).
Uses sgate.commands.base.CLEAN and VALIDATION_FAILURE for exit codes.

Run standalone: PYTHONPATH=scripts/src python3 scripts/check-sync-allowlist-regenerated.py
Run self-test:  PYTHONPATH=scripts/src python3 scripts/check-sync-allowlist-regenerated.py --self-test
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import subprocess
import sys

import click

# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import CLEAN, VALIDATION_FAILURE, run

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUTPUT_FILE = os.path.join(ROOT, "supabase/sync_field_allowlist_seed.sql")
EXPORT_FILE = os.path.join(ROOT, "config/docs/sync-field-allowlist.json")
MIGRATIONS_DIR = os.path.join(ROOT, "supabase/migrations")
GEN_SCRIPT = os.path.join(ROOT, "infra/gen_sync_allowlist_sql.py")

#: The name of the table this gate is about, and the schema the migrations
#: put it in — they create it unqualified, so it lands in the default schema.
_TABLE = "sync_field_allowlist"
_SCHEMA = "public"

#: The columns a row of the table is made of, and what each of them holds.
#: The table is `entity_type text, field text, writable boolean default true`.
_COLUMNS = ("entity_type", "field", "writable")

#: The types the server has of its own. A row of one of them is the server's
#: business — its profile table is one — and the gate says nothing about its
#: fields. A type that is neither the client's nor the server's is a row the
#: server never reads, and the gate names it.
_SERVER_TYPES = frozenset({"profile"})

#: The words PostgreSQL accepts for a boolean, unquoted and quoted.
_TRUE_WORDS = frozenset({"true", "t", "yes", "y", "on", "1"})
_FALSE_WORDS = frozenset({"false", "f", "no", "n", "off", "0"})

#: The casts that keep the value's kind, and the casts that turn it into text.
_BOOLEAN_CASTS = frozenset({"boolean", "bool"})
_TEXT_CASTS = frozenset({"text", "varchar", "character varying", "char", "character", "name"})

#: A table name as SQL spells it: an identifier or a quoted one, optionally
#: qualified with a schema.
_NAME = (
    r'(?:[A-Za-z_][A-Za-z_0-9$]*|"[^"]+")'
    r'(?:\s*\.\s*(?:[A-Za-z_][A-Za-z_0-9$]*|"[^"]+"))?'
)

#: The statement that holds the rows.
_INSERT = re.compile(
    r"\binsert\s+into\s+(?:only\s+)?(?P<table>" + _NAME + r")",
    re.IGNORECASE,
)

#: The statements that change the table in a way the reader does not model.
#: An update renames a row, a truncate empties the table, a merge writes rows
#: the reader cannot see. Each pattern is scoped to the table, so an operation
#: on another table is another table's business.
_UNMODELLED = (
    ("an update", re.compile(r"\bupdate\s+(?:only\s+)?(?P<table>" + _NAME + r")", re.IGNORECASE)),
    (
        "a delete",
        re.compile(r"\bdelete\s+from\s+(?:only\s+)?(?P<table>" + _NAME + r")", re.IGNORECASE),
    ),
    (
        "a truncate",
        re.compile(
            r"\btruncate\s+(?:table\s+)?(?:only\s+)?(?P<table>" + _NAME + r")", re.IGNORECASE
        ),
    ),
    ("a merge", re.compile(r"\bmerge\s+into\s+(?:only\s+)?(?P<table>" + _NAME + r")", re.IGNORECASE)),
)

#: The upsert that renames a row on conflict. It is scoped to the statement
#: that inserts into the table, so it is seen however it is laid out.
#: A write and the table it points at. The target is whatever follows the
#: verb, so a write whose target is a placeholder (`%I`) is caught too: the
#: gate cannot tell where it points, and says so. A copy is not here: it has
#: a direction, and `_copy_writes_into_our_table` reads it.
_WRITE_TARGET = re.compile(
    r"\b(?:insert\s+(?:or\s+(?:replace|ignore|conflict)\s+)?into"
    r"|update|delete\s+from|truncate(?:\s+table)?|merge\s+into)"
    r"\s+(?:only\s+)?(?P<target>[^\s,;()]+)",
    re.IGNORECASE,
)

#: The name of the table, as a word.
_MENTIONS = re.compile(r"\bsync_field_allowlist\b", re.IGNORECASE)

#: A write verb, and nothing but the verb after it. A literal that stands
#: where the verb's target would stand is a table's name written as a string:
#: PostgreSQL does not accept a string there — `insert into 'sync_field_
#: allowlist'` is a syntax error — so the migration does not apply. The
#: reader says so rather than passes the statement over: the file spells the
#: table's name, and the reader cannot vouch for a statement that cannot run.
#:
#: The verb and the literal have to be NEIGHBOURS. A string that holds the
#: words of a write — `values ('example: insert into sync_field_allowlist …')`
#: — is stored, and a column holds words: there the verb is inside the
#: string, not before it.
_WRITE_VERB_BEFORE = re.compile(
    r"\b(?:insert\s+(?:or\s+(?:replace|ignore|conflict)\s+)?into"
    r"|update|delete\s+from|truncate(?:\s+table)?|merge\s+into)"
    r"(?:\s+only)?$",
    re.IGNORECASE,
)

#: A statement a migration runs through `execute`. What it runs is a
#: statement, not a table name, so the target of such a write is never a name
#: the reader can hold: it is text, assembled at run time. The target is
#: whatever expression follows the keyword — `execute v_sql`, `execute (v_sql)`,
#: `execute (select v_sql)` — because the spelling of the expression does not
#: change what the statement runs.
_EXECUTE = re.compile(r"\bexecute\s+(?!function\b)(?P<target>[^\s,;]+)", re.IGNORECASE)


def _target_text(match: re.Match[str]) -> str:
    """The text a matched string literal holds.

    The literal may be spelled plain, with a prefix (`E`, `B`, `N`, `X`,
    `U&`), or dollar-quoted; the name a write points at is the text inside
    the spelling, whatever the spelling is.
    """
    for group in ("quoted", "dollars", "tagged"):
        text = match.group(group)
        if text is not None:
            return text
    return ""


def _string_targets(text: str) -> list[str]:
    """The names that stand where a table's name stands, written as strings.

    A write verb and a literal next to it: `insert into 'sync_field_allowlist'`.
    The database does not accept a string where a table's name stands, so the
    migration does not apply — and the file spells the name all the same, so
    the reader says so rather than passes the statement over.

    The literal has to be NEXT to the verb. A literal that holds the words of
    a write — `values ('example: insert into sync_field_allowlist …')` — is
    stored, and a column holds words: there the verb is inside the literal,
    not before it.

    The text is the text the database reads. An E-string's escapes are
    decoded (`E'sync\\_field_allowlist'` holds `sync_field_allowlist`, because
    PostgreSQL drops the backslash of an escape it does not know), a `U&`
    string's unicode escapes are decoded, and a dollar-quoted string holds its
    words as they are. A reader that compared the spelling would compare a
    name the database never sees.

    A hex string is read in both of its readings: the bits it holds, which is
    what the database stores, and the bytes it spells, which is what a file
    that writes the name in hex writes. So `insert into X'73796e63…'` is the
    write into this table that it spells.
    """
    targets: list[str] = []
    for span in _spans(text):
        kind, start, _end, _content = span
        if kind not in ("string", "dollar"):
            continue
        before = _drop_comments(text[:start], _spans(text[:start]))
        before = _without_literal_prefix(before)
        if _WRITE_VERB_BEFORE.search(before) is None:
            continue
        readings = _constant_readings(text, span)
        if not readings:
            decoded = _literal_body(text, span)
            if decoded:
                targets.append(decoded)
            continue
        targets.extend(readings)
    return targets


def _literal_body(text: str, span: tuple[str, int, int, str]) -> str:
    """The text a literal holds, or its spelling when the reader cannot read it.

    A literal the reader cannot decode — a bit string that is not a bit
    string — is still a literal, and its spelling is what the file wrote. The
    database does not accept it in a target position either, so the reader
    says so rather than passes the statement over.
    """
    decoded = _piece_text(text, span)
    if decoded is not None:
        return decoded
    kind, _start, _end, content = span
    if kind == "dollar":
        body = _dollar_body(content)
        return body if body is not None else ""
    return _string_body(content)

#: The names the table goes by in text: bare, and qualified with the schema
#: the migrations put it in.
_NAMES_OF_OUR_TABLE = frozenset({_TABLE, f"{_SCHEMA}.{_TABLE}"})


def _pieces_of_our_name() -> frozenset[str]:
    """The pieces the table's name is made of, as texts of their own.

    The name is `sync_field_allowlist`, and a name may be assembled out of
    pieces of it: `'sync_' || 'field_allowlist'`. A piece is a run of whole
    words of the name — fewer than all of them, because the whole name is
    the name and not a piece of it — with or without the underscore that
    joins them: so `sync_field`, `field_allowlist` and `allowlist` are
    pieces, and `sync` is a piece too.
    """
    words = _TABLE.split("_")
    pieces: set[str] = set()
    for start in range(len(words)):
        for end in range(start + 1, len(words) + 1):
            if start == 0 and end == len(words):
                continue
            run = "_".join(words[start:end])
            pieces.add(run)
            pieces.add(run + "_")
            pieces.add("_" + run)
    return frozenset(pieces)


_PIECES_OF_OUR_NAME = _pieces_of_our_name()

_ON_CONFLICT = re.compile(r"\bon\s+conflict\b", re.IGNORECASE)
_DO_UPDATE = re.compile(r"\bdo\s+update\b", re.IGNORECASE)


def migrations() -> list[str]:
    """The migration files, in order.

    Every `.sql` in the migrations directory is a migration — the directory is
    where the deploy tooling looks for them, so a file there is one. The
    generated description of the table — `supabase/sync_field_allowlist_seed.sql`
    — is not a migration and does not live here: a file that says what the
    table holds is not something to apply. It sits one directory up, next to
    the migrations, and the gate reads it as the description it is.
    """
    return sorted(
        os.path.join(MIGRATIONS_DIR, name)
        for name in os.listdir(MIGRATIONS_DIR)
        if name.endswith(".sql")
    )


def undated_migrations(paths: list[str]) -> list[str]:
    """The migration files whose name does not carry a date.

    A migration is named `2026-10-07-sync_schema.sql`: the date says when it
    was written, and the deploy tooling reads the name to know the order. A
    file without a date is a file the tooling cannot order, and a reader that
    skips it says nothing about the rows it holds.
    """
    return [
        os.path.basename(path)
        for path in paths
        if not re.match(r"^\d{4}-\d{2}-\d{2}-.*\.sql$", os.path.basename(path))
    ]


class _ExportUnreadable(Exception):
    """The export is not a projection the gate can read."""


def derived_lists(path: str) -> dict[str, set[str]]:
    """The names the server may write, per entity type, from the export.

    The export is a projection of the models' own field names, and the gate
    reads it. An export it cannot read is REPORTED, not a crash: a gate that
    dies with a traceback says nothing about the contract it exists for.
    """
    try:
        with open(path) as f:
            export = json.load(f)
    except OSError as error:
        raise _ExportUnreadable(f"the export cannot be opened: {error}") from error
    except json.JSONDecodeError as error:
        raise _ExportUnreadable(f"the export is not JSON: {error}") from error
    if not isinstance(export, dict):
        raise _ExportUnreadable("the export is not a JSON object")
    doc_types = export.get("docTypes")
    if not isinstance(doc_types, dict):
        raise _ExportUnreadable("the export holds no 'docTypes' object")
    lists: dict[str, set[str]] = {}
    for entity_type, entry in doc_types.items():
        if not isinstance(entry, dict):
            raise _ExportUnreadable(
                f"the export's entry for '{entity_type}' is not an object"
            )
        writable = entry.get("serverWritable")
        if not isinstance(writable, list) or not all(
            isinstance(name, str) for name in writable
        ):
            raise _ExportUnreadable(
                f"the export's entry for '{entity_type}' holds no 'serverWritable' "
                "list of names"
            )
        lists[entity_type] = set(writable)
    return lists


# --------------------------------------------------------------------------
# Reading SQL
# --------------------------------------------------------------------------


#: The whitespace that may stand between the parts of one constant, and the
#: line comments that may stand there too. PostgreSQL reads two constants as
#: ONE only when the whitespace between them holds a newline.
_GAP = re.compile(r"(?:[ \t\r\n\f\v]|--[^\n]*)*")


def _constant_parts(text: str, start: int, escaped: bool) -> tuple[int, list[str]]:
    """The end of the constant that starts at `start`, and the raw bodies of
    its parts.

    PostgreSQL continues a string constant across a newline: two constants
    that are only separated by whitespace with a newline in it are ONE
    constant, and the whitespace between them is dropped — `'ti'` on the
    next line `'tle'` is `title`, and its length is five. The bodies come
    back separately, because the value of the constant is their
    concatenation and not the text between them.

    A newline INSIDE one pair of quotes is another thing: PostgreSQL keeps
    it, and the body holds it.

    A part that carries a prefix of its own is not a continuation:
    PostgreSQL reads that as an error, so the constant ends where the
    prefix stands.
    """
    size = len(text)
    bodies: list[str] = []
    index = start
    while True:
        end = index + 1
        closed = False
        while end < size:
            if escaped and text[end] == "\\" and end + 1 < size:
                end += 2
                continue
            if text[end] == "'":
                if end + 1 < size and text[end + 1] == "'":
                    end += 2
                    continue
                end += 1
                closed = True
                break
            end += 1
        bodies.append(text[index + 1 : end - 1] if closed else text[index + 1 : end])
        if not closed:
            return end, bodies
        # The whitespace that follows a constant may hold the start of the
        # next one — but only when it holds a newline, because only then
        # does PostgreSQL read the two as one constant.
        gap = _GAP.match(text, end)
        width = gap.end() - end
        if "\n" not in gap.group(0) or not text.startswith("'", end + width):
            return end, bodies
        index = end + width


def _prefix_of(text: str, start: int) -> str:
    """The prefix the constant at `start` carries: `E`, `U&`, `B`, `X`, `N` or
    none.

    The prefix stands right before the opening quote, and it stands alone:
    `abcE'x'` is a name and a string, not an E-string.

    The prefix of the FIRST part of a constant that continues across a
    newline governs the whole constant, and the parts that follow carry no
    prefix of their own — PostgreSQL reads a prefix there as an error.
    """
    if start >= 2 and text[start - 2 : start].lower() == "u&":
        return "U&"
    if start < 1:
        return ""
    char = text[start - 1]
    if char not in "EeBbXxNn":
        return ""
    if start >= 2 and (text[start - 2].isalnum() or text[start - 2] == "_"):
        return ""
    if char in "Ee":
        return "E"
    if char in "Bb":
        return "B"
    if char in "Xx":
        return "X"
    return "N"


def _spans(text: str) -> list[tuple[str, int, int, str]]:
    """The protected spans of `text`: literals, dollar quotes and comments.

    A span is a kind, a start, an end and the text it covers. Everything the
    reader must not mistake for structure lives in one of them.
    """
    spans: list[tuple[str, int, int, str]] = []
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        if char == "'":
            # An E-string escapes its quotes with a backslash (`E'it\'s'`), so the
            # scanner has to know the prefix: without it the string ends at the
            # escaped quote and the rest of the file is read as SQL.
            escaped = _prefix_of(text, index) == "E"
            end, _parts = _constant_parts(text, index, escaped)
            # The span covers the text as it is written — the gap between the
            # parts is inside it, and the mask blanks it — and the content is
            # the text of the constant as the file wrote it.
            spans.append(("string", index, end, text[index:end]))
            index = end
        elif char == '"':
            close = text.find('"', index + 1)
            end = size if close < 0 else close + 1
            spans.append(("quoted", index, end, text[index:end]))
            index = end
        elif char == "-" and text.startswith("--", index):
            close = text.find("\n", index)
            end = size if close < 0 else close
            spans.append(("comment", index, end, text[index:end]))
            index = end
        elif char == "/" and text.startswith("/*", index):
            depth = 1
            end = index + 2
            while end < size and depth:
                if text.startswith("/*", end):
                    depth += 1
                    end += 2
                elif text.startswith("*/", end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
            spans.append(("comment", index, end, text[index:end]))
            index = end
        elif char == "$":
            match = re.match(r"\$([A-Za-z_][A-Za-z_0-9]*)?\$", text[index:])
            if match:
                tag = match.group(0)
                close = text.find(tag, index + len(tag))
                end = size if close < 0 else close + len(tag)
                spans.append(("dollar", index, end, text[index:end]))
                index = end
            else:
                index += 1
        else:
            index += 1
    return spans


def _mask(text: str, spans: list[tuple[str, int, int, str]]) -> str:
    """`text` with its literals, dollar quotes and comments blanked out.

    The mask keeps the length of the text, so an index into it is an index
    into the text. Quoted identifiers stay: they are names, and the reader
    reads names.
    """
    chars = list(text)
    for kind, start, end, _ in spans:
        if kind == "quoted":
            continue
        for index in range(start, end):
            if chars[index] != "\n":
                chars[index] = " "
    return "".join(chars)


#: The mark a comment leaves behind when the reader drops it: a
#: non-breaking space. The mark is whitespace for everything the reader
#: reads — a comment after a value does not change the value — and it is NOT
#: whitespace to the scanner of a constant, so a comment that stands between
#: the parts of a constant keeps the parts apart: PostgreSQL reads a block
#: comment there as an error, and the reader must not join what the server
#: will not.
_COMMENT_MARK = "\u00a0"


def _drop_comments(text: str, spans: list[tuple[str, int, int, str]]) -> str:
    """`text` without its comments.

    Every comment leaves a mark behind — see `_COMMENT_MARK` — and the
    newlines it held are kept, so that dropping it does not move a line.
    """
    parts: list[str] = []
    cursor = 0
    for kind, start, end, _ in spans:
        if kind != "comment":
            continue
        parts.append(text[cursor:start])
        parts.append(_COMMENT_MARK + "\n" * text[start:end].count("\n"))
        cursor = end
    parts.append(text[cursor:])
    return "".join(parts)


def _matching_paren(masked: str, start: int, end: int) -> int | None:
    """The index of the parenthesis that closes the one at `start`."""
    depth = 0
    for index in range(start, end):
        char = masked[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
    return None


def _split_top_level(raw: str, masked: str) -> list[str]:
    """The comma-separated parts of `raw`, ignoring commas inside parentheses."""
    parts: list[str] = []
    current: list[str] = []
    depth = 0
    for index, char in enumerate(masked):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(raw[index])
    parts.append("".join(current))
    return [part.strip() for part in parts]


def _statement_spans(masked: str) -> list[tuple[int, int]]:
    """The statements of the text, as spans of `(start, end)`."""
    spans: list[tuple[int, int]] = []
    depth = 0
    start = 0
    for index, char in enumerate(masked):
        if char == "(":
            depth += 1
        elif char == ")":
            depth = max(0, depth - 1)
        elif char == ";" and depth == 0:
            spans.append((start, index))
            start = index + 1
    if masked[start:].strip():
        spans.append((start, len(masked)))
    return spans


def _table_key(name: str) -> str:
    """The table a name refers to: schema-qualified, folded unless quoted.

    PostgreSQL folds an unquoted name to lower case and keeps a quoted one as
    it is written, so `"SYNC_FIELD_ALLOWLIST"` is a different table from
    `sync_field_allowlist` and the reader must not confuse the two.
    """
    parts: list[str] = []
    for part in name.split("."):
        part = part.strip()
        if len(part) >= 2 and part.startswith('"') and part.endswith('"'):
            parts.append(part[1:-1])
        else:
            parts.append(part.lower())
    return ".".join(parts)


def _is_our_table(key: str) -> bool:
    """Whether a table key names the table this gate is about.

    The migrations create the table unqualified, so it lives in the schema the
    deployment's default is — `public`. A table of the same name in another
    schema is another table's business, and the reader must not read its rows
    as ours.
    """
    schema, _, name = key.rpartition(".")
    if not schema:
        return name == _TABLE
    return schema == _SCHEMA and name == _TABLE


def _copy_writes_into_our_table(statement: str) -> bool:
    """Whether a `copy` statement writes rows into the table this gate is about.

    A copy writes rows without naming them one by one — `copy sync_field_allowlist
    (...) from stdin` — so the reader cannot see a single row of it. It is
    reported instead of passed over.
    """
    match = re.search(r"\bcopy\b", statement, re.IGNORECASE)
    if match is None:
        return False
    rest = statement[match.end() :]
    if re.match(r"\s*\(", rest):
        # A copy of a query: copy ( select ... ) from ...
        open_index = rest.index("(")
        close = _matching_paren(rest, open_index, len(rest))
        if close is None:
            return False
        if not re.match(r"\s*from\b", rest[close + 1 :], re.IGNORECASE):
            return False
        return _mentions_our_table(rest[:close])
    direct = re.match(
        r"\s+(?:binary\s+)?(?:only\s+)?(" + _NAME + r")", rest, re.IGNORECASE
    )
    if direct is None:
        return False
    if not re.search(r"\bfrom\b", rest[direct.end() :], re.IGNORECASE):
        return False
    return _is_our_table(_table_key(direct.group(1)))


def _drops_our_table(statement: str) -> bool:
    """Whether a statement drops the table this gate is about.

    A dropped table is an empty table: every field the server may write is a
    field it may no longer write. The reader reports it rather than passing
    over it.
    """
    match = re.search(
        r"\bdrop\s+table\s+(?:if\s+exists\s+)?(?:only\s+)?(" + _NAME + r")",
        statement,
        re.IGNORECASE,
    )
    return match is not None and _is_our_table(_table_key(match.group(1)))


def _creates_our_table_from_a_query(statement: str) -> bool:
    """Whether a statement creates the table this gate is about out of a query.

    `create table sync_field_allowlist as select ...` makes the table with
    whatever rows the query returns — a row nobody sent, written by a
    statement that names no field at all. The reader reports it.

    The plain `create table` with a column list makes an empty table, and
    `if not exists` makes it nothing at all: neither writes a row.
    """
    match = re.search(
        r"\bcreate\s+(?:or\s+replace\s+)?(?:temporary\s+|temp\s+|unlogged\s+)?"
        r"table\s+(?:if\s+not\s+exists\s+)?(?:only\s+)?(" + _NAME + r")",
        statement,
        re.IGNORECASE,
    )
    if match is None or not _is_our_table(_table_key(match.group(1))):
        return False
    return re.search(r"\bas\b", statement[match.end() :], re.IGNORECASE) is not None


def _target_key(target: str) -> str | None:
    """The table a write points at, or None when it points at none we can tell.

    A placeholder (`%I`), a string, or anything that is not a table name
    leaves the reader without a target — and a write without a target that
    names the field table is a write the reader cannot rule out.
    """
    cleaned = target.strip()
    if cleaned.startswith("'"):
        cleaned = cleaned.strip("'")
    if not re.fullmatch(
        r'(?:[A-Za-z_][A-Za-z_0-9$]*|"[^"]+")'
        r'(?:\.(?:[A-Za-z_][A-Za-z_0-9$]*|"[^"]+"))?',
        cleaned,
    ):
        return None
    return _table_key(cleaned)


def _blank(text: str, spans: list[tuple[str, int, int, str]], kinds: tuple[str, ...]) -> str:
    """`text` with the spans of the given kinds blanked out."""
    chars = list(text)
    for kind, start, end, _ in spans:
        if kind not in kinds:
            continue
        for index in range(start, end):
            if chars[index] != "\n":
                chars[index] = " "
    return "".join(chars)


def _body_code_only(raw: str, start: int, end: int) -> str:
    """The text of a dollar-quoted body with the values inside it blanked.

    A body is code, and the literals inside it are values of that code. The
    reader reads them in the body's own scope, so the text a statement keeps
    of the body holds the code and not the values: a literal that says
    `insert into …` is a value of the body, and reading it as a write of the
    statement would be reading a word as a deed.
    """
    span = raw[start:end]
    body = _dollar_body(span)
    if body is None:
        return span
    head = span[: len(span) - len(body)]
    chars = list(body)
    for kind, inner_start, inner_end, _ in _spans(body):
        if kind not in ("string", "dollar"):
            continue
        for index in range(inner_start, inner_end):
            if chars[index] != "\n":
                chars[index] = " "
    return head + "".join(chars)


def _keep_runnable_text(raw: str, blanked: str) -> str:
    """`blanked` with the blocks of text the migration runs.

    A block in a value position is a value, whatever it says; a block the
    migration runs is a statement, and the reader reads it. A body is code
    of its own: the statement keeps the body's code, and the values inside
    the body stay values — the reader reads them where the body is read.
    """
    out = list(blanked)
    for kind, start, end, _ in _spans(raw):
        position = _text_position(raw[:start])
        if position in ("value", "alias"):
            continue
        if kind == "dollar" and position == "body":
            out[start:end] = list(_body_code_only(raw, start, end))
            continue
        for index in range(start, end):
            if out[index] != "\n":
                out[index] = raw[index]
    return "".join(out)


def _mentions_our_table(text: str) -> bool:
    """Whether the text names the table this gate is about."""
    return _MENTIONS.search(text) is not None


#: The marks of a view PostgreSQL does not update automatically. A view
#: without them is a door into the table: PostgreSQL writes through it. A
#: view with one of them is read-only as far as the database is concerned,
#: and it is another table's business.
_NOT_UPDATABLE_VIEW = (
    r"\bjoin\b",
    r"\bunion\b",
    r"\bintersect\b",
    r"\bexcept\b",
    r"\bgroup\s+by\b",
    r"\bhaving\b",
    r"\bdistinct\b",
    r"\blimit\b",
    r"\boffset\b",
    r"\bover\s*\(",
    r"\b(count|sum|avg|min|max|array_agg|string_agg|json_agg|jsonb_agg)\s*\(",
)


#: What a statement may not end with: the sign of an expression the writer
#: did not finish.
_DANGLING = re.compile(r"(?:\|\||::|[+\-*/]|<>|!=|[,=(])\s*$")

#: A `*` that stands for "every column" is not an operator: `returning *` and
#: `returning t.*` are finished statements, and `returning *` is how a row is
#: read back.
_STAR = re.compile(r"\b(returning|select)\s+(?:[A-Za-z_][A-Za-z_0-9$]*\s*\.\s*)?\*\s*$", re.IGNORECASE)


def _ends_in_the_middle_of_an_expression(statement: str) -> bool:
    """Whether the statement ends where an expression may not end.

    `do $$ begin execute 'insert into ' || $$name$$ …` — the inner `$$` closes
    the outer block, and what is left is a statement the database cannot
    run. The migration does not apply, so the rows it holds are not rows the
    server will have — but a reader that cannot read the statement says so
    rather than passes over it.

    The statement is read as the migration RUNS it — with its values in place.
    The mask cannot be used here: it blanks every value, and an assignment
    whose value is blanked — `using errcode = '42501'` — looks like an
    expression nobody finished.
    """
    text = statement.rstrip().rstrip(";").rstrip()
    if _STAR.search(text):
        return False
    return _DANGLING.search(text) is not None


def _moves_our_table(statement: str) -> bool:
    """Whether a statement renames the table this gate is about, or moves it.

    A renamed table is a table the server no longer looks up: every field it
    holds becomes a field the server may not write, and the gate would say
    nothing. A table moved to another schema is the same thing by another
    name — the migrations put this one in `public`, and the server looks it
    up there.
    """
    match = re.search(
        r"\balter\s+table\s+(?:only\s+)?(?:if\s+exists\s+)?(" + _NAME + r")"
        r"[^;]*?\b(rename\s+to|set\s+schema)\b",
        statement,
        re.IGNORECASE,
    )
    return match is not None and _is_our_table(_table_key(match.group(1)))


def _creates_a_view_over_our_table(statement: str) -> bool:
    """Whether a statement makes a view over the table this gate is about.

    A view over the table is a second door into it: PostgreSQL updates an
    automatically updatable view by writing through to the table it is made
    of, so `insert into sync_allowlist_v (...)` puts the row into the table
    the server reads — under a name the reader cannot see, because the row
    names the view, not the table.

    The reader does not follow views, so a migration that makes one is
    reported rather than passed over. A view PostgreSQL does not update
    automatically — one over a join, or over an aggregate — is no door, and
    is another table's business: the marks of such a view are the ones
    PostgreSQL itself lists, and a view without them is reported whatever it
    is used for.
    """
    match = re.search(
        r"\bcreate\s+(?:or\s+replace\s+)?(?:temporary\s+|temp\s+|recursive\s+)?"
        r"view\s+(?:if\s+not\s+exists\s+)?(" + _NAME + r")",
        statement,
        re.IGNORECASE,
    )
    if match is None:
        return False
    body = statement[match.end() :]
    if not _mentions_our_table(body):
        return False
    return not any(
        re.search(mark, body, re.IGNORECASE) for mark in _NOT_UPDATABLE_VIEW
    )


#: The ways a table can become a child of another one. Every one of them
#: makes the parent's rows include the child's rows: PostgreSQL returns the
#: rows of a child when the parent is queried.
_INHERITS = re.compile(r"\binherits\s*\(([^)]*)\)", re.IGNORECASE)
_PARTITION_OF = re.compile(
    r"\bpartition\s+of\s+(?:only\s+)?(" + _NAME + r")", re.IGNORECASE
)
_ALTER_INHERIT = re.compile(
    r"\balter\s+table\s+(?:only\s+)?(?:if\s+exists\s+)?(?:"
    + _NAME
    + r")[^;]*?\b(?P<action>inherit|no\s+inherit)\s+(?:only\s+)?("
    + _NAME
    + r")",
    re.IGNORECASE,
)


def _makes_a_child_of_our_table(statement: str) -> bool:
    """Whether a statement makes a table that inherits from the field table.

    A child table is a second door into the field table: PostgreSQL returns
    the rows of a child when the parent is queried, so a row written into the
    child is a row the server's own lookup finds — the server reads
    `sync_field_allowlist`, and the child's rows stand in it. The row is
    written under the child's name, so the reader never sees it.

    The shapes are three: `create table ... inherits (parent)`, `create table
    ... partition of parent`, and `alter table ... inherit parent`. A `like`
    is not one of them — it copies the shape of a table and not its rows, and
    the server looks the table up by its own name.
    """
    match = _INHERITS.search(statement)
    if match is not None:
        for parent in match.group(1).split(","):
            if _is_our_table(_table_key(parent.strip())):
                return True
    match = _PARTITION_OF.search(statement)
    if match is not None and _is_our_table(_table_key(match.group(1))):
        return True
    match = _ALTER_INHERIT.search(statement)
    if match is not None and _is_our_table(_table_key(match.group(2))):
        # `alter table child no inherit parent` takes the child out of the
        # parent — the opposite of a door.
        return match.group("action").lower().split() == ["inherit"]
    return False


#: The words that stand in front of a parenthesis in SQL and are not a call.
#: A string that stands inside one of them is stored, not run: `values (...)`
#: puts a row's worth of text in a column, and a column holds words.
_NOT_A_CALL = frozenset(
    {
        "all", "and", "any", "array", "as", "at", "between", "both", "case",
        "cast", "check", "collate", "conflict", "constraint", "cross", "default",
        "deferrable", "distinct", "do", "else", "elsif", "end", "except",
        "exclude", "exists", "false", "fetch", "for", "foreign", "from",
        "full", "group", "having", "if", "ilike", "in", "inner", "intersect",
        "into", "is", "join", "lateral", "leading", "left", "like", "limit",
        "loop", "natural", "not", "null", "nulls", "offset", "on", "only",
        "or", "order", "outer", "over", "partition", "perform", "placing",
        "primary", "returning", "right", "row", "select", "similar", "some",
        "symmetric", "table", "then", "to", "trailing", "union", "unique",
        "unknown", "using", "values", "when", "where", "while", "with",
    }
)


#: The calls the reader knows build text and cannot run it. A string handed
#: to one of these is stored, not executed: `format('insert into …')` is a
#: column's worth of words. Every other call may run what it is handed, so a
#: string handed to it is reported.
_TEXT_BUILDING_CALLS = frozenset(
    {
        "ascii", "btrim", "chr", "coalesce", "concat", "concat_ws", "format",
        "left", "lower", "lpad", "ltrim", "md5", "nullif", "quote_ident",
        "quote_literal", "repeat", "replace", "reverse", "right", "rpad",
        "rtrim", "substring", "to_char", "trim", "upper",
    }
)


def _calls_in(statement: str) -> list[tuple[str, list[str]]]:
    """The calls a statement makes, with their arguments as text.

    A call is a name and a parenthesis. The words that stand in front of a
    parenthesis in SQL and are not a call — `values (...)`, `in (...)` — are
    not calls, and a string inside them is stored.
    """
    mask = _mask(statement, _spans(statement))
    calls: list[tuple[str, list[str]]] = []
    for match in re.finditer(r"\b([A-Za-z_][A-Za-z_0-9]*)\s*\(", mask):
        name = match.group(1)
        if name.lower() in _NOT_A_CALL:
            continue
        if _TABLE_POSITION.search(mask[: match.start()]):
            # `insert into sync_field_allowlist (entity_type, field)` — the
            # parenthesis is the table's column list, not a call.
            continue
        open_index = match.end() - 1
        close = _matching_paren(mask, open_index, len(mask))
        if close is None:
            continue
        calls.append(
            (
                name,
                _split_top_level(
                    statement[open_index + 1 : close], mask[open_index + 1 : close]
                ),
            )
        )
    return calls


def _in_call_argument(masked_before: str) -> bool:
    """Whether the text stands as an argument of a call that may run it.

    A call may run its argument: `dblink_exec('conn', 'insert into …')` runs
    the text it is handed. The reader cannot know which calls run their
    arguments, so a string that spells a write into the field table is
    reported when it is handed to a call — except to the calls the reader
    knows build text and cannot run.
    """
    depth = 0
    for index in range(len(masked_before) - 1, -1, -1):
        char = masked_before[index]
        if char == ")":
            depth += 1
        elif char == "(":
            if depth > 0:
                depth -= 1
                continue
            name = _identifier_before(masked_before, index)
            if name is None:
                return False
            lowered = name.lower()
            if lowered in _NOT_A_CALL:
                return False
            return lowered not in _TEXT_BUILDING_CALLS
    return False


def _identifier_before(text: str, index: int) -> str | None:
    """The name that stands in front of the parenthesis at `index`."""
    cursor = index
    while cursor > 0 and text[cursor - 1].isspace():
        cursor -= 1
    end = cursor
    while cursor > 0 and (text[cursor - 1].isalnum() or text[cursor - 1] in "_$"):
        cursor -= 1
    if cursor == end:
        return None
    return text[cursor:end]


def _spells_a_write_into_our_table(content: str) -> bool:
    """Whether the text names a write into the table this gate is about.

    The text is read the way the reader reads a statement: the write verbs it
    knows, and the table the write points at.
    """
    for target in _WRITE_TARGET.finditer(content):
        key = _target_key(target.group("target"))
        if key is not None and _is_our_table(key):
            return True
    return False


def _piece_target_of(content: str) -> str | None:
    """The piece of our name a write in the text points at, if it points at one.

    `'insert into sync_' || v_suffix` writes into a table the text calls
    `sync_` — a piece of the field table's name. The text the migration runs
    is assembled at run time, so the piece may grow into the whole name, and
    the reader cannot rule out that the write reaches the field table.
    """
    for target in _WRITE_TARGET.finditer(content):
        key = _target_key(target.group("target"))
        if key is not None and key in _PIECES_OF_OUR_NAME:
            return key
    return None


def _name_usage(scope: str, scope_raw: str) -> bool:
    """Whether the scope uses the table's NAME rather than reading the table.

    A read — `select ... from sync_field_allowlist` — says nothing about
    where a write points. A name that is carried in a variable, or handed to
    a call as an argument, is a name — and a name is what a write needs.

    Two texts are looked at, and the reason is the folding. The folded scope
    is the one where a name assembled from pieces reads as the name it
    spells; the raw scope is the one where the pieces are still pieces. A
    name inside a text that holds more than the name is not a name at all:
    `raise notice 'table sync_field_allowlist'` mentions the table in a
    message. And a name compared with something — `tg_table_name =
    'sync_field_allowlist'` — is a comparison, not a name in play.

    A scope that SPELLS a write into the table is a name in play whatever
    else it holds: the text of that write may be carried into a variable and
    run later, and the reader cannot follow it there. A scope that only
    READS the table spells no write, and stays quiet.
    """
    if _spells_a_write_into_our_table(scope) or _spells_a_write_into_our_table(
        scope_raw
    ):
        return True
    spans = _spans(scope)
    for match in _MENTIONS.finditer(scope):
        span = _span_at(spans, match.start())
        before = scope[: match.start()].rstrip()
        if span is not None and span[0] in ("string", "dollar"):
            literal = _piece_text(scope, span)
            if literal is None or literal.lower() not in _NAMES_OF_OUR_TABLE:
                # The name is inside a text that holds more than the name —
                # unless the raw scope holds it as a piece of its own, which
                # is what a folded name looks like before the folding.
                if _holds_the_name_whole(scope_raw):
                    return True
                continue
            if re.search(r"(=|<>|!=|\blike\b|\bilike\b)\s*$", before, re.IGNORECASE):
                # The name is compared with something, not carried.
                continue
            return True
        if re.search(r"\b(from|join)$", before, re.IGNORECASE):
            continue
        return True
    return False


def _literals_of(text: str) -> list[str]:
    """The texts the literals of `text` hold, the bodies of dollar quotes included.

    A dollar-quoted body is opaque to a scan of the text around it: the
    literals inside it are literals of the file all the same, and a name may
    be held in one of them.

    A literal is read in every reading it has: a hex string holds the bits the
    database stores and spells the bytes a name is written in, and both are
    texts the file holds.
    """
    out: list[str] = []
    for span in _spans(text):
        if span[0] not in ("string", "dollar"):
            continue
        for literal in _constant_readings(text, span):
            out.append(literal)
        if span[0] == "dollar":
            body = _dollar_body(text[span[1] : span[2]])
            if body:
                out.extend(_literals_of(body))
    return out


def _holds_the_name_whole(text: str) -> bool:
    """Whether the text holds the table's name as a literal of its own.

    A literal that is exactly the name is a name in play — carried into a
    variable, or one piece of a name the reader folds. A literal that holds
    the name among other words is a text that mentions it.
    """
    return any(
        literal.lower() in _NAMES_OF_OUR_TABLE for literal in _literals_of(text)
    )


def _holds_a_write_into_our_table(text: str) -> bool:
    """Whether the text holds the TEXT of a write into the table as a literal.

    A write the migration runs may be written as text and carried around: in
    a variable, in a row, in the return of a function. The reader cannot
    follow it there — but it can see that the text is in the file, and a file
    that holds it is a file where the table's name is in play.

    Only a LITERAL counts: the seed insert is a statement, not data, and a
    statement the reader reads is not a write it cannot place.
    """
    return any(
        _spells_a_write_into_our_table(literal) for literal in _literals_of(text)
    )


def _pieces_in(text: str) -> set[str]:
    """The pieces of the table's name the text holds as literals of their own.

    A name may be assembled out of pieces: `'sync_' || v_suffix`, where the
    suffix is a variable the reader cannot follow. The pieces the reader
    cannot fold are exactly the ones where the name goes missing — so a whole
    literal that is a piece of the name is a piece of a name in play.

    The piece has to be the whole literal: a text that mentions the name among
    other words — a query the file writes — mentions it, and does not carry
    it.
    """
    return {
        literal.lower()
        for literal in _literals_of(text)
        if literal.lower() in _PIECES_OF_OUR_NAME
    }


def _holds_a_piece_of_the_name(text: str) -> bool:
    """Whether the text holds a piece of the table's name as a literal of its own."""
    return bool(_pieces_in(text))


def _span_at(
    spans: list[tuple[str, int, int, str]], index: int
) -> tuple[str, int, int, str] | None:
    """The span that covers `index`, if any."""
    for span in spans:
        if span[1] <= index < span[2]:
            return span
    return None


def _fold_literals(text: str) -> str:
    """The text with names assembled from pieces folded into the names they spell.

    A name assembled from pieces is the name it spells, and the reader has to
    see it as such — a name nobody can see is a write nobody can place. Two
    ways a name is assembled: pieces put together with `||`, and pieces handed
    to a call — `format('%s%s', 'sync_field_', 'allowlist')`, `concat('sync_',
    'field_allowlist')`. Both are folded into the name they spell.
    """
    return _fold_literals_and_limit(text)[0]


def _fold_literals_and_limit(text: str) -> tuple[str, bool]:
    """The folded text, and whether the reader gave up folding it.

    The reader folds a statement before it reads it, and a statement it did
    not finish folding is a statement it did not finish reading. The caller
    says so rather than passes over it.
    """
    folded, gave_up = _fold_calls(_fold_concatenations(text))
    return folded, gave_up


#: What may stand between two pieces of a concatenation: a cast, and the
#: `||` that joins them. A prefix belongs to the piece that follows it, and
#: a cast to the piece that precedes it, and neither breaks the chain.
_BETWEEN_PIECES = re.compile(
    r"\s*(?:::\s*[A-Za-z_][A-Za-z_0-9_]*(?:\s*\[\s*\])?)?\s*\|\|\s*(?:[Ee]|U&|B)?\s*"
)


def _piece_text(text: str, span: tuple[str, int, int, str]) -> str | None:
    """The text a literal piece holds, or None when it is not a literal.

    A piece is a literal in any of PostgreSQL's spellings: `'…'`,
    `E'…'`, `U&'…'`, `B'…'`, `X'…'`, `N'…'` and `$$…$$`. The reader reads
    them all, because a name spelled in a spelling the reader cannot read
    is a name the reader cannot place.

    A constant that continues across a newline is one constant, and its
    parts are put together the way PostgreSQL puts them together.
    """
    readings = _constant_readings(text, span)
    return readings[0] if readings else None


def _constant_value(prefix: str, parts: list[str]) -> str | None:
    """The text the constant holds, or None when the reader cannot read it.

    The parts of a constant that continues across a newline are joined the
    way PostgreSQL joins them: the whitespace between them is dropped, and
    every part is read on its own — a doubled quote is a quote inside its
    part, and never a quote across the boundary between two parts.

    A bit string and a hex string hold BITS, and PostgreSQL stores them in a
    text column as those bits: `B'01000001'` is `01000001` there, and not
    the letter A. The bits are the text the server reads.
    """
    if prefix == "B":
        return _bit_digits("".join(parts))
    if prefix == "X":
        return _hex_bits("".join(parts))
    if prefix == "E":
        return "".join(_decode_escapes(part) for part in parts)
    if prefix == "U&":
        return "".join(_decode_unicode_escapes(part) for part in parts)
    return "".join(part.replace("''", "'") for part in parts)


def _constant_bytes(prefix: str, parts: list[str]) -> str | None:
    """The bytes a bit or hex constant spells, or None when it spells none.

    A hex string spells four bits with every digit, and eight bits make a
    byte — so a hex string of an odd number of digits spells no whole byte,
    and the reader does not invent one.
    """
    if prefix == "B":
        bits = _bit_digits("".join(parts))
    elif prefix == "X":
        bits = _hex_bits("".join(parts))
    else:
        return None
    return _bits_to_bytes(bits) if bits is not None else None


def _constant_readings(text: str, span: tuple[str, int, int, str]) -> list[str]:
    """The texts a literal holds — the text the server reads, and the name it
    spells.

    A bit string and a hex string hold bits, and the first reading is those
    bits: that is what PostgreSQL stores in a text column. A hex string spells
    BYTES as well, and a file that writes a name in hex writes those bytes —
    so the bytes are a second reading of the same constant, and a name
    spelled in hex is a name the reader sees.
    """
    kind, start, _end, content = span
    if kind == "dollar":
        body = _dollar_body(content)
        return [] if body is None else [body]
    prefix = _prefix_of(text, start)
    _end, parts = _constant_parts(content, 0, prefix == "E")
    value = _constant_value(prefix, parts)
    if value is None:
        return []
    out = [value]
    spelled = _constant_bytes(prefix, parts)
    if spelled is not None and spelled != value:
        out.append(spelled)
    return out


def _fold_concatenations(text: str) -> str:
    """The text with runs of concatenated literals folded into one literal."""
    spans = [span for span in _spans(text) if span[0] in ("string", "dollar")]
    if not spans:
        return text
    out: list[str] = []
    cursor = 0
    index = 0
    while index < len(spans):
        _kind, start, end, _content = spans[index]
        pieces: list[str] = []
        following = index
        while following < len(spans):
            piece = _piece_text(text, spans[following])
            if piece is None:
                break
            pieces.append(piece)
            end = spans[following][2]
            following += 1
            if following >= len(spans):
                break
            between = text[end : spans[following][1]]
            if not _BETWEEN_PIECES.fullmatch(between):
                break
        if len(pieces) > 1:
            out.append(text[cursor:start])
            # The pieces' quotes are escaped again: the folded literal is a
            # literal, and a literal that holds a bare quote ends at it.
            out.append("'" + "".join(pieces).replace("'", "''") + "'")
            cursor = end
        index = following if len(pieces) > 1 else index + 1
    out.append(text[cursor:])
    return "".join(out)


#: The calls that build a name out of pieces.
_FOLDERS = ("format", "concat_ws", "concat")

#: A name — a variable the reader cannot follow.
_VARIABLE = re.compile(
    r"(?:[A-Za-z_][A-Za-z_0-9$]*)(?:\s*\.\s*(?:[A-Za-z_][A-Za-z_0-9$]*))*"
)

#: The words a table's name stands after. The parenthesis that follows is
#: the table's column list, not a call: `insert into sync_field_allowlist
#: (entity_type, field)` is a table and its columns.
_TABLE_POSITION = re.compile(
    r"\b(?:into|from|update|join|table|only|copy|merge|references)\s+$",
    re.IGNORECASE,
)


def _is_a_variable(argument: str) -> bool:
    """Whether the argument is a name — a variable the reader cannot follow.

    A parenthesised expression is stripped first: `my_exec((v_sql))` hands the
    variable over as surely as `my_exec(v_sql)` does.
    """
    text = argument.strip()
    while text.startswith("(") and text.endswith(")"):
        text = text[1:-1].strip()
    return _VARIABLE.fullmatch(text) is not None

#: How many times the reader folds a statement before it gives up. A
#: statement with more nested calls than this is not a thing a migration
#: holds; and a call the reader did not fold is a call the reader could not
#: evaluate, which the reader reports when the text names the table.
_FOLD_ROUNDS = 64


def _fold_calls(text: str) -> tuple[str, bool]:
    """The text with calls whose arguments are all literals folded into one literal.

    A call whose arguments are not all literals is left alone: the reader
    cannot know what such a call spells, and a name it made up would be a
    name it could not vouch for.

    The second value says whether the reader gave up: a statement with more
    nested calls than the reader folds is a statement the reader did not
    finish reading, and the caller says so rather than passes over it.
    """
    for _ in range(_FOLD_ROUNDS):
        folded = _fold_one_call(text)
        if folded is None:
            return text, False
        text = folded
    return text, True


def _fold_one_call(text: str) -> str | None:
    """The text with one folding call replaced by the literal it builds."""
    mask = _mask(text, _spans(text))
    for match in re.finditer(
        r"\b(" + "|".join(_FOLDERS) + r")\s*\(", mask, re.IGNORECASE
    ):
        open_index = match.end() - 1
        close = _matching_paren(mask, open_index, len(mask))
        if close is None:
            continue
        args = _split_top_level(
            text[open_index + 1 : close], mask[open_index + 1 : close]
        )
        built = _build_call(match.group(1).lower(), args)
        if built is None:
            continue
        return text[: match.start()] + "'" + built.replace("'", "''") + "'" + text[close + 1 :]
    return None


def _build_call(name: str, args: list[str]) -> str | None:
    """The text a folding call builds, or None when it does not build one.

    Every argument has to be a literal: a call with an argument the reader
    cannot read builds a name the reader cannot know.
    """
    values: list[str] = []
    for arg in args:
        value = _literal_text(arg)
        if value is None:
            return None
        values.append(value)
    if name == "concat":
        return "".join(values)
    if name == "concat_ws":
        if not values:
            return None
        return values[0].join(values[1:])
    if not values:
        return None
    return _apply_format(values[0], values[1:])


def _literal_text(raw: str) -> str | None:
    """The text a literal holds, or None when it is not one.

    Any spelling counts: `'…'`, `E'…'`, `U&'…'`, `B'…'`, `X'…'`,
    `N'…'` and `$$…$$`. A call whose argument is written in a spelling the
    reader cannot read builds a name the reader cannot know.
    """
    text = raw.strip()
    spans = _spans(text)
    if not spans:
        return None
    span = spans[0]
    kind, start, _end, _content = span
    if kind not in ("string", "dollar"):
        return None
    # Only a prefix may stand before the literal — `E'…'`, `U&'…'`, `B'…'`,
    # `X'…'`, `N'…'` — and the prefix is part of the spelling, so it is read.
    if text[:start].strip().lower() not in ("", "e", "u&", "b", "x", "n"):
        return None
    return _piece_text(text, span)


def _apply_format(template: str, args: list[str]) -> str | None:
    """The text `format` builds from a template and its arguments.

    The placeholders PostgreSQL knows: `%s`, `%I`, `%L`, the numbered forms
    `%1$s`, and `%%` for a percent sign. A placeholder the reader does not
    know, or one with no argument behind it, builds nothing the reader can
    vouch for — and a name the reader cannot see is a write nobody can place.
    """
    out: list[str] = []
    index = 0
    position = 0
    while index < len(template):
        char = template[index]
        if char != "%":
            out.append(char)
            index += 1
            continue
        if template[index + 1 : index + 2] == "%":
            out.append("%")
            index += 2
            continue
        cursor = index + 1
        digits = ""
        while cursor < len(template) and template[cursor].isdigit():
            digits += template[cursor]
            cursor += 1
        if template[cursor : cursor + 1] == "$":
            cursor += 1
        if template[cursor : cursor + 1] not in ("s", "I", "L"):
            return None
        cursor += 1
        if digits:
            number = int(digits) - 1
        else:
            number = position
            position += 1
        if number < 0 or number >= len(args):
            return None
        out.append(args[number])
        index = cursor
    return "".join(out)


def _without_literal_prefix(before: str) -> str:
    """The text in front of a block, with the block's literal prefix taken off.

    `execute E'…'` — the `E` stands between the keyword and the quote, and the
    block is a statement the migration runs all the same. A prefix that is
    part of a word is not a prefix: the `e` of `execute` is a letter of the
    keyword, and the `E` of `someName` is a letter of the name.
    """
    stripped = before.rstrip()
    match = re.search(r"(?:[EeBbNnXx]|[Uu]&)$", stripped)
    if match is None:
        return stripped
    head = stripped[: match.start()]
    if head and (head[-1].isalnum() or head[-1] == "_"):
        return stripped
    return head.rstrip()


#: PostgreSQL's grammar for a block that runs statements:
#: `DO [ LANGUAGE lang_name ] code`. The language clause stands between the
#: keyword and the code, and the code is a body all the same.
_DO_BODY = re.compile(
    r"\bdo(?:\s+language\s+(?:'[^']*'|[A-Za-z_][A-Za-z_0-9_]*"
    r"(?:\s*\.\s*[A-Za-z_][A-Za-z_0-9_]*)?))?\s*$",
    re.IGNORECASE,
)


def _text_position(before: str) -> str:
    """What a block of text is, by the place it stands in.

    `before` is the text that stands in front of the block, within the
    statement the block belongs to.

    A BODY runs: `as $fn$ ... $fn$` is a function's body, `do $$ ... $$` is a
    block of statements, `do '...'` is the same in a string. A STATEMENT TO
    RUN is written where a statement may be written: `execute '...'`,
    `prepare x as '...'`, `execute format('...')`. An ALIAS is a name:
    `select id as '...'` names the column, and a name is nothing a migration
    runs. A VALUE is a value, whatever it says — and a dollar-quoted literal
    is as much a value as a quoted one.

    An `as` is a body only where a body may be written — after `function`,
    `procedure` or `prepare`. Everywhere else it is an alias, and an alias
    holding the words of an insert is a column's name, not a row.
    """
    before = _without_literal_prefix(before)
    if _DO_BODY.search(before):
        return "body"
    if re.search(r"\bas$", before, re.IGNORECASE) and _declares_a_body(before):
        return "body"
    if re.search(r"\b(execute|prepare)$", before, re.IGNORECASE):
        return "executable"
    call = re.search(r"\bformat\s*\($", before, re.IGNORECASE)
    if call is not None and re.search(
        r"\bexecute$", before[: call.start()].rstrip(), re.IGNORECASE
    ):
        # `execute format('...')` runs the text the format builds. A format
        # call that nothing runs is a value: a column that holds the words of
        # an insert is a column that holds words.
        return "executable"
    if re.search(r"\bas$", before, re.IGNORECASE):
        return "alias"
    return "value"


def _declares_a_body(before: str) -> bool:
    """Whether the text in front of an `as` declares something with a body.

    `create function ... as $$ ... $$`, `create procedure ... as '...'`,
    `prepare x as '...'` — the three places a block of text is a body. An `as`
    that follows none of them is an alias.
    """
    return re.search(r"\b(function|procedure|prepare)\b", before, re.IGNORECASE) is not None


def _drops_our_schema(statement: str) -> bool:
    """Whether a statement drops the schema the table lives in.

    A dropped schema takes the table with it. The reader reports it rather
    than passing over it.
    """
    match = re.search(
        r"\bdrop\s+schema\s+(?:if\s+exists\s+)?(\"[^\"]+\"|[A-Za-z_][A-Za-z_0-9$]*)",
        statement,
        re.IGNORECASE,
    )
    if match is None:
        return False
    name = match.group(1)
    if name.startswith('"'):
        name = name[1:-1]
    return name.lower() == _SCHEMA


def _selects_into_our_table(statement: str) -> bool:
    """Whether a statement makes the table out of a query.

    `select ... into sync_field_allowlist` is the third way PostgreSQL makes
    a table, and it makes it with whatever rows the query returns.
    """
    match = re.search(
        r"\bselect\b([^;]*?)\binto\s+(?:only\s+)?(?:temporary\s+|temp\s+|unlogged\s+)?"
        r"(" + _NAME + r")",
        statement,
        re.IGNORECASE,
    )
    if match is None:
        return False
    # The `into` of an insert is not the `into` of a select.
    if re.search(r"\binsert\b", match.group(1), re.IGNORECASE):
        return False
    return _is_our_table(_table_key(match.group(2)))


def _declares_another_default(statement: str) -> bool:
    """Whether a statement declares another default for the flag column.

    The reader takes the column's own default for a row that does not name
    the flag — `writable boolean not null default true`. A table declared
    with another default changes what such a row means, and the reader has
    to be told.
    """
    match = re.search(
        r"\bcreate\s+(?:or\s+replace\s+)?(?:temporary\s+|temp\s+|unlogged\s+)?"
        r"table\s+(?:if\s+not\s+exists\s+)?(?:only\s+)?(" + _NAME + r")",
        statement,
        re.IGNORECASE,
    )
    if match is None or not _is_our_table(_table_key(match.group(1))):
        return False
    column = re.search(
        r"\bwritable\b[^,)]*?\bdefault\s+([^,)]+)", statement, re.IGNORECASE
    )
    if column is None:
        return False
    default = column.group(1).strip()
    default = re.sub(r"::[A-Za-z_][A-Za-z_0-9$]*", "", default)
    default = re.sub(r"\bnot\s+null\b", "", default, flags=re.IGNORECASE)
    default = default.strip().strip("'\"").strip().lower()
    return default not in ("true", "t")


def _changes_our_column_default(statement: str) -> bool:
    """Whether a statement changes the default of one of the table's columns.

    The reader takes the column's default for a row that does not name the
    flag — `writable boolean not null default true`. An ALTER that changes
    that default changes what such a row means, and the reader has to be
    told.
    """
    match = re.search(
        r"\balter\s+table\s+(?:only\s+)?(" + _NAME + r")"
        r"[^;]*?\balter\s+column\b[^;]*?\b(?:set\s+default|drop\s+default)\b",
        statement,
        re.IGNORECASE,
    )
    return match is not None and _is_our_table(_table_key(match.group(1)))


def _column_name(name: str) -> str:
    """The column a name refers to: unquoted and folded."""
    name = name.strip()
    if len(name) >= 2 and name.startswith('"') and name.endswith('"'):
        name = name[1:-1]
    return name.lower()


def _dollar_body(content: str) -> str:
    """The text a dollar-quoted block holds."""
    match = re.match(r"\$([A-Za-z_][A-Za-z_0-9]*)?\$", content)
    if match is None:
        return ""
    tag = match.group(0)
    return content[len(tag) : len(content) - len(tag)] if len(content) >= 2 * len(tag) else ""


def _string_body(content: str) -> str:
    """The text a string literal holds, read as text.

    A literal the reader cannot read is read as text all the same: the
    bodies of its parts are put together the way PostgreSQL puts them
    together, and a reader that reads less than it can sees less than there
    is.
    """
    if len(content) >= 2 and content.startswith("'") and content.endswith("'"):
        _end, parts = _constant_parts(content, 0, False)
        return "".join(part.replace("''", "'") for part in parts)
    return content


#: The words that open a block. A body that ends with one is a body whose
#: block was never closed — `do $$ begin … ; do $$` — and the migration does
#: not apply. The reader reads the body as the migration runs it, so it says
#: so rather than passes the body over.
_OPENING_BLOCK = re.compile(r"\b(do|begin|then|else|loop)\s*$", re.IGNORECASE)


def _opens_a_block(body: str) -> bool:
    """Whether the body ends where a block may not end."""
    text = _drop_comments(body, _spans(body)).rstrip()
    return _OPENING_BLOCK.search(text) is not None


#: The escapes an E-string knows, and what they spell.
#:
#: These are the escapes of the PostgreSQL the deployment runs — version 15,
#: which is what `supabase/postgres:15.8.1.085` is. `\v` is not among them:
#: the vertical tab arrived with PostgreSQL 17, and in 15 `E'\v'` holds the
#: letter `v`. The reader follows the server it reads for: a name read the
#: way a newer server would read it is a name the deployed server never sees.
_ESCAPES = {
    "b": "\b",
    "f": "\f",
    "n": "\n",
    "r": "\r",
    "t": "\t",
    "\\": "\\",
    "'": "'",
    '"': '"',
}


def _decode_escapes(text: str) -> str:
    """The text an E-string holds.

    PostgreSQL's E-strings spell the escapes of a language: `\\n` is a line
    feed, `\\'` is a quote, `\\x41` is a byte, `\\101` an octal byte.

    A doubled quote is a quote as well — `E'a''b'` holds `a'b` — and it is
    read in the same walk as the escapes, because the two must not be told
    apart by accident: a doubled quote in front of a backslash is a quote
    and an escape, and not an escape of a quote.
    """
    out: list[str] = []
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        if char == "'" and index + 1 < size and text[index + 1] == "'":
            out.append("'")
            index += 2
            continue
        if char != "\\" or index + 1 >= size:
            out.append(char)
            index += 1
            continue
        nxt = text[index + 1]
        if nxt in _ESCAPES:
            out.append(_ESCAPES[nxt])
            index += 2
            continue
        if nxt == "x":
            digits = ""
            cursor = index + 2
            while cursor < size and len(digits) < 2 and text[cursor] in "0123456789abcdefABCDEF":
                digits += text[cursor]
                cursor += 1
            if digits:
                out.append(chr(int(digits, 16)))
                index = cursor
                continue
        if nxt in "01234567":
            digits = ""
            cursor = index + 1
            while cursor < size and len(digits) < 3 and text[cursor] in "01234567":
                digits += text[cursor]
                cursor += 1
            out.append(chr(int(digits, 8)))
            index = cursor
            continue
        if nxt == "u" or nxt == "U":
            width = 4 if nxt == "u" else 8
            digits = text[index + 2 : index + 2 + width]
            if len(digits) == width and all(d in "0123456789abcdefABCDEF" for d in digits):
                out.append(chr(int(digits, 16)))
                index += 2 + width
                continue
        # An escape the reader does not know: PostgreSQL drops the backslash
        # and takes the character itself — `E'\q'` is `q`, `E'sync\_field'` is
        # `sync_field` — so the reader reads the string as the database will
        # store it. A name read any other way is a name the database never
        # holds, and a row the reader reports is a row the server will not
        # have.
        out.append(nxt)
        index += 2
        continue
    return "".join(out)


def _decode_unicode_escapes(text: str) -> str:
    """The text a `U&'...'` string holds: `\\0041` is the letter A.

    A doubled quote is a quote here as well, and it is read in the same walk
    as the unicode escapes, because the two must not be told apart by
    accident.
    """
    out: list[str] = []
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        if char == "'" and index + 1 < size and text[index + 1] == "'":
            out.append("'")
            index += 2
            continue
        if char != "\\" or index + 1 >= size:
            out.append(char)
            index += 1
            continue
        cursor = index + 1
        if text[cursor : cursor + 1] == "+":
            digits = text[cursor + 1 : cursor + 7]
            if len(digits) == 6 and all(d in "0123456789abcdefABCDEF" for d in digits):
                out.append(chr(int(digits, 16)))
                index = cursor + 7
                continue
            out.append(char)
            index += 1
            continue
        digits = text[cursor : cursor + 4]
        if len(digits) == 4 and all(d in "0123456789abcdefABCDEF" for d in digits):
            out.append(chr(int(digits, 16)))
            index = cursor + 4
            continue
        out.append(char)
        index += 1
    return "".join(out)


def _bit_digits(text: str) -> str | None:
    """The bits a `B'...'` bit string holds, or None when it does not.

    PostgreSQL reads a bit string as bits, and it stores it in a text column
    as those bits: `B'01000001'` is `01000001` there, and not the letter A.
    """
    if any(char not in "01" for char in text):
        return None
    return text


def _hex_bits(text: str) -> str | None:
    """The bits a `X'...'` hex string holds, or None when it does not.

    A hex string spells four bits with every digit, and PostgreSQL stores it
    in a text column as those bits: `X'41'` is `01000001` there.
    """
    if any(char not in "0123456789abcdefABCDEF" for char in text):
        return None
    return "".join(f"{int(digit, 16):04b}" for digit in text)


def _bits_to_bytes(bits: str) -> str | None:
    """The bytes a run of bits spells, or None when it spells no whole byte.

    Eight bits make a byte, and a run of bits whose length is not a whole
    number of bytes spells no byte at all: the reader does not invent one.
    """
    if len(bits) % 8:
        return None
    return "".join(
        chr(int(bits[index : index + 8], 2)) for index in range(0, len(bits), 8)
    )


def _literal(raw: str) -> tuple[str, object] | None:
    """The kind and the value of a literal, or None when it is not one.

    The kinds are `string` and `bool`. PostgreSQL spells a boolean four ways
    unquoted (`true`, `false`) — `t`, `f`, `1` and `0` are a column
    reference and an integer — and twelve words quoted (`true`, `false`, `t`,
    `f`, `y`, `n`, `yes`, `no`, `on`, `off`, `1`, `0`) with their unique
    prefixes; the reader knows them all, because a flag it cannot read is a
    row it cannot vouch for.

    The strings come in six spellings — `'...'`, `E'...'`, `U&'...'`,
    `B'...'`, `X'...'` and `N'...'` — and the reader reads them all, because a
    legal row the reader cannot read is a legal row that blocks the gate. A
    constant that continues across a newline is one constant, and the reader
    reads it as one: PostgreSQL drops the whitespace between the parts.
    """
    text = raw.strip()
    spans = _spans(text)
    span = spans[0] if spans else None
    if (
        span is not None
        and span[0] == "string"
        and span[2] == len(text)
        and text[: span[1]].lower() in ("", "e", "u&", "b", "n", "x")
    ):
        value = _piece_text(text, span)
        if value is None:
            return None
        return "string", value
    tag = re.match(r"\$([A-Za-z_][A-Za-z_0-9]*)?\$", text)
    if tag is not None and text.endswith(tag.group(0)) and len(text) >= 2 * len(tag.group(0)):
        return "string", text[len(tag.group(0)) : -len(tag.group(0))]
    lowered = text.lower()
    # PostgreSQL spells an unquoted boolean `true` or `false` and nothing
    # else: `t`, `f`, `1` and `0` are a column reference and an integer.
    if lowered in ("true", "false"):
        return "bool", lowered == "true"
    return None


def _boolean_from_text(word: str) -> bool | None:
    """The boolean a text spells, if it spells one.

    PostgreSQL takes twelve words for a boolean — `true`, `t`, `y`, `yes`,
    `on`, `1` and their opposites — and any unique prefix of `true` and
    `false`: `tr`, `tru`, `fa`, `fal`, `fals`. The reader knows the words and
    those prefixes; a prefix it does not know is reported, not guessed at.
    """
    lowered = word.strip().lower()
    if lowered in _TRUE_WORDS:
        return True
    if lowered in _FALSE_WORDS:
        return False
    if not lowered:
        return None
    matches = [name for name in ("true", "false") if name.startswith(lowered)]
    if len(matches) == 1:
        return matches[0] == "true"
    return None


def _split_cast(raw: str, masked: str) -> tuple[str, str | None]:
    """The value and the cast that is applied to it."""
    depth = 0
    cut = -1
    for index, char in enumerate(masked):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif (
            char == ":"
            and index + 1 < len(masked)
            and masked[index + 1] == ":"
            and depth == 0
        ):
            cut = index
    if cut < 0:
        return raw, None
    return raw[:cut].strip(), raw[cut + 2 :].strip()


def _interpret(item: str) -> tuple[str, object] | None:
    """The kind and the value of one item of a row, or None when it is not one.

    An item is a value the row carries: a name, a flag, a name with a cast, a
    name in parentheses, a name with a comment in the middle of it. An item
    the reader cannot read is a row it cannot vouch for, and it says so.
    """
    text = _drop_comments(item, _spans(item)).strip()
    # A value written as a concatenation of literals is the value it spells:
    # `'task' || ''` is `task`, and reading it as anything else is a row the
    # reader cannot make sense of for no reason.
    text = _fold_literals(text)
    mask = _mask(text, _spans(text))

    # A value in parentheses is the value it holds. The mask and the text are
    # cut by the SAME indices: the mask has the literals blanked to spaces,
    # so stripping them separately would leave the two out of step.
    while text.startswith("(") and text.endswith(")"):
        close = _matching_paren(mask, 0, len(mask))
        if close != len(text) - 1:
            break
        text = text[1:-1]
        mask = mask[1:-1]
        start = 0
        while start < len(text) and text[start].isspace():
            start += 1
        end = len(text)
        while end > start and text[end - 1].isspace():
            end -= 1
        text = text[start:end]
        mask = mask[start:end]

    base, cast = _split_cast(text, mask)
    value = _literal(base)
    if value is None:
        return None
    kind, payload = value

    if cast is None:
        return kind, payload
    target = cast.strip().lower()
    if target in _BOOLEAN_CASTS:
        if kind == "bool":
            return "bool", payload
        flag = _boolean_from_text(str(payload))
        if flag is None:
            return None
        return "bool", flag
    if target in _TEXT_CASTS:
        if kind == "string":
            return "string", payload
        if kind == "bool":
            return "string", "true" if payload else "false"
        return None
    return None


class _Reader:
    """Reads the rows of the field table out of SQL.

    The reader reports every row it cannot read. It does not pass over one:
    a row the reader did not see is a field nobody sends, which is the
    direction the gate exists for.
    """

    def __init__(self) -> None:
        self.rows: list[tuple[str, str, bool, str]] = []
        self.problems: list[tuple[str, str]] = []
        #: Whether the file being read holds the table's name as a literal of
        #: its own — as data the file hands around.
        self._file_names = False
        #: Whether the file holds the TEXT of a write into the table as a
        #: literal — data the file carries around.
        self._file_writes = False
        #: The pieces of the table's name the file holds as literals of their
        #: own.
        self._file_pieces: set[str] = set()

    def read_file(self, path: str) -> None:
        """Read one file.

        A file that holds the table's name as a literal of its own — as data
        it hands around — says that the name is in play in the whole file: a
        write in another statement of the same file may take it from that
        data, and the reader cannot follow it there. A file that merely
        *names* the table — in DDL, in a privilege, in a read — says nothing
        of the kind, and the reader takes the statement's own scope for the
        name. A file that holds a *piece* of the name as a literal of its own
        says the same thing: the name may be assembled out of pieces, and the
        piece is data the file hands around.
        """
        with open(path) as f:
            content = f.read()
        file_scope_raw = _drop_comments(content, _spans(content))
        file_scope = _fold_literals(file_scope_raw)
        self._file_names = _holds_the_name_whole(file_scope_raw) or (
            _holds_the_name_whole(file_scope)
        )
        #: Whether the file holds the TEXT of a write into the table as a
        #: literal — data the file carries around. A write the migration runs
        #: may be written as text and carried: in a variable, in a row, in the
        #: return of a function. The reader cannot follow it there, so a file
        #: that holds such a text is a file where the table's name is in
        #: play.
        self._file_writes = _holds_a_write_into_our_table(
            file_scope_raw
        ) or _holds_a_write_into_our_table(file_scope)
        #: The pieces of the table's name the file holds as literals of their
        #: own. A name may be assembled out of them at run time.
        self._file_pieces = _pieces_in(file_scope_raw) | _pieces_in(file_scope)
        self.read(content, os.path.basename(path))

    def read(
        self,
        text: str,
        origin: str,
        is_body: bool = False,
        outer_scope: str = "",
        outer_scope_raw: str = "",
    ) -> None:
        """Read every statement of `text`.

        A body is a scope of its own: a name held in a variable in one
        statement of a body is a name the whole body can write with. The
        scope is kept twice — folded and raw — because the folding is what
        makes a name assembled from pieces read as the name it spells, and
        the raw text is where the pieces are still pieces.

        A body is also a scope INSIDE another: a nested body sees the scope
        it is nested in. A name the enclosing body holds is a name the nested
        body can write with, and a write text the enclosing body holds is a
        write text the nested body may run — so the scope of a body is its
        own text together with the scope it stands in.
        """
        spans = _spans(text)
        masked = _mask(text, spans)
        body_scope_raw = outer_scope_raw
        if is_body:
            body_scope_raw = _drop_comments(text, spans) + "\n" + outer_scope_raw
        body_scope = _fold_literals(body_scope_raw)
        for start, end in _statement_spans(masked):
            raw_statement = _drop_comments(text[start:end], _spans(text[start:end]))
            statement_scope_raw = body_scope_raw if body_scope_raw else raw_statement
            statement_scope = _fold_literals(statement_scope_raw)
            # A dollar-quoted body may hold statements of its own — a
            # function body's insert is a row the file holds. So may a string
            # that stands where a statement may be written: dynamic SQL is
            # written as text and run when the migration applies.
            rows_before = len(self.rows)
            inner_reads = 0
            # What the migration RUNS is what the statement means. The text it
            # runs may be assembled from pieces — `'insert into ' ||
            # 'sync_field_allowlist'` — and the reader has to read the text
            # the pieces spell, not the pieces: a row the file holds is a row
            # the file holds however it was written.
            folded, fold_gave_up = _fold_literals_and_limit(raw_statement)
            folded_spans = _spans(folded)
            folded_masked = _mask(folded, folded_spans)
            if fold_gave_up:
                # The reader folded the statement as far as it goes and there
                # is text left it did not read. A name may hide in it, and a
                # name the reader cannot see is a write nobody can place.
                self._problem(
                    origin,
                    "unreadable",
                    "a name assembled in more steps than the reader folds, so the "
                    "reader did not finish reading the statement",
                )
            for kind, inner_start, inner_end, content in folded_spans:
                position = _text_position(folded[:inner_start])
                if position in ("value", "alias"):
                    continue
                inner_reads += 1
                if kind == "dollar":
                    body = _dollar_body(content)
                    if body is not None and _opens_a_block(body):
                        # The block was never closed: the body ends where a
                        # block may not end. The migration does not apply, so
                        # the rows it holds are not rows the server will have
                        # — and a reader that cannot read it says so.
                        self._problem(
                            origin,
                            "unreadable",
                            "the body ends in the middle of a block, so the migration "
                            "does not apply",
                        )
                    self.read(
                        body,
                        origin,
                        is_body=(position == "body"),
                        outer_scope=statement_scope,
                        outer_scope_raw=statement_scope_raw,
                    )
                else:
                    piece = _piece_text(
                        folded, (kind, inner_start, inner_end, content)
                    )
                    if piece is None:
                        # A literal the reader cannot read: a `B'…'` that
                        # spells no whole byte, so the migration does not
                        # apply. The text is read as text all the same — a
                        # reader that reads less than it can sees less than
                        # there is — and the literal is reported, because the
                        # migration that holds it does not run.
                        self._problem(
                            origin,
                            "unreadable",
                            "a literal the reader cannot read — a `B'…'` that spells "
                            "no whole byte — so the migration does not apply",
                        )
                        piece = _string_body(content)
                    self.read(
                        piece,
                        origin,
                        outer_scope=statement_scope,
                        outer_scope_raw=statement_scope_raw,
                    )
            self._handed_to_a_call(
                raw_statement, origin, statement_scope, statement_scope_raw
            )
            self._statement(
                folded,
                folded_masked,
                origin,
                len(self.rows) - rows_before,
                statement_scope,
                statement_scope_raw,
                inner_reads,
            )

    def _handed_to_a_call(
        self,
        statement: str,
        origin: str,
        scope: str = "",
        scope_raw: str = "",
    ) -> None:
        """Report a string that spells a write and is handed to a call.

        A call may run its argument — `dblink_exec('conn', 'insert into …')`
        runs the text it is given. The reader cannot know which calls run
        their arguments, so an argument that spells a write into the field
        table is reported: the file hands that text to something, and the
        something may run it.

        The argument is folded first: `'insert into ' || 'sync_field_allowlist'`
        is one argument, and it spells one write.

        An argument that spells a write into a *piece* of the name is reported
        too, when the scope holds the name or a piece of it: the text the
        migration runs is assembled at run time, and the piece may grow into
        the whole name.

        A string that is *stored* is not reported: a column holds words, and
        `values ('insert into …')` puts a word in a column.
        """
        for name, args in _calls_in(statement):
            if name.lower() in _TEXT_BUILDING_CALLS:
                continue
            for arg in args:
                folded = _fold_literals(arg)
                if _spells_a_write_into_our_table(folded):
                    self._problem(
                        origin,
                        "unreadable",
                        "a string that spells a write into the field table is handed to a "
                        "call, and the reader cannot know whether the call runs it",
                    )
                    return
                if not _is_a_variable(folded):
                    # The argument is not a name: it is a literal, or a list,
                    # or an expression the reader read. There is nothing here
                    # the reader cannot follow.
                    continue
                piece = _piece_target_of(folded)
                if piece is not None and self._may_be_assembled_into_our_name(
                    piece, scope, scope_raw
                ):
                    self._problem(
                        origin,
                        "unreadable",
                        "a string that spells a write into a piece of the field table's "
                        "name is handed to a call, and the name may be assembled out of "
                        "it at run time",
                    )
                    return
                if not (
                    _holds_a_write_into_our_table(scope)
                    or _holds_a_write_into_our_table(scope_raw)
                    or self._file_writes
                ):
                    continue
                # The argument is something the reader cannot read — a
                # variable, a function's return — and the scope holds the text
                # of a write into the table. The call may run it, and the
                # reader cannot rule that out.
                self._problem(
                    origin,
                    "unreadable",
                    "an argument the reader cannot read is handed to a call, in a scope "
                    "that holds the text of a write into the field table, and the "
                    "reader cannot know whether the call runs it",
                )
                return

    def _name_in_play(self, scope: str, scope_raw: str) -> bool:
        """Whether the table's name is in play where this write stands.

        Three ways it can be. The scope itself may name it — carried in a
        variable, read into one, or spelled as an identifier. Or the file may
        hold it as a literal of its own, as data the file hands around: a
        write in another statement of the same file may take the name from
        that data, and the reader cannot follow it there. Or the file may hold
        the TEXT of a write into the table — a write written as text and
        carried in a variable, in a row, in the return of a function — and a
        statement that runs something the reader cannot see may be running
        that text.
        """
        return (
            self._file_names or self._file_writes or _name_usage(scope, scope_raw)
        )

    def _may_be_assembled_into_our_name(
        self,
        target: str,
        scope: str,
        scope_raw: str,
    ) -> bool:
        """Whether the name may be assembled out of what this scope holds.

        A write into a piece of the name — `insert into sync_` — points at a
        table the text calls `sync_`. The text the migration runs is assembled
        at run time, so the piece may grow into the whole name: `'sync_' ||
        v_suffix`, with the suffix held in the scope. The reader cannot rule
        that out, and reports it.

        A piece on its own is not enough: `'field'` is a word, and a file may
        hold it without meaning the name. What makes it the name is another
        piece of it — the name spelled out of two halves — or the whole name
        held beside it.
        """
        if target not in _PIECES_OF_OUR_NAME:
            return False
        if _holds_the_name_whole(scope) or _holds_the_name_whole(scope_raw):
            return True
        held = _pieces_in(scope) | _pieces_in(scope_raw) | self._file_pieces
        held.discard(target)
        return bool(held)

    def _problem(self, origin: str, kind: str, detail: str) -> None:
        self.problems.append((kind, f"{origin}: {detail}"))

    def _statement(
        self,
        text: str,
        masked: str,
        origin: str,
        body_rows: int = 0,
        body_scope: str = "",
        body_scope_raw: str = "",
        inner_reads: int = 0,
    ) -> None:
        statement = masked
        # The comments are not part of what a statement does: a comment that
        # says "insert" or names the table says nothing about either.
        spans = _spans(text)
        raw = _drop_comments(text, spans)
        # A dollar-quoted block is a BODY: it runs later, and the reader
        # reads it on its own. A string that is a VALUE is a value, whatever
        # it says — only a string written where a statement may be written
        # is read. So the statement's own text loses its bodies and its
        # values, and keeps the statements it runs.
        own = _blank(raw, _spans(raw), ("dollar",))
        own = _blank(own, _spans(own), ("string",))
        own = _keep_runnable_text(raw, own)
        scope = body_scope if body_scope else _fold_literals(raw)
        scope_raw = body_scope_raw if body_scope_raw else raw
        for label, pattern in _UNMODELLED:
            for match in pattern.finditer(statement):
                if _is_our_table(_table_key(match.group("table"))):
                    self._problem(origin, "unmodelled", f"{label} of the field table")
        if _copy_writes_into_our_table(statement):
            self._problem(origin, "unmodelled", "a copy into the field table")
        if _drops_our_table(statement):
            self._problem(origin, "unmodelled", "a drop of the field table")
        if _creates_our_table_from_a_query(statement):
            self._problem(
                origin, "unmodelled", "a table created from a query over the field table"
            )
        if _drops_our_schema(statement):
            self._problem(origin, "unmodelled", "a drop of the schema the field table lives in")
        if _changes_our_column_default(statement):
            self._problem(
                origin, "unmodelled", "a change of the field table's own column default"
            )
        if _selects_into_our_table(statement):
            self._problem(
                origin, "unmodelled", "a table made out of a query over the field table"
            )
        if _declares_another_default(statement):
            self._problem(
                origin,
                "unmodelled",
                "a field table declared with another default for its flag",
            )
        if _ends_in_the_middle_of_an_expression(text):
            self._problem(
                origin,
                "unreadable",
                "the statement ends in the middle of an expression, so the "
                "migration does not apply",
            )
        if _moves_our_table(statement):
            self._problem(
                origin,
                "unmodelled",
                "the field table renamed or moved out of the schema the server "
                "looks it up in",
            )
        if _creates_a_view_over_our_table(statement):
            self._problem(
                origin,
                "unmodelled",
                "a view over the field table — a second door into it, and the "
                "reader does not follow views",
            )
        if _makes_a_child_of_our_table(statement):
            self._problem(
                origin,
                "unmodelled",
                "a table that inherits from the field table — a second door "
                "into it, and the reader does not follow children",
            )
        # A body the reader read is a statement it handled: the rows are in
        # the table, whether the body is a dollar-quoted block or a string.
        handled = body_rows > 0
        for match in _INSERT.finditer(statement):
            if not _is_our_table(_table_key(match.group("table"))):
                continue
            handled = self._insert(text, masked, match.end(), len(text), origin) or handled

        # A write verb whose target is written as a string cannot run: the
        # database does not accept a string where a table's name stands. The
        # file spells the name all the same, so the statement is reported —
        # the migration does not apply, and the rows it holds are not rows
        # the server will have.
        for target in _string_targets(text):
            target = target.lower()
            if target in _NAMES_OF_OUR_TABLE or (
                target in _PIECES_OF_OUR_NAME
                and self._may_be_assembled_into_our_name(target, scope, scope_raw)
            ):
                self._problem(
                    origin,
                    "unreadable",
                    "a write whose target is written as a string, which the database "
                    "does not accept, so the migration does not apply",
                )
                break

        # Where does a write in this statement point? A write that points at
        # the table is a row the gate has to have read; a write whose target
        # cannot be told, in a statement that names the table, is a row the
        # gate cannot rule out. Either way it is reported rather than passed
        # over — this is where the dynamic SQL of a migration lands, written
        # as text and run when the migration applies.
        #
        # A write whose target cannot be told is reported whatever else the
        # statement holds: the statement reading one row of the table says
        # nothing about where the other write points.
        for target in _WRITE_TARGET.finditer(own):
            key = _target_key(target.group("target"))
            if key is None:
                if self._name_in_play(scope, scope_raw):
                    self._problem(
                        origin,
                        "unreadable",
                        "a write whose target the gate cannot tell, in a scope where "
                        "the field table's name is used as a name",
                    )
            elif _is_our_table(key) and not handled:
                self._problem(
                    origin,
                    "unreadable",
                    "a write into the field table the gate did not read",
                )
            elif key in _PIECES_OF_OUR_NAME and self._may_be_assembled_into_our_name(
                key, scope, scope_raw
            ):
                # The target is a piece of the field table's name, and the
                # scope holds what the piece may be completed with. The write
                # may reach the field table, and the reader cannot rule that
                # out.
                self._problem(
                    origin,
                    "unreadable",
                    "a write into a piece of the field table's name, in a scope where "
                    "the name may be assembled out of it",
                )
        # A statement a migration runs through `execute` is a write whose
        # target the gate cannot tell: what it runs is text, and the text may
        # name any table. It is reported in the same scope as the other
        # unplaceable write — the scope where the field table's name is used
        # as a name — because that is where the dynamic SQL of a migration
        # lands. A statement whose text the reader did read is not reported
        # again: the text was read, and whatever it holds was reported there.
        if inner_reads == 0 and self._name_in_play(scope, scope_raw):
            for _ in _EXECUTE.finditer(own):
                self._problem(
                    origin,
                    "unreadable",
                    "a statement the migration runs whose target the gate cannot tell, "
                    "in a scope where the field table's name is used as a name",
                )
                break

    def _insert(
        self,
        text: str,
        masked: str,
        pos: int,
        end: int,
        origin: str,
    ) -> bool:
        """Read the rows an insert into the table holds.

        It says whether it handled the statement: an insert it cannot read is
        reported, and a reported insert is handled too.
        """
        # The upsert belongs to THIS insert: another table's upsert in the
        # same statement is another table's business.
        own = masked[pos:end]
        if _ON_CONFLICT.search(own) and _DO_UPDATE.search(own):
            self._problem(
                origin, "unmodelled", "an upsert that renames a row of the field table"
            )
            return True

        index = pos
        while index < end and masked[index].isspace():
            index += 1

        columns: list[str] | None = None
        if index < end and masked[index] == "(":
            close = _matching_paren(masked, index, end)
            if close is None:
                self._problem(
                    origin,
                    "unreadable",
                    "a row of the field table has an unreadable column list",
                )
                return True
            columns = [
                _column_name(part)
                for part in _split_top_level(text[index + 1 : close], masked[index + 1 : close])
            ]
            index = close + 1

        while index < end and masked[index].isspace():
            index += 1
        source = masked[index:end].lower()
        if not source.startswith("values"):
            self._problem(
                origin,
                "unreadable",
                "a row of the field table comes from a query the gate cannot read",
            )
            return True

        index += len("values")
        tuples: list[tuple[int, int]] = []
        while True:
            while index < end and (masked[index].isspace() or masked[index] == ","):
                index += 1
            if index >= end or masked[index] != "(":
                break
            close = _matching_paren(masked, index, end)
            if close is None:
                self._problem(
                    origin,
                    "unreadable",
                    "a row of the field table has an unreadable value list",
                )
                return True
            tuples.append((index + 1, close))
            index = close + 1
            while index < end and masked[index].isspace():
                index += 1
            if index < end and masked[index] == ",":
                index += 1

        if not tuples:
            self._problem(
                origin,
                "unreadable",
                "a row of the field table has no values the gate can read",
            )
            return True
        if columns is None:
            self._problem(
                origin,
                "unreadable",
                "a row of the field table does not name its columns, so the gate "
                "cannot tell which value is which",
            )
            return True

        for open_index, close_index in tuples:
            self._row(
                columns,
                text[open_index:close_index],
                masked[open_index:close_index],
                origin,
            )
        return True

    def _row(
        self,
        columns: list[str],
        raw: str,
        masked: str,
        origin: str,
    ) -> None:
        """Read one row of the table."""
        items = _split_top_level(raw, masked)
        if len(items) != len(columns):
            self._problem(
                origin,
                "unreadable",
                f"a row of the field table holds {len(items)} values and the "
                f"statement names {len(columns)} columns",
            )
            return
        values = dict(zip(columns, items))
        missing = [column for column in _COLUMNS if column != "writable" and column not in values]
        if missing:
            self._problem(
                origin,
                "unreadable",
                "a row of the field table does not name the type and the field it holds",
            )
            return

        entity_type = _interpret(values["entity_type"])
        field = _interpret(values["field"])
        if entity_type is None or entity_type[0] != "string":
            self._problem(
                origin, "unreadable", "a row of the field table has a type the gate cannot read"
            )
            return
        if field is None or field[0] != "string":
            self._problem(
                origin, "unreadable", "a row of the field table has a field the gate cannot read"
            )
            return

        if "writable" in values:
            writable = _interpret(values["writable"])
            if writable is None:
                self._problem(
                    origin,
                    "unreadable",
                    "a row of the field table has a flag the gate cannot read",
                )
                return
            kind, payload = writable
            if kind == "bool":
                flag = bool(payload)
            else:
                # PostgreSQL takes a boolean written as text: the column is
                # boolean, and 'yes' is a boolean.
                flag = _boolean_from_text(str(payload))
                if flag is None:
                    self._problem(
                        origin,
                        "unreadable",
                        "a row of the field table has a flag the gate cannot read",
                    )
                    return
                flag = bool(flag)
        else:
            # The column's own default: the table says `writable boolean not
            # null default true`, so a row that does not name the flag is a
            # row the server may write.
            flag = True

        self.rows.append((str(entity_type[1]), str(field[1]), flag, origin))


def read_table(
    paths: list[str],
) -> tuple[list[tuple[str, str, bool, str]], list[tuple[str, str]]]:
    """The rows the migrations hold for the field table, and what cannot be read.

    The problems come in two kinds: `unreadable` — a row the reader cannot
    make sense of — and `unmodelled` — an operation that changes the table in
    a way the reader does not model. Both are failures: a row the reader did
    not see is a field nobody sends.
    """
    reader = _Reader()
    for path in paths:
        reader.read_file(path)
    # The same write can be met twice — once in the statement that runs it and
    # once in the statement it runs — and the two say the same thing. The
    # report names problems, not occurrences: one problem, one line.
    return reader.rows, list(dict.fromkeys(reader.problems))


def _near_miss(entity_type: str, known: set[str]) -> str | None:
    """The client's type a row's type was probably meant to be, if any."""
    normalized = re.sub(r"[^a-z0-9]", "", entity_type.lower())
    for candidate in sorted(known):
        if candidate == entity_type:
            continue
        if re.sub(r"[^a-z0-9]", "", candidate.lower()) == normalized:
            return candidate
    return None


def _is_stale(committed: str, generated: str) -> bool:
    """Whether the committed seed differs from what the generator produces.

    The gate's own criterion, in one place, so that the self-test asserts the
    criterion the gate uses rather than a lookalike of it.
    """
    return committed != generated


@click.command()
@click.option(
    "--self-test",
    is_flag=True,
    help="Assert that the gate correctly fails when the seed is stale",
)
@click.pass_context
def cli(ctx: click.Context, self_test: bool) -> None:
    """Check that the sync field lists and the server's field table agree."""
    # The export comes first: it is the projection of the models' own field
    # names, and the gate compares the server's table with it. An export the
    # gate cannot read is reported before anything else runs — the generator
    # reads the same file, and its crash would say less than a named failure.
    try:
        derived = derived_lists(EXPORT_FILE)
    except _ExportUnreadable as error:
        click.echo(f"FAIL: the export of the field lists cannot be read — {error}")
        click.echo(f"  {EXPORT_FILE}")
        click.echo(
            "The export is the projection of the models' own field names, and the "
            "gate compares the server's table with it. An export the gate cannot "
            "read is a contract it cannot vouch for."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    # Read current committed file
    with open(OUTPUT_FILE) as f:
        before = f.read()

    probed: str | None = None
    if self_test:
        # Mutate the seed to make it stale: add a field that does not exist in the
        # generator's output. The gate must see the difference and fail.
        probed = before + "    --self-test-probe-field text,\n"
        with open(OUTPUT_FILE, "w") as f:
            f.write(probed)

    try:
        # Regenerate
        result = subprocess.run(
            ["python3", GEN_SCRIPT, ROOT],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            click.echo(f"FAIL: generator script exited {result.returncode}")
            click.echo(result.stderr, err=True)
            raise ctx.exit(VALIDATION_FAILURE)

        with open(OUTPUT_FILE) as f:
            after = f.read()
    finally:
        # Restore the original content whatever happened: the seed is a tracked
        # file, and a self-test that leaves its probe in it has dirtied the
        # tree it was checking.
        with open(OUTPUT_FILE, "w") as f:
            f.write(before)

    if self_test:
        # The probe is what the gate must call stale, and the gate's criterion
        # is the one that says so: the committed file differs from what the
        # generator produces. If the probe does not differ, the gate cannot
        # tell a stale seed from a current one, and a check that cannot fail is
        # a check that does not check.
        if not _is_stale(probed or "", after):
            click.echo(
                "FAIL: the self-test probe did not make the seed stale — the gate "
                "cannot tell a stale seed from a current one"
            )
            raise ctx.exit(VALIDATION_FAILURE)
        # And the committed seed itself must be current: a self-test that says
        # "the gate works" while the gate would fail on this very file is
        # telling the reader the opposite of the truth.
        if _is_stale(before, after):
            click.echo(
                "FAIL: the committed seed is stale — the gate fails on it, and the "
                "self-test must not report a working gate over a broken tree"
            )
            raise ctx.exit(VALIDATION_FAILURE)
        click.echo("PASS: the self-test probe makes the seed stale, and the gate sees it")
        raise ctx.exit(CLEAN)

    if _is_stale(before, after):
        click.echo("FAIL: sync_field_allowlist_seed.sql is stale — run:")
        click.echo(f"  python3 {GEN_SCRIPT} {ROOT}")
        click.echo("  then commit the change")
        raise ctx.exit(VALIDATION_FAILURE)

    # The server's table against the derived lists.
    paths = migrations()

    undated = undated_migrations(paths)
    if undated:
        click.echo("FAIL: a migration file carries no date in its name:")
        for name in undated:
            click.echo(f"  {name}")
        click.echo(
            "A migration is named YYYY-MM-DD-something.sql: the date says when it "
            "was written, and the deploy tooling reads the name to order the files. "
            "A file without one is a file the tooling cannot order — and a gate that "
            "skipped it would say nothing about the rows it holds."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    rows, problems = read_table(paths)
    unmodelled = [line for kind, line in problems if kind == "unmodelled"]
    unreadable = [line for kind, line in problems if kind == "unreadable"]
    if unmodelled:
        click.echo(
            "FAIL: a migration changes the field table in a way the gate does not model:"
        )
        for line in unmodelled:
            click.echo(f"  {line}")
        click.echo(
            "The gate reads the table's rows and cannot see a row that a statement "
            "renames, removes or merges. Such a statement is one the gate has to be "
            "taught before it merges."
        )
        raise ctx.exit(VALIDATION_FAILURE)
    if unreadable:
        click.echo(
            "FAIL: a migration holds a row of the field table the gate cannot read:"
        )
        for line in unreadable:
            click.echo(f"  {line}")
        click.echo(
            "The gate reads the table's rows so that a field nobody sends cannot slip "
            "in. What it cannot read it reports rather than passes over: a row it did "
            "not see is a row it cannot vouch for."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    client_types = set(derived)
    known_types = client_types | _SERVER_TYPES

    unknown_types: list[str] = []
    for entity_type, field, _writable, name in rows:
        if entity_type in known_types:
            continue
        near = _near_miss(entity_type, client_types)
        if near:
            unknown_types.append(f"{entity_type}.{field} ({name}) — did you mean '{near}'?")
        else:
            unknown_types.append(f"{entity_type}.{field} ({name})")

    unknown: list[str] = []
    refused: list[str] = []
    for entity_type, field, writable, name in rows:
        if entity_type not in client_types:
            # A row of a type the server has of its own is the server's own
            # business — its profile table is one.
            continue
        if writable:
            if field not in derived[entity_type]:
                unknown.append(f"{entity_type}.{field} ({name})")
        elif field in derived[entity_type]:
            # The server refuses to write a field the client expects it to
            # write: the client's write of that field is dropped, and the
            # table says the field may be written.
            refused.append(f"{entity_type}.{field} ({name})")

    # Two rows of the same field that disagree about the flag: the table
    # holds one row per type and field, so the one applied first is the one
    # the table keeps. Which one that is depends on the order the deploy
    # tooling applies the files in — which the gate does not read. The row
    # that refuses the field is still a failure, because a row that says the
    # server may not write a field the client sends is a row the server
    # honours.

    if unknown_types:
        click.echo(
            "FAIL: the server's field table has a row of a type neither the client "
            "nor the server has:"
        )
        for name in unknown_types:
            click.echo(f"  {name}")
        click.echo(
            "The server looks a type up by the name the client sends it under, so a "
            "row of a type it does not know is a row the server never reads — and a "
            "row whose type is one spelling away from the client's is a row that was "
            "meant for the client and missed."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if unknown:
        click.echo(
            "FAIL: the server's field table names fields outside the derived "
            "server list:"
        )
        for name in unknown:
            click.echo(f"  {name}")
        click.echo(
            "The derived server list is the client's own field names minus the four "
            "the server owns. A name outside it is a name the server may write and "
            "nothing ever writes — and the four the server owns are not in that list "
            "either, so a row naming one of them is this failure, not a duplicate of it."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if refused:
        click.echo(
            "FAIL: the server's field table refuses a field the client expects "
            "it to write:"
        )
        for name in refused:
            click.echo(f"  {name}")
        click.echo(
            "A row with the flag off says the server may not write that field, "
            "while the derived server list says it may: the client's write of it "
            "is dropped, and the table says otherwise."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    # A type the client syncs and the server has no row for: every document of
    # that type would be refused, and the fields would be "owed" one by one,
    # which reads like a migration debt rather than a wrong name.
    seeded_types = {entity_type for entity_type, _f, _w, _n in rows}
    missing_types = sorted(client_types - seeded_types)
    if missing_types:
        click.echo("FAIL: the server's field table has no row for a type the client syncs:")
        for name in missing_types:
            click.echo(f"  {name}")
        click.echo(
            "The server looks a type up by the name the client sends it under; a row "
            "under another spelling is a row the server never reads."
        )
        raise ctx.exit(VALIDATION_FAILURE)

    owed: list[str] = []
    for entity_type, fields in sorted(derived.items()):
        written = {
            field for e_type, field, writable, _n in rows
            if e_type == entity_type and writable
        }
        for field in sorted(fields - written):
            owed.append(f"{entity_type}.{field}")

    click.echo(
        "PASS: sync_field_allowlist_seed.sql is up to date with the derived field lists, "
        "and the server's field table names no field outside the derived server list"
    )
    if owed:
        click.echo(f"NOTE: the server has not been migrated for: {', '.join(owed)} (issue #1050)")
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
