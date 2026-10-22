---
status: accepted
date: 2026-10-21
---

# The index answers through one door, and the door says what it can answer

## Context

[ADR 2026-10-21: the search asks the index](2026-10-21-the-search-asks-the-index.md)
gave the search a full-text index. The index was read through four doors: one
`observeByTextMatch` method on each of the four repositories, each of which held
the index's DAO next to its own. The question "which records carry this word"
was asked in four places and answered four times, and the two implementations of
the rule — the one in Kotlin, the one in SQL — had nothing that compared them.

Three things were left over from that work, and this record is about them.

## The rule and the index could answer differently

The matcher in Kotlin and the tokenizer in SQLite are two implementations of one
rule: *what counts as a word*. Nothing compared them, and they did not agree.

The corpus that compares them found one letter where they differ. Kotlin's
`String.lowercase()` is the *full* Unicode lowercase mapping, and for `İ`
(U+0130, Latin capital letter I with dot above) it is not one letter: it is
`i` followed by a combining dot. The combining dot is not a letter, so the
matcher split "İstanbul" into two tokens, while SQLite's `unicode61` folded it
into one.

A search for "stanbul" then matched the rule and not the index. The index
produced the candidates, the rule accepted one, and the record was silently not
returned. Nothing reported it: the user sees fewer results and concludes the
record is gone.

## Decision

### The matcher folds per character

`Char.lowercaseChar()` is the *simple* mapping — one character in, one character
out — and it is what `unicode61` does. The matcher folds per character now.

The one difference that remains is that `unicode61` removes the diacritics of a
word and the matcher does not: the index finds "café" for the term "cafe" and the
rule does not. That difference is in the safe direction — the index offers a
candidate the rule rejects, and the search answers the same either way. The
unsafe direction, the rule accepting a record the index does not offer, is what
the corpus holds closed.

> **Refined by
> [ADR 2026-10-22: the index answers the positive terms](2026-10-22-the-index-answers-the-positive-terms.md).**
> The index answers the positive terms now, so the diacritic difference is no
> longer invisible: a search for "cafe" finds "Café au lait". The direction that
> loses records is still the direction the corpus holds closed.

### The corpus compares the two against a real SQLite

`FtsMatcherAgreementTest` puts a corpus of words through both and asserts that
every record the rule accepts is among the records the index returns. The fakes
cannot say anything about this: the whole question is what SQLite's tokenizer
does with a character the matcher has never seen.

### One port, and the port says what it can answer

`SearchTextIndex` is the one port. The search, the task filter, the note list
and the link autocompletion all ask it, and the `FtsDao` has one reader.

The port also carries the answer to "can the index answer this rule at all?".
FTS has no `NOT`, so a rule made only of negated terms, and a rule that makes a
negated term an alternative of a positive one, have nothing to look up: the
records that match by *not* carrying a term are exactly the ones a positive
lookup throws away. That used to be a `null` on the resolved query, decided in
the resolver. It is a named state now — `TextMatchPlan.BroadScan` — and the
rule decides it, next to the rule, so every caller of the index reads the same
answer.

### No production code runs `VACUUM`

The index is an external-content index: a row is found through the `docid`,
which is the implicit `rowid` of the content table. `VACUUM` rebuilds a database
file and renumbers the `rowid` of every table without an `INTEGER PRIMARY KEY` —
and the id of a task is a UUID, so the tables have no such key.

After a `VACUUM` the index would still hold the postings of the old `rowid`s,
and every row it points at would be a different row. `VACUUM` succeeds, the
database is intact, and the search is quietly wrong. `VacuumIsForbiddenTest` is
what keeps it that way.

### A title match comes before a body match

FTS4 has no `bm25` and no column weights: the index says *whether* a record
carries the word, not *how well* it matches. A record whose title is
"Architecture" and a record whose description mentions architecture are the same
match to the index, and they are not the same answer to the user.

The ranking is done where the knowledge is — the rule, which is the last word
anyway. The partition is stable and happens after the sort, so the records that
carry the word in their title come first as a group, and inside each group the
order the user asked for is the order they get.

## Rationale

The alternative to the corpus was to trust that the two implementations agree.
They did not, and nothing would have said so.

The alternative to the plan was to keep the `null` and to remember, at every
call site, what a `null` means. A named state is a decision that can be read,
tested and pointed at; a `null` is a convention.

The alternative to the port was to leave the four methods where they were. Four
implementations of one lookup is four places to fix the next bug, and the two
doors to the same rows is how a search starts reading a table it should not.

The measurement is a row count and not a clock. A clock measures the machine,
and the machine is shared: a build on the same runner, a neighbour process, a
page fault. A timing assertion on a shared runner is a flaky assertion, and a
flaky assertion is an assertion people learn to ignore.

## Consequences

- The search's behaviour changes in one place: the matcher folds per character.
  The visible effect is a search for "stanbul" finding "İstanbul".
- `SearchTextIndex` is a new port, and the four repositories no longer know
  that the index exists.
- `TextMatchPlan` is a new type on the resolved query. `ResolvedSearchQuery`
  carries a plan instead of a nullable query.
- The ranking of a text search is a group, not a single order: the title
  matches first, and the sort the user chose inside the groups.
- `FtsDao.searchTaskTitlesByMatch` and `searchNoteTitlesByMatch` take a limit
  instead of carrying one.

## Links

- ADR 2026-10-21: the search asks the index — `docs/decisions/2026-10-21-the-search-asks-the-index.md`
- Grammar of the search — `docs/specs/search-query-grammar.md`
- The corpus — `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/FtsMatcherAgreementTest.kt`
- The gate — `shared/src/jvmTest/kotlin/com/singularity/todo/arch/VacuumIsForbiddenTest.kt`
- The measurement — `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/SearchCostDoesNotGrowTest.kt`
