# The substring search is a mode the surface states

Issue: [#889](https://github.com/gazon1/sing/issues/889) · Related: `docs/decisions/2026-10-22-the-substring-search-is-a-mode-the-surface-states.md`

## What

Give the person searching a way back to the old question — "does the text carry
the term *somewhere*" — without making the search answer two different things
by accident.

The full-text index stores *words*, so it can find the beginning of one and
nothing else: "arch" finds "architecture", and "port" does **not** find
"airport". That is rule F2a of the grammar, and the in-memory rule answers the
same question, so the index and the rule cannot disagree.

Concretely:

1. The search screen states the mode next to the field the query is written in:
   **Word** (the default) and **Contains**.
2. The mode changes the rule for **every** term of the query at once — a query
   never asks two questions at once.
3. The contains mode is answered by reading the rows, because the index cannot
   say whether a term occurs in the middle of a word — and the screen says what
   that costs.
4. The rule of the mode is one rule for every kind of record: a task by its
   title and its description, a note by its title, a project and a tag by their
   name.

## What is not in this change

- **The query language does not change.** There is no marker for the mode, and
  a query means the same thing wherever it is written down. The `*` is the
  index's own prefix syntax, and a marker left in a saved query is invisible
  to whoever reads it later.
- **The word mode does not change.** The index still answers the positive
  terms, the rule still answers the negated ones, and F2a still says what a
  term matches.
- **The search is not made faster.** The contains mode is the slow one, and it
  says so. A `LIKE` over the title and the description could narrow the
  candidates in the database — the way `TaskFilter.ByRegexp` already does for
  the agenda's saved selectors — but the rule's combinations (`OR`, `NOT`) are
  not expressible in one `LIKE`, and a second rule would be a second answer to
  the same question.
- **The AI tool does not change.** `SearchTasksTool` matches by occurrence
  already. It has no surface to state a mode on, and giving it one is a change
  of its own.

## The change in behaviour

The same query finds different records, and the person searching can see which
rule answered:

- "port" in the word mode finds nothing; "port" in the contains mode finds
  "Airport transfer" — for a task, a note, a project and a tag alike.
- A negated term follows the mode's rule: `NOT port` keeps "Airport transfer"
  in the word mode and rules it out in the contains mode.
- The contains mode reads the rows of the profile instead of asking the index,
  and the screen says so next to the switch.

## Why now

It is the one hole the change to the index left: the search got faster and
more exact, and the person searching lost the old question with no way back.
Issue #889 asks for an escape-hatch and names three shapes it could take — a
switch in the UI, a marker in the query, a separate search. The decision is
written down in the ADR; the short of it is that the mode has to be visible
where the question is asked, and one query has to ask one question.
