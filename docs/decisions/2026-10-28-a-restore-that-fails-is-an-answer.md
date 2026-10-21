---
title: "A restore that fails is an answer, not an event that never comes"
date: 2026-10-28
status: accepted
tags: [architecture, coroutines, auth, search, testing]
---

# A restore that fails is an answer, not an event that never comes

## Context

Two decisions came out of the same review of what the code actually promises,
and they are recorded together because they are the same mistake seen twice.

**The restore report never ended.** `AuthRepository` publishes the outcome of
a session restore on `restoreFailures`, a channel that was never closed. A
reader that had found no report waited for an event that could not arrive:
`AuthViewModel` called `restoreFailures.firstOrNull()` and sat on it for the
lifetime of the screen. "There was no failure" was an *absence*, and an
absence that is waited for is a hang — the scope of an auth screen is the
lifetime of an auth screen.

**The grammar promised a rule the code did not implement.** `docs/specs/search-query-grammar.md`
§4 says that `tag:work tag:urgent` is one condition — a task with *both*
tags. The code produced two conditions and ANDed them, which is the same
answer for a *resolvable* name and a different one for a name that resolves
to nothing: `tag:work tag:nonexistent` matched every task with the tag
`work`, while the grammar's rule says a task must have both. The grammar was
right and the code was wrong, and nothing in the build noticed, because the
document and the implementation were two texts that happened to live in the
same repository.

## Idea

A read that waits must have an end that is not the reader's patience, and a
document that promises behaviour must be checked against the behaviour.

## Decision

**The restore report is closed with the restore window.** The channel now
completes when the restore window closes, and a closed channel with a buffered
element yields the element first and then ends. "There was no failure" is
therefore an *answer* — a completed stream — and not an event to wait for.
The wait itself is already bounded: the restore is a `boundedRead`, so the
report cannot arrive later than the restore.

**The grammar is a checked claim.** A new test reads
`docs/specs/search-query-grammar.md` and the rule markers in the production
sources under `feature/search/`, and asserts three things: every rule the
grammar declares is cited by the code *in the grammar's own words*; every
rule the code cites is declared in the grammar; every rule the grammar
declares is declared in the grammar. The test does not duplicate the rules —
a second copy of the rules in the test would be a third text to keep in step.

**The rule the grammar promised is implemented.** `tag:a tag:b` is parsed
into one `HasAllTags` condition, and a name that resolves to nothing inside
such a condition means "no rows", not "the rows that resolved". The single
`tag:` form stays a `HasTag`.

## Rationale

A channel that is never closed is a promise the type system does not keep.
`firstOrNull()` on such a channel is not "read the first element if there is
one" — it is "wait for an element that may never come", and the difference
between the two is exactly the difference between a screen that renders and a
screen that hangs. Closing the channel with the window that produces it makes
the contract total: the report is delivered or the stream ends, and the
reader has to deal with both.

The grammar test exists because a document that describes behaviour and an
implementation that implements it are two artefacts that drift. A test that
compares them is the only thing that makes the drift visible at the moment it
happens. The wording requirement — the code must cite the rule *as the
grammar words it* — is what makes the test a real check rather than a
counting exercise: without it, a reworded rule would keep the test green
while the two texts said different things.

Requiring the citation to be verbatim is also what forced the disagreement
into the open. Making the code agree with §4 meant implementing `HasAllTags`
and the "unresolvable name means no rows" rule — two behaviours the product
had, in a document, and did not have in the app.

## Consequences

- `restoreFailures` completes. Its readers must handle completion, and
  "no failure reported" is now a state they can reach instead of a wait they
  cannot.
- The agreement test fails when the grammar changes without the code, and
  when the code changes without the grammar. Both directions are checked,
  because a rule the code implements and the document does not mention is
  just as much a lie as the other way round.
- The test reads a file outside the module. The path is passed in as a
  system property and declared as a task input, so editing the document
  re-runs the test that reads it.
- The agreement test covers the *numbered* rules. Three promises outside the
  numbered rules are still open and are not covered: a bare status word
  (`done`) where the code has `state:done`, the `asc` directive where the
  code has only `desc`, and `offset:<n>` which the parser does not fill in.
  They are a product decision, not a mechanical fix, and they are filed
  rather than quietly implemented.
- The bounded-read inventory (`docs/architecture/bounded-read-inventory.md`)
  now says which reads are bounded and which are deliberately not, and why.
  "The source always answers" is not the same claim as "the read cannot
  hang", and the document says so instead of blurring the two.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/ConditionExprParser.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/SearchQueryResolver.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/search/query/SearchQueryGrammarAgreementTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/search/query/SearchQueryTagConditionsTest.kt`
- `docs/specs/search-query-grammar.md`
- `docs/architecture/bounded-read-inventory.md`
