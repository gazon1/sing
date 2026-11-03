package com.singularity.todo.core.format

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class PaddingTest {
    /**
     * The digits are the app's own.
     *
     * `"%02d".format(x)` follows the *device* locale and writes "١٤" on an Arabic
     * phone — where the app means "14". Padding by hand cannot have a locale.
     */
    @Test
    fun `padded integers keep the app's own digits`() {
        assertEquals("07", formatPadded(7))
        assertEquals("14", formatPadded(14))
        assertEquals("2026", formatPadded(2026, 4))
        assertEquals("2026", formatPadded(2026))
    }

    /** The elapsed clock counts in seconds, and seconds are long. */
    @Test
    fun `padded longs keep the app's own digits`() {
        assertEquals("07", formatPadded(7L))
        assertEquals("14", formatPadded(14L))
        assertEquals("2026", formatPadded(2026L, 4))
    }
}
