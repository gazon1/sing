---
name: singularity-todo-nav3-nested-graphs
description: Full architecture of Nav3 two-level nested navigation in this KMP project: expect/actual *NavGraph pattern, sealed Route hierarchies, Local*Navigator providers, entryProvider wiring, and how the outer AppNavHost integrates nested graphs. Use when adding a new feature screen, modifying an existing nested graph, or adding a new route type to any sealed Route hierarchy.
---

# Nav3 Nested Graphs — Architecture

## Two-Level Navigation Model

```
App (top-level Nav3State)
├── NavBackStack<AppDestination>    ← tabs: Inbox, Today, Plans, Search, Settings
│   └── NavDisplay (bottom bar)
│       └── [TasksNavGraph] ← nested graph for tasks feature
│           └── NavBackStack<TasksRoute>: Inbox → Detail → Create
│       └── [ProjectsNavGraph] ← nested graph for projects feature
│           └── NavBackStack<ProjectsRoute>: List → Detail → Editor
│       └── [NotesNavGraph]
│       └── [SearchNavGraph]
│       └── [SettingsNavGraph]
```

Each nested graph is **fully isolated**: own `NavBackStack<FeatureRoute>`, own `NavDisplay`, own `Local*Navigator`. The outer graph only sees `AppDestination.TasksGraph` (a single entry per feature), never the inner routes.

---

## File Structure

```
feature/<feature>/presentation/nav/
├── <Feature>Route.kt          — sealed interface + all data objects, @Serializable
├── <Feature>NavGraph.kt      — expect declarations (commonMain)
├── <Feature>NavGraph.android.kt  — actual: navSavedStateConfig + rememberNavBackStack
└── <Feature>NavGraph.jvm.kt      — actual: rememberInMemoryNavBackStack
```

**Example: Tasks**
```
feature/tasks/presentation/nav/
├── TasksRoute.kt                 — sealed interface TasksRoute + Inbox/Today/ByProject/Detail/Create
├── TasksNavGraph.kt             — expect fun TasksNavGraph(...)
├── TasksNavGraph.android.kt      — Android actual
└── TasksNavGraph.jvm.kt         — JVM actual
```

---

## The Sealed Route Hierarchy

```kotlin
// TasksRoute.kt (commonMain)
@Serializable
sealed interface TasksRoute : NavKey {
    @Serializable data object Inbox : TasksRoute
    @Serializable data object Today : TasksRoute
    @Serializable data object ByProject : TasksRoute
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
// TasksNavGraph.kt
@Composable
expect fun TasksNavGraph(
    start: TasksRoute,
    onExitGraph: (AppDestination?) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
expect fun tasksEntryProvider(): (TasksRoute) -> NavEntry<TasksRoute>
```

The `onExitGraph: (AppDestination?) -> Unit` callback handles cross-feature navigation:
- `onExitGraph(AppDestination.ProjectDetail(id))` → outer graph navigates to that destination
- `onExitGraph(null)` → outer graph handles back (exit nested graph)

### Android: `*NavGraph.android.kt`

```kotlin
import com.singularity.todo.feature.nav.navSavedStateConfig

@Composable
actual fun TasksNavGraph(start: TasksRoute, onExitGraph: ..., modifier: ...) {
    val savedStateConfig = remember {
        navSavedStateConfig(
            TasksRoute.Inbox.serializer(),
            TasksRoute.Today.serializer(),
            TasksRoute.ByProject.serializer(),
            TasksRoute.Detail.serializer(),
            TasksRoute.Create.serializer(),
        )
    }
    @Suppress("UNCHECKED_CAST")
    val backStack: NavBackStack<TasksRoute> = rememberNavBackStack(savedStateConfig, start)
        as NavBackStack<TasksRoute>

    val navigator = remember(backStack, onExitGraph) {
        TasksNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(LocalTasksNavigator provides navigator) {
        BackHandler(enabled = backStack.size <= 1) { onExitGraph(null) }
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                entry<TasksRoute.Inbox>    { TaskListScreen(TasksRoute.Inbox) }
                entry<TasksRoute.Today>    { TaskListScreen(TasksRoute.Today) }
                entry<TasksRoute.ByProject> { TaskListScreen(TasksRoute.ByProject) }
                entry<TasksRoute.Detail>  { TaskDetailViewScreen(it.taskId) }
                entry<TasksRoute.Create>  { TaskCreateScreen(it.initialDueDate) }
            },
        )
    }
}
```

### JVM: `*NavGraph.jvm.kt`

```kotlin
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

@Composable
actual fun TasksNavGraph(start: TasksRoute, onExitGraph: ..., modifier: ...) {
    val backStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(start)

    val navigator = remember(backStack, onExitGraph) {
        TasksNavigator(backStack, onExitGraph)
    }

    CompositionLocalProvider(LocalTasksNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = { navigator.back() },
            entryProvider = entryProvider {
                // same entry bodies as Android
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
// In TasksNavGraph.kt (commonMain)
val LocalTasksNavigator = staticCompositionLocalOf<TasksNavigator?> { null }

// TasksNavigator (commonMain)
class TasksNavigator(
    private val backStack: NavBackStack<TasksRoute>,
    private val onExitGraph: (AppDestination?) -> Unit,
) {
    fun toDetail(taskId: TaskId) = backStack.add(TasksRoute.Detail(taskId))
    fun toCreate(dueDate: LocalDate? = null) = backStack.add(TasksRoute.Create(dueDate))
    fun back() {
        if (backStack.size > 1) backStack.removeLastOrNull()
        else onExitGraph(null)
    }
}

// Usage inside nested graph screens:
@Composable
fun TaskDetailScreen(taskId: TaskId) {
    val navigator = LocalTasksNavigator.current
    // Use navigator.toDetail(...) without passing callbacks
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
        entry<AppDestination.Inbox> { /* tab icon */ }
        entry<AppDestination.TasksGraph> { route ->
            TasksNavGraph(
                start = route.route,
                onExitGraph = { dest -> if (dest != null) navigator.navigate(dest) else navigator.back() },
                modifier = Modifier,
            )
        }
        entry<AppDestination.ProjectsGraph> { route ->
            ProjectsNavGraph(
                start = route.route,
                onExitGraph = { ... },
            )
        }
        // ...
    },
)
```

`AppDestination.TasksGraph(val route: TasksRoute)` carries the inner start route so the outer graph can launch the nested graph at any inner destination (e.g. deep link).

---

## Adding a New Route Type

### 1. Add to `*Route.kt`

```kotlin
@Serializable
data class NewRoute(val id: SomethingId) : TasksRoute
```

### 2. Android: update `navSavedStateConfig(...)`

```kotlin
// TasksNavGraph.android.kt
navSavedStateConfig(
    TasksRoute.Inbox.serializer(),
    TasksRoute.Today.serializer(),
    TasksRoute.ByProject.serializer(),
    TasksRoute.Detail.serializer(),
    TasksRoute.Create.serializer(),
    TasksRoute.NewRoute.serializer(),  // ← ADD THIS
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
    entry<TasksRoute.NewRoute> { route -> NewRouteScreen(route.id) }
},
```

---

## Common Mistakes

### ❌ Forgetting `@Serializable` on a route type

Android `navSavedStateConfig` calls `.serializer()` on each route. If the route isn't `@Serializable`, it won't compile. JVM doesn't care (no serialization), but Android does.

### ❌ Registering sealed intermediate interfaces in `navSavedStateConfig`

```kotlin
// WRONG
navSavedStateConfig(TasksRoute.List.serializer())  // TasksRoute.List is sealed interface, not instantiable

// CORRECT
navSavedStateConfig(TasksRoute.Inbox.serializer(), TasksRoute.Today.serializer(), ...)  // only concrete leaves
```

### ❌ Using `LocalViewModelStoreOwner` directly on Android

On Android, `LocalViewModelStoreOwner` resolves to `ComponentActivity` inside `NavDisplay` content. Always use `rememberViewModelStoreNavEntryDecorator()` to scope ViewModels to the correct `NavEntry`. This is already handled in all existing `*NavGraph.android.kt` files — don't remove it.

### ❌ Cross-feature navigation via `onExitGraph(AppDestination.X)` for routes that live in the same nested graph

`onExitGraph` is for crossing to a **different** feature. For navigation inside the same nested graph, use `Local*Navigator.current`.

---

## Related Skills and Decisions

- `singularity-todo-nav3-savedstate` — Android SavedStateConfiguration vs JVM in-memory, serializer registration rules
- `singularity-todo-feature-scaffold` — adding a new feature, Route hierarchy naming
- `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` — why JVM uses in-memory
- `docs/decisions/2026-09-16-nav3-savedstate-serializers-required.md` — why Android needs polymorphic registration
