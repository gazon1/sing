package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Builds a [SavedStateConfiguration] for one NavGraph.
 *
 * **One instance per graph, not a shared val.** [SavedStateConfiguration] carries
 * the saved-state payload of the graph that uses it, so sharing a single instance
 * across the outer graph and every nested graph lets them overwrite each other's
 * entries. The symptom is easy to misread: rotation restores the agenda but
 * silently drops the nested screen that was on top, so the app looks like it
 * "reset" rather than like a serialization failure. Found by
 * `Maestro/flows/lifecycle/03-rotate-in-editor.yaml`, which passes on the
 * pre-refactor build and failed after.
 *
 * The *registration* is deliberately common, and that part is what fixes the
 * crash this function used to have: all routes hang off [AppNavKey], so one
 * `subclassesOfSealed` call covers every graph — including the lone
 * `@Serializable data object` routes (`Settings`, `Search`), which
 * `subclassesOfSealed` rejects when handed a non-sealed root. That rejection is
 * what killed the process the first time either screen was opened. See
 * `docs/decisions/2026-09-29-single-sealed-navkey-root.md`.
 *
 * **On JVM Desktop** this is never used — `LocalSaveableStateRegistry` resolves
 * to `null` there, and NavGraphs use [rememberInMemoryNavBackStack] instead.
 *
 * **Type asymmetry.** [rememberInMemoryNavBackStack] is `reified` and returns a
 * `NavBackStack<T>`; Android's `rememberNavBackStack(config, start)` is not
 * `reified` and returns `NavBackStack<NavKey>`, so Android NavGraphs need an
 * `as NavBackStack<T>` cast. See `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun navSavedStateConfig(): SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclassesOfSealed(AppNavKey.serializer())
                }
            }
    }

/**
 * A saver for a single navigation key.
 *
 * The back stacks are saved through [navSavedStateConfig]; the *selected top-level
 * route* is not a back stack — it is one key the shell holds in a `MutableState`.
 * A plain `remember` does not survive a configuration change, and a rotation is
 * one: the Activity is recreated, the tab falls back to the start route, and the
 * stack that was open is never composed again. The app looks reset rather than
 * broken, which is exactly what `Maestro/flows/lifecycle/03-rotate-in-editor.yaml`
 * caught.
 *
 * The key is written with the same polymorphic registration the back stacks use,
 * so a route the stacks can restore, this can restore too.
 *
 * @param allowedRoutes the routes that may be restored. A key outside this set —
 *   a route an older build saved that no longer exists as a top-level route —
 *   restores as `null`, and the caller keeps its own default. Without the check a
 *   stale key would leave the shell pointing at a route it has no stack for.
 */
internal fun navKeySaver(allowedRoutes: Set<NavKey>): Saver<NavKey, SavedState> {
    val configuration = navSavedStateConfig()
    val serializer = PolymorphicSerializer(NavKey::class)
    return Saver(
        save = { route -> encodeToSavedState(serializer, route, configuration) },
        // A payload this build cannot decode must not take the app down with it:
        // the route falls back to the caller's default instead.
        restore = { saved ->
            runCatching { decodeFromSavedState(serializer, saved as SavedState, configuration) }
                .getOrNull()
                ?.takeIf { it in allowedRoutes }
        },
    )
}

/**
 * Creates an in-memory [NavBackStack] for use on JVM Desktop, where process-death
 * persistence does not exist and [SavedStateConfiguration] is dead code.
 *
 * The returned back stack is observable (`MutableList` + `StateObject`) and plugs
 * directly into `NavDisplay`.
 *
 * **When to use on JVM:** replace `rememberNavBackStack(navSavedStateConfig(), start)`
 * with this. On Android, keep `rememberNavBackStack(navSavedStateConfig(), start)`.
 *
 * @param start the initial top-level route key for this back stack.
 */
@Composable
fun <T : NavKey> rememberInMemoryNavBackStack(start: T): NavBackStack<T> = remember(start) { NavBackStack(start) }

/**
 * Typed factory for a nested graph's back stack — the one place the platform
 * asymmetry of [NavBackStack] typing is allowed to exist (B2, REQ-NAV-005 companion).
 *
 * - **Android:** delegates to `rememberNavBackStack(savedStateConfig, start)`, whose
 *   erased `NavBackStack<NavKey>` result is cast once, here, instead of in seven
 *   `@Suppress("UNCHECKED_CAST")` graph files. Saveable-backed, so entry recreation
 *   after process death restores the stack.
 * - **JVM Desktop:** falls back to [rememberInMemoryNavBackStack] — no savedstate
 *   registry exists there, and process death is not a desktop concept.
 *
 * @param savedStateConfig per-graph serializers module (see [navSavedStateConfig]);
 *   ignored on JVM.
 * @param start the seed route the stack starts with.
 */
@Composable
expect fun <T : NavKey> rememberNavBackStackTyped(
    savedStateConfig: SavedStateConfiguration,
    start: T,
): NavBackStack<T>

/**
 * The back stack of a nested graph, chosen by the platform.
 *
 * Android — saved-state backed, so the stack survives process death. Desktop — in
 * memory, because there is no process death to survive and no saveable-state
 * registry to write into.
 *
 * One seam instead of a branch in every graph: seven nested graphs each wrote the
 * same platform decision out in full, and the Desktop copy was a second
 * implementation of the same idea. The two differed in more than the storage —
 * Android's stack was keyed on the start route, the Desktop one was not — so a
 * graph written for one platform quietly behaved differently on the other.
 *
 * @param start the seed route the stack starts with.
 */
@Composable
expect fun <T : NavKey> rememberAppNavBackStack(start: T): NavBackStack<T>
