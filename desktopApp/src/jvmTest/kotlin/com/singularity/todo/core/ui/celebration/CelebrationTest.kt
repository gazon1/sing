@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.celebration

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.singularity.todo.core.platform.haptics.Haptic
import com.singularity.todo.core.ui.LocalHaptic
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
 * - Haptic is fired only when [Celebration.hapticsEnabled]=true
 * - Empty [Celebration.triggerKey] renders content without effects
 * - The default capability comes from [com.singularity.todo.core.ui.LocalHaptic], and an
 *   explicit [Celebration.haptic] still overrides it
 *
 * The `haptic = null` case this file used to cover is gone: the parameter is no longer
 * nullable. "No vibrator here" is now a no-op *implementation* behind the default rather than
 * a missing value, so the test that matters is that the default is the no-op and that nothing
 * throws when it pulses.
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
    fun `the default capability is the no-op, so a celebration without a provider is safe`() =
        runTest {
            // No CompositionLocalProvider anywhere: this is the preview and desktop shape, and
            // it is the case that used to force a `Haptic?` parameter.
            runDesktopComposeUiTest {
                setContent {
                    Celebration(
                        triggerKey = "task-1",
                        hapticsEnabled = true,
                    ) {
                        Text("Do it")
                    }
                }
            }
            advanceUntilIdle()
            // The no-op default pulsed and returned. Nothing threw.
        }

    @Test
    fun `a capability provided by the composition is used when no explicit one is passed`() = runTest {
        val haptic = RecordingHaptic()
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalHaptic provides haptic) {
                    Celebration(
                        triggerKey = "task-1",
                        animationsEnabled = false,
                        hapticsEnabled = true,
                    ) {
                        Text("Do it")
                    }
                }
            }
        }
        advanceUntilIdle()
        assert(haptic.callCount == 1) {
            "Expected the composition-provided capability to be pulsed once, " +
                "was ${haptic.callCount}"
        }
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
