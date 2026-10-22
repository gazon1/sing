---
title: "The index answers the positive terms, and the rule answers the negated ones"
date: 2026-10-22
status: accepted
tags: [search, database, room, fts, performance]
---

# The index answers the positive terms, and the rule answers the negated ones

## Context

[ADR 2026-10-21: the search asks the index](2026-10-21-the-search-asks-the-index.md)
gave the search a full-text index, and
[ADR 2026-10-21: the index answers through one door](2026-10-21-the-index-answers-through-one-door.md)
gave it one port and a corpus that compares the two implementations of the rule
against a real SQLite.

Two things were left over.

**The rule filtered twice.** The index narrowed the candidates to the records
that carry the word, and then the free-text rule was applied to those
candidates *again* — for the `NOT` terms, and for the positive terms too,
because the rule could not say "only the negated part". The result was
correct, but the rule was a second source of the answer to "does this record
carry the word", next to the index that had already answered it, and it did
the same work twice.

**The index asked a nearby question.** The `MATCH` expression was a conjunction
of prefixed tokens: `quarterly* report*` asks for both words *anywhere* in the
record. The rule asks for something stricter — the words *next to each other*,
with only the last one allowed to be unfinished. So the index offered candidates
the rule threw away, and the index could not be the answer to a phrase.

## Idea

One rule, one answer.

The rule has two halves, and the halves are not the same kind of question:

- a **positive** term asks "which records carry this word" — the index knows
  the tokenizer, it knows what a word is, and it is the thing that makes the
  candidate set small;
- a **negated** term asks "which records do *not* carry this word" — FTS has
  no `NOT` operator, so nothing else can answer it.

Let the index answer the first and the rule answer the second, and let the
`MATCH` expression say exactly what the rule means.

## Decision

### The index answers the positive terms

`FreeTextRule.accepts(plan, matchesTerm)` is the one place that reads the plan
and applies the matching half of the rule:

- a candidate that came from the index has already been matched against the
  positive terms, so only the negated terms are left to answer;
- a candidate that was read row by row — because the index could not answer the
  rule at all — is held against the whole rule.

The rule no longer asks the index's question a second time. The positives are
the index's business, and the rule says so in one method rather than in two
call sites that each had to remember the split.

### The expression is a phrase, not a conjunction

A term is rendered as a quoted phrase: `"repo*"` for a one-word term and
`"quarterly repo*"` for a two-word term. The star goes on the **last** token
only, because the rule matches the tokens before it exactly.

FTS4 reads a phrase as "these words, next to each other" and a juxtaposition
of two phrases as "both of these". So the expression now asks the rule's
question and not a nearby one, and a quoted phrase in the query language finds
the records whose words stand next to each other.

The quoting also settles the keyword problem for good: `not*` was a term
because of how the expression was built, and `"not*"` is a term because nothing
inside a quoted phrase is syntax.

### The two sides of one rule

`TextMatch` (what counts as a word, and what a prefix is) and `FtsMatchQuery`
(how that is said to SQLite) are **two sides of one rule**. They are written in
two languages — Kotlin and SQL — and nothing in the language keeps them in
step, so the rule is: *when you change one, you change the other, and you say
in the commit or the record which side moved.*

Concretely, the pairs that have to move together:

| What changes | `TextMatch` | `FtsMatchQuery` |
|---|---|---|
| what a word is | the tokenizer | the tokens the expression is built from |
| what a prefix is | the last token of a term | the `*` on the last token |
| what a phrase is | the tokens stand next to each other | the quoted phrase |
| which surface is searched | — | the columns of the index |

The corpus in `FtsMatcherAgreementTest` is what holds the two sides together:
it puts a corpus of words through the rule and through a real SQLite, and it
holds closed the direction that loses records — **every record the rule accepts
is among the records the index returns**.

### The one difference, and where it now shows

`unicode61` removes the diacritics of a word — "café" is indexed as "cafe" —
and the in-memory rule does not.

While the rule had the last word, that difference was invisible: the index
offered the candidate and the rule rejected it, and the search answered the
same either way. The index answers the positive terms now, so the difference is
in the answer: **a search for "cafe" finds "Café au lait"**.

This is a change in what a search means, and it is a change in the direction
the index was already pointing. It was taken deliberately: the index knows the
tokenizer, the in-memory rule does not, and the alternative — making the index
keep the accent — was a change of the tokenizer's own (`remove_diacritics 0`),
a schema migration and a rebuild of four indexes, in exchange for an answer
that is narrower than the one the index already gives.

The direction that *loses* a record is closed and stays closed: the rule
accepts nothing the index does not offer. The one place the two still differ
is a negated term, where the in-memory rule is the stricter of the two — so
`NOT cafe` keeps a record that `cafe` finds. That is a known asymmetry of the
split, and it is written down here rather than left to be discovered.

### The fake answers the query, not the expression

The fake database answered a `MATCH` expression by parsing it back into tokens
and matching those — a second implementation of the rule, in a place where
nothing compared it with the first one. The fake answers the *query* surface
now, with the one rule, and the expression surface of the fake says loudly
that it is not implemented.

### FTS5 will make the rule be looked at again

The rule is written against FTS4's `unicode61` and against FTS4's `MATCH`
syntax. FTS5 has `bm25` (so the ranking moves into the index, and the
in-memory ranking has to be looked at), a `snippet` that can weight the
columns, and tokenizers that behave differently — `unicode61` in FTS5 folds
and removes diacritics by a different table, and the FTS5 `trigram` tokenizer
answers a different question altogether ("contains", not "word prefix").

So: **if the index moves to FTS5, the rule is reviewed before the index is
migrated.** The two sides have to move together, and the migration is the
moment when the question "which side is the rule now?" has to be answered.

## Rationale

The alternative was to keep the rule filtering the positives and call it one
rule with two filters. It is not one rule: the index and the rule are two
implementations of "what counts as a word", and the rule filtering the
candidates means the answer depends on which of the two happened to run last.
A search that returns what the index found, and says so, is one answer.

The alternative to the phrase syntax was to keep the conjunction and let the
rule narrow the candidates down to the phrase. That is the same double
filtering, one level down: the index would keep offering candidates for a
query whose semantics it could not express.

The alternative to letting the index answer the positives was to make the
index keep the accent (`remove_diacritics 0`) and migrate. It keeps the
current answer exactly, and it costs a schema version, a rebuild of every
index, and the loss of a capability the index had. The narrower answer is the
one the search already gave, and the wider one is what the index already
knew.

## Consequences

- A search for "cafe" finds "Café au lait". The answer is wider than it was,
  and it is the index's answer.
- The rule is asked about the negated terms only, when the candidates came from
  the index. A rule the index cannot answer at all is still answered by the
  rule, in memory.
- A quoted phrase finds the records whose words stand next to each other. The
  index asked for a conjunction before, and offered candidates the rule threw
  away.
- `FtsMatchQuery` renders phrases, and `TextMatch` is the other side of the
  same rule. Changing one means changing the other.
- The fake database answers a query and refuses an expression loudly. There is
  one implementation of the rule in the test doubles.
- `FtsDao` grew a semantic surface: the callers ask for a query, and the DAO
  builds the expression.
- A negated term is still answered by the in-memory rule, which does not
  remove the diacritics — so `NOT cafe` keeps "Café au lait" while `cafe`
  finds it.
- The test doubles answer with the in-memory rule, so a fake is the stricter of
  the two. A test that asserts the behaviour of the diacritics belongs to the
  corpus, which runs against a real SQLite.

## Links

- ADR 2026-10-21: the search asks the index — `docs/decisions/2026-10-21-the-search-asks-the-index.md`
- ADR 2026-10-21: the index answers through one door — `docs/decisions/2026-10-21-the-index-answers-through-one-door.md`
- The rule, in Kotlin — `shared/src/commonMain/kotlin/com/singularity/todo/core/search/TextMatch.kt`
- The rule, in SQL — `shared/src/commonMain/kotlin/com/singularity/todo/core/database/FtsMatchQuery.kt`
- The split — `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/FreeTextRule.kt`
- The corpus — `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/FtsMatcherAgreementTest.kt`
- The search over a real database — `shared/src/jvmTest/kotlin/com/singularity/todo/feature/search/SearchUseCaseFtsTest.kt`
- Issues — [#886](https://github.com/gazon1/sing/issues/886), [#888](https://github.com/gazon1/sing/issues/888)
