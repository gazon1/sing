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
class NavigationFlowInstrumentedTest {

    @Test
    fun app_stays_alive_for_5_seconds() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(5000)
        scenario.onActivity { assertNotNull("Activity still alive", it) }
        scenario.close()
    }
}
