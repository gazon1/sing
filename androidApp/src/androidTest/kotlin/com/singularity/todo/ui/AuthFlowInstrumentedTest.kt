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
 * Smoke tests for the auth / app-launch flow.
 *
 * Uses Espresso [onView] + [withClassName] to detect a Compose view (ComposeView)
 * in the view hierarchy. This verifies that [MainActivity] successfully inflates
 * the Compose runtime and attaches it to the window.
 *
 * NOTE: [MainActivity] currently ANRs on emulator startup — see
 * docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md.
 * All tests will fail on emulator until the Koin/Startup ANR is resolved.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthFlowInstrumentedTest {

    /**
     * Smoke: [MainActivity] launches and [MainActivity.onCreate] does not throw.
     * Assertion is a tautology (scenario.onActivity receives a non-null activity
     * that was just launched), but it forces the test to wait for the
     * ActivityScenario to reach the active state before closing.
     */
    @Test
    fun main_activity_launches_without_crash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull("Activity should be created", activity)
        }
        scenario.close()
    }

    /**
     * Smoke: Compose root view is attached to the window within 5 seconds.
     * [Thread.sleep] is used here intentionally to allow the Compose runtime
     * to initialise and attach the ComposeView. This is a REAL-TIME wait,
     * not virtual time — but it is the minimum needed for cold-start Compose.
     * NOTE: [androidx.compose.ui.test.waitForIdle] from ui-test-junit4-android could not be
     * used here due to a Gradle/Compose BOM API resolution issue (known incompatibility
     * between compose-multiplatform 1.12.0 and androidx.compose.ui 1.7.x on the
     * androidTest classpath). See singularity-todo-test-flaky-prevention skill.
     */
    @Test
    fun compose_root_view_is_attached() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Allow Compose runtime to boot and attach ComposeView to the window.
        @Suppress("DEPRECATION")
        Thread.sleep(5_000)
        // ComposeView is the Android view that hosts the Compose UI tree.
        onView(withClassName(endsWith("ComposeView")))
            .check(matches(isDisplayed()))
        scenario.close()
    }
}
