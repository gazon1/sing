---
title: "One free-text rule for every kind of record"
date: 2026-10-20
status: accepted
tags: [architecture, search, product]
---

## Context

A search query meant two different things at once, and which one it meant depended on
what kind of thing the user was looking for.

Tasks were matched **per term**. Every `HasText` condition became a task predicate of
its own, and the task post-filter combined them with the query's connective — so
`quarterly report` asked for a task containing *quarterly* **and** a task containing
*report*.

Notes, projects and tags were matched against the **joined free text** — the whole
query as one phrase. `quarterly report` asked for a note, a project or a tag whose
searchable text contained the string *quarterly report*.

The two readings disagree on the same two words. A task titled "Report quarterly"
was found — the task path wanted the words, not the order. A note with the same title
was not: the note path wanted the phrase `quarterly report`, and the title said
`Report quarterly`. The user typed the same query and got a different answer
depending on the tab.

Neither reading was written down. The grammar (`docs/specs/search-query-grammar.md`)
said what a query *parses* into and stopped there; what a `HasText` condition
*matches* was left to each implementation, which is why the two implementations
disagreed.

The query language itself already answered the question the free text was asking:
§3 G2 makes juxtaposition a conjunction, so `hello world` parses into
`AND(HasText("hello"), HasText("world"))`. The notes and tags path was reading a
different grammar — one where `a b` was a phrase — wearing the same syntax.

## Idea

- **A.** Keep the two readings and document them. The task list and the other kinds
  answer different questions, and the difference is a feature.
- **B.** One rule for every kind: a query's free text is a list of terms, every term
  has to match, and the connective decides whether they are requirements or
  alternatives.
- **C.** One rule, and it is a disjunction: any term may match. Widening beats
  narrowing when the list is short.
- **D.** One rule, and it is the joined phrase: the free text has to occur as written,
  for every kind.

## Decision

**B** — one rule, one owner, and it follows the query's connective.

- The rule is a type, `FreeTextRule`, and it has one owner: the resolver builds it
  from the parsed query, and every kind of record is measured against it.
- **Every term has to match** by default. A query contains an `OR`, and its terms
  become alternatives. A term under `NOT` must not match.
- **The searchable text is the kind's own.** A task is searched by title and
  description, a note by its title, a project or a tag by its name. The rule does not
  change what is searchable for any kind; it only decides how the terms are combined.
- **A query with no free text selects no note, project or tag.** The structured terms
  of a query are filters over tasks, and a query that states none has no free-text
  part for the other kinds to match.
- The rule is written down as §6 of the query grammar (F1–F5), with the reasoning for
  the conjunction in §6.1 and a worked example in §6.2.

## Rationale

- **A query that means different things per tab is a bug the user cannot report
  usefully.** "Search finds my note but not my task" is not a description of a
  feature. The two readings were not two answers to one question either — they were
  two grammars behind one syntax.
- **B is what the query language already said.** §3 G2 makes juxtaposition a
  conjunction. A disjunction would make `hello world` mean something different from
  `hello AND world`, which contradicts G2's own rule that the explicit operator
  changes nothing. The joined phrase is not a reading of the AST at all: `a b` would
  mean something no operator can express.
- **C widens the list on every keystroke.** The user types into a search box to
  narrow a list they are already looking at. A record carrying one of two words is a
  candidate to refine, not an answer — and a search that returns more results than
  the previous keystroke is a search the user stops trusting.
- **D answers a question the user did not ask.** A phrase search is right when the
  user wants a phrase, and the grammar already has a way to say one: a quoted string is
  a single term (§3 G6), so `"quarterly report"` is one term that has to occur as
  written. The unquoted join does not need a second meaning.
- **One owner, not one implementation.** The rule used to live in the task post-filter
  on one side and in three string comparisons on the other. Now it is one object the
  resolver builds and every kind is measured against, so the next kind of record
  cannot be added with a different reading.
- **F5 is the honest answer for a structured query.** `status:open` filters tasks.
  Reporting the notes and projects whose name happens to contain the literal text
  `status:open` was never what anyone wanted, and it was what the joined phrase
  produced.

## Consequences

- **A multi-word query now finds notes and projects the same way it finds tasks.**
  `quarterly report` needs both words, in whatever text the kind is searched by.
- **The notes path no longer asks the repository for a phrase search.** The notes
  repository searches titles, and the rule needs the terms separately, so the use case
  observes the notes and applies the rule to each. The searchable surface did not
  change; the matching did.
- **`ResolvedSearchQuery` carries both forms.** `freeText` is the joined string, kept
  for display; `freeTextRule` is what records are matched against. A reader who
  reaches for the string gets the phrase, and a reader who reaches for the rule gets
  the rule.
- **The grammar grew a section.** §6 is now "Free-text matching", and conformance
  means §1–§6.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/FreeTextRule.kt` —
  the rule and its owner
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/SearchQueryResolver.kt` —
  where the rule is built
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchUseCase.kt` —
  where every kind is measured against it
- `docs/specs/search-query-grammar.md` — §6 F1–F5, §6.1, §6.2
- `2026-09-23-search-query-language.md` — the language this rule belongs to
- `2026-10-20-a-search-that-matched-nothing-is-an-answer.md` — the empty state, which
  is the other half of what a search owes the user
