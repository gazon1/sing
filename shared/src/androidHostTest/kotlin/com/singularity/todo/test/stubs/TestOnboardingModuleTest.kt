package com.singularity.todo.test.stubs

import com.singularity.todo.core.ui.onboarding.OnboardingSettingsRepository
import com.singularity.todo.core.ui.onboarding.SpotlightContent
import kotlinx.coroutines.flow.first
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
 */
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
}
