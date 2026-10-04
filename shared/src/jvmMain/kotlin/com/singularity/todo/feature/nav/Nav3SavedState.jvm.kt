package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration

@Composable
actual fun <T : NavKey> rememberNavBackStackTyped(
    @Suppress("UNUSED_PARAMETER") savedStateConfig: SavedStateConfiguration,
    start: T,
): NavBackStack<T> = rememberInMemoryNavBackStack(start)
