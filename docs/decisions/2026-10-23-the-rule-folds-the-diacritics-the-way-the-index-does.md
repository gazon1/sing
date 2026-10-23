---
title: "The rule folds the diacritics the way the index folds them"
date: 2026-10-23
status: accepted
tags: [search, fts, unicode]
---

# The rule folds the diacritics the way the index folds them

## Context

[ADR 2026-10-22: the index answers the positive terms](2026-10-22-the-index-answers-the-positive-terms.md)
split the search rule in two. The index answers the terms that narrow a
candidate set; the rule in memory answers the terms the index cannot — the
negated ones, and the whole rule of a query the index could not narrow at all.

That split left one asymmetry, and it was written down there as deliberate:
`unicode61` removes the diacritics of a word — "café" is indexed as "cafe" —
and the in-memory rule did not. So a search for "cafe" found "Café au lait",
while `NOT cafe` kept it.

The asymmetry was harmless in the direction that loses records: the rule was
the stricter of the two, so it never threw away a record the index had found.
It was visible in the other direction, and it is the direction a person
notices: a query that contradicts itself. "cafe" finds the record, and
"NOT cafe" does not rule it out — two halves of one rule answering the same
question two ways.

[ADR 2026-10-23: the rule builds the question for the index](2026-10-23-the-rule-builds-the-question-for-the-index.md)
made the rule the owner of the question the index is asked. The fold is the
one thing that was left in two places, and it is now a change in one place.

## Idea

Three ways to close the asymmetry, and what each of them costs.

**Ask the index to keep the accent.** `remove_diacritics 0` is a change of the
tokenizer's own: a schema migration and a rebuild of four indexes, in exchange
for an answer that is *narrower* than the one the index already gives. It was
rejected in ADR 2026-10-22 and it is rejected here for the same reason — a
narrower answer is a worse answer, and it costs a migration to get.

**Fold the rule by decomposition.** Decompose the text canonically and drop
the combining marks: "café" becomes "cafe" plus a mark, and the mark goes.
It is the general answer, and it is the wrong one, twice over.

- **The index does not decompose.** `unicode61` folds a character to *one* base
  letter or not at all: "ß" is not "ss", "æ" is not "ae", "ø" is not "o". A
  decomposition would fold them, and the two would answer differently about
  the same text — in the direction that *finds more*, which is the direction
  nobody notices until it is wrong.
- **The length has to hold.** The index reports where it found the word, and
  those positions are positions in the original text. A decomposition changes
  the length of a string, so it moves every position after the first accented
  character. A fold that replaces a character with exactly one character does
  not.

**Fold the rule the way the index folds.** The tokenizer has a table; the
table is copied into the rule, and the two fold the same characters to the
same letters.

## Decision

The rule folds the diacritics, with the tokenizer's own table.

The table is not a guess about what `unicode61` probably does: every character
of the Latin-1 Supplement, Latin Extended-A, Latin Extended-B and Latin
Extended Additional blocks was put into a real FTS4 table with
`tokenize=unicode61`, and the base letter was read back from the index that
answered for it. 97 ranges, 376 characters, every fold to exactly one letter.

Three things follow from the shape of the table, and each of them is a
property the rule needs:

- **A fold never changes the length.** Every folded character is replaced by
  one character, so a position in the folded text is the same position in the
  original — and the index's offsets stay offsets of the original text.
- **A letter without a single base letter is not folded.** "ß", "æ", "ø",
  "ł" and "đ" are left alone, because the index leaves them alone.
- **The fold is the same on both sides.** The text and the term are folded the
  same way, so the rule asks the index the question the index would answer.

The contains mode folds as well. It is the escape hatch — the mode that is
supposed to find *more* — and a mode that found less about the same text would
be a trapdoor: "cafe" would find "Café au lait" in the word mode and not in the
contains mode.

The rule is declared in the grammar as **F7** and cited in the code, so the
agreement test holds the citation: a fold that is dropped from the code fails
the build, and a fold that is added to the grammar without the code fails it
too.

The corpus is the standing arbiter. "cafe" moves into the group the index and
the rule have to answer *identically*, and the dedicated test now says that
both fold the diacritics the same way.

## Rationale

The rule exists to answer the questions the index cannot. It answered them with
a different tokenizer than the index's — and a rule that answers the same
question two ways is a rule with two answers, which is the defect #885 was
about in the first place. The diacritics were the last place it showed.

The cost of closing it is one table and one call in the matcher. The cost of
leaving it is a search that contradicts itself: a record that "cafe" finds and
"NOT cafe" keeps, and a person who types the second form concludes the first
form is lying.

The table is copied rather than derived because the derivation is not
available: the fold is a table inside SQLite, and the only honest source for
it is the tokenizer itself. A decomposition would have been a derivation, and
a derivation that answers differently from the tokenizer is worse than a copy
that answers the same.

## Consequences

- **`NOT cafe` now rules out "Café au lait".** This is a change in what a
  search means, and it is in the direction the index was already pointing: the
  positive term found the record, and the negated term now rules it out.
- **The contains mode folds too.** "cafe" finds "Café au lait" in both modes.
- **The contains mode case-folds the way the word rule does.** The comparison
  was `ignoreCase`, which does not fold "İ" (U+0130) to "i"; it is now the
  same per-character folding the word rule uses, so "istanbul" finds
  "İstanbul" in both modes. The index folds that character, so this is the
  index's answer too.
- **The table has to be kept in step with the tokenizer.** The corpus is what
  does that: a character the table folds wrongly is found by the corpus on the
  day a term that uses it is added to it.
- **Issue #944 is not closed by this.** Its first acceptance criterion is met
  — a negated term rules out exactly the records the positive term finds, and
  the test says so with an accented title. Its other two — a rule of only
  negated terms answered from the index, and the broad-scan path gone — are
  the subtraction the issue proposes, and they are not part of this change.
  The broad scan stays: a rule the index cannot narrow is still read in memory.

## Links

- ADR 2026-10-22: the index answers the positive terms — `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`
- ADR 2026-10-23: the rule builds the question for the index — `docs/decisions/2026-10-23-the-rule-builds-the-question-for-the-index.md`
- Issue #944 — the asymmetry, and the subtraction that would remove the broad scan
- The fold — `shared/src/commonMain/kotlin/com/singularity/todo/core/search/DiacriticFold.kt`
- The rule — `shared/src/commonMain/kotlin/com/singularity/todo/core/search/TextMatch.kt`
- The corpus, the arbiter — `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/FtsMatcherAgreementTest.kt`
- The grammar, rule F7 — `docs/specs/search-query-grammar.md`
