package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * Design tokens for the Task Creation screen — spacing only.
 *
 * The colour half used to live here as `TaskColors`: twelve fixed values in a
 * dark palette that could not respond to the theme, so a user in the app's
 * default light mode saw a dark task editor. Colours now come from the active
 * theme — plain roles from `MaterialTheme.colorScheme`, derived values from
 * [TaskDerivedColors], and the ones that must *not* follow the theme from
 * [TaskSemanticColors].
 */
object TaskSpacing {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    val screenPadding = 16.dp
    val cardCornerRadius = 14.dp
    val cardPaddingHorizontal = 16.dp
    val cardPaddingVertical = 14.dp
    val iconSize = 22.dp
    val iconSizeLarge = 26.dp
}
