---
title: "The substring search is a mode the surface states, and the query language does not change"
date: 2026-10-22
status: accepted
tags: [search, product, grammar, performance]
---

# The substring search is a mode the surface states, and the query language does not change

## Context

The search asks the index which records carry the word, and reads only those.
The index stores *words*, so it can only find the beginning of one: "arch"
finds "architecture", and "port" does **not** find "airport". That is the rule
F2a of the grammar (`docs/specs/search-query-grammar.md`), and it is the price
of the index — the same price the in-memory rule pays, so that the two cannot
answer differently (ADR 2026-10-21, ADR 2026-10-22).

It is a noticeable change of behaviour, and the person searching has no way
back. Issue #889 asks for an escape-hatch and names three shapes it could
take:

1. a switch in the UI — "exact word / occurs anywhere";
2. a marker in the query — `*port`, or some other marked form;
3. a separate search — the ordinary one by word prefix, an "extended" one by
   occurrence.

The issue leans towards the third, on the ground that a substring search is
not served by the same index and needs a different execution path.

## Decision

**The escape-hatch is a mode the search surface states: "Word" (the default)
and "Contains". The query language does not change.**

The mode sits next to the field the query is written in, and it changes the
rule for **every** term of the query at once. It is not a per-term marker, and
a query does not carry it.

The rule of the mode:

- **Word** — F2a, unchanged: a term matches a word of the searchable text by
  its beginning. The index answers the positive terms.
- **Contains** — the term occurs **anywhere** in the searchable text,
  case-insensitively. The index cannot answer this question: it stores words,
  not character positions, so the mode reads the rows and filters them in
  memory. The rule is one rule for every kind of record — a task by its title
  and its description, a note by its title, a project and a tag by their name
  (F3) — and it is the same rule in Kotlin that the mode names in the spec.

The mode says what it costs. A search in the "Contains" mode reads the rows
of the profile instead of asking the index, and the surface says so next to
the switch, because a slower answer that is not announced is a slower answer
that is not believed.

### Why not a marker in the query

The `*` is the index's own syntax: in an FTS4 query it is the prefix of the
last token, which is exactly the rule F2a already implements. A marker that
looks like the index's syntax invites the reader to believe the query means
what it looks like — and it would mean something else, or nothing, depending
on the mode it was executed in.

A marker is also invisible where it matters. A saved search keeps the query
string; a query pasted into a note or a chat keeps the marker; the person
who reads it later cannot tell which rule answered it. The mode is visible
where the question is asked, and it stays visible in the answer.

And a marker allows mixing: one term by prefix, another by occurrence, in one
query. That is the shape the last three changes removed — "two sides of one
rule", a second answer to the same question next to the first. The mode
changes the rule for the whole query, so a query never asks two questions at
once.

### Why not a separate search

The mode differs from the ordinary search in one thing: the rule that matches
a term. Everything else — the query language, the filters, the saved
searches, the results, the screen that draws them — is the same. A second
search would duplicate all of it to answer the same question in a different
way, and it would hide the mode: the person searching would have to know that
a second search exists before they could ask the question they have.

### Why the mode is not in the query language

The query language says what a query *means*. The mode says which question is
asked of the records, and it is stated by the surface, not by the query. A
query therefore means the same thing wherever it is written down, and the
record of what was asked is the mode that was on.

The grammar is still changed — F2a gains the description of the escape-hatch —
because the rule of the mode is a rule of the free text, and the document
that states the rule of the free text has to state both of them.

## Consequences

- **The index is not asked in the "Contains" mode.** The mode's plan is
  `TextMatchPlan.BroadScan`: the rows are read and filtered in memory. The
  plan is the same named plan the negated-only rule already uses, and the
  difference is that this one is chosen by the person searching.
- **The scan can be narrowed later.** A `LIKE` over the title and the
  description could narrow the candidates in the database, the way
  `TaskFilter.ByRegexp` already does for the agenda's saved selectors. It is
  not done now: the rule's combinations (`OR`, `NOT`) are not expressible in
  one `LIKE`, and a second rule would be a second answer to the same
  question. The mode is honest about being the slow one, and a measurement —
  the kind ADR 2026-10-22 asks for before an optimisation — may make it
  cheaper.
- **The mode is one rule for every kind of record.** A task, a note, a
  project and a tag are matched by the same rule in both modes, so a search
  cannot answer differently depending on the kind of thing it is looking for.
- **The search screen is the only surface with the mode.** The AI tool
  (`SearchTasksTool`) matches by occurrence already — it filters titles with
  `contains(ignoreCase = true)`. That inconsistency is noted, not fixed here:
  the tool has no surface to state a mode on, and giving it one is a change
  of its own.
- **A query with the mode on is not the same question as a query with it
  off.** The mode is part of what was asked, and the answer says which rule
  answered it.

## Links

- Issue — [#889](https://github.com/gazon1/sing/issues/889)
- The grammar of the free text — `docs/specs/search-query-grammar.md` (F2a)
- ADR 2026-10-21: the search asks the index — `docs/decisions/2026-10-21-the-search-asks-the-index.md`
- ADR 2026-10-22: the index answers the positive terms — `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md`
- ADR 2026-10-22: the broad scan is measured — `docs/decisions/2026-10-22-the-broad-scan-share-is-measured.md`
