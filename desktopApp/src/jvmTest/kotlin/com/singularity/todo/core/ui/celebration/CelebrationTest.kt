@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.celebration

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * Tests for [Celebration] composable.
 *
 * Verifies:
 * - Content is always visible regardless of [Celebration.triggerKey]
 * - Haptic is fired only when [Celebration.hapticsEnabled]=true and [Celebration.haptic] is provided
 * - Empty [Celebration.triggerKey] renders content without effects
 * - [Celebration.haptic] = null with [Celebration.hapticsEnabled]=true does not throw
 */
@Tag("fast")
class CelebrationTest {

    private class RecordingHaptic : Haptic {
        var callCount = 0
        override suspend fun perform() { callCount++ }
    }

    // ── Content visibility ─────────────────────────────────────────────────────

    @Test
    fun `content is always visible regardless of triggerKey`() = runIsolatedComposeTest {
        setContent {
            Celebration(triggerKey = "") {
                Text("Hidden content")
            }
        }
        onNodeWithText("Hidden content").assertIsDisplayed()
    }

    @Test
    fun `content is displayed when triggerKey is non-blank`() = runIsolatedComposeTest {
        setContent {
            Celebration(triggerKey = "task-1") {
                Text("Complete me")
            }
        }
        onNodeWithText("Complete me").assertIsDisplayed()
    }

    @Test
    fun `blank triggerKey renders content without any effects`() = runIsolatedComposeTest {
        setContent {
            Celebration(triggerKey = "") {
                Text("Just text")
            }
        }
        onNodeWithText("Just text").assertIsDisplayed()
    }

    // ── Haptic gating ────────────────────────────────────────────────────────

    @Test
    fun `haptic is fired when hapticsEnabled is true and haptic is provided`() = runTest {
        val haptic = RecordingHaptic()
        runDesktopComposeUiTest {
            setContent {
                Celebration(
                    triggerKey = "task-1",
                    animationsEnabled = false,
                    hapticsEnabled = true,
                    haptic = haptic,
                ) {
                    Text("Do it")
                }
            }
        }
        // After runDesktopComposeUiTest returns, advance test dispatcher so
        // LaunchedEffect coroutines execute, then verify the side-effect.
        advanceUntilIdle()
        assert(haptic.callCount == 1) {
            "Expected haptic.perform() to be called once, was called ${haptic.callCount} times"
        }
    }

    @Test
    fun `haptic is NOT fired when hapticsEnabled is false`() = runTest {
        val haptic = RecordingHaptic()
        runDesktopComposeUiTest {
            setContent {
                Celebration(
                    triggerKey = "task-1",
                    hapticsEnabled = false,
                    haptic = haptic,
                ) {
                    Text("Do it")
                }
            }
        }
        advanceUntilIdle()
        assert(haptic.callCount == 0) {
            "Expected haptic.perform() NOT to be called when hapticsEnabled=false, was called ${haptic.callCount} times"
        }
    }

    @Test
    fun `haptic is null with hapticsEnabled true does not throw`() = runTest {
        runDesktopComposeUiTest {
            setContent {
                Celebration(
                    triggerKey = "task-1",
                    hapticsEnabled = true,
                    haptic = null,
                ) {
                    Text("Do it")
                }
            }
        }
        advanceUntilIdle()
        // null haptic is silently skipped — no exception thrown
    }

    // ── Animation gating ──────────────────────────────────────────────────────

    @Test
    fun `content is displayed when animationsEnabled is false`() = runIsolatedComposeTest {
        setContent {
            Celebration(
                triggerKey = "task-1",
                animationsEnabled = false,
            ) {
                Text("Check me")
            }
        }
        onNodeWithText("Check me").assertIsDisplayed()
    }

    @Test
    fun `content is displayed when animationsEnabled is true`() = runIsolatedComposeTest {
        setContent {
            Celebration(
                triggerKey = "task-1",
                animationsEnabled = true,
            ) {
                Text("Check me too")
            }
        }
        onNodeWithText("Check me too").assertIsDisplayed()
    }
}
