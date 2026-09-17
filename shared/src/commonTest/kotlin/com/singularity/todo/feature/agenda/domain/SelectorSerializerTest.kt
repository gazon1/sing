
package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.SelectorSerializer
import com.singularity.todo.feature.tags.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [SelectorSerializer] — the JSON migration shim that accepts both
 * MR1's `"Tag"` discriminator and MR2's `"Tags"` discriminator.
 *
 * Without [SelectorSerializer] wired on the [Selector] interface, kotlinx.serialization
 * would generate a standard polymorphic deserializer that only understands the MR2 format.
 */
class SelectorSerializerTest {

    // Json.decodeFromString is a member function — use as json.decodeFromString<T>(string)
    private val json get() = StableJson

    // ─── MR1 legacy — Selector.Tag ────────────────────────────────────────────

    @Test
    fun `MR1 Tag JSON round-trips via SelectorSerializer`() {
        // MR1 format: "_type":"Tag", single "id" field
        val mr1Json = """{"_type":"Tag","id":"tag-123"}"""

        val selector = json.decodeFromString(SelectorSerializer, mr1Json)

        assertIs<Selector.Tag>(selector)
        assertEquals(TagId("tag-123"), selector.id)
    }

    @Test
    fun `MR1 Tag serializes with _type discriminator`() {
        @Suppress("DEPRECATION")
        val tag = Selector.Tag(TagId("tag-abc"))
        val encoded = json.encodeToString(SelectorSerializer, tag)

        // serialize() always includes _type for every branch
        assertEquals("""{"_type":"Tag","id":"tag-abc"}""", encoded)
    }

    // ─── MR2 current — Selector.Tags ───────────────────────────────────────────

    @Test
    fun `Tags JSON round-trips via SelectorSerializer`() {
        val tagsJson = """{"_type":"Tags","ids":["tag-1","tag-2"],"matchAll":true}"""

        val selector = json.decodeFromString(SelectorSerializer, tagsJson)

        assertIs<Selector.Tags>(selector)
        assertEquals(setOf(TagId("tag-1"), TagId("tag-2")), selector.ids)
        assertEquals(true, selector.matchAll)
    }

    @Test
    fun `Tags with matchAll false round-trips`() {
        val tagsJson = """{"_type":"Tags","ids":["tag-x"],"matchAll":false}"""
        val selector = json.decodeFromString(SelectorSerializer, tagsJson)

        assertIs<Selector.Tags>(selector)
        assertEquals(setOf(TagId("tag-x")), selector.ids)
        assertEquals(false, selector.matchAll)
    }

    @Test
    fun `Tags round-trips via SelectorSerializer`() {
        // Create a Tags selector and serialize→deserialize it
        val selector = Selector.Tags(setOf(TagId("t1"), TagId("t2")), matchAll = true)
        val encoded = json.encodeToString(SelectorSerializer, selector)
        val decoded = json.decodeFromString(SelectorSerializer, encoded)

        assertIs<Selector.Tags>(decoded)
        assertEquals(setOf(TagId("t1"), TagId("t2")), decoded.ids)
        assertEquals(true, decoded.matchAll)
    }

    // ─── Other Selector types (sanity) ─────────────────────────────────────────

    @Test
    fun `DateBucket round-trips via SelectorSerializer`() {
        val dateBucketJson = """{"_type":"DateBucket","bucket":"Today"}"""
        val selector = json.decodeFromString(SelectorSerializer, dateBucketJson)

        assertIs<Selector.DateBucket>(selector)
        assertEquals(RelativeBucket.Today, selector.bucket)
    }

    @Test
    fun `Statuses round-trips via SelectorSerializer`() {
        val statusesJson = """{"_type":"Statuses","statuses":["Active","Completed"]}"""
        val selector = json.decodeFromString(SelectorSerializer, statusesJson)

        assertIs<Selector.Statuses>(selector)
        assertEquals(2, selector.statuses.size)
    }
}
