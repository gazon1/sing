package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Regression: [SyncableEntity.toJson] uses `serializer<T>()` reflection, which
 * requires the entity's @Serializable graph to resolve every property type.
 * Task carries a [UserId] value class — this test locks in that the sync JSON
 * serializes it as a plain string (wire format), not a wrapped object.
 */
class TaskSyncSerializationTest {

    @Test
    fun `toJson serializes userId as plain string`() {
        val task = Task(
            id = TaskId("t1"),
            title = "T",
            kind = TaskKind.Task,
            priority = TaskPriority.None,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            userId = UserId("test-user"),
        )

        val json = task.toJson()

        assertEquals("test-user",
            json["userId"]?.toString()
                ?.trim('"')
        )
        assertTrue(json.containsKey("title"))
    }
}
