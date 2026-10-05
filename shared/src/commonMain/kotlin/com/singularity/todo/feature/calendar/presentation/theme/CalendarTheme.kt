package com.singularity.todo.feature.calendar.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color palette for the Calendar screen.
 *
 * Dark palette matches the original mock hex values (designed for the TickTick/Linear
 * dark theme screenshots). Light palette is derived from [MaterialTheme.colorScheme]
 * for consistency with the rest of the app.
 */
data class CalendarPalette(
    val background: Color,
    val surface: Color,
    val gridLine: Color,
    val gridLineStrong: Color,
    val taskDefault: Color,
    val taskDone: Color,
    val taskOverdue: Color,
    val taskSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val link: Color,
    val accent: Color,
    val todayBadge: Color,
    val divider: Color,
    val nowIndicator: Color,
)

val LocalCalendarPalette = compositionLocalOf<CalendarPalette> {
    error("CalendarPalette not provided — wrap CalendarScreen with ProvideCalendarPalette")
}

/** Provides the [CalendarPalette] for all composables in [content]. */
@Composable
fun ProvideCalendarPalette(content: @Composable () -> Unit) {
    // One palette for both modes. `ColorScheme` already differs by mode, so a
    // dark/light branch here could only ever disagree with the theme.
    val palette = calendarPalette(MaterialTheme.colorScheme)
    CompositionLocalProvider(LocalCalendarPalette provides palette, content = content)
}

/**
 * The calendar palette, derived from the active theme.
 *
 * This used to have a second, hand-written dark branch — sixteen navy hex values
 * tuned against a dark scheme the app no longer ships. The file claimed they were
 * "tuned for the deep navy aesthetic of the reference screenshots", which made a
 * stale palette read as a deliberate brand choice: a reviewer comparing two
 * branches would see one following the theme and one not, and reasonably
 * conclude the difference was intentional. It never was — it was the branch
 * nobody revisited. Because the theme's dark mode is a user setting rather than a
 * system read-through, that branch was only reachable when the two happened to
 * agree, so it shipped effectively unreviewed.
 *
 * Two fields stay fixed because they are verdicts rather than roles: a completed
 * task and an overdue task must read differently whatever the accent is.
 */
private fun calendarPalette(scheme: androidx.compose.material3.ColorScheme): CalendarPalette =
    CalendarPalette(
        background = scheme.background,
        surface = scheme.surface,
        gridLine = scheme.outlineVariant,
        gridLineStrong = scheme.outline,
        taskDefault = scheme.surfaceVariant,
        taskDone = scheme.surfaceVariant.copy(alpha = 0.7f),
        taskOverdue = scheme.errorContainer.copy(alpha = 0.45f),
        taskSelected = scheme.primary,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textMuted = scheme.onSurface.copy(alpha = 0.5f),
        link = scheme.primary,
        accent = scheme.primary,
        todayBadge = scheme.primary,
        divider = scheme.outlineVariant,
        nowIndicator = scheme.primary,
    )
