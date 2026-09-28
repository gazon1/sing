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
 * Smoke tests for the create-task flow.
 *
 * Despite the class name, this does NOT exercise the task-creation UI — it is
 * a placeholder verifying that [MainActivity] can host the Compose UI long enough
 * for the task feature to load.
 *
 * NOTE: [MainActivity] currently ANRs on emulator startup — see
 * docs/decisions/2026-09-28-androidApp-smoke-tests-enabled.md.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CreateTaskFlowInstrumentedTest {

    @Test
    fun app_handles_create_task_lifecycle() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        @Suppress("DEPRECATION")
        Thread.sleep(3_000)
        scenario.onActivity { assertNotNull("Activity", it) }
        onView(withClassName(endsWith("ComposeView")))
            .check(matches(isDisplayed()))
        scenario.close()
    }
}
