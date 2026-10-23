# Navigation map (Nav3 multi-back-stack)

Inventory of every navigation key: **where it lives (container), what opens it, and what its
inner start route is**.

Source of truth: the entry **contributors** in `feature/nav/entries/` — one file per
feature, in common code. `AndroidNavEntries.kt` (androidMain) and `JvmNavEntries.kt`
(jvmMain) are *assemblers*: they collect the contributors, supply the platform's context
menu, and hold the platform's own bits (the hoisted stacks on Desktop, the tasks graph
entry). Together they declare 22 `entry<>` blocks — the same 22 on both platforms;
`AppEntryParityTest` holds that.

The seven nested graphs (tasks, projects, notes, calendar, search, settings, agenda) are
**one common implementation each**: `feature/<name>/presentation/nav/<Name>NavGraph.kt` in
commonMain. The platform differences are two seams — `rememberAppNavBackStack` (saved-state
on Android, in-memory on Desktop) and `SystemBackHandler` (the system back gesture on
Android, a no-op on Desktop) — plus the agenda's context menu host, which is a gesture and
therefore a platform's business.

Architecture rationale: ADR `2026-10-04-navigation-policy.md`, ADR
`2026-10-21-nav3-1-2-0-upgrade.md`, ADR `2026-10-21-deep-link-registry.md` + skill
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
| `TasksGraph(start, initialDueDate?)` | `TasksNavGraph(start.toTasksRoute(initialDueDate))` | FAB "add task" (`FabActionResolver`), `AgendaNavigator.openTask`, `ProjectsNavigator.openTask`, `SearchNavigator.openTask`, `NotesNavigator.openTask` (wikilink), task deep link `singularity://task/{id}` | the deep link names the tab too — see *Deep links* below |
| `TasksByProject(projectId)` | *(no `entry<>` declared)* | **no production opener — unwired surface** (see `ProjectDetailUi` "see all") | candidate for follow-up issue |
| `ProjectDetail(projectId)` | `ProjectsNavGraph(Detail)` | `TasksNavigator.openProject`, `SearchNavigator.openProject` | allow-list previously permitted from Tasks/Agenda contexts only |
| `ProjectEditor(projectId?)` | `ProjectsNavGraph(Editor)` | plans FAB, project detail edit | |
| `ProjectsGraph(start)` | `ProjectsNavGraph(start.toProjectsRoute())` | *(no production opener — there is no projects deep link)* | |
| `NotesGraph(start)` | `NotesNavGraph(start.toNotesRoute())` | `TasksNavigator.openNote*`, `SearchNavigator.openNote`, task→linked-note | previously **swallowed** by the Tasks allow-list (REQ-NAV-002) |
| `CalendarGraph(start)` | `CalendarNavGraph` | *(no production opener — there is no calendar deep link)* | |
| `AgendaGraph(start ≠ tab starts)` | `AgendaNavGraph(start)` | `ProjectsNavigator.openAgendaForProject`, `SearchNavigator.openTag`, saved-view deep link `deeplinkViewId` | tab instances are top-level; `Project`/`Tag`/saved-view starts are pushed |

**Deprecated app-level members with no `entry<>`** (removed in B4 after live-ref grep):
`AppDestination.Inbox / Today / Upcoming`, `AppDestination.TaskDetail`,
`AppDestination.TaskDetailCreate`, `TasksStartRoute.Inbox / Upcoming`.
No mapping is left for them: `toTasksRoute` maps `Create` and `Detail`, and
nothing maps a member that is gone.

**Structurally invalid app-level targets** (REQ-NAV-003 — policy rejects with a
descriptive error): bare nested start routes — `TasksStartRoute.*`,
`ProjectsStartRoute.*`, `NotesStartRoute.*`, `CalendarStartRoute.*`,
`AgendaStartRoute.*` — and inner routes (`TasksRoute`, `NotesRoute`, `ProjectsRoute`,
`CalendarRoute`, lone `Settings`/`Search`) addressed without their graph wrapper.

## Deep links

A link resolves to **two** keys — a tab and a screen — and opens the screen on
that tab, with that tab's stack rebuilt as exactly `[tab, screen]`. "Back" from
a linked screen therefore returns to the tab's own screen, not to whatever the
app was showing before.

The grammar of each link is declared by the feature that owns it, and one
registry answers "what does this URI open?" from an explicit ordered list.

| Link | Owner | Tab | Screen |
|---|---|---|---|
| `singularity://task/{taskId}` | tasks — `TaskLinkSpec` | `AgendaGraph(Today)` | `TasksGraph(Detail(taskId))` |
| `singularity://view/{viewId}` | agenda — `AgendaLinkSpec` | `AgendaGraph(Today)` | `AgendaGraph(SavedAgendaResults(viewId))` |
| `singularity://verify?email=&token=` | auth — `AuthLinkSpec` | `AgendaGraph(Today)` | `MagicLinkConfirm(email, token)` |

- The registry: `feature/nav/deeplink/` — `DeepLinkTarget`, `AppDeepLink`,
  `DeepLinkRegistry`, `deepLinkModule()`.
- The matchers: `TaskDeepLink`, `AgendaDeepLink`, `AuthDeepLink`, each beside
  its spec.
- The producer (calendar sync) and the matcher (the tasks feature) read the same
  `TaskLinkSpec`, so the format of the task link has one home.
- An unknown link is a quiet no-op: the app stays on its start tab.
- ADR `2026-10-21-deep-link-registry.md`.

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
| Where the difference lives | the seam `rememberAppNavBackStack` | the same seam, other side |
| System back gesture | `SystemBackHandler` → the platform's `BackHandler` | `SystemBackHandler` → a no-op |
| ViewModel store per entry | `rememberAppEntryDecorators` | the same — both platforms scope the store per entry |
| Top-level graph stacks | each graph owns its own | hoisted into the shell's composition (ADR `2026-10-01-desktop-nav-followup`, Bug 3) |
| `TasksGraph` inner stack | graph dispatch by stack top (ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch`) | seeded `rememberInMemoryNavBackStack` with the requested route — the seed *is* the screen |
| Agenda context menu | a bottom sheet, opened by a long press | a popup, opened by a right click |
| Shell chrome | bottom bar + menu bottom sheet | drawer + window menu bar |
| Deep link | `singularity://task/{id}`, `singularity://view/{id}`, `singularity://verify?…` → the deep-link registry | `singularity://…` via `--deeplink=<uri>`, resolved by the same registry |

### Where a nested graph is registered

The nested graphs are **common** — one implementation each. A new screen is
registered in the contributor of its own feature:

| Feature | Contributor |
|---|---|
| tasks | the `TasksGraph` entry in the platform assembler (platform-specific — see below) |
| projects | `feature/nav/entries/ProjectsEntries.kt` |
| notes | `feature/nav/entries/NotesEntries.kt` |
| calendar | `feature/nav/entries/CalendarEntries.kt` |
| search | `feature/nav/entries/SearchEntries.kt` |
| settings | `feature/nav/entries/SettingsEntries.kt` |
| agenda | `feature/nav/entries/AgendaEntries.kt` |
| the app's own screens | `feature/nav/entries/AppEntries.kt` |

A new screen inside a nested graph goes into that graph's `entryProvider` in
`feature/<name>/presentation/nav/<Name>NavGraph.kt` — **one** file, not two.

The tasks graph entry is the one exception: it stays in the platform assemblers,
because the desktop seeds the graph's stack itself — with the route the outer
graph asked for, so that the right frame renders immediately instead of a
placeholder under the screen the person asked for.

## Baseline (B0, 2026-10-04, before policy change)

`Nav3StateReselectTest` (7), `Nav3SavedStateTest` (6), `NavSavedStateConfigTest` (4),
`NavKeyRegistrationTest` (4), `DestinationKindTest` (8), `NavigationLabelsTest` (2),
desktop `NavigationFlowTest` (3), `PlatformParityTest` (1),
`OpenTaskFromAgendaFlowTest` (3) — **all green**.
