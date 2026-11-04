package com.singularity.todo.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's spacing scale — the one set of steps every screen lays out on.
 *
 * There used to be two, both in the task feature: `TaskSpacing` (8 / 12 / 16 / 24)
 * and `TaskListSpacing` (2 / 4 / 8 / 12 / 16 / 20 / 24). The same name therefore
 * meant two different gaps depending on which file a composable lived in — `xl`
 * was 24 dp in the editor and 20 dp in the list — and the 20 dp step had no
 * reader at all. A name that does not answer one question is not a token.
 *
 * The steps are the 4 dp grid the code already used (91 % of the app's `.dp`
 * literals sit on it), plus [xxs] = 2 dp for the hairline between a control and
 * its label. A gap that is not a step — 14 dp, 6 dp — is written by composing
 * two steps (`md + xs`, `xs + xxs`) rather than by minting a third name for it,
 * so the scale stays a scale and the layout keeps the pixels it had.
 *
 * Read it through [MaterialTheme.spacing], which [SingularityTheme] provides.
 */
@Immutable
data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

/** The app's spacing scale. A screen reads it from the theme; it never builds its own. */
val AppSpacing: Spacing = Spacing()

/**
 * The spacing scale in scope.
 *
 * It has a default rather than an error, unlike the calendar palette's local: a
 * `@Preview` that mounts a card without the theme still has to lay out, and every
 * value here is a constant — there is no per-theme variation that a missing
 * provider could silently substitute.
 */
val LocalSpacing = staticCompositionLocalOf { AppSpacing }

/** The spacing scale of the theme in scope. */
val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
