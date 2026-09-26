---
title: "Search query language: AST, SimpleFilter, SavedSearch, canonical SearchViewModel"
date: 2026-09-23
tags: [search, query-ast, room, viewmodel, dsl]
status: accepted
---

## Context

Orgzly-revived's query/savedsearch subsystems needed to be ported into the KMP project. The existing search was a simple free-text string passed directly to SQLite `LIKE` — no structured filtering, no saved searches, no filter UI. We needed: (1) a typed AST for search queries, (2) a round-trippable SimpleFilter DSL for the UI, (3) per-profile saved searches persisted to Room, and (4) a canonical SearchViewModel with Intent/State pattern.

Three non-obvious design choices arose:

1. **Negation scoping** — `Not(...)` conditions need to invert predicates in post-filter, but only within the scope of that negation. A naive approach of wrapping predicates in `!p` loses the scoping.
2. **SimpleFilter `states` default** — Should "no state filter" be `null` (show all states) or `setOf(Active)` (show only active)? The builder's `state()` method accumulates states, so `null` + add semantics is cleaner than `setOf(Active)` + add semantics where single-state filters end up with wrong cardinality.
3. **SavedSearch stores raw `queryString`** — Should we store the parsed `Query` AST or the raw string the user typed? Storing the raw string avoids round-trip loss for complex queries that `SimpleFilterMapper` cannot express.

## Decision

### Query AST (Phase 1)

Use a sealed `Condition` interface as the canonical AST. Negation is a `Condition.Not(inner)` wrapper — not a `not: Boolean` flag on each condition. The `Query` data class holds an optional root `Condition`, `SortOrder`, `sortDescending`, and `Options`.

Syntax examples:
- `meeting` → `HasText("meeting")`
- `priority:high` → `HasPriority(HIGH)`
- `-tag:work` → `Not(HasTag("work"))`
- `tag:work tag:urgent` → `And(HasTag("work"), HasTag("urgent"))` → resolved to `ByTags(matchAll=true)`
- `due:today` → `Due(NOW, EQ)`
- `project:Plans` → `InProject("Plans")`

A Pratt parser (precedence climbing) handles `AND`/`OR`/`NOT`/`(`/`)` with correct precedence: NOT > AND > OR. The parser is `SingularityQueryParser(input: String)`, producing a `Query`.

### SearchQueryResolver (Phase 2)

`DefaultSearchQueryResolver` converts a `Query` AST into a `ResolvedSearchQuery` containing:
- `taskFilter: TaskFilter?` — DAO-executable filter, or null
- `needsPostFilter: Boolean` — whether a Kotlin predicate post-filter is needed
- `postFilter: (Sequence<Task>) -> Sequence<Task>` — predicates for conditions the DAO can't express (negated, OR, pinned, archived, has:description, complex date relations)
- `isOrPostFilter: Boolean` — whether the post-filter uses OR (true) or AND (false) semantics
- `freeText: String?` — text for note/project/tag search
- `unknownTagNames / unknownProjectNames` — names that couldn't be resolved

**Negation scoping**: A `negationDepth: Int` counter increments when entering a `Not` handler and decrements on exit. `addPostFilter(p)` wraps `p` in `if (negationDepth > 0) { { t -> !p(t) } } else { p }`, correctly handling double-negation `Not(Not(HasTag))` → positive predicate.

**DAO + post-filter coexistence**: Resolved tags always add both DAO IDs (to the `ByTag` filter) AND post-filter predicates. The DAO efficiently filters by ID; the post-filter handles edge cases. Negation only affects the post-filter polarity via `negationDepth`.

### SimpleFilter DSL (Phase 3)

`SimpleFilter` is a flat data class for the UI. Key field: `states: Set<TaskStatus>?` where `null` means "no state filter" (all states shown). Non-null is an explicit OR of selected states.

`SimpleFilterBuilder` is a Kotlin DSL with `@DslMarker`. `states` starts as `null`; calling `state(status)` does `states = (states ?: emptySet()) + status` — set replacement, not accumulation over a default.

`SimpleFilterMapper` round-trips `Query ↔ SimpleFilter`:
- `fromQuery`: flattens `And` nodes, maps each atom; unsupported conditions (OR, NOT, Archived, Scheduled) throw `UnsupportedSimpleFilterException`; complex date relations (`GT`, `LT`) silently degrade to `TODAY` (no exception).
- `toQuery`: builds conditions list; `null` states means no `HasStatus` added; empty filter produces `Query(condition = null, ...)`, not `HasText("")`; single condition is returned unwrapped.

`SimpleFilterSheet` is a `ModalBottomSheet` with `FilterChip` rows for state/priority/due, `SwitchRow` for hasDescription/pinned, and Sort chips. Local mutable state is committed only on "Apply".

### SavedSearch (Phase 4)

`SavedSearch` stores `id` (UUID), `userId`, `name`, `queryString` (raw input), `createdAt`, `updatedAt`. Raw string is stored — not the parsed `Query` — to avoid round-trip loss for complex queries.

`SavedSearchEntity` uses composite primary key `(id, user_id)` for per-profile isolation. Unique index on `(user_id, name)` prevents duplicate names per profile.

`SavedSearchRepository` extends `GenericUserScopedRepository<SavedSearch, SavedSearchId>` and adds `findByNameForUser` (for duplicate-name validation) and `upsert`.

Room migration 15→16 adds the `saved_searches` table via `@AutoMigration(from = 15, to = 16, spec = Migration15To16::class)`.

### Canonical SearchViewModel (Phase 4)

`SearchViewModel` uses a 7-argument constructor:
```
(searchUseCase, savedSearchRepo, parseQuery, tagLookup, projectLookup, clock, scope)
```

Sealed `SearchIntent` with: `OnQueryChange`, `OnApplyFilter`, `OnSaveCurrentSearch`, `OnLoadSavedSearch`, `OnDeleteSavedSearch`, `OnRenameSavedSearch`, `OnTogglePin`.

`SearchUiState` includes: `query`, `parsedQuery: Query?`, `activeFilter: SimpleFilter?`, `activeSavedSearchId`, `savedSearches: List<SavedSearch>`, `results`, `isSearching`.

`processIntent()` routes all intents. Debounced (300ms) `flatMapLatest` executes searches. Saved searches are observed via `savedSearchRepo.observeAll()`.

## Rationale

**`Not` wrapper over `not: Boolean`**: Canonical AST form. Every condition type has exactly one shape; the parser doesn't need to thread a negation flag through every match. The `negationDepth` counter in the resolver makes scoping correct without complex predicate wrapping.

**Raw `queryString` in SavedSearch**: The user's exact input is preserved. Complex queries (OR trees, negated conditions, custom date relations) cannot round-trip through `SimpleFilterMapper`, so storing the parsed form would silently lose information. Better to store the raw string and re-parse on load.

**`states: Set<TaskStatus>? = null`**: Builder accumulation semantics are cleaner (`null` + add = correct set) than a default set that accumulates. `isEmpty` check uses `states == null`. `toQuery` skips `HasStatus` emission when `states == null`.

**Complex date relations degrade to TODAY**: Throwing `UnsupportedSimpleFilterException` would prevent loading saved searches that use `due:>3d`. Degrading to TODAY is a reasonable UX compromise — the filter sheet UI doesn't expose these relations anyway.

**Composite PK `(id, user_id)` on SavedSearchEntity**: Matches the pattern established by `AgendaViewEntity`. Profile isolation is enforced at the database level, not just in the repository layer.

## Consequences

- Query AST lives in `feature/search/query/` — `Condition.kt`, `Query.kt`, `QueryInterval.kt`, `QueryTokenizer.kt`, `QueryParser.kt`, `SingularityQueryParser.kt`, `ResolvedSearchQuery.kt`, `SearchQueryResolver.kt`, `SimpleFilter.kt`, `SimpleFilterBuilder.kt`, `SimpleFilterMapper.kt`.
- `Not(Condition)` is the only negation representation — never add a `not: Boolean` flag to any condition data class.
- `SimpleFilter.states` is `null` for "no filter"; never default to `setOf(Active)` in new code.
- `SearchQueryResolver.addPostFilter` must check `negationDepth > 0` to determine polarity — never call `negationDepth--` without a matching `negationDepth++`.
- `SimpleFilterMapper.toQuery` produces `condition = null` (not `HasText("")`) for empty filters.
- `SavedSearch.queryString` is the raw user input — never try to normalize/format it on save.
- `SearchViewModel` always uses the 7-arg constructor for production; the 6-arg secondary constructor creates its own `AutoCloseableCoroutineScope`.
- Room schema version increments by 1 per feature migration; `Migration15To16` is the current head.
- `SearchUseCase` accepts both `Query` (structured) and `String` (raw, parsed internally) — the string overload is for backwards compatibility only; new code should pass `Query`.

## Links

- Commits: `e4aa6b3` (Phase 1), `9b62433` (Phase 2), `7274717` (Phase 3), `530740e` (Phase 4)
- New files: `Condition.kt`, `Query.kt`, `QueryInterval.kt`, `Relation.kt`, `Options.kt`, `SortOrder.kt`, `QueryTokenizer.kt`, `QueryParser.kt`, `SingularityQueryParser.kt`, `QueryParseException.kt`, `ResolvedSearchQuery.kt`, `SearchQueryResolver.kt`, `DaoAdapters.kt`, `SimpleFilter.kt`, `SimpleFilterBuilder.kt`, `SimpleFilterMapper.kt`, `SimpleFilterSheet.kt`, `SavedSearchId.kt`, `SavedSearch.kt`, `SavedSearchRepository.kt`, `SavedSearchEntity.kt`, `SavedSearchDao.kt`, `Migration15To16.kt`, `RoomSavedSearchRepository.kt`, `SearchViewModel.kt`
- Test files: `QueryIntervalTest.kt`, `QueryTokenizerTest.kt`, `SingularityQueryParserTest.kt`, `SearchQueryResolverTest.kt`, `SimpleFilterBuilderTest.kt`, `SimpleFilterMapperTest.kt`
- Related: `docs/decisions/2026-09-22-fake-overrides-link-schemes-savedpulse-tests.md` (prior art for Room test patterns)
