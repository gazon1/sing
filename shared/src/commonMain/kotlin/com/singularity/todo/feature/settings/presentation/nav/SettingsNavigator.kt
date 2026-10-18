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
     * Navigate to the attach-account screen.
     *
     * Shown only when the current session is [com.singularity.todo.core.auth.Session.Anonymous].
     * The target screen accepts email/password and calls
     * [com.singularity.todo.feature.auth.AuthIntent.AttachAccount] to attach
     * an account to the anonymous session.
     */
    open fun openAttachAccount() {
        onExitGraph(AppDestination.AttachAccount)
    }

    /**
     * Navigate to the change-password screen.
     *
     * Shown only when the current session is [com.singularity.todo.core.auth.Session.SignedIn].
     * The target screen accepts a new password and calls
     * [com.singularity.todo.feature.profile.presentation.ChangePasswordIntent.Submit].
     */
    open fun openChangePassword() {
        onExitGraph(AppDestination.ChangePassword)
    }

    /**
     * Go back — exits the settings graph back to the outer app back stack.
     */
    open fun back() {
        onExitGraph(null)
    }
}
