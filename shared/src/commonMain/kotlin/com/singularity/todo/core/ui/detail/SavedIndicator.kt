package com.singularity.todo.core.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Animated "Saved" indicator — fades in/out with a 300ms alpha animation.
 *
 * Replaces the inline pattern:
 * ```kotlin
 * var savedVisible by remember { mutableStateOf(false) }
 * // onSaved: savedVisible = true; LaunchedEffect: savedVisible = false
 * if (savedVisible || savedAlpha > 0f) {
 *     Text("Saved", modifier = Modifier.alpha(savedAlpha), ...)
 * }
 * ```
 *
 * @param visible Whether the indicator should be shown.
 * @param modifier Modifier for the row.
 */
@Composable
fun SavedIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "savedAlpha",
    )

    if (alpha > 0f) {
        Row(
            modifier = modifier
                .padding(horizontal = 8.dp)
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Saved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
