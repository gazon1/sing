---
name: singularity-todo-nav3-nested-graphs
description: 'Full architecture of Nav3 two-level nested navigation in this KMP project: the common *NavGraph pattern with three platform seams (rememberAppNavBackStack, SystemBackHandler, rememberAppEntryDecorators), sealed Route hierarchies, Local*Navigator providers, per-feature entry contributors, and how the outer shell integrates nested graphs. Covers both the per-feature graph pattern (TasksNavGraph, ProjectsNavGraph) and the singleton graph pattern (AgendaNavGraph — same graph reused for 3 tabs with different start routes). Use when adding a new feature screen, modifying an existing nested graph, or adding a new route type to any sealed Route hierarchy.'
---

# Nav3 Nested Graphs — Architecture

## Two Navigation Patterns

### Pattern A: Per-feature nested graph (projects, notes, search, settings)
Each feature owns its own isolated back stack.

```
App (top-level Nav3State)
├── NavBackStack<AppDestination>
│   └── NavDisplay (bottom bar)
│       └── [TasksNavGraph] ← Detail + Create only after AgendaEngine MR1
│       └── [ProjectsNavGraph] ← List → Detail → Editor
│       └── [NotesNavGraph] ← List → Preview/Editor
│       └── [SearchNavGraph]
│       └── [SettingsNavGraph]
```

### Pattern B: Singleton graph, multiple start routes (AgendaNavGraph)
One graph reused for 3 tab destinations by varying the `start` parameter.

```
App (top-level Nav3State)
├── NavBackStack<AppDestination>
│   └── NavDisplay (bottom bar)
│       └── AgendaNavGraph(start=AgendaStartRoute.Inbox)   ← Inbox tab
│       └── AgendaNavGraph(start=AgendaStartRoute.Today)   ← Today tab
│       └── AgendaNavGraph(start=AgendaStartRoute.Upcoming) ← Upcoming tab
│       └── ProjectsNavGraph
│       └── ...
```

Each `AgendaNavGraph` instance has its **own** `NavBackStack<AgendaStartRoute>` — the start route is the initial entry, but the stack grows independently as the user navigates within each tab.

---

## File Structure

Route **keys** live in one package (`feature/nav/`) because `AppNavKey` is a
sealed hierarchy and Kotlin requires sealed hierarchies in a single package.
Graphs, navigators and entry providers stay per feature:

```
feature/nav/                        ← ALL route keys (one package, sealed root)
├── AppNavKey.kt                    — the single sealed root every route extends
├── AppDestination.kt               — top-level destinations + nested *StartRoute
├── AgendaStartRoute.kt
├── TasksRoute.kt / NotesRoute.kt / ProjectsRoute.kt / CalendarRoute.kt
├── Settings.kt / Search.kt         — lone data objects (valid sealed leaves)
└── Nav3SavedState.kt               — shared appNavSavedStateConfig

feature/<feature>/presentation/nav/ ← graphs + navigators (per feature)
└── <Feature>NavGraph.kt                — ONE implementation, commonMain

feature/nav/ ← the seams and the entry contributors
├── Nav3SavedState.kt                   — rememberAppNavBackStack (expect) + the saved-state config
├── SystemBackHandler.kt                — the system back gesture (expect)
├── NavEntryDecorators.kt               — rememberAppEntryDecorators
├── AppNavStacks.kt                     — the stacks the platform hoists
└── entries/                            — one contributor per feature
    ├── AppEntries.kt  AgendaEntries.kt  CalendarEntries.kt
    ├── NotesEntries.kt  ProjectsEntries.kt  SearchEntries.kt
    └── SettingsEntries.kt
```

**There is no `*NavGraph.android.kt` / `*NavGraph.jvm.kt`.** The platform
differences are seams, and a seam is one function — not a second copy of the
graph. See "Three Seams" below.

**Example: Tasks (Detail + Create only — after AgendaEngine MR1)**
```
feature/nav/TasksRoute.kt               — route key (sealed interface, in feature/nav/)
feature/tasks/presentation/nav/
└── TasksNavGraph.kt                    — the whole graph, in commonMain
```
> After AgendaEngine MR1, TasksNavGraph only handles Detail + Create screens.
> Inbox/Today/Upcoming tabs now use `AgendaNavGraph(start = AgendaStartRoute.Inbox/Today/Upcoming)`.

**Example: Agenda (singleton graph, 3 tab variants)**
```
feature/nav/AgendaStartRoute.kt         — route key (in feature/nav/)
feature/agenda/presentation/nav/
├── AgendaNavGraph.kt                   — the whole graph, in commonMain
└── AgendaContextMenuHost.kt            — the host type
shared/src/androidMain/.../agenda/presentation/nav/
└── AgendaContextMenuHost.android.kt    — the bottom sheet
shared/src/jvmMain/.../agenda/presentation/nav/
└── AgendaContextMenuHost.jvm.kt        — the popup
```
> AgendaNavGraph is mounted 3 times at the top level (one per tab) with different `start` values.
> The `start: AgendaStartRoute` parameter makes a single graph reusable for all variants.

---

## The Sealed Route Hierarchy

```kotlin
// feature/nav/AppNavKey.kt — THE root. Every route extends this, never NavKey.
@Serializable
sealed interface AppNavKey : NavKey

// feature/nav/AgendaStartRoute.kt
@Serializable
sealed interface AgendaStartRoute : AppNavKey {
    @Serializable data object Inbox : AgendaStartRoute
    @Serializable data object Today : AgendaStartRoute
    @Serializable data object Upcoming : AgendaStartRoute
    @Serializable data class Project(val projectId: String) : AgendaStartRoute {
        val id: ProjectId get() = ProjectId.fromString(projectId)
    }
    @Serializable data class Tag(val tagId: String) : AgendaStartRoute {
        val id: TagId get() = TagId.fromString(tagId)
    }
}

// feature/nav/TasksRoute.kt
@Serializable
sealed interface TasksRoute : AppNavKey {
    @Serializable data class Detail(val taskId: TaskId) : TasksRoute
    @Serializable data class Create(val initialDueDate: LocalDate? = null) : TasksRoute
}

// feature/nav/Settings.kt — a lone data object is a valid sealed leaf
@Serializable
data object Settings : AppNavKey
```

**Rules:**
- Every route extends **`AppNavKey`**, never `NavKey`. A route on `NavKey` is
  invisible to `subclassesOfSealed` and crashes its screen on open — see
  `singularity-todo-nav3-savedstate`.
- Every concrete leaf is `@Serializable`; the intermediate sealed interfaces are
  `@Serializable` too (the root must be, for `subclassesOfSealed`).
- `NavKey` is a plain marker interface; it is NOT sealed and NOT `@Serializable`.

---

## The Common Pattern and Three Seams

A nested graph is **one implementation in commonMain**. The platform differences
are three seams and one parameter.

### Seam 1 — the stack's storage: `rememberAppNavBackStack`

```kotlin
// commonMain — Nav3SavedState.kt
@Composable
expect fun <T : NavKey> rememberAppNavBackStack(start: T): NavBackStack<T>
```

Android is saved-state backed (the stack survives process death); Desktop is in
memory (there is no process death, and no saveable-state registry to write into).

### Seam 2 — the system back gesture: `SystemBackHandler`

```kotlin
// commonMain — SystemBackHandler.kt
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
```

Android delegates to the platform's `BackHandler`; Desktop does nothing — there
is no system back gesture to intercept.

### Seam 3 — the ViewModel store per entry: `rememberAppEntryDecorators`

```kotlin
// commonMain — NavEntryDecorators.kt
@Composable
fun <T : NavKey> rememberAppEntryDecorators(): List<NavEntryDecorator<T>> =
    listOf(rememberViewModelStoreNavEntryDecorator())
```

**Both platforms.** Without it an entry resolves the store of whatever is above
it, so two entries of the same graph share one store — and Koin keys a ViewModel
by its class alone, not by its parameters, so the second screen silently reuses
the first one's ViewModel with the first one's parameters. Desktop used to omit
the decorator on the belief that "each NavDisplay entry already has proper
per-entry ViewModel scoping"; `NestedEntryViewModelScopeTest` measured it and the
belief was wrong.

### Parameter — a platform gesture: `AgendaContextMenuHost`

The agenda's context menu is a gesture (a long press on Android, a right click on
Desktop), so the host is a parameter and the graph stays in common code.

### The graph itself

```kotlin
// feature/agenda/presentation/nav/AgendaNavGraph.kt — commonMain
@Composable
fun AgendaNavGraph(
    start: AgendaStartRoute,
    onExitGraph: (AppDestination?) -> Unit,
    contextMenuHost: AgendaContextMenuHost,
    modifier: Modifier = Modifier,
    backStack: NavBackStack<AgendaStartRoute>? = null,
) {
    val stack: NavBackStack<AgendaStartRoute> = backStack ?: rememberAppNavBackStack(start)
    val navigator = remember(stack, onExitGraph) {
        AgendaNavigator(backStack = stack, onExitGraph = onExitGraph)
    }
    CompositionLocalProvider(LocalAgendaNavigator provides navigator) {
        SystemBackHandler(enabled = stack.size <= 1) { onExitGraph(null) }
        NavDisplay(
            backStack = stack,
            modifier = modifier,
            onBack = { onExitGraph(null) },
            entryDecorators = rememberAppEntryDecorators(),
            entryProvider = agendaScreenEntryProvider(contextMenuHost),
        )
    }
}
```

The `onExitGraph: (AppDestination?) -> Unit` callback handles cross-feature navigation:
- `onExitGraph(AppDestination.ProjectDetail(id))` → outer graph navigates to that destination
- `onExitGraph(null)` → outer graph handles back (exit nested graph)

### What is left platform-specific

| Aspect | Android | Desktop |
|---|---|---|
| Stack storage | saved state | in memory |
| System back | the platform's `BackHandler` | a no-op |
| ViewModel store | per entry | per entry |
| Top-level graph stacks | each graph owns its own | hoisted into the shell's composition |
| Agenda context menu | a bottom sheet | a popup |
| The tasks graph entry | in the platform assembler | in the platform assembler |

The last two rows are the only ones that are *not* a seam, and they are the only
`entry<>` blocks left outside the common contributors.

## Local*Navigator Pattern

Each nested graph provides a `CompositionLocal` so screens inside can navigate without callbacks:

```kotlin
// In AgendaNavGraph.kt (commonMain)
val LocalAgendaNavigator = staticCompositionLocalOf<AgendaNavigator?> { null }

// AgendaNavigator (commonMain)
class AgendaNavigator(
    private val backStack: NavBackStack<AgendaStartRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {
    fun toProject(projectId: ProjectId) = backStack.add(AgendaStartRoute.Project(projectId.value))
    fun toTag(tagId: TagId) = backStack.add(AgendaStartRoute.Tag(tagId.value))
    fun back() {
        if (backStack.size > 1) backStack.removeLastOrNull()
        else onExitGraph(null)
    }
}

// Usage inside nested graph screens:
@Composable
fun AgendaScreen(definition: AgendaDefinition, ...) {
    val navigator = LocalAgendaNavigator.current
    // Use navigator.toProject(...) without passing callbacks
}
```

---

## Outer Wiring — the entry contributors

The app's entry provider is built by the platform's **assembler**, which collects
the per-feature contributors:

```kotlin
// shared/src/jvmMain/.../feature/nav/JvmNavEntries.kt
@Composable
fun createJvmEntryProvider(nav: NavCallbacks, ...): (AppDestination) -> NavEntry<AppDestination> {
    // Desktop hoists the top-level graph stacks into the shell's composition, so
    // they survive an entry leaving NavDisplay's visible set.
    val stacks = AppNavStacks(
        projects = remember { NavBackStack<ProjectsRoute>(ProjectsRoute.List) },
        // ...
    )
    return entryProvider {
        appEntries(nav, pomodoroTimer, pomodoroTaskList)
        agendaEntries(nav, stacks, desktopAgendaContextMenuHost())
        calendarEntries(nav, stacks, today)
        notesEntries(nav, stacks)
        projectsEntries(nav, stacks)
        searchEntries(nav, stacks)
        settingsEntries(nav, stacks)

        // The one platform-specific entry: Desktop seeds the pushed graph's stack
        // itself and pushes the detail route onto it.
        entry<AppDestination.TasksGraph> { route -> /* ... */ }
    }
}
```

A contributor is an extension on the entry-provider scope, in the feature's own
file — or in `feature/nav/entries/` when the feature has no nav package of its own:

```kotlin
// feature/nav/entries/ProjectsEntries.kt
fun EntryProviderScope<AppDestination>.projectsEntries(nav: NavCallbacks, stacks: AppNavStacks) {
    entry<AppDestination.Plans> {
        ProjectsNavGraph(
            start = ProjectsRoute.List,
            onExitGraph = nav.graphExit,
            backStack = stacks.projects,
        )
    }
    entry<AppDestination.ProjectDetail> { route ->
        ProjectsNavGraph(
            start = ProjectsRoute.Detail(route.projectId),
            onExitGraph = nav.graphExit,
        )
    }
}
```

`AppNavKey` is the single sealed root and must not grow parallel hierarchies (ADR
`2026-09-29-single-sealed-navkey-root`). New screens are leaves of `AppDestination`
— and they are registered **in their own feature's contributor**, not in both
platform files. `AppEntryParityTest` holds the set of registered destinations.

## Adding a New Route Type

### 1. Add to the route file in `feature/nav/`

```kotlin
@Serializable
data class NewRoute(val id: SomethingId) : AgendaStartRoute   // AgendaStartRoute : AppNavKey
```

### 2. Serializer registration: nothing to do

The shared `appNavSavedStateConfig` walks the sealed root, so a new leaf is
covered automatically. **The failure mode is declaring the route against
`NavKey`** — that compiles, tests green, and crashes the screen on open. Extend
`AppNavKey` (directly or via an intermediate sealed interface).

### 3. No platform changes needed

`rememberAppNavBackStack(start)` works for any `T : NavKey` on both platforms — the
seam picks the storage.

### 4. Add `entry { }` in `entryProvider`

In the graph's **one** file — `feature/<feature>/presentation/nav/<Feature>NavGraph.kt`,
in commonMain:
```kotlin
entryProvider = entryProvider {
    // ... existing entries
    entry<AgendaStartRoute.NewRoute> { route -> AgendaScreen(AgendaPresets.bySomething(route.id)) }
},
```

There is no second file to edit, and no platform to forget.

### 5. Add the preset in `AgendaPresets`

```kotlin
// AgendaPresets.kt
fun bySomething(id: SomethingId): AgendaDefinition = agenda("Something") {
    section("Items", Selector.AllOf(listOf(...)), order = 0)
}
```

---

## PreviewAgendaNavigator — @Preview Without Koin

Screens inside a nested graph need a navigator in `@Preview`. The `PreviewAgendaNavigator` provides a no-op navigator for previews.

**File:** `feature/agenda/presentation/nav/AgendaPreviewHelpers.kt`

```kotlin
class PreviewAgendaNavigator : AgendaNavigator(
    backStack = NavBackStack<AgendaStartRoute>(
        AgendaStartRoute.SavedAgendaList,  // start route
        AgendaStartRoute.SavedAgendaList,  // current
    ),
    onExitGraph = {},
) {
    override fun openSavedAgendaList() { /* no-op for preview */ }
    override fun openSavedAgendaEdit(viewId: SavedAgendaViewId) { /* no-op for preview */ }
    override fun back() { /* no-op for preview */ }
}

@Composable
fun PreviewAgendaNavigator(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAgendaNavigator provides PreviewAgendaNavigator(),
        content = content,
    )
}
```

**Usage in preview:**
```kotlin
@Preview
@Composable
private fun SavedAgendaListContentLoadedPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaListContent(
            state = SavedAgendaListState.Loaded(views = listOf(...)),
            onViewSelected = {},
            onDelete = {},
        )
    }
}
```

**Key rules:**
- `LocalAgendaNavigator` is a `staticCompositionLocalOf` — must be provided at every preview entry point
- The `backStack` needs two args: the initial entry AND the current top-of-stack (both `SavedAgendaList` for a list screen preview)
- `onExitGraph = {}` — preview never exits the graph

## Common Mistakes

### ❌ Declaring a route against `NavKey` instead of `AppNavKey`

```kotlin
// WRONG — compiles, tests green, crashes the screen when it opens
@Serializable data object MyScreen : NavKey

// CORRECT
@Serializable data object MyScreen : AppNavKey
```

The route is invisible to `subclassesOfSealed`, so the shared config never
registers it. This shipped twice (`Settings`, `Search`). Route types also must
live in `feature/nav/` — the sealed hierarchy is single-package.

### ❌ Building a second `SavedStateConfiguration` for one graph

The shared `appNavSavedStateConfig` is the only config. A per-graph config was
the shape of the duplicate-registration crash.

### ❌ Forgetting `@Serializable` on a route type

The shared config serializes every leaf; a route without `@Serializable` fails
at config build (`subclassesOfSealed` needs standard serializers).

### ❌ Using `LocalViewModelStoreOwner` directly

Inside a `NavDisplay`, `LocalViewModelStoreOwner` resolves to whatever is above it —
the `ComponentActivity` on Android, the window on Desktop. Always use
`rememberAppEntryDecorators()` to scope ViewModels to the correct `NavEntry`. This is
handled by every graph in commonMain — don't remove it, and don't add a graph without it.

This was not a belief on Desktop: it was measured. `NestedEntryViewModelScopeTest` showed
that a Desktop entry inherited the outer store, so two entries of one graph shared one
ViewModel — and Koin keys a ViewModel by its class alone, not by its parameters.

### ❌ Writing a second `*NavGraph.android.kt` / `*NavGraph.jvm.kt`

The platform differences are seams. A second copy of the graph is how the agenda's
context menu ended up on five sections on Android and eight on Desktop, and how the
Desktop graphs ended up without the ViewModel-store decorator.

### ❌ Cross-feature navigation via `onExitGraph(AppDestination.X)` for routes that live in the same nested graph

`onExitGraph` is for crossing to a **different** feature. For navigation inside the same nested graph, use `Local*Navigator.current`.

---

## Related Skills and Decisions

- `singularity-todo-nav3-savedstate` — the shared config, the single sealed root, and the crash history
- `singularity-todo-feature-scaffold` — adding a new feature, Route hierarchy naming
- `singularity-todo-dsl-pattern` — DSL pattern with @DslMarker for agenda presets
- `docs/decisions/2026-09-29-single-sealed-navkey-root.md` — one root, one config; the Settings/Search crash
- `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` — why JVM uses in-memory
- `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md` — the Android cast
- `docs/decisions/2026-09-16-agenda-engine.md` — AgendaEngine design decision
- `docs/decisions/2026-10-02-nav-entries-dedup-deferred.md` — the addendum of 2026-10-21:
  why the deduplication was possible after all (the UI artifact's common variant)
- `docs/decisions/2026-10-21-agenda-context-menu-parity.md` — the gesture is the
  platform's, the list of sections is the app's
- `docs/navigation.md` — the map of every destination and where it is registered
