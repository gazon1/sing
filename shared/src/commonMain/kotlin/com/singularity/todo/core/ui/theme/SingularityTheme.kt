package com.singularity.todo.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

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

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFCE93D8),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF7B1FA2),
    tertiary = Color(0xFF00796B),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
)

@Composable
fun SingularityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: SingularityAccents = SingularityAccents.Blue,
    fontSizeScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val typography = remember(fontSizeScale) {
        createTypography(fontSizeScale)
    }

    CompositionLocalProvider(LocalAccentColor provides accent) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
