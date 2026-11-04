package com.singularity.todo.core.platform

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
 * ## What the platforms answer
 *
 * Android reads the system animator setting, which is what "Remove animations"
 * in the developer options and the accessibility animation settings write.
 * The desktop has no such setting that can be read portably, so it answers
 * `1f` — animations are on, and the platform's own accessibility switch (macOS
 * "Reduce motion") governs what it governs.
 */
expect fun systemAnimationDurationScale(): Float
