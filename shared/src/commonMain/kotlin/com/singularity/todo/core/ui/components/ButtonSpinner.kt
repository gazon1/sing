package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small in-button spinner used by LoginScreen / BackupScreen.
 *
 * Kept separate from [LoadingIndicator] (which fills the whole screen) because
 * the latter requires being placed inside a `Box(fillMaxSize)` to centre itself
 * — inappropriate for an inline button indicator.
 */
@Composable
fun ButtonSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(24.dp),
        strokeWidth = 2.dp,
    )
}
