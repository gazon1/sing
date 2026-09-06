package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ButtonSpinnerLightPreview() = PreviewThemed(darkTheme = false) {
    Row {
        ButtonSpinner()
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = {}) { Text("Continue") }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ButtonSpinnerDarkPreview() = PreviewThemed(darkTheme = true) {
    Row {
        ButtonSpinner()
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = {}) { Text("Continue") }
    }
}
