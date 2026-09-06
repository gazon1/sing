package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Centered spinner used by every screen's loading branch.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoadingIndicatorLightPreview() = PreviewThemed(darkTheme = false) {
    LoadingIndicator()
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LoadingIndicatorDarkPreview() = PreviewThemed(darkTheme = true) {
    LoadingIndicator()
}
