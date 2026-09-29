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
 * The app's single [SavedStateConfiguration].
 *
 * Navigation 3 restores a back stack by deserializing its keys polymorphically
 * under [NavKey], so the serializers module has to know every concrete route.
 * [AppNavKey] is the one sealed root holding all of them and `subclassesOfSealed`
 * walks it — which is why this is one call with no per-graph registration and no
 * runtime type inspection.
 *
 * Sharing one configuration across every NavGraph is deliberate. A nested graph
 * only ever holds keys from its own hierarchy, but registering the whole app costs
 * a single sealed walk and removes a whole class of bug: a route declared outside
 * [AppNavKey] is invisible to `subclassesOfSealed`, and its screen throws the
 * moment it opens. `Settings` and `Search` did exactly that.
 * See `docs/decisions/2026-09-29-single-sealed-navkey-root.md`.
 *
 * **On JVM Desktop** this configuration is never consulted — `LocalSaveableStateRegistry`
 * resolves to `null` there (the `savedstate-compose-desktop` artifact is deliberately
 * empty) — so NavGraphs use [rememberInMemoryNavBackStack] instead, which carries no
 * serializer overhead and has no process-death state to restore.
 *
 * **Type asymmetry.** [rememberInMemoryNavBackStack] is `reified` and returns a
 * `NavBackStack<T>`; Android's `rememberNavBackStack(config, start)` is not `reified`
 * and returns `NavBackStack<NavKey>`, so Android NavGraphs need an `as NavBackStack<T>`
 * cast. See `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md`.
 */
internal val appNavSavedStateConfig: SavedStateConfiguration = SavedStateConfiguration {
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
 * **When to use on JVM:** replace `rememberNavBackStack(appNavSavedStateConfig, start)`
 * with this. On Android, keep `rememberNavBackStack(appNavSavedStateConfig, start)`.
 *
 * @param start the initial top-level route key for this back stack.
 */
@Composable
fun <T : NavKey> rememberInMemoryNavBackStack(start: T): NavBackStack<T> = remember(start) { NavBackStack(start) }
