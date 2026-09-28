package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Constructs a [SavedStateConfiguration] that registers [routeHierarchy] under the polymorphic
 * [NavKey] root, so that `rememberNavBackStack(configuration, ...)` can dispatch correctly during
 * serialization.
 *
 * **Pass the serializer of a sealed route base — never a hand-maintained list of leaves.**
 * `subclass(serializer)` is an `inline reified` function that keys the registration on
 * `T::class` resolved *at the call site*. Erasing the static type to `KSerializer<NavKey>`
 * (or looping over a `KSerializer<out NavKey>` list) makes `T` infer as `NavKey`, so every
 * entry registers under `NavKey::class` and the second one throws
 * `SerializerAlreadyRegisteredException`. Passing the sealed base once — e.g.
 * `AppDestination.serializer()` — infers `T = AppDestination`, and the generated
 * `SealedClassSerializer` already covers every leaf, so a new destination needs no
 * registration change.
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
 * @param routeHierarchy serializer of a `@Serializable sealed` hierarchy rooted at [NavKey].
 * @see rememberInMemoryNavBackStack
 */
internal inline fun <reified T : NavKey> navSavedStateConfig(routeHierarchy: KSerializer<T>): SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule = SerializersModule {
            polymorphic(NavKey::class) {
                subclassesOfSealed(routeHierarchy)
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
fun <T : NavKey> rememberInMemoryNavBackStack(start: T): androidx.navigation3.runtime.NavBackStack<T> =
    remember(start) {
        androidx.navigation3.runtime.NavBackStack(start)
    }
