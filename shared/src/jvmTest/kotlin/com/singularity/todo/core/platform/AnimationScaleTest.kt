package com.singularity.todo.core.platform

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
 */
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
        assertEquals(1f, systemAnimationDurationScale())
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
