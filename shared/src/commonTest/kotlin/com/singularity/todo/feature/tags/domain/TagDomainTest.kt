package com.singularity.todo.feature.tags.domain

import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.test.fakes.TestUsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

private val NOW = Instant.fromEpochMilliseconds(1_700_000_000_000)

private fun aTag(name: String) = Tag(
    id = TagId.fromString("tg-${name.lowercase()}"),
    name = name,
    color = 0xFFE91E63.toInt(),
    createdAt = NOW,
    updatedAt = NOW,
    userId = TestUsers.DEFAULT,
)

/**
 * The duplicate-tag rule, and the name rules it sits next to.
 *
 * There is no uniqueness anywhere else in the app — no unique index on
 * `tags.name`, no rule in `TagDomain` before this one, no check in
 * `TagsRepositoryImpl.create` — so the rule is pinned here, where it is
 * written down, rather than in the screen that happens to enforce it.
 */
@org.junit.jupiter.api.Tag("fast")
class TagDomainTest {

    @Test
    fun `a tag is found by its name whatever the case`() {
        val tags = listOf(aTag("Invoice"), aTag("work"))

        assertEquals(
            "Invoice",
            assertNotNull(TagDomain.findByName(tags, "invoice")).name,
            "a name that differs only in case is the same label",
        )
        assertEquals(
            "Invoice",
            assertNotNull(TagDomain.findByName(tags, "INVOICE")).name,
            "the comparison is case-insensitive in both directions",
        )
    }

    @Test
    fun `a name is matched after it is trimmed`() {
        val tags = listOf(aTag("Invoice"))

        assertEquals(
            "Invoice",
            assertNotNull(TagDomain.findByName(tags, "  invoice  ")).name,
            "the surrounding whitespace is not part of the name",
        )
    }

    @Test
    fun `a name no tag carries finds nothing`() {
        val tags = listOf(aTag("Invoice"))

        assertNull(TagDomain.findByName(tags, "receipt"), "there is no such tag")
        assertNull(TagDomain.findByName(tags, ""), "an empty name matches nothing")
        assertNull(TagDomain.findByName(tags, "   "), "a blank name matches nothing")
        assertNull(TagDomain.findByName(emptyList(), "invoice"), "there are no tags at all")
    }

    @Test
    fun `a blank name is refused with the reason stated`() {
        assertEquals(
            "Tag name cannot be blank",
            assertNotNull(TagDomain.validateName("")).message,
        )
        assertEquals(
            "Tag name cannot be blank",
            assertNotNull(TagDomain.validateName("   ")).message,
            "whitespace is not a name",
        )
        assertNull(TagDomain.validateName("work"), "an ordinary name is valid")
    }

    @Test
    fun `a name longer than a hundred characters is refused with the reason stated`() {
        assertEquals(
            "Tag name too long (max 100 characters)",
            assertNotNull(TagDomain.validateName("a".repeat(101))).message,
        )
        assertNull(
            TagDomain.validateName("a".repeat(100)),
            "a hundred characters is still a valid name",
        )
    }
}
