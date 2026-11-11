package com.singularity.todo.test.stubs

import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.core.ui.onboarding.SpotlightContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertTrue

// No `fast`/`slow` tag: this is a JUnit4 class under the Vintage engine, and
// Jupiter's `@Tag` does not reach it — a tag here would read as "this can be
// selected" and select nothing.

/**
 * The E2E build starts after the spotlight tour.
 *
 * The tour's scrim fills the screen, swallows every tap and prunes the agenda out of
 * the accessibility tree, so an E2E build that starts as a user who has never seen it
 * cannot see the row it just seeded. That is what made 41 flows fail on one cause
 * (issue #2819); the fix is the state of the test environment, not the product.
 *
 * The module lives in `androidMain`, so the test lives here: the JVM lane cannot
 * reach it.
 *
 * The composition itself — that the application loads this module under
 * `TEST_AUTH_BYPASS` — is guarded by `E2eOnboardingWiringTest` in the JVM lane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestOnboardingModuleTest {
    @Test
    fun `the E2E build reports the spotlight tour as seen`() =
        runTest {
            val koin = koinApplication { modules(testOnboardingModule()) }.koin
            val store = koin.get<OnboardingSettingsRepository>()

            assertTrue(
                store.seenSpotlightVersion.first() >= SpotlightContent.VERSION,
                "the E2E build must start as a user who has seen the tour, or the " +
                    "tour's scrim covers the agenda every flow is about",
            )
        }

    @Test
    fun `the tour does not come back when the E2E build is asked to show it again`() =
        runTest {
            val koin = koinApplication { modules(testOnboardingModule()) }.koin
            val store = koin.get<OnboardingSettingsRepository>()

            val replays = mutableListOf<Unit>()
            val collector = launch { store.spotlightReplayRequests.collect { replays += it } }
            runCurrent() // the collector is subscribed before the reset

            store.resetSpotlight()
            runCurrent()

            assertTrue(
                replays.isEmpty(),
                "the E2E build must not replay the tour: the scrim would cover the " +
                    "screen every flow is about, and prune the agenda out of the " +
                    "accessibility tree",
            )
            assertTrue(
                store.seenSpotlightVersion.first() >= SpotlightContent.VERSION,
                "the reset must not clear the E2E build's record either — a cleared " +
                    "record makes the tour eligible again on the next composition",
            )
            collector.cancel()
        }
}
