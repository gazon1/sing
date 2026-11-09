package com.singularity.todo.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme
import com.singularity.todo.core.settings.ThemeMode

/**
 * The eight accent choices.
 *
 * [color] is a **seed**, not a fill: Material derives a tonal palette from it
 * ([accentColorScheme]), and the app paints that palette's primary
 * ([accentPrimaryColor]). Painting `color` directly promises a colour the app
 * never produces — which is exactly what the settings picker used to do for all
 * eight swatches.
 */
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
        fun fromString(name: String): SingularityAccents =
            entries.find {
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
 * Resolves the mode to the dark flag the app paints with, reading the operating
 * system's appearance only when the mode follows it.
 *
 * This is the **single point** where the operating system's appearance is read:
 * every other part of the app derives its answer from the mode. The resolution
 * itself lives on the mode ([ThemeMode.isDark]) — this function only supplies
 * the system flag that the "follow the system" mode needs.
 */
@Composable
fun ThemeMode.resolvedIsDark(): Boolean = isDark(isSystemInDarkTheme())

/**
 * The contrast level every accent palette is built at: the single knob that moves
 * a seed from its own chroma to the tonal palette Material paints with.
 *
 * It is a constant, not a literal at the call site, because two call sites that
 * disagree about it produce two different palettes from one accent — the theme
 * would paint one and the settings picker would promise the other.
 */
const val ACCENT_CONTRAST_LEVEL: Double = 0.5

/**
 * The palette [SingularityTheme] paints from [accent] — the **only** place a seed
 * becomes a colour scheme.
 *
 * Material does not paint the seed: it derives a tonal palette from it, so a
 * saturated seed arrives desaturated (yellow becomes olive, blue becomes slate).
 * Everything that shows the user what their accent *does* must therefore read the
 * palette, not the seed — see [accentPrimaryColor].
 */
fun accentColorScheme(
    accent: SingularityAccents,
    isDark: Boolean,
): ColorScheme =
    dynamicColorScheme(
        seedColor = accent.color,
        isDark = isDark,
        contrastLevel = ACCENT_CONTRAST_LEVEL,
    )

/**
 * The colour the app actually paints for [accent]: the palette's primary.
 *
 * This is what a picker's swatch shows. Painting the seed instead promises a
 * colour the user never sees — the eight swatches of Settings → Interface used
 * to do exactly that, and every one of them was wrong (yellow offered a yellow
 * the app never paints).
 */
fun accentPrimaryColor(
    accent: SingularityAccents,
    isDark: Boolean,
): Color = accentColorScheme(accent, isDark).primary

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
    val isDark = themeMode.resolvedIsDark()

    val colorScheme =
        remember(accent, isDark) {
            accentColorScheme(accent = accent, isDark = isDark)
        }

    val typography =
        remember(fontSizeScale) {
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
