package com.singularity.todo.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme
import com.singularity.todo.core.settings.ThemeMode

enum class SingularityAccents(val displayName: String, val color: Color) {
    Blue("Blue", Color(0xFF2196F3)),
    Purple("Purple", Color(0xFF9C27B0)),
    Pink("Pink", Color(0xFFE91E63)),
    Red("Red", Color(0xFFF44336)),
    Orange("Orange", Color(0xFFFF9800)),
    Yellow("Yellow", Color(0xFFFFEB3B)),
    Green("Green", Color(0xFF4CAF50)),
    Teal("Teal", Color(0xFF009688)), ;

    companion object {
        fun fromString(name: String): SingularityAccents = entries.find {
            it.name.equals(
                name,
                ignoreCase = true,
            )
        }
            ?: Blue
    }
}

val LocalAccentColor = compositionLocalOf { SingularityAccents.Blue }

/**
 * The *resolved* dark-mode flag of the active theme.
 *
 * Consumers must read this instead of calling [isSystemInDarkTheme]: the app's dark mode is a
 * user setting ([com.singularity.todo.core.settings.SettingsBundle.Appearance.darkTheme], default
 * `false`) and does not follow the system setting. Reading the system value directly silently
 * desynchronises any theme-aware subtree from the palette actually in effect.
 */
val LocalIsDarkTheme = compositionLocalOf { false }

/**
 * The Material 3 palette is derived at runtime from [SingularityTheme]'s `accent` seed color
 * (MaterialKolor / Google's `material-color-utilities`), so light and dark are a single call
 * and adding an accent is a data change rather than a palette redesign.
 *
 * ## Theme mode resolution (REQ-THEME-008, REQ-THEME-009)
 *
 * The tri-state [ThemeMode] is resolved to a boolean here — the **single resolution point**.
 * - [ThemeMode.System] reads `isSystemInDarkTheme()` at composition time.
 * - [ThemeMode.Light] → `false`.
 * - [ThemeMode.Dark] → `true`.
 *
 * Every consumer reads [LocalIsDarkTheme] (provided here) rather than calling
 * `isSystemInDarkTheme()` directly. This prevents the calendar bug (ADR 2026-10-05):
 * a subtree that re-derives the flag from the OS silently desynchronises from the palette
 * actually in effect.
 */
@Composable
fun SingularityTheme(
    themeMode: ThemeMode = ThemeMode.System,
    accent: SingularityAccents = SingularityAccents.Blue,
    fontSizeScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // Single resolution point: the tri-state is resolved to a boolean exactly once,
    // here, using the current system appearance only when the mode is System.
    val isDark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val colorScheme = rememberDynamicColorScheme(
        seedColor = accent.color,
        isDark = isDark,
    )

    val typography = remember(fontSizeScale) {
        createTypography(fontSizeScale)
    }

    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalIsDarkTheme provides isDark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
