package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlin.test.Test

class TaskToJsonProbeTest {
    @Test
    fun probe() {
        val task = Task(
            id = com.singularity.todo.feature.tasks.domain.model.TaskId("t1"),
            title = "T",
            kind = TaskKind.Task,
            priority = TaskPriority.None,
            createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
            updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
            userId = UserId("test-user"),
        )
        try {
            val json = task.toJson()
            println("TASKTOJSON OK: ${json.toString().take(120)}")
        } catch (e: Throwable) {
            println("TASKTOJSON THROWS: ${e::class.simpleName}: ${e.message?.take(150)}")
        }
    }
}
