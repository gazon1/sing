package com.singularity.todo.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.singularity.todo.core.platform.systemAnimationDurationScale

/**
 * The scale of the animations the platform runs, as a factor of their normal
 * duration: `1f` is normal speed, `0f` means animations are off.
 *
 * ## Why a composition local
 *
 * The platform's answer is [systemAnimationDurationScale]. The local exists so
 * that the answer can be *overridden* — a test that needs a screen to behave as
 * though animations were off provides `0f` instead of asking the platform.
 *
 * Compose has no `LocalMotionDurationScale` to read: it carries the platform's
 * scale in a `CoroutineContext.Element` that a composable cannot ask for the
 * scale from. This local is the same idea with a name this codebase can use.
 */
val LocalAnimationDurationScale = staticCompositionLocalOf { systemAnimationDurationScale() }

/**
 * Whether an animation may play at [durationScale].
 *
 * A scale of zero means the user has turned animations off, and an animation
 * that plays anyway is an animation the user did not ask for. A scale of half
 * is not off: the animation is slowed, not forbidden.
 */
fun animationsEnabled(durationScale: Float): Boolean = durationScale > 0f
