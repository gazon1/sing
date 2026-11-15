package com.singularity.todo.core.ui

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The bundle reader's contract: the text is returned exactly as the file writes it.
 *
 * The reader used to trim, which silently turned the recurrence separator `", "`
 * into `","` — the assertion in `RecurrenceStringsResourceTest` was right and the
 * reader was wrong. These two cases pin both halves: a meaningful space survives,
 * and a label without padding is not given any.
 *
 * It does not prove what a device shows. The rendering tests in `desktopApp` own
 * that half, and they resolve the real Compose resources.
 */
@Tag("fast")
class ComposeStringsBundleTest {
    @Test
    fun `a meaningful trailing space is part of the value`() {
        assertEquals(", ", bundleString("values", "recurrence_weekday_separator"))
        assertEquals(", ", bundleString("values-ru", "recurrence_weekday_separator"))
    }

    @Test
    fun `a label is returned exactly as written`() {
        assertEquals("Back", bundleString("values", "back"))
        assertEquals("Назад", bundleString("values-ru", "back"))
    }
}
