package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Design tokens specific to the Upcoming screen.
 *
 * All other colors are reused from [TaskListColors] to maintain
 * a single source of truth for the dark theme palette.
 */
object UpcomingTokens {
    /** Red accent for overdue / deadline-flag badge. Exact paste value: #E05353. */
    val AccentRed = Color(0xFFE05353)
}
