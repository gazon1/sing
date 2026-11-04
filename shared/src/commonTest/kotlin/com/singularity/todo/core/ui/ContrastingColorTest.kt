package com.singularity.todo.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The luminance threshold behind [contrastingForeground], pinned from both sides.
 *
 * The rule is one comparison, so inverting it or swapping the two colours puts a black
 * icon on every dark project colour and a white one on every bright accent, and nothing
 * else in the suite reads the result: these two cases are the only place the threshold's
 * two directions are stated as behaviour (#1672).
 */
@Tag("fast")
class ContrastingColorTest {
    @Test
    fun `a bright background gets a black foreground`() {
        val foreground = Color.Yellow.contrastingForeground()
        assertEquals(
            Color.Black,
            foreground,
            "yellow is brighter than the 0.5 luminance threshold, but resolved to $foreground",
        )
    }

    @Test
    fun `a dark background gets a white foreground`() {
        val foreground = Color(0xFF1E1E1E).contrastingForeground()
        assertEquals(
            Color.White,
            foreground,
            "0xFF1E1E1E is darker than the 0.5 luminance threshold, but resolved to $foreground",
        )
    }
}
