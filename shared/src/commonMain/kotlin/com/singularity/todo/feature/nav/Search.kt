package com.singularity.todo.feature.nav

import kotlinx.serialization.Serializable

/**
 * Navigation route for the search nested graph.
 *
 * Lives inside [SearchNavGraph] which provides its own NavBackStack.
 * Single-route — the search form is the only entry.
 *
 * `@Serializable` so that `SearchNavGraph.jvm` can register it in the
 * polymorphic `NavKey` serializers module required by
 * `rememberNavBackStack(SavedStateConfiguration, ...)`.
 */
@Serializable
data object Search : AppNavKey
