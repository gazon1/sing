# Feature Specification: Search

**Feature Branch**: `011-search`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: system/search-finds-task.yaml asserts real user behaviour and has no scenario"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Find a task by name (Priority: P1)

A user remembers roughly what a task was called but not where it is. They search its title and it appears. Without search, the only way to find an old task is to scroll every list the app has.

**Why this priority**: Search is the only way back to anything the user has not thought about recently. Every other feature here helps with what a user just did.

**Independent Test**: Can be validated by seeding a task with a distinctive title and searching for it.

**Acceptance Scenarios**:

1. **Given** a task exists, **When** the user opens search and types the task's title, **Then** the task appears in the results.

### Edge Cases

- The search input carries a `search_input` test tag, but results are rendered through the ordinary task list with their normal task-item ids. There is no result type of their own, which is why the assertion reads the task's own identity rather than a search result identity.
- Matching is on title text. Whether it is a substring match, case-insensitive, or ordered by relevance is not asserted here, and no flow pins it.
- The empty and no-match states of Search are a separate feature: see the empty-state scenarios.

## Scenario Matrix

```yaml
scenarios:
  - id: SEARCH-01
    title: Searching for a task's title finds the task
    area: feature.search
    priority: P1
    status: confirmed
    targets: [android]
  - id: SEARCH-02
    title: Cyrillic query text is rendered and matched correctly
    area: feature.search
    priority: P2
    status: confirmed
    targets: [android]
  - id: SEARCH-03
    title: Clearing the search query returns to the idle prompt
    area: feature.search
    priority: P1
    status: confirmed
    targets: [android]
  - id: SEARCH-04
    title: Filter by has-description toggles task results
    area: feature.search
    priority: P1
    status: confirmed
    targets: [android]
  - id: SEARCH-05
    title: Filter by pinned toggles task results
    area: feature.search
    priority: P1
    status: confirmed
    targets: [android]
  - id: SEARCH-06
    title: A query with no matches shows an explicit no-results state
    area: feature.search
    priority: P1
    status: confirmed
    targets: [android]
  - id: SEARCH-07
    title: A saved search persists and can be loaded
    area: feature.search
    priority: P2
    status: confirmed
    targets: [android]
  - id: SEARCH-08
    title: A term matches the beginning of a word, and not its middle
    area: feature.search
    priority: P1
    status: confirmed
    targets: [android]
  - id: SEARCH-09
    title: The result shows the word that was matched
    area: feature.search
    priority: P1
    status: confirmed
    targets: [desktop]
```

## Requirements

### Functional Requirements

- **FR-001**: The user MUST be able to reach a search input from the app shell.
- **FR-002**: Searching for a task's title MUST return that task among the results.
- **FR-003**: Search MUST distinguish "nothing typed" from "nothing matched".

### Success Criteria

- **SC-001**: A user can find a task they last touched a month ago without opening any list.
- **SC-002**: A search that finds nothing says so rather than showing an empty list.

## Out of Scope

- Search over notes, projects and tags. The flow searches for a task title and asserts nothing about other entity types.
- Ranking, fuzzy matching, and prefix behaviour — not asserted by any flow.
- Search-as-you-type versus search-on-submit.

## Risks

- The flow seeds a task with a *unique* title and searches for the whole of it. A search that only matched exact full titles, or only matched from the first character, would pass here; a search that returned every task unfiltered would also pass. This scenario proves the task is findable, not that the matching is precise — which is why FR-003 and the match-quality questions belong to other specs.