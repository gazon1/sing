package com.singularity.todo.core.ui.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.singularity.todo.core.settings.ThemeMode
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.core.ui.theme.SingularityTheme

// ===== Preview theme wrapper =====

/**
 * Wraps a preview content in [SingularityTheme] with optional dark/light mode
 * and accent color override.
 *
 * @param darkTheme  If true, uses the dark color scheme; otherwise light.
 * @param accent     Accent color used by [SingularityAccents].
 * @param useSurface If true, wraps content in a [Surface] with the background color.
 *                   Pass false when the preview root already contains a Scaffold
 *                   (which provides its own background via MaterialTheme.colorScheme.background).
 */
@Composable
internal fun PreviewThemed(
    darkTheme: Boolean = false,
    accent: SingularityAccents = SingularityAccents.Blue,
    useSurface: Boolean = true,
    content: @Composable () -> Unit,
) {
    SingularityTheme(themeMode = if (darkTheme) ThemeMode.Dark else ThemeMode.Light, accent = accent) {
        if (useSurface) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        } else {
            content()
        }
    }
}

/** No-op click handler for @Preview composables. Use instead of `onClick = {}`. */
internal val noopClick: () -> Unit = {}
