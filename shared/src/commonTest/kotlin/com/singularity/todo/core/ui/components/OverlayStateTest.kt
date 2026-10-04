package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [OverlayState] — sheet/menu/snackbar coordinator.
 */
@Tag("fast")
class OverlayStateTest {

    private sealed class Sheet {
        data object PickColor : Sheet()
        data object PickIcon : Sheet()
    }

    private fun fresh() = OverlayState<Sheet>()

    @Test
    fun `initial state is all nulls`() {
        val s = fresh()
        assertNull(s.sheet)
        assertFalse(s.isOverflowOpen)
    }

    @Test
    fun `show sheet sets active and clears overflow`() {
        val s = fresh()
        s.show(Sheet.PickColor)

        assertEquals(Sheet.PickColor, s.sheet)
        assertFalse(s.isOverflowOpen)
    }

    @Test
    fun `show sheet while overflow open clears overflow first`() {
        val s = fresh()
        s.toggleOverflow()
        assertTrue(s.isOverflowOpen)

        s.show(Sheet.PickIcon)

        assertEquals(Sheet.PickIcon, s.sheet)
        assertFalse(s.isOverflowOpen)
    }

    @Test
    fun `dismissSheet clears active`() {
        val s = fresh()
        s.show(Sheet.PickColor)
        s.dismissSheet()

        assertNull(s.sheet)
        assertFalse(s.isOverflowOpen)
    }

    @Test
    fun `toggleOverflow flips state`() {
        val s = fresh()

        s.toggleOverflow()
        assertTrue(s.isOverflowOpen)

        s.toggleOverflow()
        assertFalse(s.isOverflowOpen)
    }

    @Test
    fun `toggleOverflow opening clears active sheet`() {
        val s = fresh()
        s.show(Sheet.PickColor)

        s.toggleOverflow()

        // Opening overflow hides the sheet; overflow is now open
        assertTrue(s.isOverflowOpen)
        assertNull(s.sheet)
    }

    @Test
    fun `dismissOverflow clears overflow only`() {
        val s = fresh()
        s.show(Sheet.PickColor)
        s.toggleOverflow() // opens overflow, clears sheet

        s.dismissOverflow()

        // dismissOverflow only closes the overflow menu — sheet was already cleared
        assertFalse(s.isOverflowOpen)
        assertNull(s.sheet)
    }

    @Test
    fun `dismissAll clears everything`() {
        val s = fresh()
        s.show(Sheet.PickIcon)
        s.toggleOverflow()

        s.dismissAll()

        assertNull(s.sheet)
        assertFalse(s.isOverflowOpen)
    }

    @Test
    fun `snackbarHostState is always available`() {
        val s = fresh()
        // Just verify it doesn't throw and is the same instance
        assertEquals(s.snackbarHostState, s.snackbarHostState)
    }
}
