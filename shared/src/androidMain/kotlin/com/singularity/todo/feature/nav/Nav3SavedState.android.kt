package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration

@Composable
actual fun <T : NavKey> rememberNavBackStackTyped(
    savedStateConfig: SavedStateConfiguration,
    start: T,
): NavBackStack<T> {
    // The single unchecked cast for all Android nested graphs — SavedStateConfiguration
    // cannot parameterise NavBackStack, so rememberNavBackStack erases to NavKey.
    @Suppress("UNCHECKED_CAST")
    return rememberNavBackStack(savedStateConfig, start) as NavBackStack<T>
}
