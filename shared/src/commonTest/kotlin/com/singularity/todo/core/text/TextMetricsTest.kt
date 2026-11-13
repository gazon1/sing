package com.singularity.todo.core.text

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [visibleLength] counts Unicode code points, and these pin that it does.
 *
 * That is the number today's name limits enforce — but it is *not* the visible
 * characters their documentation promises, and not the glyphs a person sees. A
 * flag is one glyph and two code points, a ZWJ family is one glyph and seven.
 * Whether the limit should count code points, catalog-recognized emoji units or
 * grapheme clusters is an open decision (#2933); this test pins what the code
 * does today. Vanniktech Emoji's `visualLength` counts each sequence its catalog
 * recognizes as an emoji as one (and other characters by UTF-16 length); it is
 * not a general grapheme counter. The two are therefore not interchangeable; see
 * `docs/decisions/2026-11-13-the-emoji-helpers-have-no-library-equivalent.md`.
 *
 * These would fail if one were swapped for the other, which is the point of
 * writing them down.
 */
@Tag("fast")
class TextMetricsTest {
    @Test
    fun `an empty string has no characters`() {
        assertEquals(0, "".visibleLength())
    }

    @Test
    fun `ASCII counts one per character`() {
        assertEquals(5, "hello".visibleLength())
    }

    @Test
    fun `a character outside the base plane counts once, not twice`() {
        // U+1F389 is two UTF-16 units and one code point.
        assertEquals(2, "🎉".length, "precondition: UTF-16 sees two units")
        assertEquals(1, "🎉".visibleLength())
    }

    @Test
    fun `a ZWJ sequence counts every code point`() {
        // U+1F9D1 U+200D U+1F4BB — five UTF-16 units, three code points, one glyph.
        assertEquals(5, "🧑‍💻".length, "precondition: UTF-16 sees five units")
        assertEquals(3, "🧑‍💻".visibleLength())
    }

    @Test
    fun `a family counts its people and its joiners`() {
        // Four people and three joiners: eleven UTF-16 units, seven code points.
        assertEquals(11, "👨‍👩‍👧‍👦".length, "precondition: UTF-16 sees eleven units")
        assertEquals(7, "👨‍👩‍👧‍👦".visibleLength())
    }

    @Test
    fun `a flag counts both regional indicators`() {
        // 🇬🇧 is two regional indicators, each outside the base plane.
        assertEquals(4, "🇬🇧".length, "precondition: UTF-16 sees four units")
        assertEquals(2, "🇬🇧".visibleLength())
    }

    @Test
    fun `a skin-tone modifier counts with the person it modifies`() {
        assertEquals(4, "🧘🏽".length, "precondition: UTF-16 sees four units")
        assertEquals(2, "🧘🏽".visibleLength())
    }

    @Test
    fun `a variation selector counts`() {
        // U+1F54A U+FE0F — three UTF-16 units, two code points.
        assertEquals(3, "🕊️".length, "precondition: UTF-16 sees three units")
        assertEquals(2, "🕊️".visibleLength())
    }

    @Test
    fun `a lone surrogate is one code point and does not throw`() {
        // Malformed input still has to produce a number: the value came from a
        // settings file or an import, not from a keyboard.
        assertEquals(1, "\uD83C".visibleLength(), "a high surrogate on its own")
        assertEquals(1, "\uDE00".visibleLength(), "a low surrogate on its own")
        assertEquals(2, "\uDE00\uD83C".visibleLength(), "a low-then-high pair is not a pair")
        assertEquals(3, "a\uD83Ca".visibleLength(), "an unpaired surrogate between two letters")
    }
}
