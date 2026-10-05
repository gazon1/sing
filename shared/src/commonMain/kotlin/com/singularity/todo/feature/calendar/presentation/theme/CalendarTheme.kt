package com.singularity.todo.feature.calendar.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.singularity.todo.core.ui.theme.LocalAccentColor
import com.singularity.todo.core.ui.theme.LocalIsDarkTheme
import com.singularity.todo.core.ui.theme.SingularityAccents

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
    val scheme = MaterialTheme.colorScheme
    val accent = LocalAccentColor.current
    // Resolved from the active theme, NOT from isSystemInDarkTheme(): the app's dark mode is a
    // user setting that can disagree with the system, and a system-derived branch would paint
    // the navy dark palette on top of a light app.
    val isDark = LocalIsDarkTheme.current
    val palette: CalendarPalette = if (isDark) {
        darkCalendarPalette(accent)
    } else {
        lightCalendarPalette(scheme, accent)
    }
    CompositionLocalProvider(LocalCalendarPalette provides palette, content = content)
}

/** Dark palette — tuned for the deep navy/blue-grey aesthetic of the reference screenshots. */
private fun darkCalendarPalette(accent: SingularityAccents): CalendarPalette {
    val accentColor = accent.color
    return CalendarPalette(
        background = Color(0xFF0B1220),
        surface = Color(0xFF101A2C),
        gridLine = Color(0xFF1C2740),
        gridLineStrong = Color(0xFF263252),
        taskDefault = Color(0xFF2A3B5C),
        taskDone = Color(0xFF1A2438),
        taskOverdue = Color(0xFF3A2230),
        taskSelected = accentColor,
        textPrimary = Color(0xFFE7ECF5),
        textSecondary = Color(0xFF8792A8),
        textMuted = Color(0xFF5C6784),
        link = Color(0xFF5AA9F5),
        accent = accentColor,
        todayBadge = accentColor,
        divider = Color(0xFF1C2740),
        nowIndicator = Color(0xFF4FA8FF),
    )
}

/** Light palette — derived from the app's [MaterialTheme.colorScheme]. */
private fun lightCalendarPalette(
    scheme: androidx.compose.material3.ColorScheme,
    accent: SingularityAccents,
): CalendarPalette {
    val accentColor = accent.color
    return CalendarPalette(
        background = scheme.background,
        surface = scheme.surface,
        gridLine = scheme.outlineVariant,
        gridLineStrong = scheme.outline,
        taskDefault = scheme.surfaceVariant,
        taskDone = scheme.surfaceVariant.copy(alpha = 0.7f),
        taskOverdue = Color(0xFF3A2230).copy(alpha = 0.15f),
        taskSelected = accentColor,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textMuted = scheme.onSurface.copy(alpha = 0.5f),
        link = scheme.primary,
        accent = accentColor,
        todayBadge = accentColor,
        divider = scheme.outlineVariant,
        nowIndicator = accentColor,
    )
}
