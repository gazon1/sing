package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Holder for the current screen's TopAppBar actions on platforms where the
 * shell owns the only bar (Android). Screens publish their actions through the
 * platform-aware [TopAppBar]/[BackTopAppBar] wrappers; the Android shell reads
 * [actions] and renders them in its own bar.
 */
class ScreenActionsHolder {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)

    // Selection-mode overrides for the Android shell bar (title/back/colors) so
    // the shell can render one bar instead of a screen-level duplicate.
    var title: (@Composable () -> Unit)? by mutableStateOf(null)
    var navigationIcon: (@Composable () -> Unit)? by mutableStateOf(null)
    var colors: androidx.compose.material3.TopAppBarColors? by mutableStateOf(null)
    var override: Boolean by mutableStateOf(false)
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
