package com.singularity.todo.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.singularity.todo.core.platform.rememberSystemAnimationDurationScale

/**
 * An animation scale pinned by this composition, or `null` when none has been pinned
 * and the platform should be asked — see [animationDurationScale].
 *
 * ## Why this is not `staticCompositionLocalOf { systemAnimationDurationScale() }`
 *
 * A composition local's **default** is evaluated once per composition and cached, so a
 * default that reads the platform freezes the platform's answer at startup. That is a
 * real bug, not a theory: launched with animations on, the app kept a rotating sync
 * spinner after the user turned "Remove animations" on; launched with animations off,
 * the spinner never rotated again until the process restarted.
 *
 * So the platform is not read by the default at all. [animationDurationScale] reads it,
 * and does so *again* on the next read rather than replaying a cached answer, while a
 * composition that overrides this local — a test pinning `0f`, a preview — wins and the
 * platform is not consulted.
 *
 * The local is a dynamic [compositionLocalOf], not a static one: a reader must be
 * invalidated when the provided value changes, and static locals deliberately skip
 * that.
 */
val LocalAnimationDurationScale = compositionLocalOf<Float?> { null }

/**
 * The animation scale the current composition should honour.
 *
 * The overriding provider's value when there is one, the platform's live answer
 * otherwise. `1f` is normal speed, `0f` means the user has turned animations off; see
 * [animationsEnabled].
 */
@Composable
fun animationDurationScale(): Float = LocalAnimationDurationScale.current ?: rememberSystemAnimationDurationScale()

/**
 * Whether an animation may play at [durationScale].
 *
 * A scale of zero means the user has turned animations off, and an animation that plays
 * anyway is an animation the user did not ask for. A scale of half is not off: the
 * animation is slowed, not forbidden.
 */
fun animationsEnabled(durationScale: Float): Boolean = durationScale > 0f
