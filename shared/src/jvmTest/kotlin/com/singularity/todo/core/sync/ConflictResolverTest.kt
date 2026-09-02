package com.singularity.todo.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `merge keeps remote when newer by HLC`() {
        val local = buildJsonObject { put("title", JsonPrimitive("Local")) }
        val remote = buildJsonObject { put("title", JsonPrimitive("Remote")) }
        val localHlc = Hlc.of(1000, 0, "n1")
        val remoteHlc = Hlc.of(2000, 0, "n2")

        val merged = ConflictResolver.merge(local, remote, localHlc, remoteHlc)

        assertEquals("Remote", merged.jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `merge keeps local when newer by HLC`() {
        val local = buildJsonObject { put("title", JsonPrimitive("Local")) }
        val remote = buildJsonObject { put("title", JsonPrimitive("Remote")) }
        val localHlc = Hlc.of(2000, 0, "n1")
        val remoteHlc = Hlc.of(1000, 0, "n2")

        val merged = ConflictResolver.merge(local, remote, localHlc, remoteHlc)

        assertEquals("Local", merged.jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `merge takes remote when local is null`() {
        val local: kotlinx.serialization.json.JsonElement = JsonNull
        val remote = buildJsonObject { put("title", JsonPrimitive("Remote")) }

        val merged = ConflictResolver.merge(local, remote, null, Hlc.zero("n"))

        assertEquals("Remote", merged.jsonObject["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun `merge takes local when remote is null`() {
        val local = buildJsonObject { put("title", JsonPrimitive("Local")) }
        val remote: kotlinx.serialization.json.JsonElement = JsonNull

        val merged = ConflictResolver.merge(local, remote, Hlc.zero("n"), null)

        assertEquals("Local", merged.jsonObject["title"]?.jsonPrimitive?.content)
    }

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
