package com.singularity.todo.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [animationsEnabled] and [LocalAnimationDurationScale].
 *
 * The local exists so a test can pin `0f` without asking the platform (the platform
 * reads live, and no test may depend on the host's accessibility settings). These
 * tests pin both halves of that: the predicate, and the fact that a pinned value is
 * what a reader sees — and that changing it is seen, because a local whose reader is
 * not invalidated is the bug this file exists to keep out.
 */
@Tag("slow")
@Tag("slow:compose")
class AnimationScaleTest {
    @Test
    fun `a zero scale means animations are off`() {
        assertFalse(animationsEnabled(0f))
    }

    @Test
    fun `a slowed scale is not off`() {
        assertTrue(animationsEnabled(0.5f))
    }

    @Test
    fun `a normal scale is on`() {
        assertTrue(animationsEnabled(1f))
    }

    @Test
    fun `a pinned scale is what the reader sees and a change to it is followed`() =
        runIsolatedComposeTest {
            var pinned by mutableStateOf(0f)

            setContent {
                CompositionLocalProvider(LocalAnimationDurationScale provides pinned) {
                    Text(if (animationsEnabled(animationDurationScale())) "animations on" else "animations off")
                }
            }

            assertTextDisplayed("animations off")

            pinned = 1f

            assertTextDisplayed("animations on")
        }
}
