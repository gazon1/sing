package com.singularity.todo.core.emoji

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker's emoji come from the library, and its search is the library's.
 *
 * The catalog is a global install, so these assertions are about the shape the
 * picker relies on: categories in the provider's order, and the shortcode search
 * with its empty-query rule.
 */
@Tag("fast")
class EmojiCatalogTest {
    @Test
    fun `the catalog is the provider's categories in order`() {
        val categories = EmojiCatalog.categories

        assertTrue(categories.size >= 8, "the provider publishes its categories: ${categories.size}")
        assertTrue(categories.all { it.emojis.isNotEmpty() }, "no category is empty")
        assertEquals("Faces", categories.first().categoryNames["en"], "the first category is stable")
    }

    @Test
    fun `an empty query matches nothing`() {
        assertTrue(EmojiCatalog.search("").isEmpty(), "an empty query is browse mode, not a search")
    }

    @Test
    fun `a shortcode match returns its emoji`() {
        assertTrue(
            EmojiCatalog.search("rocket").any { it.unicode == "🚀" },
            "the library's shortcode index answers: ${EmojiCatalog.search("rocket").map { it.unicode }}",
        )
    }

    @Test
    fun `an unknown shortcode matches nothing`() {
        assertTrue(EmojiCatalog.search("zzznomatch").isEmpty(), "the search may return no matches")
    }
}
