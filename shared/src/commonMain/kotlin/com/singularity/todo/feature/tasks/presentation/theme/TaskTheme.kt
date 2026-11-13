package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TouchTarget

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

    /**
     * The smallest height an interactive row may have.
     *
     * The app-wide minimum touch target, not a number of this screen's own: a row
     * that is pressed is a control, and the floor for controls is one value. The
     * row's own content is usually taller than this, and the row grows with it —
     * the minimum only stops a row from shrinking below a finger's worth.
     */
    val rowMinHeight = TouchTarget.MinSize

    /**
     * The width a row reserves for the clear button it may or may not show.
     *
     * The row shows a clear button only when there is something to clear, and the
     * space it takes is reserved either way, so the text does not move when the
     * button appears. The slot is the size of the button itself.
     */
    val clearButtonSlot = TouchTarget.MinSize
}
