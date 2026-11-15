package com.singularity.todo.core.ui

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The bundle reader's contract: trimming is the default, and a value whose
 * whitespace is the point asks for it untrimmed.
 *
 * The recurrence separator `", "` used to be read as `","` by the default trim,
 * and `RecurrenceStringsResourceTest` asked for the space. These two cases pin
 * both halves: the opt-out keeps a meaningful trailing space, and the default
 * still gives a plain label without padding.
 *
 * It does not prove what a device shows. The rendering tests in `desktopApp` own
 * that half, and they resolve the real Compose resources.
 */
@Tag("fast")
class ComposeStringsBundleTest {
    @Test
    fun `the opt-out keeps a meaningful trailing space`() {
        assertEquals(", ", bundleString("values", "recurrence_weekday_separator", trim = false))
        assertEquals(", ", bundleString("values-ru", "recurrence_weekday_separator", trim = false))
    }

    @Test
    fun `the default returns a label without padding`() {
        assertEquals("Back", bundleString("values", "back"))
        assertEquals("Назад", bundleString("values-ru", "back"))
    }
}
