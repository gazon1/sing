package com.singularity.todo.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.singularity.todo.core.platform.rememberSystemAnimationDurationScale

/**
 * The animation scale this composition should honour, or `null` when nothing has
 * pinned one and the platform is to be asked — see [animationDurationScale].
 *
 * ## Why the default does not read the platform
 *
 * A composition local's **default** is evaluated once per composition and cached, so a
 * default that reads the platform freezes the platform's answer at startup. The
 * platform's answer is not constant: Android's "Remove animations" is one tap away and
 * does not restart the process.
 *
 * So the platform is not read by the default at all. [animationDurationScale] reads it,
 * and does so *again* on the next read rather than replaying a cached answer, while a
 * composition that overrides this local — a test pinning `0f`, a preview — wins and the
 * platform is not consulted.
 *
 * ## Why the local is dynamic
 *
 * The local is a [compositionLocalOf], not a `staticCompositionLocalOf`, and the
 * difference is granularity. A static local does invalidate when the provided value
 * changes; what it does is invalidate *every* composable under the provider rather than
 * only the ones that read it, because it keeps no per-reader tracking. This local is
 * provided at the shell, above the whole UI, so a static local would re-compose the app
 * on every change to a value that a handful of spinners care about. A dynamic local
 * invalidates the readers and nothing else.
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
