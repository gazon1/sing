---
name: singularity-todo-nav3-nested-graphs
description: Full architecture of Nav3 two-level nested navigation in this KMP project: expect/actual *NavGraph pattern, sealed Route hierarchies, Local*Navigator providers, entryProvider wiring, and how the outer AppNavHost integrates nested graphs. Covers both the per-feature graph pattern (TasksNavGraph, ProjectsNavGraph) and the singleton graph pattern (AgendaNavGraph — same graph reused for 3 tabs with different start routes). Use when adding a new feature screen, modifying an existing nested graph, or adding a new route type to any sealed Route hierarchy.
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

```
feature/<feature>/presentation/nav/
├── <Feature>Route.kt          — sealed interface + all data objects, @Serializable
├── <Feature>NavGraph.kt      — expect declarations (commonMain)
├── <Feature>NavGraph.android.kt  — actual: navSavedStateConfig + rememberNavBackStack
└── <Feature>NavGraph.jvm.kt      — actual: rememberInMemoryNavBackStack
```

**Example: Tasks (Detail + Create only — after AgendaEngine MR1)**
```
feature/tasks/presentation/nav/
├── TasksRoute.kt                 — sealed interface TasksRoute + Detail/Create
├── TasksNavGraph.kt             — expect fun TasksNavGraph(...)
├── TasksNavGraph.android.kt      — Android actual
└── TasksNavGraph.jvm.kt         — JVM actual
```
> After AgendaEngine MR1, TasksNavGraph only handles Detail + Create screens.
> Inbox/Today/Upcoming tabs now use `AgendaNavGraph(start = AgendaStartRoute.Inbox/Today/Upcoming)`.

**Example: Agenda (singleton graph, 3 tab variants)**
```
feature/agenda/presentation/nav/
├── AgendaRoute.kt               — AgendaStartRoute (Inbox/Today/Upcoming/Project/Tag)
├── AgendaNavGraph.kt           — expect fun AgendaNavGraph(start: AgendaStartRoute, ...)
├── AgendaNavGraph.android.kt    — Android actual
└── AgendaNavGraph.jvm.kt       — JVM actual
```
> AgendaNavGraph is mounted 3 times at the top level (one per tab) with different `start` values.
> The `start: AgendaStartRoute` parameter makes a single graph reusable for all variants.

---

## The Sealed Route Hierarchy

```kotlin
// AgendaStartRoute.kt (feature/nav/ — not inside presentation/nav/)
@Serializable
sealed interface AgendaStartRoute : NavKey {
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

// TasksRoute.kt (commonMain) — after AgendaEngine MR1
@Serializable
sealed interface TasksRoute : NavKey {
    @Serializable data class Detail(val taskId: TaskId) : TasksRoute
    @Serializable data class Create(val initialDueDate: LocalDate? = null) : TasksRoute
}
```

**Rules:**
- The sealed interface itself is NOT `@Serializable` — only the concrete leaves.
- Every data object/class that appears in a back stack **must** be `@Serializable` (for Android serialization).
- Intermediate sealed interfaces (`TasksRoute.List`, etc.) are NOT registered — only concrete leaves.
- `NavKey` is a plain marker interface; it is NOT sealed and NOT `@Serializable`.

---

## The Expect/Actual Pattern

### commonMain: `*NavGraph.kt`

```kotlin
// AgendaNavGraph.kt
@Composable
expect fun AgendaNavGraph(
    start: AgendaStartRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
expect fun agendaEntryProvider(): (AgendaStartRoute) -> NavEntry<AgendaStartRoute>
```

The `onExitGraph: (AppDestination?) -> Unit` callback handles cross-feature navigation:
- `onExitGraph(AppDestination.ProjectDetail(id))` → outer graph navigates to that destination
- `onExitGraph(null)` → outer graph handles back (exit nested graph)

### Android: `*NavGraph.android.kt`

```kotlin
import com.singularity.todo.feature.nav.navSavedStateConfig

@Composable
actual fun AgendaNavGraph(start: AgendaStartRoute, onExitGraph: ..., modifier: ...) {
    val savedStateConfig = remember {
        navSavedStateConfig(
            AgendaStartRoute.Inbox.serializer(),
            AgendaStartRoute.Today.serializer(),
            AgendaStartRoute.Upcoming.serializer(),
            AgendaStartRoute.Project.serializer(),
            AgendaStartRoute.Tag.serializer(),
        )
    }
    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<AgendaStartRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<AgendaStartRoute>

    val navigator = remember(backStack, onExitGraph) {
        AgendaNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(LocalAgendaNavigator provides navigator) {
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.back() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<AgendaStartRoute.Inbox>     { AgendaScreen(AgendaPresets.Inbox) }
                entry<AgendaStartRoute.Today>     { AgendaScreen(AgendaPresets.Today) }
                entry<AgendaStartRoute.Upcoming>  { AgendaScreen(AgendaPresets.Upcoming) }
                entry<AgendaStartRoute.Project>   { AgendaScreen(AgendaPresets.byProject(it.id)) }
                entry<AgendaStartRoute.Tag>       { AgendaScreen(AgendaPresets.byTag(it.id)) }
            },
        )
    }
}
```

### JVM: `*NavGraph.jvm.kt`

```kotlin
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

@Composable
actual fun AgendaNavGraph(start: AgendaStartRoute, onExitGraph: ..., modifier: ...) {
    val backStack: NavBackStack<AgendaStartRoute> = rememberInMemoryNavBackStack(start)

    val navigator = remember(backStack, onExitGraph) {
        AgendaNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(LocalAgendaNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<AgendaStartRoute.Inbox>     { AgendaScreen(AgendaPresets.Inbox) }
                entry<AgendaStartRoute.Today>     { AgendaScreen(AgendaPresets.Today) }
                entry<AgendaStartRoute.Upcoming>  { AgendaScreen(AgendaPresets.Upcoming) }
                entry<AgendaStartRoute.Project>   { AgendaScreen(AgendaPresets.byProject(it.id)) }
                entry<AgendaStartRoute.Tag>       { AgendaScreen(AgendaPresets.byTag(it.id)) }
            },
        )
    }
}
```

**Key differences:**

| Aspect | Android | JVM |
|---|---|---|
| Back stack | `rememberNavBackStack(savedStateConfig, start)` | `rememberInMemoryNavBackStack(start)` |
| Serializers | Required — `navSavedStateConfig(...)` registers all `NavKey` subtypes | Not needed |
| BackHandler | Yes — intercept system back at start route | No — desktop has no system back |
| ViewModelStore decorator | `rememberViewModelStoreNavEntryDecorator()` | Not needed |
| Process death survival | ✅ Yes | ❌ No (in-memory only) |

---

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

## Outer AppNavHost Wiring

The outer `AppNavHost` integrates nested graphs via `entryProvider`:

```kotlin
// AppNavHost.kt
NavDisplay(
    backStack = navState.backStack,
    onBack = { navigator.back() },
    entryProvider = entryProvider {
        // Pattern B: singleton AgendaNavGraph for 3 tabs
        entry<AppDestination.Inbox> {
            AgendaNavGraph(start = AgendaStartRoute.Inbox, onExitGraph = { ... })
        }
        entry<AppDestination.Today> {
            AgendaNavGraph(start = AgendaStartRoute.Today, onExitGraph = { ... })
        }
        entry<AppDestination.Upcoming> {
            AgendaNavGraph(start = AgendaStartRoute.Upcoming, onExitGraph = { ... })
        }
        // Pattern A: per-feature graphs
        entry<AppDestination.AgendaGraph> { route ->
            AgendaNavGraph(
                start = route.start.toAgendaStartRoute(),
                onExitGraph = { dest -> ... },
            )
        }
        entry<AppDestination.TasksGraph> { route ->
            TasksNavGraph(
                start = route.start.toTasksRoute(),
                onExitGraph = { ... },
            )
        }
        // ...
    },
)
```

`AppDestination.AgendaGraph(val start: AgendaStartRoute)` carries the inner start route so the outer graph can launch the nested agenda at any inner destination (e.g. deep link from a notification).

---

## Adding a New Route Type

### 1. Add to `*Route.kt`

```kotlin
@Serializable
data class NewRoute(val id: SomethingId) : AgendaStartRoute
```

### 2. Android: update `navSavedStateConfig(...)`

```kotlin
// AgendaNavGraph.android.kt
navSavedStateConfig(
    AgendaStartRoute.Inbox.serializer(),
    AgendaStartRoute.Today.serializer(),
    AgendaStartRoute.Upcoming.serializer(),
    AgendaStartRoute.Project.serializer(),
    AgendaStartRoute.Tag.serializer(),
    AgendaStartRoute.NewRoute.serializer(),  // ← ADD THIS
)
```

**The compiler will NOT warn if you forget.** Always update `navSavedStateConfig` when adding a route.

### 3. JVM: no changes needed

`rememberInMemoryNavBackStack(start)` works for any `T : NavKey` — no serializer registration.

### 4. Add `entry { }` in `entryProvider`

In both `*NavGraph.android.kt` and `*NavGraph.jvm.kt`:
```kotlin
entryProvider = entryProvider {
    // ... existing entries
    entry<AgendaStartRoute.NewRoute> { route -> AgendaScreen(AgendaPresets.bySomething(route.id)) }
},
```

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

### ❌ Forgetting `@Serializable` on a route type

Android `navSavedStateConfig` calls `.serializer()` on each route. If the route isn't `@Serializable`, it won't compile. JVM doesn't care (no serialization), but Android does.

### ❌ Registering sealed intermediate interfaces in `navSavedStateConfig`

```kotlin
// WRONG
navSavedStateConfig(AgendaStartRoute.List.serializer())  // AgendaStartRoute.List is sealed interface

// CORRECT
navSavedStateConfig(AgendaStartRoute.Inbox.serializer(), ...)  // only concrete leaves
```

### ❌ Using `LocalViewModelStoreOwner` directly on Android

On Android, `LocalViewModelStoreOwner` resolves to `ComponentActivity` inside `NavDisplay` content. Always use `rememberViewModelStoreNavEntryDecorator()` to scope ViewModels to the correct `NavEntry`. This is already handled in all existing `*NavGraph.android.kt` files — don't remove it.

### ❌ Cross-feature navigation via `onExitGraph(AppDestination.X)` for routes that live in the same nested graph

`onExitGraph` is for crossing to a **different** feature. For navigation inside the same nested graph, use `Local*Navigator.current`.

---

## Related Skills and Decisions

- `singularity-todo-nav3-savedstate` — Android SavedStateConfiguration vs JVM in-memory, serializer registration rules
- `singularity-todo-feature-scaffold` — adding a new feature, Route hierarchy naming
- `singularity-todo-dsl-pattern` — DSL pattern with @DslMarker for agenda presets
- `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` — why JVM uses in-memory
- `docs/decisions/2026-09-16-nav3-savedstate-serializers-required.md` — why Android needs polymorphic registration
- `docs/decisions/2026-09-16-agenda-engine.md` — AgendaEngine design decision
