package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Constructs a [SavedStateConfiguration] that registers the concrete [NavKey] subtypes in
 * [routeSerializers] under the polymorphic `NavKey` root, so that
 * `rememberNavBackStack(configuration, ...)` can dispatch correctly during serialization.
 *
 * **When to use:** call this from Android-side NavGraph actuals that need process-death
 * persistence of the back stack. The resulting configuration is a no-op on JVM Desktop because
 * `LocalSaveableStateRegistry` resolves to `null` there (the `savedstate-compose-desktop` artifact
 * is deliberately empty), so the polymorphic serializer is never consulted.
 *
 * On JVM Desktop, use [rememberInMemoryNavBackStack] instead — it constructs an in-memory
 * `NavBackStack` directly, without any serializer overhead.
 *
 * **Type-asymmetry note:** [rememberInMemoryNavBackStack] is `reified` and returns
 * `NavBackStack<T>` (fully typed). Android's `rememberNavBackStack(config, start)` is not
 * `reified` and returns `NavBackStack<NavKey>`, so Android NavGraphs require an explicit
 * `as NavBackStack<T>` cast. This is a known limitation — see
 * `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md`.
 *
 * @param routeSerializers one [KSerializer] per concrete route type that may appear in the stack.
 * @see rememberInMemoryNavBackStack
 */
internal fun navSavedStateConfig(
    vararg routeSerializers: KSerializer<out NavKey>,
): SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            routeSerializers.forEach {
                @Suppress("UNCHECKED_CAST")
                subclass(it as KSerializer<NavKey>)
            }
        }
    }
}

/**
 * Creates an in-memory [androidx.navigation3.runtime.NavBackStack] for use on JVM Desktop,
 * where process-death persistence does not exist and [SavedStateConfiguration] is dead code.
 *
 * The returned `NavBackStack` is observable (`MutableList` + `StateObject`) and plugs
 * directly into [androidx.navigation3.ui.NavDisplay].
 *
 * **When to use on JVM:** replace any `rememberNavBackStack(savedStateConfig, start)` call
 * with `rememberInMemoryNavBackStack(start)`. On Android, keep using
 * `rememberNavBackStack(navSavedStateConfig(...), start)`.
 *
 * **Type-asymmetry note:** this function is `reified` and returns `NavBackStack<T>` (fully
 * typed), unlike Android's `rememberNavBackStack` which returns `NavBackStack<NavKey>`.
 * This means JVM callers can use `stack.last()` without a cast, while Android callers
 * cannot. See `docs/decisions/2026-09-16-nav3-type-asymmetry-adr.md`.
 *
 * @param start the initial top-level route key for this back stack.
 */
@Composable
fun <T : NavKey> rememberInMemoryNavBackStack(
    start: T,
): androidx.navigation3.runtime.NavBackStack<T> = remember(start) {
    androidx.navigation3.runtime.NavBackStack(start)
}
