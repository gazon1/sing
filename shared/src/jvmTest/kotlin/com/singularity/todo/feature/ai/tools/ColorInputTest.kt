package com.singularity.todo.feature.ai.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class ColorInputTest {

    private val DEFAULT = 0xFF2196F3.toInt() // ARGB blue
    private val FALLBACK = 0xFF9E9E9E.toInt() // ARGB grey

    // Compare via unsigned Long because test values like 0xFF4CAF50 overflow signed Int.
    private fun argb(int: Int): Long = int.toLong() and 0xFFFFFFFFL

    @Test fun nullStringDefaults() {
        assertEquals(argb(DEFAULT), argb(parseColor(null as String?, DEFAULT)))
    }

    @Test fun blankStringDefaults() {
        assertEquals(argb(FALLBACK), argb(parseColor("   ", FALLBACK)))
        assertEquals(argb(FALLBACK), argb(parseColor("", FALLBACK)))
    }

    @Test fun unrecognisedNonHexStringFallsBack() {
        assertEquals(argb(DEFAULT), argb(parseColor("red", DEFAULT)))
        assertEquals(argb(DEFAULT), argb(parseColor("not-a-color", DEFAULT)))
    }

    @Test fun hexWithHashSixDigitsAssumesAlphaFF() {
        assertEquals(0xFF4CAF50L, argb(parseColor("#4CAF50", DEFAULT)))
        assertEquals(0xFF000000L, argb(parseColor("#000000", DEFAULT)))
    }

    @Test fun hexWithHashEightDigitsPassesThrough() {
        assertEquals(0x804CAF50L, argb(parseColor("#804CAF50", DEFAULT)))
        assertEquals(0xFFFFFFFFL, argb(parseColor("#FFFFFFFF", DEFAULT)))
    }

    @Test fun hexWithoutHashSixDigitsAssumesAlphaFF() {
        assertEquals(0xFF4CAF50L, argb(parseColor("4CAF50", DEFAULT)))
    }

    @Test fun hexWithoutHashEightDigitsPassesThrough() {
        assertEquals(0x124CAF50L, argb(parseColor("124CAF50", DEFAULT)))
    }

    @Test fun mixedCaseHexParses() {
        assertEquals(0xFFA1B2C3L, argb(parseColor("#A1b2C3", DEFAULT)))
    }

    @Test fun decimalIntAsStringPassesThrough() {
        // ARGB(0xFF, 0x4C, 0xAF, 0x50) as a decimal string. Verified:
        //   0xFF4CAF50 in decimal = 4283215696 (too big for signed Int as literal).
        val expected = argb(0xFF4CAF50L.toInt())
        assertEquals(expected, argb(parseColor("4283215696", DEFAULT)))
    }

    @Test fun anyTypedOverloadAcceptsInt() {
        assertEquals(42, parseColor(42 as Any?, DEFAULT))
    }

    @Test fun anyTypedOverloadAcceptsLong() {
        val expected = argb(0xFF4CAF50L.toInt())
        assertEquals(expected, argb(parseColor(0xFF4CAF50L as Any?, DEFAULT)))
    }

    @Test fun anyTypedOverloadAcceptsNull() {
        assertEquals(argb(DEFAULT), argb(parseColor(null as Any?, DEFAULT)))
    }

    @Test fun anyTypedOverloadRejectsUnrelatedType() {
        assertEquals(argb(DEFAULT), argb(parseColor(3.14 as Any?, DEFAULT)))
    }
}
