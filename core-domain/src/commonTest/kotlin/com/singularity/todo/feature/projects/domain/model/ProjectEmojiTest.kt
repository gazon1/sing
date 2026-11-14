package com.singularity.todo.feature.projects.domain.model

import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@Tag("fast")
class ProjectEmojiTest {
    @Test
    fun `emoji serializes to and deserializes from its string`() {
        val emoji = Emoji("🏠")
        val json = StableJson.encodeToString(Emoji.serializer(), emoji)
        assertEquals("\"🏠\"", json)
        assertEquals(emoji, StableJson.decodeFromString(Emoji.serializer(), json))
    }

    @Test
    fun `null emoji is handled`() {
        val project = project(icon = null)
        val json = project.toJson()
        assertEquals(JsonNull, json["icon"])
        val decoded = StableJson.decodeFromJsonElement(Project.serializer(), json)
        assertNull(decoded.icon)
    }

    @Test
    fun `empty string is preserved`() {
        val project = project(icon = Emoji(""))
        val json = project.toJson()
        assertEquals("", json["icon"]?.jsonPrimitive?.content)
        val decoded = StableJson.decodeFromJsonElement(Project.serializer(), json)
        assertEquals(Emoji(""), decoded.icon)

        val withEmpty = json.toMutableMap()
        withEmpty["icon"] = JsonPrimitive("")
        val decodedEmpty =
            StableJson.decodeFromJsonElement(Project.serializer(), JsonObject(withEmpty))
        assertEquals(Emoji(""), decodedEmpty.icon)
    }

    @Test
    fun `complex unicode sequences are preserved`() {
        val sequences = listOf("🧑‍💻", "🇬🇧", "🧘🏽", "🕊️", "👨‍👩‍👧‍👦", "1️⃣")
        sequences.forEach { seq ->
            val emoji = Emoji(seq)
            val json = StableJson.encodeToString(Emoji.serializer(), emoji)
            val decoded = StableJson.decodeFromString(Emoji.serializer(), json)
            assertEquals(seq, decoded.value)

            val project = project(icon = emoji)
            val projectJson = project.toJson()
            assertEquals(seq, projectJson["icon"]?.jsonPrimitive?.content)
            val decodedProject = StableJson.decodeFromJsonElement(Project.serializer(), projectJson)
            assertEquals(emoji, decodedProject.icon)
        }
    }

    private fun project(icon: Emoji?): Project =
        Project(
            id = ProjectId.fromString("p1"),
            userId = UserId("u1"),
            name = "P",
            color = 0xFF112233.toInt(),
            icon = icon,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
