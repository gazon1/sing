# nav3-desktop-jvm-entry-dispatch

## Purpose

The app uses Navigation 3 (Nav3) with a multi-back-stack pattern. A top-level `AppDestination`
holds a map of top-level routes (tabs and menu entries) to nested back-stacks. Each nested
back-stack belongs to one feature: Agenda, Tasks, Calendar, Notes, Projects, Settings.

`NavDisplay` is the shared renderer (Android + JVM) that displays the screen corresponding to
the current top entry of each back-stack.

These requirements pin the entry-dispatch contract that both platform shells share: which
route a nested graph renders, who is obliged to seed the stack, and how a nested screen asks
the shell to open something it does not own.

## Requirements

### Requirement: REQ-3 Agenda graph seed always matches start

The Agenda graph entry SHALL use `start = route.start` where the stack is created with
`rememberNavBackStackTyped(route.start)`.

**Rationale:** The Agenda tab's outer route is one of `Today`, `Inbox`, `Upcoming`,
`Project(...)`, or `Tag(...)`. The stack seed is always that same route, so `backStack.top`
equals `route.start` by construction. No explicit `add()` is needed.

#### Scenario: Entering Agenda from tab bar

- **Given** the user taps the Agenda tab
- **When** `nav.navigate(AppDestination.AgendaGraph(AgendaStartRoute.Today))` is called
- **Then** the Agenda graph entry is created with stack seed `Today`
- **And** `start = Today` (same as seed)
- **And** `NavDisplay` renders the Today agenda screen

### Requirement: REQ-4 Cross-graph navigation via onExitGraph callback

When a screen inside a nested graph needs to navigate to a screen in a different feature, it
SHALL call its navigator's method, which SHALL invoke `onExitGraph(destination)`. The outer
graph entry's `onExitGraph` handler SHALL route the request through the shell `Navigator`,
which resolves it with `NavigationPolicy`.

**Rationale:** Nested graphs do not hold references to the outer navigator. The callback is
the only communication channel. Since ADR `2026-10-04-navigation-policy` the handler no
longer keeps a per-origin allow-list — `NavCallbacks.graphExit` sends the target straight to
`Navigator.open`, so a cross-feature open can no longer silently degrade into back-navigation.

#### Scenario: Task detail links to a project

- **Given** the user is viewing a task detail screen
- **When** they tap the project row
- **And** `TasksNavigator.openProject(projectId)` is called
- **Then** `onExitGraph(AppDestination.ProjectDetail(projectId.value))` is invoked
- **And** the outer handler calls `Navigator.open(ProjectDetail(...))`
- **And** the Projects graph is entered with `Detail(projectId)` as the start route

#### Scenario: Task detail links to a note preview

- **Given** the user is viewing a task detail screen
- **When** they tap a linked note in the backlinks section
- **And** `TasksNavigator.openNote(noteId)` is called
- **Then** `onExitGraph(AppDestination.NotesGraph(NotesStartRoute.Preview(noteId.value)))` is invoked
- **And** the Notes graph is entered with `Preview(noteId)` as the start route

### Requirement: REQ-5 Same-graph navigation via backStack.add

When a screen navigates to another screen within the same feature graph, the navigator SHALL
call `backStack.add(Route)` to push the new entry onto the shared stack.

**Rationale:** `onExitGraph` is only for crossing graph boundaries. Within one graph, pushing
onto the shared stack is sufficient.

#### Scenario: Navigate from task list to task detail

- **Given** the user is on the Tasks tab and can see the task list
- **When** they tap a task row
- **And** `TasksNavigator.openDetail(taskId)` is called
- **Then** `backStack.add(TasksRoute.Detail(taskId))` is executed
- **And** `NavDisplay` re-renders with `Detail(taskId)` at `backStack.top`

#### Scenario: Navigate from task detail back to task list

- **Given** the user is on the task detail screen
- **When** they tap the back button
- **And** `TasksNavigator.back()` is called
- **And** `backStack.size > 1`
- **Then** `backStack.removeLastOrNull()` is executed
- **And** `NavDisplay` re-renders with the previous entry at `backStack.top`

### Requirement: REQ-6 Back navigation exits graph when stack has one entry

When `back()` is called and `backStack.size <= 1`, the navigator SHALL call `onExitGraph(null)`
to exit the nested graph and go back in the outer stack.

**Rationale:** `size == 1` means only the seed entry remains. Popping it would leave an empty
stack, which `NavDisplay` cannot render.

#### Scenario: Back from root of Tasks graph

- **Given** the user is on the task creation screen (seed entry, `backStack.size == 1`)
- **When** they tap the back button
- **And** `TasksNavigator.back()` is called
- **Then** `onExitGraph(null)` is invoked
- **And** the outer graph goes back (closes Tasks tab or returns to previous tab)

### Requirement: REQ-7 Tab reselect emits reselectEvents

Tapping the currently active tab SHALL emit a `reselectEvents` event instead of re-navigating
to the same tab.

**Rationale:** Reselect tells the active screen to reset its scroll position or refresh its
content, matching platform UX expectations for bottom/tab bars.

#### Scenario: Reselect Agenda tab

- **Given** the user is on the Agenda tab viewing Today
- **When** they tap the Agenda tab again (it is already active)
- **Then** `Nav3State.onTabTapped(Agenda)` emits a reselect event
- **And** `AgendaScreen` receives the event and resets its scroll position

#### Scenario: Switch to different tab

- **Given** the user is on the Agenda tab
- **When** they tap the Calendar tab
- **Then** `Nav3State.onTabTapped(Calendar)` switches `topLevelRoute` to `Calendar`
- **And** `NavDisplay` hides the Agenda stack and shows the Calendar stack

### Requirement: REQ-8 Typed stack factory: in-memory on JVM, saved-state on Android

Nested graphs SHALL obtain their back stack from `rememberNavBackStackTyped(start)`, whose
platform actual differs: on Android it delegates to `rememberNavBackStack(savedStateConfig,
start)` and persists across process death; on JVM it builds a plain in-memory `NavBackStack`.

**Rationale:** JVM Desktop has no process death, so saved-state is unnecessary. Android can be
killed and restored, requiring state preservation. The expect/actual seam keeps the single
unchecked cast (Android) and the in-memory fallback (JVM) in one file each, so the seven graph
composables no longer carry `@Suppress("UNCHECKED_CAST")`.

#### Scenario: JVM Desktop navigation

- **Given** the app is running on JVM Desktop
- **When** any nested graph entry is created
- **Then** the JVM actual is used
- **And** the stack is held in memory for the lifetime of the session

#### Scenario: Android process death and restoration

- **Given** the app was on the Tasks detail screen when Android killed the process
- **When** the user relaunches the app
- **Then** the Android actual restores the stack, seeded with `Detail(taskId)`
- **And** `NavDisplay` renders the Tasks detail screen with the same `taskId`

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
