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
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }
