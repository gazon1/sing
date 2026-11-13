package com.singularity.todo.feature.projects.domain.logic

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history of recently used emoji in the project icon picker.
 *
 * The rules are asserted one at a time, because each of them is a way the
 * history can go wrong quietly: the newest emoji not at the front, the same
 * emoji twice, the row growing without end, and a composed sequence — a ZWJ
 * family, a flag, a skin-tone modifier — torn into two entries.
 */
@Tag("fast")
class RecentEmojiHistoryTest {
    @Test
    fun `the picked emoji goes to the front`() {
        val history = rememberRecentEmoji(current = listOf("🚀", "🎯"), emoji = "💡")

        assertEquals(listOf("💡", "🚀", "🎯"), history)
    }

    @Test
    fun `the history keeps the order it was given in`() {
        val history = rememberRecentEmoji(current = listOf("🚀", "🎯", "💡"), emoji = "🔥")

        assertEquals(listOf("🔥", "🚀", "🎯", "💡"), history)
    }

    @Test
    fun `a repeated emoji moves to the front instead of appearing twice`() {
        val history = rememberRecentEmoji(current = listOf("🚀", "🎯", "💡"), emoji = "🎯")

        assertEquals(listOf("🎯", "🚀", "💡"), history)
    }

    @Test
    fun `the history is bounded`() {
        val current = (1..RECENT_EMOJI_LIMIT + 5).map { "emoji$it" }

        val history = rememberRecentEmoji(current = current, emoji = "new")

        assertEquals(RECENT_EMOJI_LIMIT, history.size)
        assertEquals("new", history.first())
        // The oldest entries fall off the end: the newest ten are kept.
        assertEquals("emoji${RECENT_EMOJI_LIMIT - 1}", history.last())
    }

    @Test
    fun `a blank emoji changes nothing`() {
        val history = rememberRecentEmoji(current = listOf("🚀", "🎯"), emoji = "  ")

        assertEquals(listOf("🚀", "🎯"), history)
    }

    @Test
    fun `a blank emoji does not shorten the history either`() {
        val current = (1..RECENT_EMOJI_LIMIT + 3).map { "emoji$it" }

        val history = rememberRecentEmoji(current = current, emoji = "")

        assertEquals(current, history)
    }

    @Test
    fun `a composed emoji is one entry`() {
        val family = "👨‍👩‍👧‍👦"
        val flag = "🇩🇪"

        val history = rememberRecentEmoji(current = listOf(flag), emoji = family)

        assertEquals(listOf(family, flag), history)
        assertTrue(history.all { it.codePointCount(0, it.length) > 0 })
    }

    @Test
    fun `a composed emoji repeated is not duplicated`() {
        val family = "👨‍👩‍👧‍👦"

        val history = rememberRecentEmoji(current = listOf(family, "🚀"), emoji = family)

        assertEquals(listOf(family, "🚀"), history)
    }
}

/**
 * What is read back from storage.
 *
 * The file on disk is not always what was written — an older version kept a
 * longer list — so reading normalizes instead of trusting what is there.
 */
@Tag("fast")
class NormalizeRecentEmojiTest {
    @Test
    fun `a longer history is cut to the bound`() {
        val stored = (1..RECENT_EMOJI_LIMIT + 5).map { "emoji$it" }

        val history = normalizeRecentEmoji(stored)

        assertEquals(RECENT_EMOJI_LIMIT, history.size)
        assertEquals("emoji1", history.first())
    }

    @Test
    fun `a repeated value is shown once`() {
        val history = normalizeRecentEmoji(listOf("🚀", "🎯", "🚀", "💡"))

        assertEquals(listOf("🚀", "🎯", "💡"), history)
    }

    @Test
    fun `the order of the first appearance is kept`() {
        val history = normalizeRecentEmoji(listOf("🎯", "🚀", "🎯", "💡"))

        assertEquals(listOf("🎯", "🚀", "💡"), history)
    }

    @Test
    fun `a blank value is dropped`() {
        val history = normalizeRecentEmoji(listOf("🚀", "", "   ", "🎯"))

        assertEquals(listOf("🚀", "🎯"), history)
    }

    @Test
    fun `a composed emoji stays whole`() {
        val family = "👨‍👩‍👧‍👦"

        val history = normalizeRecentEmoji(listOf(family, "🚀"))

        assertEquals(listOf(family, "🚀"), history)
    }
}
