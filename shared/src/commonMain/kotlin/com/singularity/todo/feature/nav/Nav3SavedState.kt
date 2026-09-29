package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
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
internal fun navSavedStateConfig(): SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclassesOfSealed(AppNavKey.serializer())
        }
    }
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
