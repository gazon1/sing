---
name: singularity-todo-nav3-savedstate
description: Nav3 back stack persistence in this KMP project. Covers Android (SavedStateConfiguration + navSavedStateConfig helper for process-death persistence) vs Desktop JVM (in-memory NavBackStack via rememberInMemoryNavBackStack, no SavedStateConfiguration). Use when creating or modifying any *NavGraph.kt file, when adding a new route/data-object to AppDestination or any sealed route hierarchy, or when a SerializationException "Serializer for subclass 'X' is not found in the polymorphic scope of 'NavKey'" appears on Android.
---

# Nav3 Back Stack — Android vs Desktop JVM

This project uses two different back stack strategies by platform:

| | Android | Desktop JVM |
|---|---|---|
| Back stack type | `rememberNavBackStack(savedStateConfig, start)` | `rememberInMemoryNavBackStack(start)` |
| Process death survival | ✅ Yes — `SavedStateConfiguration` + `navSavedStateConfig(...)` | ❌ No — in-memory only |
| `LocalSaveableStateRegistry` | Real registry from ComponentActivity | Always `null` (`savedstate-compose-desktop` is an empty stub) |
| Serializer registration | Required — `navSavedStateConfig(...)` registers all `NavKey` subtypes | Not needed — no serialization |

**The two paths are not equivalent.** If you are uncertain which template to use, check the file extension: `*.android.kt` → Android template; `*.jvm.kt` → Desktop template.

---

## Android: `navSavedStateConfig(...)` helper

On Android, `rememberNavBackStack` requires a `SavedStateConfiguration` whose `serializersModule` registers every concrete `NavKey` subtype reachable in the stack. Use the shared `navSavedStateConfig(...)` helper from `com.singularity.todo.feature.nav.navSavedStateConfig`.

**Do NOT** write the `SerializersModule { polymorphic(NavKey::class) { subclass(...) } }` block inline — use the helper instead.

### Top-level factory — `Nav3StateFactory.android.kt`

```kotlin
import com.singularity.todo.feature.nav.navSavedStateConfig

val savedStateConfig = remember {
    navSavedStateConfig(
        AppDestination.Inbox.serializer(),
        AppDestination.Today.serializer(),
        // ... all AppDestination subtypes
    )
}

val backStacks = topLevelRoutes.associateWith { key ->
    rememberNavBackStack(savedStateConfig, key)
}
```

### Nested multi-route graph — `*NavGraph.android.kt`

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
    val backStack: NavBackStack<TasksRoute> = rememberNavBackStack(savedStateConfig, start)

    // ... NavDisplay setup
}
```

### Nested single-route graph — `SettingsNavGraph.android.kt`, `SearchNavGraph.android.kt`

```kotlin
val savedStateConfig = remember {
    navSavedStateConfig(Settings.serializer()) // or Search.serializer()
}
val backStack: NavBackStack<Settings> = rememberNavBackStack(savedStateConfig, Settings)
```

---

## Desktop JVM: `rememberInMemoryNavBackStack(...)`

On Desktop, `LocalSaveableStateRegistry` is always `null` (the `savedstate-compose-desktop` artifact is a deliberate empty stub). Any `SavedStateConfiguration` is dead code. Use the in-memory `NavBackStack` directly:

### Top-level factory — `Nav3StateFactory.jvm.kt`

```kotlin
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

val backStacks = topLevelRoutes.associateWith { key ->
    rememberInMemoryNavBackStack(key)
}
```

### Any nested graph — `*NavGraph.jvm.kt`

```kotlin
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

@Composable
actual fun TasksNavGraph(start: TasksRoute, onExitGraph: ..., modifier: ...) {
    val backStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(start)

    // ... NavDisplay setup
}
```

No `SavedStateConfiguration`, no `SerializersModule`, no `polymorphic`, no `subclass`. The type is inferred from `start: TasksRoute` — no cast needed, no `@Suppress("UNCHECKED_CAST")`.

---

## Adding a new route type

### On Android (always required)

Every `NavKey` subtype that may appear in an Android back stack **must** be registered in `navSavedStateConfig(...)` for every NavGraph that can contain it. Without registration, process death / rotation causes `SerializationException`.

```kotlin
// TasksNavGraph.android.kt — add the new subtype:
navSavedStateConfig(
    TasksRoute.Inbox.serializer(),
    TasksRoute.Today.serializer(),
    TasksRoute.ByProject.serializer(),
    TasksRoute.Detail.serializer(),
    TasksRoute.Create.serializer(),
    TasksRoute.NewRoute.serializer(), // ← ADD THIS LINE
)
```

The compiler will not warn if you forget. Always update `navSavedStateConfig(...)` when adding a new route.

### On Desktop (not needed)

No registration required. `rememberInMemoryNavBackStack(start)` works for any `T : NavKey`.

### In `commonMain` sealed hierarchy

The route type itself must be `@Serializable` (so Android can call `.serializer()` on it):

```kotlin
@Serializable
data object NewRoute : TasksRoute()
```

---

## Symptoms of getting it wrong

### Android: missing serializer registration

**`SerializationException: Serializer for subclass 'X' is not found in the polymorphic scope of 'NavKey'`** at rotation or process death. The back stack silently fails to restore — the app appears to restart from the initial route instead of restoring the previous navigation state.

### Cascade crash on both platforms

**`Size(2147483647 x 64)`** inside `TopAppBarMeasurePolicy`. This is a cascade failure: `SerializationException` aborts composition mid-measure, leaving `Constraints.Infinity` on width. Fix the serializer registration and the layout error vanishes.

---

## What NOT to do

- **Never** write `SavedStateConfiguration { }` with an empty body on Android — it opts into `PolymorphicSerializer(NavKey::class)` with no registrations. Always use `navSavedStateConfig(...)`.
- **Never** use `SavedStateConfiguration` on Desktop — it is dead code and a maintenance burden. Use `rememberInMemoryNavBackStack(...)`.
- **Never** call `subclass(SomeSealedInterface.serializer())` — sealed intermediate interfaces are not instantiable; only concrete leaf types can be in the stack. Register only the leaves.
- **Never** add `@Suppress("UNCHECKED_CAST")` for the `NavBackStack` cast — `NavBackStack<T>(start)` or `rememberNavBackStack(config, start)` already returns the correctly typed stack.

---

## Related decisions and skills

- `docs/decisions/2026-09-16-nav3-savedstate-serializers-required.md` — Android serializer requirement (still in force for Android)
- `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` — this design decision
- `singularity-todo-cross-feature-navigation` — orthogonal; chip navigation
- `singularity-todo-feature-scaffold` — broader CRUD-feature pattern
