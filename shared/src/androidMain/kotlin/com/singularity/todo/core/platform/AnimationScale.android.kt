package com.singularity.todo.core.platform

import android.animation.ValueAnimator

/**
 * Android actual of [systemAnimationDurationScale].
 *
 * `ValueAnimator.areAnimatorsEnabled()` is the platform's own answer, and it is
 * the same answer Compose gives its animations: it is false when the system
 * animator scale is zero, which is what "Remove animations" writes.
 *
 * The setting can also hold a fraction (half speed), which still means
 * animations are on — so this reports `1f` for every enabled scale and `0f`
 * only for the one that turns them off.
 */
actual fun systemAnimationDurationScale(): Float =
    if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
