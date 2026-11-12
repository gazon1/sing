---
title: "The applied filter is the executed query"
date: 2026-11-11
status: accepted
tags: ["search", "filter", "state-machine"]
---

# The applied filter is the executed query

## Context

The search screen had two sources of truth for one question, and they could
disagree.

`SearchViewModel` held the parsed query (`_parsedQuery`) — the thing the
pipeline actually executes — and the active filter (`_activeFilter`), derived
from it. `SearchIntent.OnApplyFilter` carried a **nullable** filter, and the
null branch did this:

```kotlin
_activeFilter.value = null
// ... emptied the results, stopped searching, and returned
```

It never touched `_parsedQuery`. So the path that "cleared the filter" cleared
the *display* of the filter and left its predicates in the executed query: the
structured constraints stayed in force and the screen answered "no results" for
a query the user had already unfiltered. The state was self-contradicting —
the chip row said "no filter", the search was still filtered — and the way out
did not exist, because there was nothing to clear.

The sheet had the mirror defect. Its Apply built a **fresh** `SimpleFilter`
from the facets it edits, so applying a filter dropped the free text, the tags
and the project the user had typed — the sheet answered a different question
than the one on the screen.

Two further facts shaped the fix:

- The free text of a query is a **conjunction** of terms. `"milk bread"` is two
  terms, and joining them into one string produced the single term
  `"milk bread"`, which no record carries.
- ADR `2026-10-22-the-broad-scan-share-is-measured.md` forbids the search path
  from reading the projects table: a query of filters only must not scan the
  projects. The project name a result row shows therefore cannot be resolved
  inside `SearchUseCase`.

## Idea

Three ways to make the filter and the executed query agree.

**A — the field text is the source of truth.** The filter sheet rewrites the
text in the field (`priority:high` → ``), and the executed query is parsed from
the text as always. One mechanism, and the text always says what is searched
for. The price is a second parser: the sheet has to turn a facet into query
syntax and splice it into a string, and the text the user sees becomes a
generated artifact. It also contradicts the issue this came from, which asks
that clearing the filters *not* clear the query.

**B — the filter is the executed query.** The filter is turned back into a
query, and that query is what runs. The text in the field stays what the user
typed. One mechanism, and the sheet and the no-results state both speak the
filter's vocabulary. The price: the text in the field can describe a query that
is not the one running — after "clear filters" the text still reads
`priority:high` while the search runs the free text alone.

**C — a separate effective filter.** The executed query becomes a third value
beside the text and the filter. Nothing is rewritten and nothing is
reinterpreted, but three values answer one question, and the two that disagree
have to be reconciled somewhere — which is the defect this decision is about.

## Decision

**B**, with the null removed from the path that caused the defect.

- `SearchIntent.OnApplyFilter` carries a **non-null** `SimpleFilter`. "No
  filter" is the empty filter; a nullable argument made a path that could clear
  the display of a filter while leaving it in force, and the type no longer
  admits that path.
- `onApplyFilter` **always** replaces `_parsedQuery` with `toQuery(filter)`. A
  filter that cannot be turned back into a query is the same shape as a query
  that does not parse: the pending flag is lowered, the filter the surface
  states is put back, and the failure is reported.
- `SimpleFilter.freeText: String?` became `freeTextTerms: List<String>` — one
  term per `HasText` atom. A quoted phrase is one term however many words it
  holds; two bare words are two terms.
- `SimpleFilter` grew `hasStructuredConstraints` and `withoutStructuredConstraints()`.
  The free text and the sort are not constraints: the sort orders the answer,
  it does not narrow it, and the free text is what the user asked for by words.
- The no-results state chooses its action from `activeFilter?.hasStructuredConstraints`
  and dispatches `OnApplyFilter(activeFilter.withoutStructuredConstraints())` —
  the same intent the sheet uses, so there is one filtering implementation.
- The sheet's Apply is `initialFilter.copy(facets…)`: the parts of the query the
  sheet does not edit are carried over, not dropped.
- The no-results state offers a **clear-query** action of its own, and it is the
  field's clear: the button calls the field's `clearText()`, so there is one way
  to empty the field and two ways to ask for it. The field's trailing clear
  button stays where it is; the empty state's button is the labelled one.
- The **project badge** lookup is a ViewModel-side subscription
  (`projectRepo.observeAllIncludingClosed()`, one per screen, resolved once per
  build) and lives in `SearchUiState.projectsById`. It is not in the search
  matching path, so ADR 2026-10-22 still holds. The precedent is the agenda's
  `AgendaViewModel`.

## Rationale

The defect was a disagreement between two representations of one question.
Option B removes the disagreement by removing one of the representations: the
filter *is* the query from the moment it is applied.

The field text stays the user's text. Nothing rewrites it, so the "clear
filters" action cannot clear the query as a side effect — the requirement the
issue states explicitly.

The saved-search serialization keeps working unchanged: a saved search is
still one string of query text. *Which* query that string carries after a
filter action is settled by
`2026-11-12-a-saved-search-carries-the-query-that-runs.md` — the query that
runs, which after a filter action is not the text in the field.

The cost is real and is accepted deliberately: after "clear filters" the field
still reads `priority:high` while the search runs the free text alone. The
alternative (rewriting the field) would have made the text a generated
artifact and would have needed a second query-language parser to do it. The
field is the user's statement of intent; the filter is what the screen is
currently *doing* with it. When the user edits the field again, the text is
parsed as always and the filter comes back — which is the behaviour a user
expects.

The clear-query affordance is a second *way to ask*, not a second *way to
clear*. The field already has a trailing clear button, and the issue asks that
the no-results state not duplicate it unnecessarily. Two readings of that were
weighed: leave the empty state without an action and rely on the field's
button, or give the empty state a labelled button. The first leaves the user
looking at an explanation of why nothing matched, with the way out in a small
glyph at the top of the screen — the opposite end of the screen from where the
attention is. The second puts the way out under the explanation, in the words
the user is already reading. The duplication is of the *affordance*, not of the
behaviour: the button calls the field's own `clearText()`, so the field cannot
be emptied in two different ways and the two buttons cannot disagree.

## Consequences

- `SearchIntent.OnApplyFilter(null)` no longer compiles. Every call site is a
  call site that had to say what it means.
- The field's text and the executed query can differ after a filter action. The
  no-results state is the one place this is visible, and it says so in words
  ("Nothing matched with these filters."). A saved search carries the query
  that runs, so that the difference does not come back when it is loaded — see
  ADR `2026-11-12-a-saved-search-carries-the-query-that-runs.md`.
- The free text survives a filter action, so a query of `"milk"` plus a
  priority filter still searches for `milk` when the filter is applied.
- The ViewModel test proves the executed query changes: after clearing the
  filters, `parsedQuery` holds the free-text condition and not the priority
  predicate, and the results the constraints had hidden come back.
- The search path still does not read the projects table; the badge costs one
  subscription per screen, not a lookup per row.
- The no-results state shows a clear-query button beside the field's own
  trailing clear button. Both go through the field's clear — one `OnQueryChange`
  with the empty text — so they cannot disagree; a test asserts the empty
  state's button produces exactly that intent.

## Links

- Issue: https://github.com/gazon1/sing/issues/2742
- ADR: `2026-10-22-the-broad-scan-share-is-measured.md` — a search of filters
  only no longer reads the notes, the projects and the tags.
- ADR: `2026-09-28-mr4-combine-soundness.md` — the intent paths and the
  pipeline must agree on what "the search will run" means.
- ADR: `2026-10-20-one-free-text-rule-for-every-kind-of-record.md` — the free
  text is one rule for every kind of record.
