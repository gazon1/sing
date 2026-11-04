package com.singularity.todo.core.ui.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sheet type shaped like the ones the screens declare: a sealed hierarchy whose
 * variants are `@Serializable`, which is what [rememberOverlayState] requires of a
 * sheet it is asked to save.
 */
@Serializable
private sealed interface SavedSheet {
    @Serializable
    data object PickColor : SavedSheet

    @Serializable
    data class PickParent(val projectId: String) : SavedSheet
}

/** Stands in for the scope `rememberSaveable` hands a [Saver]. */
private object SavingScope : SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}

/** Writes a holder through its saver, the way `rememberSaveable` does. */
private fun <S : Any> Saver<OverlayState<S>, String>.encode(state: OverlayState<S>): String? =
    with(SavingScope) { save(state) }

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

    private fun saver() = overlayStateSaver(SavedSheet.serializer())

    private fun roundTrip(state: OverlayState<SavedSheet>): OverlayState<SavedSheet> {
        val saver = saver()
        return saver.restore(saver.encode(state)!!)!!
    }

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

    // ─── What survives a rotation and process death ────────────────────────────

    @Test
    fun `a restored holder reopens the sheet it was showing`() {
        val original = OverlayState<SavedSheet>()
        original.show(SavedSheet.PickParent(projectId = "project-1"))

        val restored = roundTrip(original)

        assertEquals(SavedSheet.PickParent(projectId = "project-1"), restored.sheet)
        assertFalse(restored.isOverflowOpen)
    }

    @Test
    fun `a restored holder reopens a payload-free sheet`() {
        val original = OverlayState<SavedSheet>()
        original.show(SavedSheet.PickColor)

        val restored = roundTrip(original)

        assertEquals(SavedSheet.PickColor, restored.sheet)
    }

    @Test
    fun `a restored holder keeps the overflow menu open`() {
        val original = OverlayState<SavedSheet>()
        original.toggleOverflow()

        val restored = roundTrip(original)

        assertTrue(restored.isOverflowOpen)
        assertNull(restored.sheet)
    }

    @Test
    fun `a restored holder brings a new, empty snackbar host`() {
        val original = OverlayState<SavedSheet>()

        val restored = roundTrip(original)

        // A snackbar is a message about something that already happened; replaying
        // it into a restored screen would say it twice.
        assertNotSame(original.snackbarHostState, restored.snackbarHostState)
        assertNull(restored.snackbarHostState.currentSnackbarData)
    }

    @Test
    fun `a saved value that cannot be read restores nothing instead of throwing`() {
        val restored = saver().restore("{ not a saved overlay")!!

        assertNull(restored.sheet)
        assertFalse(restored.isOverflowOpen)
    }
}
