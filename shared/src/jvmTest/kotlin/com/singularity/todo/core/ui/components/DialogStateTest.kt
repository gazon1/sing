package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DialogStateTest {

    private sealed interface TestDialog {
        data object Alpha : TestDialog
        data class Bravo(val value: String) : TestDialog
    }

    @Test
    fun `show then active returns the dialog`() {
        val state = DialogState<TestDialog>()
        state.show(TestDialog.Alpha)
        assertEquals(TestDialog.Alpha, state.active)
    }

    @Test
    fun `dismiss then active returns null`() {
        val state = DialogState<TestDialog>()
        state.show(TestDialog.Alpha)
        state.dismiss()
        assertNull(state.active)
    }

    @Test
    fun `dismiss then show other dialog returns other`() {
        val state = DialogState<TestDialog>()
        state.show(TestDialog.Alpha)
        state.dismiss()
        state.show(TestDialog.Bravo("hello"))
        val active = state.active
        assertIs<TestDialog.Bravo>(active)
        assertEquals("hello", (active as TestDialog.Bravo).value)
    }

    @Test
    fun `data class variant payload retained through show`() {
        val state = DialogState<TestDialog>()
        state.show(TestDialog.Bravo("world"))
        val active = state.active
        assertIs<TestDialog.Bravo>(active)
        assertEquals("world", (active as TestDialog.Bravo).value)
    }

    @Test
    fun `show replaces previous dialog`() {
        val state = DialogState<TestDialog>()
        state.show(TestDialog.Alpha)
        state.show(TestDialog.Bravo("x"))
        val active = state.active
        assertIs<TestDialog.Bravo>(active)
        assertEquals("x", (active as TestDialog.Bravo).value)
    }
}
