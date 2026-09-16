package com.singularity.todo.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true;
        encodeDefaults = true
    }

    @Test
    fun `DeltaPatch serializes and deserializes`() {
        val patch = DeltaPatch(
            patchId = "p1",
            entityId = "e1",
            entityType = DocType.Task,
            baseVersion = 5L,
            isDelete = false,
            shadowChecksum = "abc123",
            ops = listOf(
                FieldChange("title", FieldOp.SET, JsonPrimitive("New Title")),
            ),
        )

        val serialized = json.encodeToString(patch)
        val deserialized = json.decodeFromString<DeltaPatch>(serialized)

        assertEquals("p1", deserialized.patchId)
        assertEquals("e1", deserialized.entityId)
        assertEquals(DocType.Task, deserialized.entityType)
        assertEquals(5L, deserialized.baseVersion)
        assertEquals("abc123", deserialized.shadowChecksum)
        assertEquals(1, deserialized.ops.size)
        assertEquals("title", deserialized.ops[0].field)
        assertEquals(FieldOp.SET, deserialized.ops[0].op)
    }

    @Test
    fun `BatchPushRequest serializes correctly`() {
        val request = BatchPushRequest(
            protocolVersion = 1,
            deviceId = "device-1",
            patches = listOf(
                DeltaPatch(
                    patchId = "p1",
                    entityId = "e1",
                    entityType = DocType.Note,
                    baseVersion = 0L,
                ),
            ),
        )

        val serialized = json.encodeToString(request)
        assertTrue(serialized.contains("\"deviceId\":\"device-1\""))
        assertTrue(serialized.contains("\"protocolVersion\":1"))
    }

    @Test
    fun `PatchResult isRetriable returns true for retryable errors`() {
        val retryable = PatchResult("p1", ok = false, error = "network_timeout")
        val nonRetryableMismatch = PatchResult("p2", ok = false, error = "shadow_mismatch")
        val nonRetryableOld = PatchResult("p3", ok = false, error = "too_old")
        val success = PatchResult("p4", ok = true)

        assertTrue(retryable.isRetriable)
        assertFalse(nonRetryableMismatch.isRetriable)
        assertFalse(nonRetryableOld.isRetriable)
        assertFalse(success.isRetriable)
    }

    @Test
    fun `SyncEvent roundtrips`() {
        val event = SyncEvent(
            serverLsn = 12345L,
            entityId = "e1",
            entityType = DocType.Project,
            eventType = SyncEventType.UPDATED,
            createdAt = System.currentTimeMillis(),
        )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SyncEvent>(serialized)

        assertEquals(12345L, deserialized.serverLsn)
        assertEquals("e1", deserialized.entityId)
        assertEquals(DocType.Project, deserialized.entityType)
        assertEquals(SyncEventType.UPDATED, deserialized.eventType)
    }

    @Test
    fun `DocType fromKey returns correct type`() {
        assertEquals(DocType.Task, DocType.fromKey("task"))
        assertEquals(DocType.Note, DocType.fromKey("note"))
        assertEquals(DocType.Project, DocType.fromKey("project"))
        assertEquals(DocType.Tag, DocType.fromKey("tag"))
    }

    @Test
    fun `helper functions create correct patches`() {
        val patch = deltaPatchSet(
            "pid1",
            "eid1",
            DocType.Task,
            5L,
            "title",
            kotlinx.serialization.json.JsonPrimitive("New Title"),
        )

        assertEquals("pid1", patch.patchId)
        assertEquals("eid1", patch.entityId)
        assertEquals(DocType.Task, patch.entityType)
        assertEquals(5L, patch.baseVersion)
        assertEquals(1, patch.ops.size)
        assertEquals(FieldOp.SET, patch.ops[0].op)

        val deletePatch = deltaPatchDelete("pid2", "eid2", DocType.Tag, 10L)
        assertEquals("pid2", deletePatch.patchId)
        assertTrue(deletePatch.isDelete)
    }
}
