---
title: "The search asks the index, and the index narrows the rows"
date: 2026-10-21
status: accepted
tags: [search, database, room, performance, fts]
---

# The search asks the index, and the index narrows the rows

## Context

The search screen had two ways of finding a task by its text, and both of them
read every row.

The first was the DAO query:

```sql
SELECT * FROM tasks
WHERE user_id = :userId AND archived_at IS NULL
  AND (title LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%')
```

A leading wildcard cannot use an index. SQLite reads every row of the table and
compares every character of every title and description, for every keystroke.

The second was the search use case itself: the resolver built a free-text rule
and applied it to a flow that already held *every* task of the profile. The
structured conditions of a query were pushed into SQL, but the free text was
not — it was a predicate over a list that had already been read. A profile with
ten thousand tasks paid for all ten thousand on every search, and the search
was the thing that ran while the user typed.

The two paths also disagreed about what a match is. `LIKE` matched a substring
anywhere in the text; the in-memory rule matched a substring too, but per term
and with the query's connective. One query, two implementations of "contains",
and no single place that owned the answer.

## Idea

Give the database an index of the words, and let the index do the narrowing.

SQLite has had full-text search since 3.7: an FTS table is an *inverted index* —
a dictionary of words and, for each word, the list of rows it occurs in. A
lookup is a dictionary hit, so it costs the same at ten rows and at a hundred
thousand.

Room supports the pattern directly: `@Fts4(contentEntity = …)` declares a
virtual table whose content lives in an ordinary table. The index stores the
postings and not the text, and Room installs the triggers that keep it in step
with the rows.

## Decision

**The searchable kinds get an external-content FTS4 index.** Four of them:
`tasks_fts` (title, description), `notes_fts` (title), `projects_fts` (name),
`tags_fts` (name). The columns are exactly the searchable text of the kind —
F3 of the search grammar — so the index of a field nothing searches is never
built. The queries that read the index live in one DAO (`FtsDao`), because the
index is one read model and four DAOs with a full-text method each would be
four places to keep the same join and the same `docid` rule in step.

**The `docid` of an index is the content table's `rowid`.** The id of a task
is a UUID and an FTS `docid` is an integer, so the queries join
`tasks.rowid = tasks_fts.docid`. The alternative — an integer surrogate key on
every searchable table — would put a second identity on the data and a
migration on every one of them.

**The tokenizer is `unicode61`.** The default `simple` tokenizer treats
letters outside ASCII as separators, which would make a Russian title
unsearchable. This app is used in Russian; the index has to tokenize Russian.

**The index narrows, the rule decides.** The search still applies the
free-text rule to the candidates in memory. The index answers positive
questions — "which rows carry this word" — and has no way of expressing "must
not carry", so a negated term is still the rule's business. The rule is what
decides; the index is what makes the candidate set small.

**A term matches a word by its beginning.** The index stores words, so it can
only find the beginning of one: "arch" finds "architecture", and "port" does
not find "airport". The in-memory rule was changed to the same rule, so the two
do not disagree — a record found by one is found by the other.

**The `MATCH` expression is built from tokens only.** A term is split into runs
of letters and digits and every token is emitted as `token*`. A person typing
into a search box cannot produce syntax, and a prefixed token is always a term
even when it happens to be a keyword (`not*` is the word "not", not the
operator).

**A rule the index cannot answer falls back to reading the rows.** A rule made
only of negated terms has nothing to look up. So has a rule that disjoins a
negated term with a positive one: the records that match by *not* carrying the
negated term are exactly the ones a positive lookup throws away.

**The upgrade creates the indexes and fills them.** `Migration42To43` is a
manual migration — an index is a virtual table, and no schema differ describes
one — and it runs `rebuild` over every content table, so the rows that existed
before the upgrade are in the index the moment it ends. An index that starts
empty would return fewer results than the database holds, and nobody would
report that.

## Rationale

The search was the one place in the app where the amount of work grew with the
amount of data, and it grew on the keystroke path. The index removes that: the
rows read are the rows that carry the word.

The external-content pattern is what makes the index affordable. A standalone
FTS table would hold a second copy of every title and description — the text of
a profile's tasks, doubled — and two copies are two things to keep in step. As
an external-content table the index holds postings only, and Room's triggers
keep it in step with the rows in the same transaction.

The word-prefix rule is the price, and it is the price the index charges
everyone. The alternative was a matcher that said "contains" while the index
said "word prefix" — two answers to one question, and which one a user saw
would depend on whether the row happened to reach the index. One rule, stated
once, is worth more than the substring.

The `unicode61` tokenizer is not a detail. The default `simple` tokenizer
would have made every Russian title invisible to the search, and the failure
would have been silent — a search that finds nothing in a database full of
text.

The fallback exists because an index that answers a question it cannot answer
is worse than no index. The rule knows how to say "not"; the index does not.
Where the rule is the only one that can answer, the rule answers.

## Consequences

- A search reads the rows that carry the word, not every row of the table.
- The database grows by an index, not by a second copy of the text.
- "port" no longer finds "airport". This is a change in what a search means,
  and it is stated in the search grammar (F2) rather than left implicit.
- A note's body is not indexed, because the grammar does not search it.
- The upgrade has to rebuild the indexes, which is a one-off cost on a
  database of existing rows.
- `LIKE '%…%'` is gone from the search paths. It remains only where a query is
  a fixed pattern rather than a search.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/FtsEntities.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/FtsDao.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/FtsMatchQuery.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Migration42To43.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/search/TextMatch.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchUseCase.kt`
- `docs/specs/search-query-grammar.md`
