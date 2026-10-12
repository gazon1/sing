# Feature Specification: Empty States

**Feature Branch**: `004-empty-states`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: six negative-path flows exist but are attached to no scenario, so the matrix cannot see them"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Open an empty screen and be told what to do (Priority: P1)

A new user opens a screen that has nothing in it yet. Every list in the app starts empty, and an empty screen that renders as a blank rectangle is indistinguishable from a broken one. Each list says what it is empty of and how to put something in it.

**Why this priority**: It is the first thing every new user sees on every screen. It is also the cheapest class of scenario in the app to get wrong and the easiest to leave untested, because a developer testing on a seeded device never sees an empty list.

**Independent Test**: Can be validated by launching with no data and opening each of the six screens.

**Acceptance Scenarios**:

1. **Given** no tasks exist, **When** the user opens Inbox, **Then** the empty state names what is missing rather than showing a blank list.
2. **Given** no notes exist, **When** the user opens the notes list, **Then** the empty state is shown.
3. **Given** no projects exist, **When** the user opens the Plans tab, **Then** the empty state is shown.
4. **Given** nothing is archived, **When** the user opens the Archive screen, **Then** the empty state is shown.
5. **Given** no tags exist, **When** the user opens the Tags tab of Settings, **Then** the empty state is shown.

---

### User Story 2 - Search that says whether it is waiting or has nothing (Priority: P2)

A user opens Search with nothing typed. The screen then has two distinct empty states, and conflating them is the failure: "type something" and "nothing matched" are different answers and a user who has typed a query deserves to be told the query found nothing rather than being invited to type again.

**Why this priority**: It is the one screen with two empty states, so it is the one place the pattern can be got wrong while the other five still pass.

**Independent Test**: Can be validated by opening Search empty, then searching for a string that matches nothing.

**Acceptance Scenarios**:

1. **Given** an empty query, **When** the user opens Search, **Then** the screen invites them to search.
2. **Given** a query with no matches, **When** the user searches, **Then** the screen says nothing matched, and does not ask for a query again.

### Edge Cases

- The Tags tab's list container carries a `tags_list` test tag that does not surface in the semantics tree, so the flow addresses the screen rather than the list. Asserting on the tag would make the scenario depend on a selector that does not exist where the assertion thinks it does.
- A screen that is empty because a filter matched nothing is not the same as a screen with no data — that distinction belongs to Search, and is why it is split from the other five.

## Scenario Matrix

```yaml
scenarios:
  - id: NEGATIVE-01
    title: The Inbox empty state names what is missing
    area: feature.negative
    priority: P1
    status: confirmed
    targets: [android]
  - id: NEGATIVE-02
    title: The notes list shows an empty state
    area: feature.negative
    priority: P1
    status: confirmed
    targets: [android]
  - id: NEGATIVE-03
    title: The Plans tab shows an empty state with no projects
    area: feature.negative
    priority: P1
    status: confirmed
    targets: [android]
  - id: NEGATIVE-04
    title: The Archive screen shows an empty state
    area: feature.negative
    priority: P1
    status: confirmed
    targets: [android]
  - id: NEGATIVE-05
    title: Search distinguishes an idle prompt from no matches
    area: feature.negative
    priority: P2
    status: confirmed
    targets: [android]
  - id: NEGATIVE-06
    title: The Tags tab shows an empty state
    area: feature.negative
    priority: P1
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: Every task-, note-, project- and tag-backed list MUST render an explicit empty state when it holds nothing.
- **FR-002**: An empty state MUST state what the screen is empty of.
- **FR-003**: Search MUST distinguish an idle prompt from a query that matched nothing.

### Success Criteria

- **SC-001**: No list in the app renders as a blank area with no message.
- **SC-002**: A user who searches for something absent is told so, rather than being asked to search again.

## Out of Scope

- Empty states on screens that cannot be empty (Pomodoro's timer, a running sync indicator).
- The copy itself. These scenarios assert that a message is present and distinct, not what it says — that is a design review, not a test.

## Risks

- These six flows only exercise the no-data path. A screen that shows a correct empty state and a broken populated state would pass all of them.