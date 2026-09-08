package com.singularity.todo.feature.ai.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class ColorInputTest {

    private val DEFAULT = 0xFF2196F3.toInt()       // ARGB blue
    private val FALLBACK = 0xFF9E9E9E.toInt()      // ARGB grey

    // Compare via unsigned Long because test values like 0xFF4CAF50 overflow signed Int.
    private fun argb(int: Int): Long = int.toLong() and 0xFFFFFFFFL

    @Test fun `null String defaults`() {
        assertEquals(argb(DEFAULT), argb(parseColor(null as String?, DEFAULT)))
    }

    @Test fun `blank String defaults`() {
        assertEquals(argb(FALLBACK), argb(parseColor("   ", FALLBACK)))
        assertEquals(argb(FALLBACK), argb(parseColor("", FALLBACK)))
    }

    @Test fun `unrecognised non-hex string falls back`() {
        assertEquals(argb(DEFAULT), argb(parseColor("red", DEFAULT)))
        assertEquals(argb(DEFAULT), argb(parseColor("not-a-color", DEFAULT)))
    }

    @Test fun `hex with hash six digits assumes alpha FF`() {
        assertEquals(0xFF4CAF50L, argb(parseColor("#4CAF50", DEFAULT)))
        assertEquals(0xFF000000L, argb(parseColor("#000000", DEFAULT)))
    }

    @Test fun `hex with hash eight digits passes through`() {
        assertEquals(0x804CAF50L, argb(parseColor("#804CAF50", DEFAULT)))
        assertEquals(0xFFFFFFFFL, argb(parseColor("#FFFFFFFF", DEFAULT)))
    }

    @Test fun `hex without hash six digits assumes alpha FF`() {
        assertEquals(0xFF4CAF50L, argb(parseColor("4CAF50", DEFAULT)))
    }

    @Test fun `hex without hash eight digits passes through`() {
        assertEquals(0x124CAF50L, argb(parseColor("124CAF50", DEFAULT)))
    }

    @Test fun `mixed case hex parses`() {
        assertEquals(0xFFA1B2C3L, argb(parseColor("#A1b2C3", DEFAULT)))
    }

    @Test fun `decimal int as string passes through`() {
        // ARGB(0xFF, 0x4C, 0xAF, 0x50) as a decimal string. Verified:
        //   0xFF4CAF50 in decimal = 4283215696 (too big for signed Int as literal).
        val expected = argb(0xFF4CAF50L.toInt())
        assertEquals(expected, argb(parseColor("4283215696", DEFAULT)))
    }

    @Test fun `Any-typed overload accepts Int`() {
        assertEquals(42, parseColor(42 as Any?, DEFAULT))
    }

    @Test fun `Any-typed overload accepts Long`() {
        val expected = argb(0xFF4CAF50L.toInt())
        assertEquals(expected, argb(parseColor(0xFF4CAF50L as Any?, DEFAULT)))
    }

    @Test fun `Any-typed overload accepts null`() {
        assertEquals(argb(DEFAULT), argb(parseColor(null as Any?, DEFAULT)))
    }

    @Test fun `Any-typed overload rejects unrelated type`() {
        assertEquals(argb(DEFAULT), argb(parseColor(3.14 as Any?, DEFAULT)))
    }
}
