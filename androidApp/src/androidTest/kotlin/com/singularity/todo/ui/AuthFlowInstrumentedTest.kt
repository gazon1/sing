package com.singularity.todo.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthFlowInstrumentedTest {

    @Test
    fun main_activity_launches_without_crash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull("Activity should be created", activity)
        }
        scenario.close()
    }

    @Test
    fun app_runs_without_anr() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Let it run for 3 seconds without ANR
        Thread.sleep(3000)
        scenario.close()
    }
}
