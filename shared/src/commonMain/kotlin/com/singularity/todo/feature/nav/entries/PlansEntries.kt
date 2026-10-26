package com.singularity.todo.feature.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.stableContentKey
import com.singularity.todo.feature.plans.presentation.screen.PlansScreen

/**
 * The app-level entry the plans feature owns.
 *
 * The Plans tab is a screen of its own, not a nested graph: it opens the
 * timeline, and the destinations it opens — a task, the editor — belong to
 * the tasks feature, which the app-level stack already carries.
 */
fun EntryProviderScope<AppDestination>.plansEntries() {
    entry<AppDestination.Plans>(clazzContentKey = { it.stableContentKey() }) {
        PlansScreen()
    }
}
