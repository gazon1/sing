# Feature Specification: Plans Timeline and Projects Menu

**Feature Branch**: `016-plans-timeline-projects-menu`
**Created**: 2026-10-26
**Status**: Draft

**Input**: User description: "Убрать скрин plans - список проектов и встроить древовидную иерархию проектов в меню. Экран plans сделать как upcoming в singularity-app.ru - прлсто последовательные секции-дни списком и в кажлой секции - задачи на этот день, гранулярность со дня можно менять на нелелю, месяц и т.д., а кнопку навигации в календарь убрать в меню"

**What this changes, in one paragraph**: The Plans tab stops being a project list and becomes a chronological timeline of upcoming work — plain sequential day sections, each listing the tasks of that day, with the time bucket switchable between day, week and month. The project hierarchy moves out of the tab bar and into the app menu, where it is rendered as an expandable tree. The Calendar leaves the bottom navigation bar and becomes an entry of the menu (bottom sheet on Android, drawer on Desktop).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read the upcoming work as a sequence of days (Priority: P1)

A user opens Plans to answer "what is coming up". Instead of a project list, they get the days
themselves: today first, then tomorrow, then each following day, and inside each day the tasks
due on it.

**Why this priority**: This is the whole point of the screen. Until the tab shows days, the
timeline the user asked for does not exist, and every other part of this feature is decoration
around it.

**Independent Test**: Give the app tasks due on three different dates, open Plans, and read the
sections from top to bottom.

**Acceptance Scenarios**:

1. **Given** tasks due on three different dates, **When** the user opens Plans, **Then** the days
   appear in chronological order and each day section lists exactly the tasks due on that day.
2. **Given** a task due today, **When** Plans renders, **Then** the task is in the "Today"
   section and in no other section.
3. **Given** a task due in six days, **When** Plans renders, **Then** it is in that day's section
   and not in the section of any other day.
4. **Given** several empty days between two days that have tasks, **When** Plans renders, **Then**
   only the days that hold tasks are shown, and the days that are shown keep their chronological
   order.
5. **Given** a task in a day section, **When** the user taps it, **Then** the task's detail screen
   opens.
6. **Given** a new task created from a day section, **When** the editor opens, **Then** the due
   date is prefilled with that section's date.
7. **Given** no task falls inside the current horizon, **When** Plans opens, **Then** an explicit
   empty state says there is nothing scheduled, and no task without a due date is listed.

---

### User Story 2 - Change the granularity of the timeline (Priority: P1)

A day is the right bucket for a single day and the wrong bucket for a fortnight. The user
switches the timeline between day, week and month and the sections re-group accordingly.

**Why this priority**: Without a granularity switch the timeline answers only one question at a
time, and the "and so on" in the request is exactly the demand for more than one bucket.

**Independent Test**: Switch through all three granularities on a task set that spans more than
one month and read the section headers each time.

**Acceptance Scenarios**:

1. **Given** Plans in day granularity, **When** the user switches to week, **Then** the sections
   become calendar weeks in chronological order, and each week section holds the tasks due within
   that week.
2. **Given** Plans in day granularity, **When** the user switches to month, **Then** the sections
   become calendar months in chronological order, and each month section holds the tasks due within
   that month.
3. **Given** any granularity, **When** the user reads the granularity control, **Then** it names
   the granularity currently in effect.
4. **Given** a task whose due date falls inside one period, **When** the granularity is switched,
   **Then** the task still appears exactly once, in the period that contains it.
5. **Given** the user last worked in week granularity, **When** the user opens Plans again later,
   **Then** Plans opens in week granularity.
6. **Given** a task due in 45 days, **When** the user switches between granularities, **Then** the
   task appears in month granularity and in neither day nor week granularity.
7. **Given** the timeline in any granularity, **When** a section header is read, **Then** it names
   the period it covers — the date for a day, the date range for a week, the month and year for a
   month.

---

### User Story 3 - Reach projects from the menu as a tree (Priority: P1)

Projects are hierarchical, and the tab bar has no room for a hierarchy. The user finds them in
the menu, where the parent-child structure is visible and can be opened up.

**Why this priority**: It is the reason the project list leaves the tab bar. If the menu tree
does not show the hierarchy, the move removes a surface without giving anything back.

**Independent Test**: Create a parent project with a child project, open the menu, expand the
parent, and read the child under it.

**Acceptance Scenarios**:

1. **Given** a project that has a child project, **When** the user opens the menu, **Then** the
   Projects section lists the parent project and, when the parent is expanded, the child appears
   indented under it.
2. **Given** a project in the tree, **When** the user taps it, **Then** the project's detail
   screen opens.
3. **Given** a project without children, **When** the tree renders, **Then** that project shows
   no expander control.
4. **Given** a project created elsewhere in the app, **When** the user opens the menu, **Then**
   the new project is in the tree without the menu needing to be reopened first.
5. **Given** a project renamed elsewhere in the app, **When** the user opens the menu, **Then**
   the tree shows the new name.
6. **Given** a closed project, **When** the user opens the menu, **Then** that project is not in
   the tree.
7. **Given** the Projects section of the menu, **When** the user looks for the full project list,
   **Then** an entry opens it.

---

### User Story 4 - Reach the Calendar from the menu (Priority: P2)

The bottom navigation bar carries too much and the Calendar is the least load-bearing item on it.
The Calendar moves into the menu, where it is still one tap away.

**Why this priority**: The Calendar is a real screen with real users, so it must stay reachable —
but where it lives is a placement decision, not a capability.

**Independent Test**: Open the bottom navigation on Android and look for the Calendar button; open
the menu on both platforms and look for the Calendar entry.

**Acceptance Scenarios**:

1. **Given** the app on Android, **When** the user looks at the bottom navigation bar, **Then**
   there is no Calendar button on it.
2. **Given** the app on Android, **When** the user opens the menu, **Then** the menu holds a
   Calendar entry and choosing it opens the Calendar.
3. **Given** the app on Desktop, **When** the user opens the navigation drawer, **Then** the
   drawer holds a Calendar entry and choosing it opens the Calendar.
4. **Given** the Calendar opened from the menu, **When** the user navigates back, **Then** the app
   returns to the screen the Calendar was opened from and the menu is closed.

---

### Edge Cases

- **Overdue tasks** belong to no future day. They are shown in a leading "Overdue" section, before
  the first dated section, and never inside a day, week or month section.
- **Tasks without a due date** are not on a timeline at all — there is no period they belong to.
  They stay where the app keeps undated work.
- **A task the user has already completed** is not on the timeline any more, and leaves its section
  as soon as it is marked done.
- **A task due beyond the horizon** of the current granularity is not shown. The horizon differs
  per granularity, so the same task appears under one granularity and not under another.
- **The month boundary** is a real edge: a task due on the first of a month belongs to that month
  and not to the month before it, even though the two sections are adjacent in the list.
- **The week starts on Monday**, matching the app's existing relative week buckets. A task due on
  Sunday belongs to the week that started the preceding Monday.
- **The horizon is measured from a single "today"** for the whole screen. A screen left open
  across midnight does not re-label its first section halfway through the read.
- **A project whose parent was removed** has no place in the hierarchy; it is shown at the top
  level rather than disappearing.
- **Deep hierarchies** (a project nested several levels down) must stay usable in the menu: the
  tree cannot require scrolling to reach a node the user can see the name of.
- **An empty timeline** — no task falls inside the current horizon — shows an explicit empty
  state, not a blank screen.
- **A task that changes date while the screen is open** moves between sections without the user
  reloading anything.

## Scenario Matrix

```yaml
scenarios:
  - id: PLANS-01
    title: Plans renders days in chronological order with that day's tasks
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-02
    title: A task appears in exactly one section at every granularity
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-03
    title: The granularity control switches between day, week and month and names the selection
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-04
    title: The chosen granularity is restored the next time Plans is opened
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-05
    title: Overdue tasks lead the timeline and never sit inside a dated section
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-06
    title: The Plans tab no longer shows a project list
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-07
    title: The horizon follows the granularity
    area: feature.plans
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-08
    title: A section header names the period it covers
    area: feature.plans
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-09
    title: A task is opened from a section and a new task inherits that section's date
    area: feature.plans
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: PLANS-10
    title: An empty horizon shows an empty state, and undated tasks are absent from the timeline
    area: feature.plans
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: MENU-01
    title: The menu shows projects as an expandable tree with children indented
    area: feature.menu
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: MENU-02
    title: Tapping a project in the tree opens the project detail screen
    area: feature.menu
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: MENU-03
    title: The project tree reflects creations and renames made elsewhere
    area: feature.menu
    priority: P1
    status: confirmed
    targets: [android, desktop]
  - id: MENU-04
    title: The Calendar is an entry of the menu on Android and Desktop
    area: feature.menu
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: MENU-05
    title: A project without children shows no expander, and a closed project is absent from the tree
    area: feature.menu
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: MENU-06
    title: The full project list stays reachable from the Projects section of the menu
    area: feature.menu
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: NAV-01
    title: The bottom navigation bar no longer holds a Calendar button
    area: feature.nav
    priority: P2
    status: confirmed
    targets: [android, desktop]
  - id: NAV-02
    title: Android and Desktop expose the same menu entries and the same project tree
    area: feature.nav
    priority: P2
    status: confirmed
    targets: [android, desktop]
```

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Plans tab MUST open a chronological timeline of upcoming tasks and MUST NOT open
  a project list.
- **FR-002**: Tasks MUST be grouped into sections, one section per time period, and every open task
  MUST appear in exactly one section. A completed task MUST NOT appear on the timeline: it shows
  the work that is still ahead, and a task the user has already finished has left the plan.
- **FR-003**: Sections MUST be ordered chronologically, earliest period first.
- **FR-004**: The user MUST be able to switch the timeline granularity between day, week and month.
- **FR-005**: In day granularity, one section MUST represent one calendar day and MUST hold exactly
  the tasks due on that day.
- **FR-006**: In week granularity, one section MUST represent one calendar week running from Monday
  to Sunday and MUST hold exactly the tasks due within that week that are not overdue.
- **FR-007**: In month granularity, one section MUST represent one calendar month and MUST hold
  exactly the tasks due within that month that are not overdue.
- **FR-008**: The control that switches granularity MUST name the granularity currently in effect.
- **FR-009**: Day granularity MUST cover the next 30 days including today, week granularity the
  next 12 weeks and month granularity the next 12 months, counted from today.
- **FR-010**: Only periods holding at least one task MUST be rendered as sections.
- **FR-011**: Overdue tasks MUST be rendered in a leading "Overdue" section, before the first dated
  section, and MUST NOT be rendered inside a dated section.
- **FR-012**: Tasks without a due date MUST NOT appear on the timeline.
- **FR-013**: Each section header MUST name its period: the date for a day, the date range for a
  week, the month and year for a month.
- **FR-014**: The chosen granularity MUST be remembered for the current user profile and restored
  the next time Plans is opened.
- **FR-015**: Opening a task from a timeline section MUST open that task's detail screen.
- **FR-016**: Creating a task from a timeline section MUST prefill the due date with the section's
  date in day granularity and with the first date of the section in week and month granularity.
- **FR-017**: The task-row interactions available on task lists MUST remain available on the
  timeline, including completing a task and swiping a task away.
- **FR-018**: Plans MUST show an explicit empty state when no task falls inside the current horizon.
- **FR-019**: The menu MUST contain a Projects section that renders the user's projects as a tree.
- **FR-020**: Projects without a parent MUST be listed at the top level of the tree, and projects
  with a parent MUST appear under that parent, indented, when the parent is expanded.
- **FR-021**: Every project node MUST be expandable and collapsible, and a project without children
  MUST show no expander control.
- **FR-022**: Tapping a project node MUST open that project's detail screen.
- **FR-023**: The project tree MUST reflect creations, renames, moves and deletions made elsewhere
  in the app without requiring the menu to be closed and reopened.
- **FR-024**: Closed projects MUST NOT appear in the project tree.
- **FR-025**: The full project list, with its search, sorting and closed projects, MUST remain
  reachable from the Projects section of the menu.
- **FR-026**: The bottom navigation bar MUST NOT contain a Calendar button.
- **FR-027**: The Calendar MUST be reachable as an entry of the app menu — the bottom sheet on
  Android, the navigation drawer on Desktop — and the menu MUST be its only new entry point.
- **FR-028**: The Calendar screen's own behaviour MUST NOT change by this feature.
- **FR-029**: Android and Desktop MUST expose the same menu entries and the same project tree.
- **FR-030**: The Upcoming view MUST remain available and unchanged by this feature.

### Requirement traceability

Every functional requirement below is exercised by at least one acceptance scenario, and every
scenario in the matrix above is carried by a test or a Maestro flow. The carriers are named in the
feature's plan; the scenario files under the traceability scenarios directory hold the behaviour
each one asserts.

| Requirements | Exercised by |
|---|---|
| FR-001 | PLANS-06 |
| FR-002 | PLANS-01, PLANS-02, PLANS-05 |
| FR-003 | PLANS-01 |
| FR-004 | PLANS-03 |
| FR-005 – FR-007 | PLANS-01, PLANS-03, PLANS-08 |
| FR-008 | PLANS-03 |
| FR-009 | PLANS-07 |
| FR-010 | PLANS-01 |
| FR-011 | PLANS-05 |
| FR-012 | PLANS-10 |
| FR-013 | PLANS-08 |
| FR-014 | PLANS-04 |
| FR-015 – FR-016 | PLANS-09 |
| FR-017 | PLANS-02 |
| FR-018 | PLANS-10 |
| FR-019 | MENU-01, PLANS-06 |
| FR-020 – FR-021 | MENU-01, MENU-05 |
| FR-022 | MENU-02 |
| FR-023 | MENU-03 |
| FR-024 | MENU-05 |
| FR-025 | MENU-06 |
| FR-026 | NAV-01 |
| FR-027 – FR-028 | MENU-04 |
| FR-029 | NAV-02 |
| FR-030 | PLANS-06 |

### Key Entities

- **Granularity**: the size of one timeline period — day, week or month. Chosen by the user,
  remembered per profile.
- **Period**: one calendar day, Monday-to-Sunday week, or calendar month, with a label and an
  inclusive start and end date.
- **Timeline section**: a period that holds at least one task, together with those tasks.
- **Project tree node**: a project, its parent, its children, and whether the node is expanded.
- **Project**: an existing concept — a named container of tasks that may itself sit under another
  project.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user opens Plans and sees every task due in the next 30 days grouped by day,
  without opening any other screen.
- **SC-002**: Switching granularity re-groups the visible timeline in under 1 second on a device
  holding 500 open tasks.
- **SC-003**: A user with 30 projects nested up to three levels deep reaches any of them from the
  menu in at most three interactions — open the menu, expand, tap.
- **SC-004**: At every granularity, no task is rendered in more than one section; the count of
  duplicated rows is zero.
- **SC-005**: After the change, the Android bottom navigation holds five tabs and no Calendar
  button, and the menu holds the Calendar entry, the Projects tree and the entries it held before.
- **SC-006**: A user who last worked in week granularity finds Plans already in week granularity
  the next time they open it.
- **SC-007**: Plans opens and shows its first screen within 2 seconds with 500 open tasks.
- **SC-008**: Every task-row interaction that worked on a task list before this change still
  works on the timeline — a completed task stays completed after the screen is reopened.

## Assumptions

- Overdue tasks lead the timeline in their own section, and undated tasks stay out of it. Both
  placements follow the app's existing convention, where overdue is the first section of every
  bucket-based view and undated work has its own home.
- The week starts on Monday and the month is the calendar month, matching the app's existing
  relative date buckets.
- The Projects section of the menu is the primary way into projects; the full project list stays
  available behind an entry in that same section so that search, sorting, closed projects and the
  project review are not lost.
- The granularity choice is a per-profile preference and survives an app restart.
- Only active projects appear in the tree; closed projects are managed from the full list.
- The visual reference for the timeline is the "upcoming" view of the app's web version
  (singularity-app.ru): plain sequential day sections, each listing the tasks of that day, with no
  grid and no calendar chrome.
- The Calendar screen itself is untouched — it moves in the navigation, it does not change.

## Out of Scope

- Editing, reordering, re-parenting or deleting projects from the menu tree. The tree navigates;
  the project detail screen owns editing.
- The Calendar screen's own features — its month grid, its day view, its event handling.
- The saved agenda views and their section editor.
- The Upcoming view's bucket structure.
- A year granularity. The set of granularities is open, but day, week and month are the scope here.
- Any change to how projects, tasks or their dates are stored or synchronised.

## Dependencies

- The project hierarchy — a project that may sit under another project — already exists in the
  app's data. This feature renders it; it does not introduce it.
- Tasks already carry a due date, and overdue work is already distinguishable from work that is
  merely undated.
- The Calendar screen already exists and already works; this feature changes where it is reached
  from, not what it does.
- The app menu already exists on both platforms with the same set of entries; this feature adds a
  Projects section to it and moves the Calendar into it.
- The full project list already exists with its search, sorting, closed projects and review; this
  feature changes what the Plans tab shows, not what that list can do.

## Risks

- **The same task rendered in two sections** is a known failure mode in this app: the screen
  crashes instead of showing the task twice. FR-002 and FR-004 exist to make that impossible at
  every granularity, and the scenario matrix asserts it rather than assuming it.
- **The tree and the full project list can disagree** — a project closed in the list but still
  shown in the tree. The tree is the live view of the projects and is the one the menu shows.
- **Moving the Calendar out of the tab bar** leaves a screen with no tab. FR-027 keeps it one
  interaction away in the menu on both platforms; a deep link alone would not, which is why the
  menu entry is a requirement and not a convenience.
