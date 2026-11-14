---
title: "The saved-searches row is the suggestion surface"
date: 2026-11-14
status: accepted
tags: ["search", "saved-search", "ui"]
---

# The saved-searches row is the suggestion surface

## Context

`SEARCH-07` gave the search screen a row of saved-search chips above the results,
and #2943 asks the screen to *suggest* saved searches while the user types. The
issue requires that saved searches never appear twice and that the existing
`SavedSearchesRow` be reused "where practical".

Two shapes satisfy that:

**A — a second list.** Keep the row as it is and add a suggestion list under the
field, shown only for a non-empty query. The price: two presentations of the same
entities, two places to keep the load/rename/delete affordances in step, a new
composable and a new set of tags — for a query that in most sessions matches no
saved search at all, where the panel is empty.

**B — one row that narrows.** The row keeps its chips and gains one input: the
current query. For a blank query it offers every saved search; for a stated one
it offers the saved searches the query matches, and says so compactly when none
does.

## Decision

**B.** `SavedSearchesRow` takes the query, and the chips are
`savedSearchSuggestions(savedSearches, query)` — a pure function over the list
`SearchUiState` already observes.

- The match rule is the contains rule the app already owns
  (`TextMatch.contains`): the term occurs in the saved search's name or in the
  stored query, case-insensitively and diacritic-folded. No second matcher.
- The order is the list's own (the repository reads saved searches by name), so
  a suggestion never moves while the user types.
- The chip keeps its primary label — the saved-search name — and draws the
  stored query as a second line, where a chip can afford one. The chip stays one
  control: same tag, same tap, same long-press menu.
- The no-match state is one line in the row's own place, not a panel over the
  results and not a row that disappears (a disappearing row would move the
  results under the user's finger).
- No saved searches at all means no row: the screen keeps the state it had, and
  the issue's "otherwise show no suggestion panel" holds.

## Rationale

The saved searches on this screen are already a strip of chips the user reads and
taps. A second list would have to repeat their names, their queries, their tap
targets and their rename/delete menus, and the two would drift — the rename flow
alone reaches into the chip. Narrowing the one strip gives the same answer with
one presentation.

The filtering is a read of state the screen already holds, so nothing is asked of
the database per keystroke, and nothing is added to `SearchUiState`.

## Consequences

- Typing a query that matches no saved search shows a one-line answer in the row
  instead of those chips. The chips are reachable again by clearing the query —
  which is the field's own clear, not a new path.
- The row's chips are the suggestion surface for every query shape: a free-text
  term, `tag:work`, or the beginning of a saved search's name.
- The rule lives in `savedSearchSuggestions` and is asserted without Compose;
  the row and the no-match line are asserted in `SavedSearchesRowUiTest`.
- A user who wants a saved search that the typed query does not match can still
  save the current query: the "Save" chip stays first, unfiltered.

## Links

- Issue #2943, parent #2940.
- `docs/decisions/2026-11-12-a-saved-search-carries-the-query-that-runs.md` — why
  the stored query is part of what a saved search is, and therefore worth
  showing beside its name.
