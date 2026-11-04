package com.singularity.todo.core.platform

import androidx.compose.runtime.Composable

/**
 * The scale of the animations the operating system runs, as a factor of their
 * normal duration: `1f` is normal speed, `0f` means the user has turned
 * animations off.
 *
 * ## What this is for
 *
 * A UI that animates forever — a spinner that rotates while a sync runs — is
 * exactly the thing a user who has turned animations off does not want to see
 * moving. The scale is the platform's own answer to "are animations on?", and
 * this function is how the shared code asks.
 *
 * ## Why a platform fact and not a composition local
 *
 * Compose carries the platform's answer in `MotionDurationScale`, a
 * `CoroutineContext.Element` that the Android runtime installs in the
 * composition's context — there is no `LocalMotionDurationScale` to read, and
 * the coroutine context is not something a composable can ask for the scale
 * from. Reading the platform's own setting is therefore the way to get the
 * same answer.
 *
 * ## One read is not enough
 *
 * This is a snapshot. The setting can change while the app runs — "Remove
 * animations" in the accessibility settings is one tap away — so a composable
 * must call [rememberSystemAnimationDurationScale] rather than this function:
 * the answer captured once at startup is the answer the user then overrides.
 *
 * ## What the platforms answer
 *
 * Android reads the system animator setting, which is what "Remove animations"
 * in the developer options and the accessibility animation settings write. The
 * desktop has no such setting that can be read portably, so it answers `1f` —
 * see the JVM actual and #2175.
 */
expect fun systemAnimationDurationScale(): Float

/**
 * [systemAnimationDurationScale], kept current for the lifetime of the
 * composition.
 *
 * A screen that animates asks this, not the snapshot: a platform whose setting
 * can change while the process runs re-reads it whenever it does, and the
 * composition that already read it is invalidated rather than left holding the
 * value it saw at startup.
 */
@Composable
expect fun rememberSystemAnimationDurationScale(): Float

/**
 * The scale that corresponds to a platform's "are animators enabled?" answer.
 *
 * Shared by the actuals that ask a yes/no question rather than a fraction: a
 * platform that only reports enabled/disabled still has to answer in the same
 * units as [systemAnimationDurationScale], and `enabled` is normal speed, not
 * some fraction of it.
 */
internal fun animationDurationScaleFor(animatorsEnabled: Boolean): Float = if (animatorsEnabled) 1f else 0f
