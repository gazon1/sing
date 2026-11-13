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
        assertEquals(
            listOf("Faces", "Nature", "Food", "Activities", "Places", "Objects", "Symbols", "Flags"),
            EmojiCatalog.categories.map { it.categoryNames["en"] },
            "the picker shows the provider's order and its English names",
        )
        assertTrue(EmojiCatalog.categories.all { it.emojis.isNotEmpty() }, "no category is empty")
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
