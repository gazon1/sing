---
title: "The index says where the word is, and the search draws it"
date: 2026-10-22
status: accepted
tags: [search, database, room, fts, ui]
---

# The index says where the word is, and the search draws it

## Context

The search shows *that* a record matched, not *where* it matched. A person
searching for "architecture" gets a list of tasks, and the task whose title
*is* "Architecture" looks exactly like the task whose description mentions it.
The index knows the difference: it holds the tokenizer, and it knows which
column of the record matched.

FTS4 can say where, not only that there is a match. The `offsets()` function
answers with the position of every matched token, in four integers each: the
column, the number of the term, the byte offset and the byte length.

## What was measured before designing this

Everything below was measured on SQLite 3.51.2, on an external-content FTS4
table — the shape this project uses.

**`snippet()` is broken in this build.** `snippet(t, 0, 5, 5, '…')` answers
`0architecture5 plan` — it echoes its own arguments into the text. The
seven-argument form, with start and end markers, is rejected outright: *"wrong
number of arguments to function snippet()"*. So `snippet()` cannot be the
primitive here, and a plan that starts from it starts from a broken function.

**`offsets()` works**, on a virtual table and on an external-content one, and
the offsets are the offsets in the *original* text — not in the tokenised text.
That is what makes them usable for drawing.

**The answer is four integers per matched token**, and the length is the length
of the token *in the original text* — not of the folded token the index stores.
A search for "cafe" finds "Café au lait" and reports five bytes, because "Café"
is five bytes in the text even though the index stores it folded as "cafe".

**The offsets are bytes, and a Kotlin string is not.** SQLite counts UTF-8
bytes; a Kotlin string counts UTF-16 code units. For ASCII the two agree, and
the difference is invisible until a title carries an accent — and then the
highlight lands on the wrong word, or on half of one.

## Idea

One answer, carried along the whole way.

The row the index returns carries the position of the match. The port carries
it with the record. The results carry it to the screen, and the screen draws
the range that falls in the text it is drawing.

## Decision

### The row carries the position

Every query in `FtsDao` answers with a row *and* the position, read from
`offsets()`:

```sql
SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
JOIN tasks_fts ON t.rowid = tasks_fts.docid
WHERE tasks_fts MATCH :match
```

The position travels as the raw answer of `offsets()` — a string of integers.
Reading it needs the text of the column, and only the reader has it, so the
conversion happens in `SearchTextIndexImpl`, which is the one place that holds
the title of a task, the description of a task, the title of a note and the
name of a project or a tag.

The queries did not grow: the same six queries answer with the position beside
the row, instead of a second set of queries that answer with the position
alone. Two sets of queries would be two doors to the same rows, and the two
answers could disagree.

### The port carries the match

`SearchTextIndex` answers with `IndexedRecord<T>` — the record, and the ranges
of the match:

```kotlin
data class IndexedRecord<T>(val record: T, val ranges: List<TextMatchRange>)
```

The alternative was a second method on the port that answers with the
positions. That is the same two doors, one level up: a caller that wants the
positions and a caller that does not would read two different queries, and the
row a caller shows and the range it draws could come from two different reads.

The callers that do not want the position — the filter by text, the link
suggestions — unwrap the record and ignore the ranges. That is a line of code,
not a second door.

### The bytes become characters in one place

`FtsOffsets` is the one place that reads the answer of `offsets()` and turns
it into ranges in the characters of the text. The conversion walks the text the
way SQLite wrote it: byte by byte, counting the characters it passes. A
character outside the Basic Multilingual Plane — an emoji — is four bytes and
two UTF-16 units, and the range it lands in has to count it as two.

A range is in the units of the string the screen is about to draw, so it lands
on the word and not next to it.

### The fake has no positions

The fake database has no index, so it has no tokenizer to read a position from,
and it answers with no ranges at all.

That could have been papered over with a rule in Kotlin that finds the word in
the text — the rule knows the term and the title, and it could say where the
word is. It is not done, because it would be a **second answer to a question
only the index can answer**: the index's answer is right by construction, and
the rule's answer is right by the rule's own idea of what a word is. For a
multi-word term the two can differ in position.

So the position is asserted where the index is — over a real SQLite — and the
fake answers the question it can answer: which records carry the word.

### The screen draws the range that falls in the text it draws

A record is searched by more than one surface: a task by its title and its
description, a note by its title, a project or a tag by its name. The screen
asks for the ranges of the column it is drawing, so **a record that matched in
its description is not highlighted in its title** — the title does not carry the
word, and highlighting it would be a lie about where the match is.

## Rationale

The alternative to the whole chain is to locate the word in Kotlin with the
rule: the rule knows the term and the title, and it is simpler — no offsets, no
bytes, no conversion. It is not the index's answer, and the index's answer is
the one that is right: the index says which column matched and where, and it
says it about the text it indexed.

The alternative to a second DAO was considered and rejected for the same reason
the semantic surface was: the queries would be duplicated, and a duplicated
query is a duplicated answer waiting to drift.

The alternative to reading the position in the reader was to read it in the
DAO: the DAO has the offsets and not the text, so it cannot. The conversion
needs the text of the column, and the text of the column is the row's text.

## Consequences

- A search result shows the matched word, in the title it was matched in.
- The port answers with the record and the position. The two callers that do
  not want the position unwrap the record.
- The rows of the index carry a column they did not carry before, and the
  `offsets()` answer is read in one place — `FtsOffsets`.
- The byte-to-character conversion is the part that can be got wrong, and it is
  invisible while the texts are ASCII. A test with an accented title is not
  optional, and there is one.
- The fake answers with no positions. A test that asserts a position runs
  against a real SQLite.
- A record that was not read through the index — one a broad scan read — has
  no position to draw, and is drawn as any other record.
- `snippet()` is not used, and should not be: it is broken in this build.

## Links

- ADR 2026-10-21: the search asks the index — `docs/decisions/2026-10-21-the-search-asks-the-index.md`
- ADR 2026-10-21: the index answers through one door — `docs/decisions/2026-10-21-the-index-answers-through-one-door.md`
- ADR 2026-10-22: the index answers the positive terms — `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`
- The position, read — `shared/src/commonMain/kotlin/com/singularity/todo/core/database/FtsOffsets.kt`
- The port — `shared/src/commonMain/kotlin/com/singularity/todo/core/search/SearchTextIndex.kt`
- The drawing — `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/text/HighlightedText.kt`
- The position, over a real SQLite — `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/FtsMatchOffsetsTest.kt`
- Issue — [#895](https://github.com/gazon1/sing/issues/895)
