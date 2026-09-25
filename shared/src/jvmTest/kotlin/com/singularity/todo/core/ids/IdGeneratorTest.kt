package com.singularity.todo.core.ids

import kotlin.test.Test
import kotlin.test.assertEquals

class IdGeneratorTest {

    @Test
    fun `UlidIdGenerator returns 26-character ULID string`() {
        val id = UlidIdGenerator.next()
        assertEquals(26, id.length)
    }

    @Test
    fun `UlidIdGenerator returns unique ids across calls`() {
        val ids = (1..100).map { UlidIdGenerator.next() }.toSet()
        assertEquals(100, ids.size)
    }

    @Test
    fun `SequenceIdGenerator returns sequential ids with prefix`() {
        val gen = SequenceIdGenerator("task")
        assertEquals("task-1", gen.next())
        assertEquals("task-2", gen.next())
        assertEquals("task-3", gen.next())
    }

    @Test
    fun `SequenceIdGenerator default prefix is id`() {
        val gen = SequenceIdGenerator()
        assertEquals("id-1", gen.next())
        assertEquals("id-2", gen.next())
    }

    @Test
    fun `SequenceIdGenerator ids are unique within same generator`() {
        val gen = SequenceIdGenerator("msg")
        val ids = (1..50).map { gen.next() }.toSet()
        assertEquals(50, ids.size)
    }
}
