# Navigation map (Nav3 multi-back-stack)

Inventory of every navigation key: **where it lives (container), what opens it, and what its
inner start route is**. Source of truth: `AndroidNavEntries.kt` (androidMain) and
`JvmNavEntries.kt` (jvmMain) — both declare the same 18 `entry<>` blocks; they differ only
in stack persistence (saved-state vs in-memory) and in the JVM `TasksGraph` in-memory stack
seeding.

Architecture rationale: ADR `2026-10-04-navigation-policy.md` + skill
`singularity-todo-nav3-nested-graphs`.

## Containers

| Container | Holds | Created by |
|---|---|---|
| **Top-level destination** (13 back stacks) | app-level keys | `rememberNav3State()`: `DestinationKind.tabs` (6) + `DestinationKind.menuEntries` (7) |
| **Pushed app-level sub-route** | app-level keys on top of the current top-level stack | `Navigator.open` (policy: `Push` / `ExitAndOpen`) |
| **Inner feature route** | `TasksRoute` / `NotesRoute` / `ProjectsRoute` / `CalendarRoute` / `AgendaStartRoute` / `Settings` / `Search` | feature navigator (`backStack.add`) inside its graph |

All app-level keys are leaves of the single sealed root `AppNavKey` (see
`AppNavKey.kt`); inner routes are also `AppNavKey` leaves (they must round-trip the
polymorphic saved-state module) but are never valid app-level open targets.

## Top-level destinations (own back stack)

| Key | Inner content | Opened from (sources) |
|---|---|---|
| `AgendaGraph(Inbox / Today / Upcoming)` | `AgendaNavGraph(start = …)` | bottom bar tabs (3 instances), desktop drawer/menu, back-at-root returns to previous |
| `Plans` | `ProjectsNavGraph(List)` | bottom bar tab, desktop drawer/menu |
| `Pomodoro` | `PomodoroScreen` | bottom bar tab, desktop drawer/menu |
| `Calendar` | `CalendarNavGraph(Month(today))` | bottom bar tab, desktop drawer/menu |
| `Statistics` | `StatisticsScreen` | menu sheet / desktop menu |
| `Notes` | `NotesNavGraph(List)` | menu sheet / desktop menu |
| `AiChat` | `ChatScreen` | menu sheet / desktop menu |
| `Search` | `SearchNavGraph` | menu sheet, Ctrl+F, top-bar search icon |
| `Archive` | `ArchiveScreen` | menu sheet / desktop menu |
| `ProfileSwitcher` | `ProfileSwitcherScreen` | menu sheet, `SettingsNavigator.openProfiles()` |
| `Settings` | `SettingsNavGraph` | menu sheet, Ctrl+, |
| `AiUsage` | `AiUsageScreen` | settings → AI usage row |

Tab reselect emits `Nav3State.reselectEvents`; reselect never mutates a stack
(REQ-NAV-006).

## Pushed app-level sub-routes (enter the current top-level stack)

| Key | Inner start | Opened from (sources) | Notes |
|---|---|---|---|
| `TasksGraph(start, initialDueDate?)` | `TasksNavGraph(start.toTasksRoute())` | FAB "add task" (`FabActionResolver`), `AgendaNavigator.openTask`, `ProjectsNavigator.openTask`, `SearchNavigator.openTask`, `NotesNavigator.openTask` (wikilink), task deep link `singularity://task/{id}` | `fromString` unpacking of `Detail.taskId` lives in the entry converter today (→ B2 typed id) |
| `TasksByProject(projectId)` | *(no `entry<>` declared)* | **no production opener — unwired surface** (see `ProjectDetailUi` "see all") | candidate for follow-up issue |
| `ProjectDetail(projectId)` | `ProjectsNavGraph(Detail)` | `TasksNavigator.openProject`, `SearchNavigator.openProject` | allow-list previously permitted from Tasks/Agenda contexts only |
| `ProjectEditor(projectId?)` | `ProjectsNavGraph(Editor)` | plans FAB, project detail edit | |
| `ProjectsGraph(start)` | `ProjectsNavGraph(start.toProjectsRoute())` | projects deep link | |
| `NotesGraph(start)` | `NotesNavGraph(start.toNotesRoute())` | `TasksNavigator.openNote*`, `SearchNavigator.openNote`, task→linked-note | previously **swallowed** by the Tasks allow-list (REQ-NAV-002) |
| `CalendarGraph(start)` | `CalendarNavGraph` | calendar deep link | |
| `AgendaGraph(start ≠ tab starts)` | `AgendaNavGraph(start)` | `ProjectsNavigator.openAgendaForProject`, `SearchNavigator.openTag`, saved-view deep link `deeplinkViewId` | tab instances are top-level; `Project`/`Tag`/saved-view starts are pushed |

**Deprecated app-level members with no `entry<>`** (removed in B4 after live-ref grep):
`AppDestination.Inbox / Today / Upcoming`, `AppDestination.TaskDetail`,
`AppDestination.TaskDetailCreate`, `TasksStartRoute.Inbox / Upcoming`
(production mapping still exists: `toTasksRoute` maps the latter two to `Create(null)`).

**Structurally invalid app-level targets** (REQ-NAV-003 — policy rejects with a
descriptive error): bare nested start routes — `TasksStartRoute.*`,
`ProjectsStartRoute.*`, `NotesStartRoute.*`, `CalendarStartRoute.*`,
`AgendaStartRoute.*` — and inner routes (`TasksRoute`, `NotesRoute`, `ProjectsRoute`,
`CalendarRoute`, lone `Settings`/`Search`) addressed without their graph wrapper.

## Cross-feature emitters (the `onExitGraph(dest)` producers)

| Emitter | Emits (non-null) | Previously allowed by the entry allow-list? |
|---|---|---|
| `TasksNavigator` | `ProjectDetail`, `NotesGraph(Preview)`, `NotesGraph(EditorForTask)` | ProjectDetail ✔ / **NotesGraph ✘ swallowed** |
| `ProjectsNavigator` | `AgendaGraph(Project)`, `TasksGraph(Detail)` | **✘ swallowed from Plans/ProjectsGraph/Editor contexts** (only `ProjectDetail` entry allowed them) |
| `AgendaNavigator` | `TasksGraph(Detail)`, project/detail targets | TasksGraph ✔, ProjectDetail ✔ |
| `CalendarNavigator` | tasks / project targets | TasksGraph ✔ / **ProjectDetail ✘ swallowed** |
| `SearchNavigator` | tasks / notes / project / `AgendaGraph(Tag)` | search bypassed the allow-list (connected `navCallbacks` directly) ✔ |
| `NotesNavigator` | `TasksGraph(Create())` — **bug: task id dropped** | ✔ but opens the wrong screen (B1 fixes to `Detail(taskId)`) |
| `SettingsNavigator` | `ProfileSwitcher` | settings bypassed the allow-list ✔ |

`null` from any emitter = "exit the graph" → `Navigator.close()` (was `goBack()`).

After B1 every entry's `onExitGraph` is the uniform
`{ dest -> if (dest == null) nav.close() else nav.navigate(dest) }`; `navigate`
delegates to `Navigator.open`, which resolves via `NavigationPolicy`.

## Platform differences

| Aspect | Android | Desktop JVM |
|---|---|---|
| Stack persistence | `rememberNavBackStack(navSavedStateConfig(), key)` (process death) | `rememberInMemoryNavBackStack(key)` (ADR `2026-09-16`) |
| `TasksGraph` inner stack | graph dispatch by stack top (ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch`) | seeded `rememberInMemoryNavBackStack`, `Detail` start pushed eagerly |
| Shell chrome | bottom bar + menu bottom sheet | drawer + window menu bar |
| Deep link | `singularity://task/{id}`, saved-view id → `App.kt` `LaunchedEffect` | none |

## Baseline (B0, 2026-10-04, before policy change)

`Nav3StateReselectTest` (7), `Nav3SavedStateTest` (6), `NavSavedStateConfigTest` (4),
`NavKeyRegistrationTest` (4), `DestinationKindTest` (8), `NavigationLabelsTest` (2),
desktop `NavigationFlowTest` (3), `PlatformParityTest` (1),
`OpenTaskFromAgendaFlowTest` (3) — **all green**.
