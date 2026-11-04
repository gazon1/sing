package com.singularity.todo.core.platform

/**
 * JVM actual of [systemAnimationDurationScale].
 *
 * The desktop has no system-wide animation scale that can be read portably —
 * macOS keeps "Reduce motion" in the accessibility API, and Linux and Windows
 * have nothing that corresponds — so the answer is `1f`: animations are on,
 * and the desktop's own accessibility switch governs what it governs.
 */
actual fun systemAnimationDurationScale(): Float = 1f
