package com.singularity.todo.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * JVM actual of [systemAnimationDurationScale].
 *
 * ## What this platform can answer
 *
 * Compose Desktop installs no `MotionDurationScale` element, and neither the JVM nor
 * Skiko exposes macOS "Reduce motion", GNOME's `enable-animations` or Windows'
 * "Animation effects" portably — the only ways to reach them are a shell-out to
 * `defaults`/`gsettings` or a platform-specific native call, per platform, per read.
 *
 * So the OS reduce-motion switch is not read here. The answer is `1f` — animations on
 * — unless the process is configured otherwise, below. #2486 tracks reading the
 * desktop's own switch.
 *
 * ## The knob that does exist
 *
 * The answer can be forced off for a whole process, which is also how a repackaging
 * can honour whichever switch its platform really has:
 *
 * ```
 * -Dsingularity.todo.animations=off     # or off/false/no/none/0
 * SINGULARITY_TODO_ANIMATIONS=off       # same values, as an environment variable
 * ```
 */
actual fun systemAnimationDurationScale(): Float =
    animationDurationScaleForConfiguration(
        property = System.getProperty(ANIMATIONS_PROPERTY),
        environment = System.getenv(ANIMATIONS_ENVIRONMENT),
    )

/**
 * The scale a desktop configuration means: `0f` when it says animations off, `1f`
 * otherwise.
 *
 * Split out from [systemAnimationDurationScale] so the mapping is testable. The actual
 * reads the process's ambient property and environment, which a test cannot set to a
 * known value — `SINGULARITY_TODO_ANIMATIONS` cannot be unset from inside a running JVM,
 * so a test that called the actual would fail on whichever developer or CI machine has
 * the knob off, while the code being tested was fine. Pass the arguments instead.
 */
internal fun animationDurationScaleForConfiguration(
    property: String?,
    environment: String?,
): Float = if (animationsDisabledByConfiguration(property, environment)) 0f else 1f

/**
 * JVM actual of [rememberSystemAnimationDurationScale].
 *
 * The desktop answer comes from the process's own configuration, which cannot change
 * while the process runs, so a single read is the whole story — unlike Android, whose
 * setting is one tap away and is observed while the app runs.
 */
@Composable
actual fun rememberSystemAnimationDurationScale(): Float = remember { systemAnimationDurationScale() }

/** Whether the process was configured with animations off. See [systemAnimationDurationScale]. */
internal fun animationsDisabledByConfiguration(
    property: String? = System.getProperty(ANIMATIONS_PROPERTY),
    environment: String? = System.getenv(ANIMATIONS_ENVIRONMENT),
): Boolean = isAnimationOff(property) || isAnimationOff(environment)

/**
 * Whether a configured value means "no animations".
 *
 * Several spellings because this is typed by a person in a launch script: `off`,
 * `false`, `no`, `none` and `0` all say the same thing, and anything else — including
 * an absent value — leaves animations on.
 */
internal fun isAnimationOff(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized in OFF_VALUES
}

private const val ANIMATIONS_PROPERTY = "singularity.todo.animations"
private const val ANIMATIONS_ENVIRONMENT = "SINGULARITY_TODO_ANIMATIONS"
private val OFF_VALUES = setOf("off", "false", "no", "none", "0")
