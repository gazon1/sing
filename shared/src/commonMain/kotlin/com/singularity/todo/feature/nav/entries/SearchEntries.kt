package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavStacks
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.search.presentation.nav.SearchNavGraph

/**
 * The app-level entries the search feature owns.
 *
 * @param nav the outer navigation callbacks.
 * @param stacks the stacks the platform hoists.
 */
fun EntryProviderScope<AppDestination>.searchEntries(nav: NavCallbacks, stacks: AppNavStacks) {
    entry<AppDestination.Search>(clazzContentKey = { it.stableContentKey() }) {
        SearchNavGraph(
            navCallbacks = nav,
            backStack = stacks.search,
        )
    }
}
