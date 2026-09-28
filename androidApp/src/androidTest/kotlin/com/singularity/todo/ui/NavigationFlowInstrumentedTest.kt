package com.singularity.todo.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.MainActivity
import org.hamcrest.Matchers.endsWith
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the navigation flow.
 *
 * Despite the class name, this does NOT exercise real navigation — it is
 * a placeholder verifying that [MainActivity] stays alive long enough for
 * Compose navigation to initialise.
 *
 * NOTE: [MainActivity] currently ANRs on emulator startup — see
 * docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NavigationFlowInstrumentedTest {

    @Test
    fun app_stays_alive_for_5_seconds() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        @Suppress("DEPRECATION")
        Thread.sleep(5_000)
        scenario.onActivity { assertNotNull("Activity still alive", it) }
        // Also verify ComposeView is displayed.
        onView(withClassName(endsWith("ComposeView")))
            .check(matches(isDisplayed()))
        scenario.close()
    }
}
