package com.singularity.todo.core.platform

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The platform half of the animation scale.
 *
 * `:shared:jvmTest` can reach the JVM actual and the mapping both actuals use; the
 * Android actual's own platform call (`ValueAnimator.areAnimatorsEnabled()`) needs a
 * device or Robolectric, so it is kept to one call into [animationDurationScaleFor],
 * which is what this file pins.
 *
 * Nothing here reads the process's own configuration. `SINGULARITY_TODO_ANIMATIONS=off`
 * cannot be unset from inside the JVM, so a test that called [systemAnimationDurationScale]
 * would assert a value it does not control: it would pass on a developer's machine and
 * fail in the run that turned the knob off to prove the knob works. The mapping the
 * actual performs is [animationDurationScaleForConfiguration], and that is what is
 * pinned below.
 */
@Tag("fast")
class AnimationScaleTest {
    @Test
    fun `enabled animators are normal speed, not some fraction of it`() {
        assertEquals(1f, animationDurationScaleFor(animatorsEnabled = true))
    }

    @Test
    fun `disabled animators are zero, not a slow animation`() {
        assertEquals(0f, animationDurationScaleFor(animatorsEnabled = false))
    }

    @Test
    fun `the desktop reads animations as on when it is not configured otherwise`() {
        assertEquals(
            1f,
            animationDurationScaleForConfiguration(property = null, environment = null),
        )
    }

    @Test
    fun `the desktop reads animations as off when the configuration says so`() {
        assertEquals(
            0f,
            animationDurationScaleForConfiguration(property = "off", environment = null),
        )
        assertEquals(
            0f,
            animationDurationScaleForConfiguration(property = null, environment = "off"),
        )
    }

    @Test
    fun `every spelling of off turns the desktop animations off`() {
        listOf("off", "OFF", "false", "no", "none", "0", " off ").forEach { value ->
            assertTrue(isAnimationOff(value), "'$value' should mean animations off")
        }
    }

    @Test
    fun `anything else leaves the desktop animations on`() {
        listOf(null, "", "on", "true", "1", "yes").forEach { value ->
            assertFalse(isAnimationOff(value), "'$value' should leave animations on")
        }
    }

    @Test
    fun `either the property or the environment variable disables animations`() {
        assertTrue(animationsDisabledByConfiguration(property = "off", environment = null))
        assertTrue(animationsDisabledByConfiguration(property = null, environment = "off"))
        assertFalse(animationsDisabledByConfiguration(property = null, environment = null))
        assertFalse(animationsDisabledByConfiguration(property = "on", environment = "on"))
    }
}
