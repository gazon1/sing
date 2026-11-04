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
 * was 24 dp in the editor and 20 dp in the list.
 *
 * [xl] keeps the editor's 24 dp. The list's 20 dp is not a step here, and which
 * 20 dp this drops is a question that was answered by counting, not by reading:
 * `git grep 'TaskListSpacing\.Xl' origin/main -- '*.kt'` returns **no hits**. The
 * whole read tally of that object was `Xs 9, Md 4, Xxs 3, Lg 3, Xxl 1` — every
 * member the list actually used, with `Xl` absent. So dropping the step broke no
 * reader. (The list path lays out on [lg] and [lg] + [xs] now; neither of those
 * was `Xl` before, and neither is 20 dp.)
 *
 * The one place in the app that wants a 20 dp inset is the onboarding spotlight
 * card, and that was a bare literal, not a token — this change writes it as
 * [lg] + [xs] rather than promoting it to a name. A name no call site reads is
 * not a token.
 *
 * The steps are the 4 dp grid the code already used (91 % of the app's `.dp`
 * literals sit on it), plus [xxs] = 2 dp for the hairline between a control and
 * its label. A gap that is not a step — 14 dp, 6 dp, 20 dp — is written by
 * composing two steps (`md + xs`, `xs + xxs`, `lg + xs`) rather than by minting
 * a third name for it, so the scale stays a scale and the layout keeps the
 * pixels it had.
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
