package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.feature.tasks.domain.model.TaskId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The task link format, in both directions.
 *
 * The producer (calendar sync) and the consumer (the deep-link matcher) share
 * this spec, so a round-trip through it is what proves the two agree. Before
 * the spec existed the format lived in three places and the round-trip was not
 * guaranteed by anything.
 */
@Tag("fast")
class TaskLinkSpecTest {

    @Test
    fun `a task id becomes a link`() {
        assertEquals("singularity://task/t1", TaskLinkSpec.uri(TaskId("t1")))
    }

    @Test
    fun `a link becomes a task id`() {
        assertEquals(TaskId("t1"), TaskLinkSpec.taskIdFromUri("singularity://task/t1"))
    }

    @Test
    fun `the link round-trips`() {
        val id = TaskId("9f8b1c2d-0000-4000-8000-000000000001")

        assertEquals(id, TaskLinkSpec.taskIdFromUri(TaskLinkSpec.uri(id)))
    }

    @Test
    fun `the link is found inside an event description`() {
        val description = "Buy milk\n\nsingularity://task/t1"

        assertEquals(TaskId("t1"), TaskLinkSpec.taskIdFromUri(description))
    }

    @Test
    fun `text without a link yields nothing`() {
        assertNull(TaskLinkSpec.taskIdFromUri("Buy milk"))
    }

    @Test
    fun `a link without an id yields nothing`() {
        assertNull(TaskLinkSpec.taskIdFromUri("singularity://task/"))
    }

    @Test
    fun `another authority is not a task link`() {
        assertNull(TaskLinkSpec.taskIdFromUri("singularity://view/v1"))
    }
}
