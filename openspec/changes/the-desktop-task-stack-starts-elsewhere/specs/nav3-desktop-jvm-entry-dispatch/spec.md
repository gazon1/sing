# nav3-desktop-jvm-entry-dispatch — Observable Behavior

**capability:** `nav3-desktop-jvm-entry-dispatch` | **status:** proposed

---

## REMOVED Requirements

### Requirement: REQ-1 NavDisplay renders the entry at back-stack top

**Reason:** the requirement stated that the rendered entry is the top of the stack "regardless
of any `start` parameter", and the scenarios it listed described one platform's seed as if it
were the contract for both. The renderer rule is still true and is restated below; what is
removed is the claim that the seed may be a route nobody asked for.

### Requirement: REQ-2 Entry block obligation when start differs from seed

**Reason:** the requirement existed to repair a mismatch between the seed and the requested
route. Once the seed is the requested route there is no mismatch to repair, and the repair
itself was the cause of a screen appearing underneath the one the user asked for.

---

## ADDED Requirements

### Requirement: A nested graph's stack is seeded with the route the outer graph asked for

`NavDisplay` SHALL render the screen that corresponds to `backStack.top`, and the stack of a
nested graph SHALL be seeded with the route the outer graph asked for.

**Rationale:** `backStack.top` is the single source of truth for which entry is active. The
seed decides what that top is before anything is pushed, so a seed that is not the requested
route is a screen the user never asked for — either instead of the one they asked for, or
underneath it.

**Test coverage:** a test that the stack the tasks entry produces has the requested route at
its top, on both platforms.

#### Scenario: The requested route is the top of the stack
- **Given** the app opens the tasks graph
- **When** the graph entry is created
- **Then** the stack is seeded with the route the outer graph asked for
- **And** `NavDisplay` renders that screen

#### Scenario: A detail route needs no extra push
- **Given** the app is on the Agenda tab
- **And** a task with title "Buy milk" exists with due date today
- **When** the user taps "Buy milk" in the Agenda list
- **Then** the tasks graph entry is seeded with the detail route
- **And** `NavDisplay` renders the task detail screen
- **And** the editor shows "Buy milk" as title and "2026-10-03" as due date

#### Scenario: The Create route needs no extra push
- **Given** the user is on the Agenda tab
- **When** they tap the floating action button
- **Then** the tasks graph entry is seeded with the new-task route
- **And** `NavDisplay` renders the new-task screen

---

### Requirement: The entry does not push the requested route a second time

When the stack of a nested graph is seeded with the route the outer graph asked for, the
entry block SHALL NOT push that route onto the stack again.

**Rationale:** pushing it makes the stack two entries deep, and the back gesture then
uncovers the seed instead of leaving the graph — the user presses back and gets a screen
they never opened.

**Test coverage:** a test that back from a task's detail screen leaves the graph, on both
platforms.

#### Scenario: Back from a task detail leaves the graph
- **Given** the user is on the Agenda tab viewing the Today section
- **When** they tap a task row
- **Then** the tasks graph renders the task detail screen
- **And** pressing back leaves the graph and returns to the agenda

#### Scenario: The graph carries no screen underneath
- **Given** the user opened a task's detail screen
- **When** the stack the entry produced is inspected
- **Then** it holds the detail route and nothing else

#### Scenario: Opening a task from the calendar behaves the same
- **Given** the user is on the Calendar tab
- **When** they tap a task shown on a day
- **Then** the tasks graph renders the task detail screen
- **And** pressing back leaves the graph

---

### Requirement: The seed carries the start route's parameters

When a nested graph is seeded with a route that takes parameters — a date to pre-fill, a
section to pre-select — the seeded route SHALL carry those parameters.

**Rationale:** the seed replaces the requested route wholesale. If the seed is a route of the
same kind but with default parameters, the pre-filled values are silently dropped, and the
screen opens with less in it than the user asked for. The date that a tab was already showing
is the clearest case: it is filled in on one platform and absent on the other.

**Test coverage:** a test that the new-task screen opened from the Today tab carries the
tab's date, on both platforms.

#### Scenario: The Today tab's date reaches the new-task screen
- **Given** the user is on the Today tab
- **When** they tap the floating action button
- **Then** the new-task screen opens with today's date filled in

#### Scenario: The Inbox tab's empty date reaches the new-task screen
- **Given** the user is on the Inbox tab
- **When** they tap the floating action button
- **Then** the new-task screen opens with no date filled in
