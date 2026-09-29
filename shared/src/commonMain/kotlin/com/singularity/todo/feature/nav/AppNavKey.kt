package com.singularity.todo.feature.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The single sealed root of every navigation key in the app.
 *
 * Navigation 3 persists a back stack by serializing its keys polymorphically
 * under [NavKey]. The polymorphic module has to know every concrete type that can
 * appear, and `subclassesOfSealed` is what supplies that list — it walks a sealed
 * hierarchy once at configuration time, so a newly added route needs no edit
 * anywhere else.
 *
 * That only works if there *is* one hierarchy. The app used to declare eight
 * independent roots, of which five were sealed interfaces and two were lone
 * `@Serializable data object`s (`Settings`, `Search`). `subclassesOfSealed`
 * rejects the latter outright:
 *
 * ```
 * IllegalArgumentException: subclassesOfSealed only supports automatic adding
 * of subclasses of sealed types with standard serializers.
 * ```
 *
 * so opening Settings or Search killed the process on Android — and no test
 * covered either route, which is why it shipped. See
 * `docs/decisions/2026-09-29-single-sealed-navkey-root.md`.
 *
 * Every route is now a leaf (or a sealed sub-hierarchy) of this one root, which
 * removes the special case entirely: [navSavedStateConfig()] registers the whole
 * app with a single `subclassesOfSealed` call, and needs no runtime type check.
 *
 * Kotlin requires a sealed hierarchy to live in one package, which is why every
 * route type sits here rather than beside the screen it opens. Declaring a new
 * route means extending *this* interface, not [NavKey]:
 *
 * ```kotlin
 * @Serializable data object MyScreen : AppNavKey
 *
 * @Serializable sealed interface MyRoute : AppNavKey {
 *     @Serializable data object List : MyRoute
 * }
 * ```
 */
@Serializable
sealed interface AppNavKey : NavKey
