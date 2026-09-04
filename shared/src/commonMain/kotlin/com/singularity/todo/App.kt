package com.singularity.todo

import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.nav.HomeTab

@Composable
fun App() {
    SingularityTheme {
        HomeTab()
    }
}
