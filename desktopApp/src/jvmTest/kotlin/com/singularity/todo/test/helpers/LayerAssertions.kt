package com.singularity.todo.test.helpers

import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin

/**
 * Asserts that the data layer holds exactly [expected] tasks for the current user.
 *
 * Usage in a flow test — after creating a task through the UI, verify the repository
 * received it before asserting on the screen:
 * ```kotlin
 * runDesktopAppTest { koin ->
 *     assertSeeded(koin, expected = 1)
 *     awaitTag(TestTags.taskItem("Buy milk")).assertIsDisplayed()
 * }
 * ```
 *
 * This pins the failure to the data layer ("repository doesn't know") vs the VM
 * ("screen doesn't render what the repository returns"), replacing a silent
 * "nothing appears" with a concrete count mismatch.
 *
 * @param koin The Koin application under test.
 * @param expected The exact number of tasks the data layer should hold.
 */
@Suppress("NoRunBlocking") // Test helper — blocking bridge is correct here
fun assertSeeded(koin: Koin, expected: Int) {
    val taskRepo = koin.get<TaskRepository>()
    val tasks = runBlocking { taskRepo.observeAll().first() }
    check(tasks.size == expected) {
        "Data layer holds ${tasks.size} tasks, expected $expected. " +
            "Task IDs: ${tasks.map { it.id.value }}"
    }
}
