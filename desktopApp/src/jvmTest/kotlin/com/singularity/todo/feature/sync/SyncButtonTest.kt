package com.singularity.todo.feature.sync.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.ui.LocalAnimationDurationScale
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sync spinner stops when the platform's animations are off.
 *
 * Asserted on pixels rather than on a state flag, because the thing the user sees is
 * the drawing: a test that read "animate = false" would still pass if the icon were
 * rotated by something else. Two frames half a second apart are compared — the
 * rotation is one turn per 1500 ms, so half a second is a sixth of a turn, which is
 * plainly visible in a 24 dp icon. The clock is driven by hand so that the comparison
 * does not depend on how long the test took to run.
 */
class SyncButtonTest {
    @Test
    fun `the spinner does not move when animations are off`() =
        runIsolatedComposeTest(width = 200, height = 200) {
            mainClock.autoAdvance = false
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAnimationDurationScale provides 0f) {
                        SyncButton(status = SyncEngineStatus.Pushing, onClick = {})
                    }
                }
            }

            mainClock.advanceTimeBy(32)
            val before = captureToImage().toPixelMap().buffer.copyOf()
            mainClock.advanceTimeBy(500)
            val after = captureToImage().toPixelMap().buffer

            assertTrue(
                before.contentEquals(after),
                "a sync in progress must be drawn still while the platform's animations are off",
            )
        }

    @Test
    fun `the spinner turns while animations are on`() =
        runIsolatedComposeTest(width = 200, height = 200) {
            mainClock.autoAdvance = false
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAnimationDurationScale provides 1f) {
                        SyncButton(status = SyncEngineStatus.Pushing, onClick = {})
                    }
                }
            }

            mainClock.advanceTimeBy(32)
            val before = captureToImage().toPixelMap().buffer.copyOf()
            mainClock.advanceTimeBy(500)
            val after = captureToImage().toPixelMap().buffer

            assertFalse(
                before.contentEquals(after),
                "a sync in progress must turn while animations are on",
            )
        }
}
