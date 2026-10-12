# Feature Specification: Calendar Browsing

**Feature Branch**: `010-calendar-browsing`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: calendar has three flows — month view, view-mode switch, tap-a-day — and no scenarios"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See the month at a glance (Priority: P1)

A user opens Calendar to answer "what is happening today". The Month view is the default answer: weekday columns with a month of days in one screen.

**Why this priority**: It is what the screen is for, and it is the view a user lands on.

**Independent Test**: Can be validated by opening the Calendar tab and reading the grid.

**Acceptance Scenarios**:

1. **Given** the Calendar tab, **When** the user opens it, **Then** the current month renders as weekday columns of numbered days, first through last.

---

### User Story 2 - Switch to the view that answers the question (Priority: P2)

Month is the wrong shape for "what is on Tuesday" and for "how busy am I this week". The user switches to Week, Day or Year, and the control must report which mode they are in — otherwise switching is navigation without feedback.

**Why this priority**: The grid being visible at all times makes the switch feel optional, which is what keeps it at P2.

**Independent Test**: Can be validated by switching through every mode and reading the control each time.

**Acceptance Scenarios**:

1. **Given** the Calendar tab in Month view, **When** the user opens the mode control and chooses another mode, **Then** the view changes and the control reports the mode now selected.

---

### User Story 3 - Go from the month to a single day (Priority: P1)

A user sees something on the 14th and taps it. They land on that day, not on the wrong one — with 30-odd identical-looking numbers on screen, "the day opened" and "the right day opened" are different claims.

**Why this priority**: Getting to the day is the main reason to look at a month, and picking the wrong day silently is the failure that makes the view untrustworthy.

**Independent Test**: Can be validated by tapping a day and reading the date header.

**Acceptance Scenarios**:

1. **Given** the Month view, **When** the user taps a numbered day, **Then** that day's view opens with its date in the header.

### Edge Cases

- **Day cells carry no testTag and no contentDescription** — only their number as text. Every assertion here is therefore on visible text, and a future accessibility improvement could change what is addressable without changing what is true. The flows say so; the scenarios do not depend on the tag.
- In a single month grid each date number appears once, so matching the number as text is unambiguous. A leading or trailing day from the adjacent month would break that, and no flow renders one.
- The mode control is labelled with the *current* mode, so the header is asserted on the control rather than on the month title — Week shows a date range, not "Month".

## Scenario Matrix

```yaml
scenarios:
  - id: CALENDAR-01
    title: The Month view renders the current month with weekday columns
    area: feature.calendar
    priority: P1
    status: confirmed
    targets: [android]
  - id: CALENDAR-02
    title: The view-mode control switches modes and reports the selection
    area: feature.calendar
    priority: P2
    status: confirmed
    targets: [android]
  - id: CALENDAR-03
    title: Tapping a day in Month view opens that day's view
    area: feature.calendar
    priority: P1
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: The Calendar tab MUST open in Month view showing the current month.
- **FR-002**: Month view MUST render weekday columns and every day of the month.
- **FR-003**: The user MUST be able to switch between Month, Week, Day and Year.
- **FR-004**: The mode control MUST display the currently selected mode.
- **FR-005**: Selecting a day in Month view MUST open that day's view and name the date.

### Success Criteria

- **SC-001**: A user can reach a single day's agenda from the Calendar tab in at most two interactions.
- **SC-002**: After switching view, the user can tell which mode they are in without counting the grid shape.

## Out of Scope

- Event creation and editing from the calendar grid.
- Syncing — that is `feature.calendar_sync`, with its own scenarios.
- Adjacent-month days shown in the grid.

## Risks

- Two of these scenarios assert on rendered text rather than on a stable test tag, because day cells expose neither. If a future change adds a contentDescription, the assertions stay valid; if it removes the visible number, they fail loudly, which is the preferable direction.