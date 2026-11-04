package com.singularity.todo.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's elevation scale: Material 3's tonal levels, named once.
 *
 * Elevation had no scale at all. Five numbers were written at their call sites,
 * and the two tokens the task feature declared for it — `CardElevation` (1 dp)
 * and `CardElevationHover` (4 dp) — were read by nothing, so the only surviving
 * description of how high a surface sits was the literal beside it.
 *
 * The levels are Material's own (0 / 1 / 3 / 6 / 8 / 12), not invented steps:
 * a raised card, a bar and an overlay land on a level the platform's components
 * already use, which is what lets a screen say "this sits above the page"
 * instead of "3 dp".
 *
 * Read it through [MaterialTheme.elevation], which [SingularityTheme] provides.
 */
@Immutable
data class Elevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp,
)

/** The app's elevation scale. */
val AppElevation: Elevation = Elevation()

/** The elevation scale in scope. Defaulted for the same reason as [LocalSpacing]. */
val LocalElevation = staticCompositionLocalOf { AppElevation }

/** The elevation scale of the theme in scope. */
val MaterialTheme.elevation: Elevation
    @Composable
    @ReadOnlyComposable
    get() = LocalElevation.current
