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

### Requirement: REQ-1 NavDisplay renders the entry at back-stack top

`NavDisplay` SHALL render the screen that corresponds to `backStack.top`, regardless of any
`start` parameter passed to the nested graph.

**Rationale:** The `start` parameter seeds the stack; `backStack.top` is the single source of
truth for which entry is active. This is true on both Android and JVM.

#### Scenario: Create entry with Create seed

- **Given** the app launches with the Tasks tab active
- **When** the Tasks graph entry is created with seed `Create(null)`
- **Then** `NavDisplay` renders the task creation screen

#### Scenario: Detail entry with Create seed without explicit add

- **Given** the app is on the Agenda tab
- **And** a task with title "Buy milk" exists with due date today
- **When** the user taps "Buy milk" in the Agenda list
- **And** the Tasks graph entry is created with seed `Create(null)` but `start = Detail("Buy milk")`
- **Then** `NavDisplay` renders the task creation screen (NOT the detail screen)
- **And** the editor shows empty title and "Добавить дату" as due date

#### Scenario: Detail entry with Create seed with explicit add (correct)

- **Given** the app is on the Agenda tab
- **And** a task with title "Buy milk" exists with due date today
- **When** the user taps "Buy milk" in the Agenda list
- **And** the entry block adds `Detail("Buy milk")` to `tasksStack` before rendering
- **Then** `NavDisplay` renders the task detail screen
- **And** the editor shows "Buy milk" as title and "2026-10-03" as due date

### Requirement: REQ-2 Entry block obligation when start differs from seed

When an entry block creates a back-stack with seed `X` but the outer route specifies
`start = Y`, the entry block SHALL add `Y` to the back-stack before the graph is rendered, if
`X` and `Y` represent different route types.

**Rationale:** Without this, `NavDisplay` renders the wrong screen.

#### Scenario: Agenda → Tasks Detail navigation

- **Given** the user is on the Agenda tab viewing the Today section
- **When** they tap a task row
- **And** `AgendaNavigator.openTask(taskId)` is called
- **Then** `onExitGraph(TasksGraph(Detail(taskId)))` is invoked
- **And** the Tasks graph entry is created with seed `Create(null)`
- **And** the entry block adds `Detail(taskId)` to `tasksStack`
- **And** `NavDisplay` renders the task detail screen

#### Scenario: Calendar → Tasks Detail navigation

- **Given** the user is on the Calendar tab viewing October 2026
- **When** they tap a task shown on that day
- **And** `CalendarNavigator.openTask(taskId)` is called
- **Then** `onExitGraph(TasksGraph(Detail(taskId)))` is invoked
- **And** the Tasks graph entry behaves as in the previous scenario

#### Scenario: Agenda → Tasks Create navigation

- **Given** the user is on the Agenda tab
- **When** they tap the "+" button in the Today section header
- **And** `AgendaNavigator.openCreateInSection("today")` is called
- **Then** `onExitGraph(TasksGraph(Create))` is invoked
- **And** the Tasks graph entry is created with seed `Create(null)`
- **And** `start = Create(null)` (matches seed, no explicit add needed)
- **And** `NavDisplay` renders the task creation screen

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
