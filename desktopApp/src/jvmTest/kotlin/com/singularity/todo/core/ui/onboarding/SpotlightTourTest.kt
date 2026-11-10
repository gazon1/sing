@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.countNodesWithTagInAnyRoot
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The two ways the tour is supposed to end and to come back.
 *
 * Both defects these tests pin are wiring defects — code that existed but nothing called —
 * so the assertions are on the store's record and on the overlay being on screen, not on
 * the state machine, which already has its own tests.
 */
@Tag("fast")
class SpotlightTourTest {
    @Test
    fun `reaching the last step with Got it records the tour as seen`() =
        runIsolatedComposeTest {
            val store = RecordingOnboardingStore()

            setContent { SpotlightTourHost(store) }

            assertTagDisplayed(TestTags.Onboarding.SPOTLIGHT_NEXT)
            clickTag(TestTags.Onboarding.SPOTLIGHT_NEXT)
            clickTag(TestTags.Onboarding.SPOTLIGHT_NEXT)

            waitUntil(timeoutMillis = 5_000) {
                store.seen.value == SpotlightContent.VERSION
            }
            assertEquals(
                SpotlightContent.VERSION,
                store.seen.value,
                "completing the tour must record it, or it returns on every launch",
            )
        }

    @Test
    fun `a tour the user has already finished stays closed`() =
        runIsolatedComposeTest {
            val store = RecordingOnboardingStore(seenAtStart = SpotlightContent.VERSION)

            setContent { SpotlightTourHost(store) }

            waitForIdle()
            // A version the user has finished must not show itself again — the relaunch
            // check, without a relaunch.
            assertEquals(0, countNodesWithTagInAnyRoot(TestTags.Onboarding.SPOTLIGHT_CARD))
        }

    @Test
    fun `showing the tips again starts the tour on the same screen`() =
        runIsolatedComposeTest {
            val store = RecordingOnboardingStore(seenAtStart = SpotlightContent.VERSION)

            setContent { SpotlightTourHost(store) }
            waitForIdle()
            assertTagNotExists(TestTags.Onboarding.SPOTLIGHT_CARD)

            // What the settings row does.
            store.resetSpotlight()

            assertTagDisplayed(TestTags.Onboarding.SPOTLIGHT_CARD)
        }
}

/**
 * A store whose record the test can read without collecting.
 *
 * The production fake exposes the version only as a `Flow`, which is the right shape for
 * the app and the wrong one for a `waitUntil` predicate.
 */
private class RecordingOnboardingStore(seenAtStart: Int = 0) : OnboardingSettingsRepository {
    val seen = MutableStateFlow(seenAtStart)

    private val replayRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val seenSpotlightVersion: Flow<Int> = seen

    override val spotlightReplayRequests: Flow<Unit> = replayRequests.asSharedFlow()

    override suspend fun markSpotlightSeen(version: Int) {
        if (version > seen.value) seen.value = version
    }

    override suspend fun resetSpotlight() {
        seen.value = 0
        replayRequests.tryEmit(Unit)
    }
}

/**
 * The agenda's half of the tour, reduced to what the tour needs: two laid-out targets and
 * the real overlay driven by the controller's own actions.
 */
@Composable
private fun SpotlightTourHost(store: OnboardingSettingsRepository) {
    val tour = rememberSpotlightTour(settingsStore = store)
    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .spotlightAnchor(SpotlightAnchorId.SavedViews, tour.registry),
            )
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .spotlightAnchor(SpotlightAnchorId.SaveCurrent, tour.registry),
            )
        }

        val stepIndex = tour.state.stepIndex
        if (stepIndex != null) {
            SpotlightOverlay(
                state = tour.state,
                step = SpotlightContent.STEPS[stepIndex],
                stepPosition = stepIndex,
                stepCount = SpotlightContent.STEPS.size,
                onNext = tour::next,
                onSkip = tour::skip,
            )
        }
    }
}
