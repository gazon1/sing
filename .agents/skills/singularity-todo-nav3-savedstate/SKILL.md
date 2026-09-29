---
name: singularity-todo-nav3-savedstate
description: Nav3 back stack persistence in this KMP project. Covers the single shared appNavSavedStateConfig (one sealed AppNavKey root, one subclassesOfSealed call) on Android vs in-memory NavBackStack on Desktop JVM, where route types must live (feature/nav/), and why a route extending NavKey instead of AppNavKey crashes its screen on open. Use when creating or modifying any *NavGraph.kt file, when adding a new route type, or when IllegalArgumentException "subclassesOfSealed only supports automatic adding of subclasses of sealed types" or "Serializer for subclass 'X' is not found" appears on Android.
---

# Nav3 Back Stack — Android vs Desktop JVM

This project uses two different back stack strategies by platform:

| | Android | Desktop JVM |
|---|---|---|
| Back stack type | `rememberNavBackStack(appNavSavedStateConfig, start)` | `rememberInMemoryNavBackStack(start)` |
| Process death survival | Yes — the shared config serializes keys | No — in-memory only |
| `LocalSaveableStateRegistry` | Real registry from ComponentActivity | Always `null` (`savedstate-compose-desktop` is an empty stub) |
| Serializer registration | One shared config registers the whole app | Not needed — no serialization |

**The two paths are not equivalent.** If uncertain which template applies, check
the file extension: `*.android.kt` → Android; `*.jvm.kt` → Desktop.

---

## The single sealed root: `AppNavKey`

Every route in the app is a leaf (or a sealed sub-hierarchy) of one interface:

```kotlin
// feature/nav/AppNavKey.kt
@Serializable
sealed interface AppNavKey : NavKey
```

`appNavSavedStateConfig` registers it once:

```kotlin
// feature/nav/Nav3SavedState.kt
internal val appNavSavedStateConfig: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed(AppNavKey.serializer())
        }
    }
}
```

`subclassesOfSealed` walks the sealed hierarchy at configuration time, so a newly
added route needs no registration anywhere. **The config is shared by all eight
NavGraphs** — a nested graph only ever holds keys from its own hierarchy, and
registering the whole app costs one sealed walk.

**Why one root, not per-graph configs.** The previous pattern called
`navSavedStateConfig(serializer)` per graph and assumed every route base was a
sealed hierarchy. Two graphs had none: `Settings` and `Search` were lone
`data object`s extending `NavKey`, and `subclassesOfSealed` rejects those —
opening either screen killed the process
(`IllegalArgumentException: subclassesOfSealed only supports automatic adding of
subclasses of sealed types`). One root makes that state unrepresentable. See
`docs/decisions/2026-09-29-single-sealed-navkey-root.md`.

**Where route types live.** Kotlin requires a sealed hierarchy in one package,
so every route type sits in `com.singularity.todo.feature.nav` —
`AppDestination.kt`, `AgendaStartRoute.kt`, `TasksRoute.kt`, `NotesRoute.kt`,
`ProjectsRoute.kt`, `CalendarRoute.kt`, `Settings.kt`, `Search.kt` — not beside
the screens they open. NavGraphs and entry providers stay per feature.

---

## Android NavGraph actual

```kotlin
import com.singularity.todo.feature.nav.appNavSavedStateConfig

@Composable
actual fun TasksNavGraph(start: TasksRoute, onExitGraph: ..., modifier: ...) {
    // A schema constant — remember { } around it is unnecessary.
    val savedStateConfig = appNavSavedStateConfig

    @Suppress("UNCHECKED_CAST")  // rememberNavBackStack is not reified; see ADR below
    val backStack: NavBackStack<TasksRoute> =
        rememberNavBackStack(savedStateConfig, start) as NavBackStack<TasksRoute>
    // ... NavDisplay setup
}
```

---

## Desktop JVM

On Desktop, `LocalSaveableStateRegistry` is always `null`, so any
`SavedStateConfiguration` is dead code. Use the in-memory back stack:

```kotlin
import com.singularity.todo.feature.nav.rememberInMemoryNavBackStack

@Composable
actual fun TasksNavGraph(start: TasksRoute, onExitGraph: ..., modifier: ...) {
    val backStack: NavBackStack<TasksRoute> = rememberInMemoryNavBackStack(start)
    // ... NavDisplay setup
}
```

No config, no cast, no `@Suppress`. The type is inferred from `start: TasksRoute`.

---

## Adding a new route type

1. Declare it as a leaf of the root — `AppNavKey`, never `NavKey`:

```kotlin
// feature/nav/TasksRoute.kt
@Serializable
data class NewRoute(val id: SomeId) : TasksRoute   // TasksRoute : AppNavKey
```

2. Add the `entry { }` in both NavGraph actuals' `entryProvider`.
3. Done. The shared config picks it up; no serializer list to edit.

A route extending `NavKey` directly compiles and tests green — it is only
invisible to `subclassesOfSealed`, so its screen crashes the moment it opens.
`NavSavedStateConfigTest` holds the inventory of nested graph routes to catch
this; name a new graph's route there.

---

## Symptoms of getting it wrong

| Symptom | Cause |
|---|---|
| `IllegalArgumentException: subclassesOfSealed only supports automatic adding of subclasses of sealed types` when a screen opens | A route (usually a lone `data object`) extends `NavKey` or a non-sealed type instead of `AppNavKey`. |
| `SerializerAlreadyRegisteredException` at startup | Two registrations under the same base — historically caused by erasing the static type to `KSerializer<NavKey>` in a vararg helper. The shared-config design removes the failure mode; if it reappears, something registered outside `appNavSavedStateConfig`. |
| `SerializationException: Serializer for subclass 'X' is not found` at rotation/process death | A route not reachable from `AppNavKey` (see above) — same root cause, different surfacing. |

---

## What NOT to do

- **Never** declare a route as `... : NavKey`. Extend `AppNavKey`.
- **Never** build a per-graph `SavedStateConfiguration` — the shared
  `appNavSavedStateConfig` is the single source. A second config is how the
  duplicate-registration crash returned last time.
- **Never** use `SavedStateConfiguration` on Desktop — dead code. Use
  `rememberInMemoryNavBackStack(...)`.
- **Never** register sealed intermediate interfaces manually —
  `subclassesOfSealed(AppNavKey.serializer())` already covers every leaf.

---

## Related

- `docs/decisions/2026-09-29-single-sealed-navkey-root.md` — the one-root decision and the Settings/Search crash
- `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` — JVM in-memory rationale
- `docs/decisions/2026-09-16-nav3-savedstate-serializers-required.md` — Android serializer requirement (historical, superseded in part by the one-root ADR)
- `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md` — why Android needs the cast and JVM does not
- `singularity-todo-nav3-nested-graphs` — graph architecture and entry wiring
