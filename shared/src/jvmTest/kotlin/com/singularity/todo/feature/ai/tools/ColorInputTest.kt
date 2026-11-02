package com.singularity.todo.feature.ai.tools

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class ColorInputTest {
    private val defaultColor = 0xFF2196F3.toInt() // ARGB blue
    private val fallbackColor = 0xFF9E9E9E.toInt() // ARGB grey

    // Compare via unsigned Long because test values like 0xFF4CAF50 overflow signed Int.
    private fun argb(int: Int): Long = int.toLong() and 0xFFFFFFFFL

    @Test fun nullStringDefaults() {
        assertEquals(argb(defaultColor), argb(parseColor(null as String?, defaultColor)))
    }

    @Test fun blankStringDefaults() {
        assertEquals(argb(fallbackColor), argb(parseColor("   ", fallbackColor)))
        assertEquals(argb(fallbackColor), argb(parseColor("", fallbackColor)))
    }

    @Test fun unrecognisedNonHexStringFallsBack() {
        assertEquals(argb(defaultColor), argb(parseColor("red", defaultColor)))
        assertEquals(argb(defaultColor), argb(parseColor("not-a-color", defaultColor)))
    }

    @Test fun hexWithHashSixDigitsAssumesAlphaFF() {
        assertEquals(0xFF4CAF50L, argb(parseColor("#4CAF50", defaultColor)))
        assertEquals(0xFF000000L, argb(parseColor("#000000", defaultColor)))
    }

    @Test fun hexWithHashEightDigitsPassesThrough() {
        assertEquals(0x804CAF50L, argb(parseColor("#804CAF50", defaultColor)))
        assertEquals(0xFFFFFFFFL, argb(parseColor("#FFFFFFFF", defaultColor)))
    }

    @Test fun hexWithoutHashSixDigitsAssumesAlphaFF() {
        assertEquals(0xFF4CAF50L, argb(parseColor("4CAF50", defaultColor)))
    }

    @Test fun hexWithoutHashEightDigitsPassesThrough() {
        assertEquals(0x124CAF50L, argb(parseColor("124CAF50", defaultColor)))
    }

    @Test fun mixedCaseHexParses() {
        assertEquals(0xFFA1B2C3L, argb(parseColor("#A1b2C3", defaultColor)))
    }

    @Test fun decimalIntAsStringPassesThrough() {
        // ARGB(0xFF, 0x4C, 0xAF, 0x50) as a decimal string. Verified:
        //   0xFF4CAF50 in decimal = 4283215696 (too big for signed Int as literal).
        val expected = argb(0xFF4CAF50L.toInt())
        assertEquals(expected, argb(parseColor("4283215696", defaultColor)))
    }

    @Test fun anyTypedOverloadAcceptsInt() {
        assertEquals(42, parseColor(42 as Any?, defaultColor))
    }

    @Test fun anyTypedOverloadAcceptsLong() {
        val expected = argb(0xFF4CAF50L.toInt())
        assertEquals(expected, argb(parseColor(0xFF4CAF50L as Any?, defaultColor)))
    }

    @Test fun anyTypedOverloadAcceptsNull() {
        assertEquals(argb(defaultColor), argb(parseColor(null as Any?, defaultColor)))
    }

    @Test fun anyTypedOverloadRejectsUnrelatedType() {
        assertEquals(argb(defaultColor), argb(parseColor(3.14 as Any?, defaultColor)))
    }
}
