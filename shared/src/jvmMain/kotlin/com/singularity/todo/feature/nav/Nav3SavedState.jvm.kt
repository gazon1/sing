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

/** Desktop: the nested graph's stack lives in memory — there is no process death here. */
@Composable
actual fun <T : NavKey> rememberAppNavBackStack(start: T): NavBackStack<T> =
    rememberInMemoryNavBackStack(start)
