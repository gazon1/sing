package com.singularity.todo.feature.sync.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.sync.SyncEngineStatus

/**
 * Animated sync button that reflects [SyncEngineStatus].
 *
 * - **Idle**: static cloud-done icon
 * - **Pushing/Pulling**: rotating refresh icon (continuous spin animation)
 * - **NoConnection**: cloud-off icon (no animation)
 * - **Failure**: sync-problem icon (no animation, error tint)
 *
 * The entire button is wrapped in [key][androidx.compose.runtime.key] so that a
 * recomposition with a different [SyncEngineStatus] does NOT restart a running
 * rotation animation.
 *
 * Tapping triggers [SyncIntent.SyncNow].
 */
@Composable
fun SyncButton(status: SyncEngineStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isRunning = status.isRunning()

    // Wrapping in key(status) means: when status changes, the entire block is
    // disposed and recreated (fresh animation). When status is the same across
    // recompositions, the block is skipped and the animation continues.
    key(status) {
        val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotation",
        )

        val icon = when (status) {
            is SyncEngineStatus.Idle -> Icons.Default.CloudDone

            is SyncEngineStatus.Pushing,
            is SyncEngineStatus.Pulling,
            -> Icons.Default.Refresh

            is SyncEngineStatus.NoConnection -> Icons.Default.CloudOff

            is SyncEngineStatus.Failure -> Icons.Default.SyncProblem
        }

        val tint = when (status) {
            is SyncEngineStatus.Failure -> MaterialTheme.colorScheme.error
            is SyncEngineStatus.NoConnection -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.onSurface
        }

        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = !isRunning,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = when (status) {
                    is SyncEngineStatus.Idle -> "Synced"
                    is SyncEngineStatus.Pushing -> "Syncing…"
                    is SyncEngineStatus.Pulling -> "Syncing…"
                    is SyncEngineStatus.NoConnection -> "Offline"
                    is SyncEngineStatus.Failure -> "Sync error"
                },
                tint = tint,
                modifier = Modifier
                    .size(24.dp)
                    .then(
                        if (isRunning) Modifier.rotate(rotation) else Modifier,
                    ),
            )
        }
    }
}
