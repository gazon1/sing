package com.singularity.todo.feature.search.presentation.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal for [SearchNavigator] — gives screens inside the search
 * nested graph a type-safe way to trigger cross-feature navigation without
 * threading callbacks through every composable.
 */
val LocalSearchNavigator = compositionLocalOf<SearchNavigator> {
    error("SearchNavigator not provided — wrap with SearchNavGraph")
}
