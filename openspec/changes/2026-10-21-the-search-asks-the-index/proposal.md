# The search asks the index, and the index narrows the rows

Issue: — · Related: `docs/decisions/2026-10-21-the-search-asks-the-index.md`

## What

Make the search read the rows that carry the word, instead of reading every row
and looking at each one.

The search screen had two ways of finding a task by its text, and both read
everything:

1. The stored query asked for a substring anywhere in the title or the
   description. A leading wildcard cannot use an index, so every row of the
   table was read and every character of it compared — for every keystroke.
2. The search itself applied the free-text rule to a list that already held
   every task of the profile. The structured conditions of a query were pushed
   into the database; the free text was not, so it was a predicate over rows
   that had already been read.

The two paths also disagreed about what a match is: one matched a substring
anywhere in the text, the other matched a substring per term with the query's
connective. One query, two implementations of "contains", and no single place
that owned the answer.

Concretely:

1. The searchable kinds get a full-text index — tasks, notes, projects and tags
   — that holds the words of the searchable text and the rows each word occurs
   in. The text itself is not copied: the index reads the row from the table it
   belongs to.
2. A search asks the index for the candidates and applies the rule to them. The
   index narrows; the rule decides. A rule the index cannot answer — one that
   only says "not" — is answered the way it was before.
3. A term matches a *word* by its beginning, and the rule in memory says the
   same thing. The index stores words, so it can only find the beginning of
   one; a rule that said "contains" would disagree with the index that produced
   its candidates.
4. The upgrade creates the indexes and fills them, so the records that existed
   before it are found by the same search as the ones written after it.

## What is not in this change

- The query language does not change. The same queries mean the same things.
- The searchable text of a kind does not change. A note is still searched by its
  title; its body is still not searched, and therefore not indexed.
- Ranking does not change. The results are still ordered as they were.
- The stored queries that are not searches — a lookup by a fixed pattern — keep
  the pattern they had.

## The change in behaviour

A term matches a word by its beginning. "arch" finds "architecture"; "port" no
longer finds "airport". This is the price of an index of words, and it is
stated in the search grammar (F2a) rather than left to be discovered.

## Why now

The search is the one place in the app where the work grows with the amount of
data, and it grows on the keystroke path. The index removes that: the rows read
are the rows that carry the word.
