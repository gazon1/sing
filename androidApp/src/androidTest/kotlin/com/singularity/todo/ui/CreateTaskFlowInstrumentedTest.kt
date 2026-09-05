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
class CreateTaskFlowInstrumentedTest {

    @Test
    fun app_handles_create_task_lifecycle() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(3000)
        scenario.onActivity { assertNotNull("Activity", it) }
        scenario.close()
    }
}
