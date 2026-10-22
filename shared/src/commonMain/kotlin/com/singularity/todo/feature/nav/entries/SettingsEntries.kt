package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavStacks
import com.singularity.todo.feature.nav.NavCallbacks
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.settings.presentation.nav.SettingsNavGraph

/**
 * The app-level entries the settings feature owns.
 *
 * @param nav the outer navigation callbacks.
 * @param stacks the stacks the platform hoists.
 */
fun EntryProviderScope<AppDestination>.settingsEntries(nav: NavCallbacks, stacks: AppNavStacks) {
    entry<AppDestination.Settings>(clazzContentKey = { it.stableContentKey() }) {
        SettingsNavGraph(
            navCallbacks = nav,
            backStack = stacks.settings,
        )
    }
}
