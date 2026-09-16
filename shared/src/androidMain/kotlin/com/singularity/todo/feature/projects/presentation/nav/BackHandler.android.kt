package com.singularity.todo.feature.projects.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun ProjectsBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
