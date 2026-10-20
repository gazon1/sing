---
title: "A search that matched nothing is an answer, not a blank screen"
date: 2026-10-20
status: accepted
tags: [search, product, ui-state, query-ast]
---

## Context

The search screen is supposed to show three states, and it showed two of them
wrongly.

Two Maestro flows say so, and they say it in their comments rather than only in
their assertions:

- `Maestro/flows/search/06-no-results.yaml` — *"The flow distinguishes this from
  the idle prompt (empty query) and from results that happen to be empty for
  other reasons — the no-results state is asserted by its own testTag
  (`search_empty_results`)."*
- `Maestro/flows/negative/05-empty-search.yaml` — *"Nothing typed yet -> a
  prompt, not a blank screen."*
- `Maestro/flows/search/03-clear.yaml` — *"After clearing, the idle prompt
  returns."*

The app contradicted them. A query that matched nothing
(`zzzznomatchzzzz999999`) returned a list of tasks — the nightly screenshot for
`search-no-results` shows three "Buy milk" tasks and other flows' seeds — so the
no-results state never appeared, and the flow could only pass by accident.

The cause was not in the screen. `SearchScreen.kt` already branched on
`state.query.isBlank()` and on whether the results were empty. The cause was one
layer down, in `DefaultSearchQueryResolver`:

```kotlin
is Condition.HasText -> ctx.addText(c.text)
```

A word that matches no condition rule is a `HasText` condition
(`docs/specs/search-query-grammar.md` §3 G5). The resolver put it into
`freeText` — "text for note/project/tag search" — and produced **no predicate
for tasks at all**. The task flow therefore had no filter to apply:
`resolved.taskFilter` was null, `resolved.needsPostFilter` was false, and
`SearchUseCase` collected `taskRepo.observeAll()`. Every task came back for
every query.

`Condition.HasText` says in its own KDoc that it is *"Full-text search across
task title and description"*, and `TaskFilter.Search` in the tasks feature
already implemented exactly that rule. The translation between them was missing.

## Idea

- **A.** Make the screen show one empty state — the no-results state — for both
  an empty query and a query that matched nothing. The flows lose their
  distinction, and the user is told "no results" before asking a question.
- **B.** Keep the screen's branching as it is and fix the results: a free-text
  condition must constrain the tasks, the way every other condition does. The
  screen then has three honest states, because the results now say what
  happened.
- **C.** Keep the screen's branching, fix the results, and *additionally* make
  the screen's decision a pure function the module can assert — so the three
  states are stated in code and in tests, not only in a composable nobody can
  reach from `commonTest`.

## Decision

**C.**

- **`Condition.HasText` produces a task predicate.** The resolver turns a
  free-text word into an in-memory post-filter over tasks, using the tasks
  feature's own rule — `TaskDomain.matchesFilter(task, TaskFilter.Search(text),
  today)` — so the search screen and the task list cannot drift apart on what
  "contains" means. The word still goes into `freeText` for notes, projects and
  tags, unchanged.
- **The screen's decision is a pure function.** `searchContent(query, results)`
  returns `SearchContent.IdlePrompt`, `SearchContent.NoResults` or
  `SearchContent.Results`, and `SearchScreen.kt` draws exactly what it
  returns. The idle prompt wins over results: a blank query is not a query, and
  the results of the previous one must not linger under it. A non-empty query
  with no results is `NoResults` — never `IdlePrompt`, which would tell the
  user to type a query they have already typed.

## Rationale

- **The flows are the statement of the product, and they were right.** The
  distinction between "nothing has been searched yet" and "the search found
  nothing" is not a test convenience. One is an invitation, the other is an
  answer, and a screen that shows the invitation after the search has run is
  lying about what it knows. The flows assert them with two test tags, and the
  comment in `06-no-results.yaml` spells out why: the no-results state has to
  be distinguishable from *"results that happen to be empty for other
  reasons"*.
- **A defect that hides behind a correct-looking screen is a defect in the
  results, not in the screen.** The screen branched correctly and was handed
  the wrong data. Fixing the screen would have produced a no-results state for
  a query that matched every task — technically satisfying the flow, and
  nonsense to a user.
- **A missing translation is the defect.** The resolver's job is to turn a
  condition into a way to select tasks. `HasText` had no translation for tasks
  at all, so the task list was "everything" for every text query. The
  `TaskFilter.Search` rule already existed in the tasks feature; the resolver
  now uses it instead of inventing a second one.
- **The decision is stated where it can be checked.** The three states were
  previously a `when` inside a composable with no Compose test harness in
  `shared`. `searchContent` is a pure function, so the states are asserted in
  `commonTest` — the level this repository can actually run.

## Consequences

- **A text query returns the tasks that match it.** A query for a task title
  finds that task; a query that matches nothing returns nothing, and the screen
  says so.
- **`HasText` now sets `needsPostFilter`.** A query with free text is
  post-filtered in memory, after the DAO filter and before sort, limit and
  offset. A query with no free text is unaffected: it still resolves to the
  same DAO filter it did before.
- **The text rule has one owner.** "A task matches this text" is
  `TaskFilter.Search`, in the tasks feature. The search feature consumes it
  rather than restating it.
- **The free text of a multi-word query is a conjunction for tasks.** `hello
  world` is `AND(HasText("hello"), HasText("world"))` in the AST, so a task must
  contain both terms. Notes, projects and tags keep matching the joined phrase
  — that path is unchanged and is not part of this decision.
- **The screen can no longer drift from its states.** The composable draws
  `searchContent(query, results)`; it does not decide anything itself.
- **What a spec for this would have to say** (not written here — the openspec
  layer is owned elsewhere): the three states and their test tags; that the
  idle prompt takes precedence over results for a blank query; that a
  non-empty query with an empty result set is the no-results state and never
  the idle prompt; and that a free-text condition constrains tasks by title and
  description.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/SearchQueryResolver.kt`
  — the missing translation
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchContent.kt`
  — the three states as a pure function
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchScreen.kt`
  — draws what `searchContent` returns
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/search/SearchContentTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/search/SearchTextQueryTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/search/presentation/viewmodel/SearchViewModelTest.kt`
- `Maestro/flows/search/06-no-results.yaml`,
  `Maestro/flows/negative/05-empty-search.yaml`,
  `Maestro/flows/search/03-clear.yaml` — the flows that state the intent
- `docs/specs/search-query-grammar.md` §3 G5 — a word that matches no rule is
  free text
- `2026-09-23-search-query-language.md` — the query language ADR this
  completes
