package com.singularity.todo.feature.settings.presentation.nav

import com.singularity.todo.feature.nav.AppDestination

/**
 * Type-safe navigation API for screens inside the settings nested graph.
 *
 * @param onExitGraph Called when the user should exit the nested graph.
 *                    The optional [AppDestination] argument allows the inner graph
 *                    to signal a destination to navigate to in the outer graph.
 */
open class SettingsNavigator(protected val onExitGraph: (AppDestination?) -> Unit) {

    /**
     * Navigate to the profile switcher screen (outside the settings graph).
     */
    open fun openProfileSwitcher() {
        onExitGraph(AppDestination.ProfileSwitcher)
    }

    /**
     * Go back — exits the settings graph back to the outer app back stack.
     */
    open fun back() {
        onExitGraph(null)
    }
}
