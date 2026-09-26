package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictResolverTest {

    @Test
    fun `checksum is deterministic`() {
        val state = buildJsonObject {
            put("title", JsonPrimitive("Test"))
            put("priority", JsonPrimitive("HIGH"))
        }

        val c1 = ConflictResolver.checksum(state)
        val c2 = ConflictResolver.checksum(state)

        assertEquals(c1, c2)
        assertTrue(c1.length == 64) // SHA-256 hex = 64 chars
    }

    @Test
    fun `checksum differs for different content`() {
        val state1 = buildJsonObject { put("title", JsonPrimitive("A")) }
        val state2 = buildJsonObject { put("title", JsonPrimitive("B")) }

        assertTrue(ConflictResolver.checksum(state1) != ConflictResolver.checksum(state2))
    }
}
