package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire names of a task, pinned.
 *
 * ## Why this test exists
 *
 * The domain property is called `blockedBy` — that is what it holds, since the
 * set was narrowed to the edges that block. The serialized name stayed
 * `dependsOn`, because that name is already in the sync allow-list and in
 * every backup ever written, and the server stores documents by it.
 *
 * A rename of the domain property that quietly renamed the wire would have
 * been invisible in every test that only looked at Kotlin: the field-list
 * conformance check used to pass when a field was *absent* as well as when it
 * was present under the right name, and the server would have received a
 * document with an unknown field and dropped the dependencies from it. So the
 * name is asserted here, in both directions — on the wire and in the list the
 * contract derives from the model.
 */
@Tag("fast")
class TaskWireFieldNamesTest {

    @Test
    fun `the serialized task keeps the dependsOn key`() {
        val json = task().toJson()

        assertTrue(
            "dependsOn" in json.keys,
            "The wire name of the blocking edges must stay 'dependsOn' — " +
                "the sync allow-list and every backup written so far use it. Keys: ${json.keys}",
        )
    }

    @Test
    fun `the serialized task does not carry the domain name`() {
        val json = task().toJson()

        assertFalse(
            "blockedBy" in json.keys,
            "The domain name must not leak onto the wire — the server would " +
                "not know the field. Keys: ${json.keys}",
        )
    }

    @Test
    fun `the wire field list still names the wire field`() {
        assertTrue(
            "dependsOn" in SyncContract.wireFieldsFor(DocType.Task),
            "The wire's field list must keep the wire name, or the server drops the field.",
        )
    }

    private fun task(): Task = Task(
        id = TaskId("task-1"),
        title = "Task",
        kind = TaskKind.Task,
        priority = TaskPriority.None,
        blockedBy = setOf(TaskId("task-2")),
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        userId = UserId("test-user"),
    )
}
