package com.singularity.todo.feature.projects.presentation.components

import com.singularity.todo.core.emoji.Emoji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectIconResolverTest {
    @Test
    fun `blank or null resolves to None`() {
        assertEquals(ResolvedProjectIcon.None, resolveProjectIcon(null))
        assertEquals(ResolvedProjectIcon.None, resolveProjectIcon(Emoji("")))
        assertEquals(ResolvedProjectIcon.None, resolveProjectIcon(Emoji("   ")))
    }

    @Test
    fun `non-blank value resolves to Emoji`() {
        val resolved = resolveProjectIcon(Emoji("Work"))
        assertTrue(resolved is ResolvedProjectIcon.EmojiIcon)
        assertEquals("Work", resolved.value)
    }

    @Test
    fun `emoji string resolves to Emoji`() {
        val resolved = resolveProjectIcon(Emoji("🚀"))
        assertTrue(resolved is ResolvedProjectIcon.EmojiIcon)
        assertEquals("🚀", resolved.value)
    }

    @Test
    fun `composed emoji sequences resolve to Emoji`() {
        val sequences =
            listOf(
                "🧑‍💻", // ZWJ sequence
                "🇬🇧", // two regional indicators
                "🧘🏽", // skin-tone modifier
                "🕊️", // variation selector
            )
        for (sequence in sequences) {
            val resolved = resolveProjectIcon(Emoji(sequence))
            assertTrue(resolved is ResolvedProjectIcon.EmojiIcon, "$sequence must resolve to Emoji")
            assertEquals(sequence, (resolved as ResolvedProjectIcon.EmojiIcon).value)
        }
    }
}
