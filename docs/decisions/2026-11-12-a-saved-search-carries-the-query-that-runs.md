---
title: "A saved search carries the query that runs"
date: 2026-11-12
status: accepted
tags: ["search", "saved-search", "filter"]
---

# A saved search carries the query that runs

## Context

ADR `2026-11-11-the-applied-filter-is-the-executed-query.md` chose option B: the
filter *is* the executed query, and the field's text stays what the user typed.
It accepted the price of that choice — after "clear filters" the field still
reads `priority:high` while the search runs the free text alone.

It also claimed, in its rationale, that "the saved-search serialization keeps
working unchanged, because a saved search stores the text and the text is still
what the user typed". That claim covers the query the user typed. It does not
cover the query a filter produced, and a review of the PR found the gap:

1. The user searches for `milk priority:high`.
2. The results are filtered by both conditions.
3. The user clears the filters. The screen now runs the free text alone.
4. The field still reads `milk priority:high`.
5. The user saves the search. `onSaveCurrentSearch` stored the field's text.
6. Loading that saved search parses `milk priority:high` again — the priority
   filter is back, and the saved search does not reproduce the results that
   were on screen.

The same divergence appears whenever a filter is applied and the field's text
does not change: the text says one query, the screen runs another.

## Idea

Three ways to make "save current search" honest.

**A — save the query that runs.** The saved search carries the executed query,
written back into the query text language. Loading it reproduces what was on
screen. The price: a saved search's text may no longer be the text the user
typed, and the query text language has to be able to say a filter — which
needs a writer that did not exist.

**B — save the text, and say so.** The saved search keeps the user's text, and
the screen communicates that loading it brings the constraints back. The price:
the saved search does not reproduce the search on screen, which is the defect
itself. It also leaves the user to discover the difference.

**C — store the filter next to the text.** The saved search carries the filter
as its own value. The price: a second column on the saved-search table, a
migration, a second field in the backup format, and a second thing to keep in
step — for a case the query text language can already say.

## Decision

**A.**

- `onSaveCurrentSearch` stores the **effective query text**: the field's text
  when that text still says what is running, and the applied filter written
  back into the query text language when it does not.
- The user's text is kept whenever it still says the same thing. A query typed
  in full is saved as it was typed, with its `due:3d` and its `limit:` intact:
  the text language says more than the filter can hold, and a saved search
  must not lose it.
- A filter the text language cannot say — a custom date range, a tag or a
  project whose name has no spelling — is **not** saved as something else.
  The text is kept, because half a query would say something the screen is
  not doing.
- The field's text is not rewritten. ADR 2026-11-11 stands: the field is the
  user's statement of intent, the filter is what the screen is doing with it,
  and only the *saved search* has to reproduce the screen.

## Rationale

A saved search is a promise: load it and the screen shows what it showed when
it was saved. The field's text cannot keep that promise on its own, because
the text is not what runs — the filter is, from the moment it is applied.

The choice between the text and the executed query is not free-form: the
screen has one answer to give, and the saved search has to be that answer.
Saving the text would keep the saved search *different* from the screen, and
would make "clear filters" a promise the saved search breaks.

Writing the filter back into the query text language is what makes A cheap
rather than invasive. The language already says states, priorities, tags,
projects, due dates, descriptions, pins and sorts; a filter is a set of those,
and the text is the only thing a saved search is made of. No schema changes,
no second field, no migration, and the backup format is untouched — the
saved search is still one string.

The rule that keeps the user's text whenever it still says the same thing is
what stops this from rewriting saved searches wholesale. A saved search that
was typed in full is saved in full; only a filter that has displaced the text
changes what is stored, and only then because the stored text would otherwise
be a lie.

## Consequences

- `SimpleFilter.toQueryText()` is a new writer: the filter's own vocabulary
  turned back into query text, and `null` for a filter the language cannot
  say. It writes a due date with the relation the filter has — `due:=today` is
  "on that day", where `due:today` is "on or before that day".
- `effectiveQueryText()` is the rule that picks between the two texts, and it
  lives beside the writer in the query package rather than in the ViewModel:
  the ViewModel holds the state, the query package holds what a query means.
- A saved search's text can differ from the field's text after a filter
  action. That is the point: the saved search has to reproduce the screen.
- The field's text still does not change when a filter is applied or cleared.
  ADR 2026-11-11's decision and its accepted price stand.
- The ViewModel test proves the round trip: query, apply a filter, clear the
  filters, save, load — the screen shows the same results, and the saved
  text is the query that was running.
- A query typed in full is still saved as it was typed, with a test that says
  so.

## Links

- Issue: https://github.com/gazon1/sing/issues/2742
- ADR: `2026-11-11-the-applied-filter-is-the-executed-query.md` — the filter is
  the executed query; this decision says what a saved search therefore carries.
- ADR: `2026-10-20-one-free-text-rule-for-every-kind-of-record.md` — the free
  text is one rule for every kind of record.
