package com.singularity.todo.core.ui.celebration

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.ui.LocalHaptic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A celebration overlay that triggers a brief glow + scale pulse when [triggerKey] changes.
 *
 * This is a **stateless decoration wrapper**: it observes [triggerKey] changes and fires
 * the animation independently, without modifying any state. The wrapped [content] is
 * rendered as-is inside the effect.
 *
 * **Usage**: wrap any interactive element that fires a completing action.
 * ```kotlin
 * Celebration(
 *     triggerKey = if (task.isCompleted) task.id.value else "",
 *     hapticsEnabled = true,
 * ) {
 *     Checkbox(checked = task.isCompleted, onCheckedChange = { toggle(task) })
 * }
 * ```
 *
 * @param triggerKey A value that, when it changes, fires the celebration.
 *        Pass the completed-item identifier (e.g. `task.id.value`) when complete,
 *        or an empty string `""` when not — the empty key never fires.
 * @param animationsEnabled Whether to play the scale + glow animation.
 * @param hapticsEnabled Whether to fire a haptic pulse on celebration.
 * @param haptic The haptic capability to pulse. Defaults to
 *        [com.singularity.todo.core.ui.LocalHaptic], which is a no-op in a preview and on
 *        desktop — so callers do not pass one, and this parameter is only for a test that
 *        needs to observe the pulse. It was `Haptic?` while callers resolved the port through
 *        `koinInject` and had to guard for previews; see `LocalHaptic` for why that went away.
 * @param content The composable to wrap — the checkbox, button, or card being celebrated.
 */
@Composable
fun Celebration(
    triggerKey: String,
    animationsEnabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    haptic: Haptic = LocalHaptic.current,
    content: @Composable () -> Unit,
) {
    if (triggerKey.isBlank()) {
        content()
        return
    }

    var glowAlpha by remember { mutableFloatStateOf(0f) }

    val scale by animateFloatAsState(
        targetValue = if (glowAlpha > 0f) 1.08f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "celebration_scale",
    )

    val scope = rememberCoroutineScope()

    LaunchedEffect(triggerKey) {
        if (triggerKey.isBlank()) return@LaunchedEffect

        // Fire haptic immediately on trigger. The capability is never null: the default is
        // NoOpHaptic, so "no vibrator here" is a no-op implementation, not a missing one.
        if (hapticsEnabled) {
            scope.launch { haptic.perform() }
        }

        // Glow: ramp up and fade out
        glowAlpha = 1f
        delay(80)
        glowAlpha = 0f
    }

    Box {
        content()

        if (glowAlpha > 0f && animationsEnabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .scale(scale)
                    .alpha(glowAlpha * 0.3f)
                    .background(
                        color = Color(0xFF4CAF50), // success green
                        shape = CircleShape,
                    ),
            )
        }
    }
}
