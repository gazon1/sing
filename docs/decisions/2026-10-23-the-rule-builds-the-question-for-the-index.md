---
title: "The question the index is asked is built by the rule, and the rule lives next to the plan"
date: 2026-10-23
status: accepted
tags: [search, architecture, index]
---

# The question the index is asked is built by the rule, and the rule lives next to the plan

## Context

The search asks the index which records carry the word, and the index answers
the positive terms while the rule answers the negated ones (ADR 2026-10-22).

The decision "what can the index answer for this rule" was moved out of the
resolver and into the rule — `FreeTextRule.plan()` — because every other caller
of the index has to reach the same answer, and a rule that is narrowed in one
place and read in another is a rule with two answers (issue #885).

That part is done. What the issue also asks for is not: **every other caller
of the index goes through the same factory**. Three callers did not:

- `NotesRepositoryImpl.search` — the search field of the notes list;
- `InternalLinkRepositoryImpl.searchNotes` / `searchTasks` — the autocompletion
  of a link;
- `TaskRepositoryImpl` — the text filter of the task list.

Each of them built the question by hand — `TextMatchQuery(listOf(query))` — and
each of them answered, again and on its own, the question of what the index can
answer. A hand-built question is a decision taken away from the rule that knows:
a caller that built it wrongly — an empty question, a negated term inside it,
terms joined the other way — would ask the index something nobody wrote down,
and the answer would look like an answer all the same.

The rule itself was also in the wrong place for that: it lived in
`feature/search/query` — the *query language* — while the plan it decides and
the shape it produces lived in `core/search`, next to the port of the index.
The plan's own documentation already pointed at a rule that was in another
package.

## Decision

**The rule moves next to the plan, and it builds the question.**

- `FreeTextRule` lives in `core/search`, beside `TextMatchPlan`,
  `TextMatchQuery` and the index port. The query language builds the rule; the
  other features ask it.
- The rule answers the question of the index itself:
  `FreeTextRule.indexQuery()` returns the positive terms as the index is asked
  for them, and an **empty** question when the index cannot narrow the rule at
  all. An empty question finds nothing — the caller that has to read the rows
  reads them itself, and does not get every row of the profile by accident.
- A plain search text — a text with no connective in it — is a rule of one
  term: `FreeTextRule.singleTerm(text)`. The notes list, the link
  autocompletion and the task list's text filter use it, so they are the same
  rule as the search, and not a second one that happens to look similar.
- **The shape of the question is built in one place only.** A rule in the
  architecture tests says that `TextMatchQuery` is constructed in `core/search`
  and nowhere else, so the next caller cannot build it by hand.

## Consequences

- The three callers ask the same question they asked before — the answers do
  not change — but the question now comes from the rule, and the rule knows
  what the index can answer.
- The notes list and the link autocompletion are searched by the word-prefix
  rule (F2a), and they are the indexed search. The contains mode is a mode of
  the search screen, where it is named on the screen (ADR 2026-10-22) — a
  surface that does not offer it does not answer by it, and does not pretend
  to.
- A change to the rule — a different connective, a different notion of a term —
  changes every caller at once. That was the point of the rule having one
  owner.
- The rule is in `core/search`, so the data layers of other features depend on
  the core layer and not on the search feature: a task repository that asks the
  index does not depend on the query language.

## Links

- Issue — [#885](https://github.com/gazon1/sing/issues/885)
- The rule — `shared/src/commonMain/kotlin/com/singularity/todo/core/search/FreeTextRule.kt`
- The plan — `shared/src/commonMain/kotlin/com/singularity/todo/core/search/TextMatchPlan.kt`
- ADR 2026-10-21: the search asks the index — `docs/decisions/2026-10-21-the-search-asks-the-index.md`
- ADR 2026-10-22: the index answers the positive terms — `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`
- ADR 2026-10-22: the substring search is a mode the surface states — `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`
