package com.singularity.todo.core.emoji

import com.singularity.todo.core.text.visibleLength
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
 *
 * The composed sequences are asserted too, because they are what the app stores:
 * a ZWJ sequence, a flag, a skin-tone modifier and a variation selector are
 * several code points, and the library has to know them as one emoji — otherwise
 * the picker would offer a person's family as four separate glyphs.
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

    /**
     * The imported person technologist — `1f9d1-200d-1f4bb` in the legacy
     * import format — is one emoji in the catalog, not three code points.
     *
     * This is the sequence the importer produces and the app stores, so it is
     * the one the picker has to be able to find again.
     */
    @Test
    fun `a ZWJ sequence is one catalog entry`() {
        assertEquals(
            1,
            catalogEntries("🧑‍💻").size,
            "the person technologist is one emoji, not a person and a laptop",
        )
        assertTrue(
            EmojiCatalog.search("technologist").any { it.unicode == "🧑‍💻" },
            "the shortcode search finds the composed sequence: " +
                EmojiCatalog.search("technologist").map { it.unicode },
        )
    }

    @Test
    fun `a flag is one catalog entry`() {
        assertEquals(1, catalogEntries("🇬🇧").size, "the flag is one emoji, not two regional indicators")
    }

    @Test
    fun `a ZWJ family is one catalog entry`() {
        assertEquals(
            1,
            catalogEntries("👨‍👩‍👧‍👦").size,
            "the family is one emoji, not four people",
        )
    }

    /**
     * A skin-tone modifier is a variant of its base emoji, not a separate entry.
     *
     * The catalog holds the base once and the toned forms beside it, so the
     * picker offers the toned form without listing a second "meditating" entry.
     */
    @Test
    fun `a skin-tone form is a variant of its base emoji`() {
        assertTrue(
            catalogEntries("🧘🏽").isEmpty(),
            "the catalog has no entry of its own for a toned emoji",
        )
        assertTrue(
            catalogVariants("🧘").any { it == "🧘🏽" },
            "the toned form hangs off the base emoji: ${catalogVariants("🧘")}",
        )
    }

    /**
     * A variation selector is a variant of its base emoji.
     *
     * The dove is stored by the app as `🕊️` — the emoji-presentation form, with
     * the selector. The catalog holds the text-presentation base `🕊` and the
     * emoji-presentation form beside it.
     */
    @Test
    fun `a variation-selector form is a variant of its base emoji`() {
        assertTrue(
            catalogVariants("🕊").any { it == "🕊️" },
            "the emoji-presentation form hangs off the base emoji: ${catalogVariants("🕊")}",
        )
    }

    /**
     * The keycap the app stores is the variant, not the base.
     *
     * `1️⃣` is `1` + variation selector + keycap. The catalog holds `1⃣` as
     * the base and the form with the selector as its variant.
     */
    @Test
    fun `a keycap form is a variant of its base emoji`() {
        assertTrue(
            catalogVariants("1⃣").any { it == "1️⃣" },
            "the keycap with the selector hangs off the base: ${catalogVariants("1⃣")}",
        )
    }

    /**
     * Every composed form the app stores is in the library's data unchanged.
     *
     * The app stores the exact string it was given — no normalization, no
     * stripped selector, no split ZWJ sequence. The library knows the same
     * strings, so a stored icon and the catalog's own entry are the same value
     * and the picker can offer it again.
     */
    @Test
    fun `the stored composed forms are in the library's data byte-for-byte`() {
        for (stored in STORED_COMPOSED_FORMS) {
            assertTrue(
                catalogEntries(stored).isNotEmpty() || catalogVariantsOf(stored).isNotEmpty(),
                "'$stored' is neither a catalog entry nor a variant",
            )
        }
    }

    /**
     * The retained helper counts code points; the library counts emoji.
     *
     * `visibleLength()` is `codePointCount`, so it charges three code points for
     * the person technologist, while the catalog holds it as one emoji. The two
     * are not the same count, and the helper stays a code-point count — which
     * quantity a name limit should bound is #2933's decision.
     */
    @Test
    fun `the retained code-point count is not an emoji count`() {
        assertEquals(3, "🧑‍💻".visibleLength(), "the helper counts code points")
        assertEquals(2, "🇬🇧".visibleLength(), "a flag is two code points")
        assertEquals(
            1,
            catalogEntries("🧑‍💻").size,
            "the library holds the same string as one emoji",
        )
    }

    private fun catalogEntries(unicode: String) = catalogEmojis().filter { it.unicode == unicode }

    private fun catalogVariants(base: String) =
        catalogEmojis().firstOrNull { it.unicode == base }?.variants?.map { it.unicode } ?: emptyList()

    private fun catalogVariantsOf(unicode: String) =
        catalogEmojis().flatMap { it.variants }.filter { it.unicode == unicode }

    private fun catalogEmojis() = EmojiCatalog.categories.flatMap { it.emojis }

    private companion object {
        /**
         * The composed forms the app stores: a ZWJ person, a flag, a family, a
         * skin-tone modifier, a variation selector and a keycap.
         */
        val STORED_COMPOSED_FORMS =
            listOf("🧑‍💻", "🇬🇧", "👨‍👩‍👧‍👦", "🧘🏽", "🕊️", "1️⃣")
    }
}
