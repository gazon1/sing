---
title: "The mode is a property of every search surface, and the rule decides with it"
date: 2026-10-23
status: accepted
tags: [search, product, grammar, architecture]
---

# The mode is a property of every search surface, and the rule decides with it

## Context

The escape-hatch of 2026-10-22 gave the search screen a mode: "Word" (the
index's rule — a term matches a word by its beginning) and "Contains" (the
term matches wherever it occurs). The mode lived in `feature/search/query`,
next to the screen that owns the switch.

Two things were left behind by that decision.

**The mode was a property of one screen.** The note search, the link
autocompletion and the task filter went on without one. They search by the
word rule, and the search screen could answer a question they could not — and
nothing said so. The difference was silent, which is the kind of difference
that reads as a bug when it is found and as a feature when it is wanted.

**Two surfaces had no mode at all, and did not even follow a rule.** The
project list and the "add an existing task" popup filtered their rows with
`String.contains(ignoreCase = true)` — a third rule, written in two places,
folding neither the diacritics nor anything else. A search for "cafe" missed
"Café" there, while the search screen found it (F7). A surface that filters
without stating a mode answers a question nobody wrote down.

**One question was answered in two places.** `SearchMode.plan(rule)` returned
`BroadScan` for the contains mode, and `FreeTextRule.plan()` answered the same
question for the word mode. Two places answering one question are two answers,
and the second one answered a question the first one had already answered.

## Decision

**The mode moves to `core/search`, and it is a parameter of every search
surface's contract. The rule decides what the index can do with it.**

- `SearchMode` lives in `core/search`, beside the rule that needs it. The rule
  asks "what can the index do with this rule?", and the answer depends on the
  mode: in the contains mode the answer is "nothing", because the index stores
  words and not character positions. A type the rule needs cannot live outside
  the rule's own package.
- `FreeTextRule.plan(mode)` and `FreeTextRule.indexQuery(mode)` take the mode
  as an argument. `SearchMode.plan(rule)` is gone: the rule is the one that
  decides what the index can answer, and the mode is one of the inputs of that
  question.
- Every surface states the mode it searches in (F8 of the grammar). The notes
  list, the link autocompletion and the task filter state the word mode; the
  search screen states the mode the person chose. The project list and the
  "add an existing task" popup state the contains mode: they filter the rows
  they already hold, and a term may occur anywhere in a name, in a title or in
  a description. A
  surface that does not offer the switch states the mode it uses — that is
  different from a surface whose mode is an accident of where the code
  happens to be written.
- The contains mode is answered where a surface can answer it: the note search
  and the task filter read the rows and match them in memory, the same answer
  the search screen gives. The link autocompletion answers it the same way —
  its list is short, and the mode is a parameter of its contract either way.

## Consequences

- The mode is no longer a property of the screen: `TaskFilter.Search` carries
  it, `NotesRepository.search` takes it, `InternalLinkRepository.searchNotes`
  takes it. A caller that does not say which mode it searches in does not
  compile.
- The word mode stays the rule of the index, and the contains mode stays the
  escape-hatch. What changed is that the difference between surfaces is stated
  rather than implied.
- The contains mode reads the rows. That was already true of the search
  screen; it is now true of the other surfaces that offer the mode, and the
  surfaces say so in the code that does it.
- The rule of the mode is one rule for every kind of record (F3), so a surface
  that states a mode states the same mode the search screen states.
- The contains mode folds the diacritics the way the index folds them (F7), so
  the surfaces that state it now answer "cafe" with "Café" as well. Before this
  they filtered with `String.contains`, which folded nothing.

## Alternatives rejected

- **Keep the mode in the feature and pass a boolean into the rule.**
  `rule.plan(narrows: Boolean)` says what the caller already knows and hides
  what the rule has to decide. The mode is a name for two rules; the rule
  needs the name.
- **Leave the three surfaces without a mode and say so in the interface.**
  This was the alternative: the surfaces say plainly that they search by the
  beginning of a word. It is honest, and it leaves the difference between the
  surfaces as a difference — the search screen answers a question the others
  cannot, and the contract says so instead of the code.
- **Give every surface a switch.** The mode would then be a property of the
  screens rather than of the contracts, and three switches to maintain for one
  rule.

## Related

- ADR 2026-10-21 — the search asks the index.
- ADR 2026-10-22 — the index answers the positive terms, and the substring
  search is a mode the surface states.
- ADR 2026-10-23 — the rule folds the diacritics the way the index folds them.
- `docs/specs/search-query-grammar.md` — F6, F8 and the map of the owners in §9.
