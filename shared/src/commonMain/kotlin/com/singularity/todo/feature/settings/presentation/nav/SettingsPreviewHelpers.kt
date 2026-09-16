package com.singularity.todo.feature.settings.presentation.nav

/**
 * No-op [SettingsNavigator] for use in @Preview composables where [LocalSettingsNavigator]
 * is not available (i.e., outside of [SettingsNavGraph]).
 */
class PreviewSettingsNavigator :
    SettingsNavigator(
        onExitGraph = {},
    ) {
    override fun openProfileSwitcher() { /* no-op in preview */ }
}
