package com.singularity.todo.feature.projects.domain.logic

import com.singularity.todo.core.emoji.Emoji
import com.singularity.todo.feature.projects.COMPOSED_PROJECT_ICONS
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The contract of the project icon picker's custom field.
 *
 * The field takes free text, and the rule is deliberately thin: the text is
 * trimmed at both ends, and what is left is the icon — byte for byte. There is
 * no emoji validation, no normalization and no catalog lookup, so a value the
 * picker has never seen is a value like any other.
 *
 * These cases are the ones that break silently. A normalization step (NFC or
 * NFD) rewrites a composed sequence into a different string; a catalog check
 * rejects a valid composed value the provider does not list; a parser that
 * counts code points splits a family into two entries. The test therefore
 * compares the exact string, not "an emoji was picked".
 */
@Tag("fast")
class CustomEmojiInputTest {
    @Test
    fun `empty input picks nothing`() {
        assertNull(customEmojiFromInput(""), "there is no icon in an empty field")
    }

    @Test
    fun `whitespace-only input picks nothing`() {
        assertNull(customEmojiFromInput("   "), "spaces are not an icon")
        assertNull(customEmojiFromInput("\t\n "), "and neither are tabs and newlines")
    }

    @Test
    fun `ordinary text is an icon`() {
        assertEquals(Emoji("Work"), customEmojiFromInput("Work"), "text the catalog does not know is a value")
    }

    @Test
    fun `a single emoji is an icon`() {
        assertEquals(Emoji("😀"), customEmojiFromInput("😀"))
    }

    @Test
    fun `the value is trimmed at both ends`() {
        assertEquals(Emoji("🎯"), customEmojiFromInput("  🎯  "), "the padding is not part of the icon")
    }

    @Test
    fun `the inside of the value is left alone`() {
        assertEquals(Emoji("a b"), customEmojiFromInput("a b"), "inner spaces are the user's text")
    }

    @Test
    fun `a composed sequence is kept exactly as it was typed`() {
        COMPOSED_PROJECT_ICONS.forEach { sequence ->
            val picked = customEmojiFromInput(sequence)

            assertEquals(Emoji(sequence), picked, "'$sequence' was changed on the way in")
            assertEquals(sequence, picked?.value, "'$sequence' did not survive byte for byte")
        }
    }

    @Test
    fun `a composed sequence keeps its code points`() {
        val family = customEmojiFromInput("👨‍👩‍👧‍👦")

        assertEquals("👨‍👩‍👧‍👦", family?.value, "the family is one string of several code points")
        assertEquals(7, family?.value?.codePointCount(0, family.value.length), "and it was not split")
    }

    @Test
    fun `an unknown sequence is a value, not a rejection`() {
        // A sequence no catalog lists, and a lone joiner: the picker has no
        // opinion about either, so it keeps what it was given.
        assertEquals(Emoji("\u200D"), customEmojiFromInput("\u200D"), "a lone joiner is still text")
        assertEquals(Emoji("\uD83D"), customEmojiFromInput("\uD83D"), "an unpaired surrogate is kept verbatim")
        assertEquals(Emoji("🏳️‍🌈"), customEmojiFromInput("🏳️‍🌈"), "a rainbow flag is a value")
    }
}
