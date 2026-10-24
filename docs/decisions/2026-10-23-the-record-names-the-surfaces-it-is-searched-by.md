---
title: "The record names the surfaces it is searched by"
date: 2026-10-23
status: accepted
tags: [search, architecture, grammar]
---

# The record names the surfaces it is searched by

## Context

F3 says what a record is searched by: a task by its title and its
description, a note by its title, a project and a tag by their name.

That map was written at every call site, and the call sites could not be checked
against each other. The task autocomplete and the task filter answered "does
this task match" through `TaskDomain.matches`, which knew the map; the search
screen's ranking asked the rule about the *title* alone, on purpose, because
that was a different question; and the notes repository and the projects list
each carried a list of their own fields. Every one of those lists was a
statement about the same map, written in a place where the next reader had to
find it to know whether it was right — and the review of one PR found the
contains branch of the task autocomplete filtering by the title alone, while
the word branch asked the index, and the index holds the title and the
description.

The ceiling of an answer had the same shape: the index's note query carried
`LIMIT 20`, and the in-memory branch carried `NOTES_LIMIT = 20`, and the two
copies of the same number were the same number only until someone changed
one of them.

## Decision

**The record says what it is searched by, and the search says how much of an
answer there is. Both live in `core/search`, and both are asked through one
call.**

- `core/search/Searchable.kt` is the protocol: a record exposes the surfaces
  a term may match. `Task` says its title and its description, `Note` its
  title, `Project` and `Tag` their names. The models live in the features and
  the port lives in the core — the same way the rest of the repository keeps
  a port in the core and a policy in the feature.
- `SearchMode.matches(record, term)` is the one call a surface makes. It
  asks the record for its surfaces and asks the matcher about them; a caller
  no longer chooses the text, so a caller can no longer choose it wrong.
  A question about *one* surface — the ranking, which asks whether the title
  carries the word — still asks about that surface on purpose, and says so.
- `core/search/SearchLimits.kt` holds the ceilings. The index's query and the
  in-memory branch read the same number, so the number is written once. The
  size of an answer is still the size the mode produces: the index's ceiling
  is applied to its candidates and the in-memory ceiling to the rows the
  contains mode filtered, so the word mode may answer with fewer records than
  the ceiling and the contains mode with exactly as many.

## Consequences

- The rule of the mode is one rule for every kind of record, and now it is
  *asked* one way: `mode.matches(record, term)`. `TextMatch.matchesAny` is
  still there for the cases that are about text and not about a record — the
  ranking, which asks about one surface on purpose.
- A new kind of record states its own surfaces once, in the model, instead of
  in every repository that searches for it.
- The corpus grew a second rule. `FtsMatcherAgreementTest` compared the word
  rule with the real index; it now also asserts that the contains mode loses
  nothing the index found — the weaker rule has to find everything the
  stronger one finds — and that the contains mode folds the diacritics the
  way the index folds them, asking the index too. The property is one-sided
  on purpose: the contains mode answers *more* ("port" finds "airport"), and
  that is the escape hatch working.
- A phrase is not covered by that property, and the corpus says so: a phrase
  is a question about tokens that stand next to each other, while the
  contains mode asks whether the phrase occurs in a row. "snake_case
  identifier" holds the tokens "snake" and "case" next to each other and does
  not hold the words "snake case" in a row — two questions, not one.

## Alternatives rejected

- **Keep the lists of fields at the call sites.** This is what was there. The
  call sites disagreed, and nothing in the tree said that a call site was
  allowed to disagree.
- **Put the map in the core as data** — a table of "task → title, description".
  A table needs the models to be in the core, and they are in the features.
  The protocol keeps the core free of the models and lets each model answer
  for itself.
- **One predicate, but a private one per feature.** A private predicate per
  feature is the same duplication with a different name.

## Related

- ADR 2026-10-22 — the index answers the positive terms, and the substring
  search is a mode the surface states.
- ADR 2026-10-23 — the mode is a property of every search surface, and the
  rule decides with it.
- ADR 2026-10-23 — the rule folds the diacritics the way the index folds them.
- `docs/specs/search-query-grammar.md` — F3, F7, F8 and the map of the owners
  in §9.
