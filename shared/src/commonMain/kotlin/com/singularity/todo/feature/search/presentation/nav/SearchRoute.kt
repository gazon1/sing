package com.singularity.todo.feature.search.presentation.nav

import androidx.navigation3.runtime.NavKey

/**
 * Navigation route for the search nested graph.
 *
 * Lives inside [SearchNavGraph] which provides its own NavBackStack.
 * Single-route — the search form is the only entry.
 * NOT @Serializable — the nested graph uses an empty SavedStateConfiguration.
 */
data object Search : NavKey
