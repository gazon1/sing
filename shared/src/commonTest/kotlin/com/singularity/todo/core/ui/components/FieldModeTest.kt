package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldModeTest {

    @Test fun `View is singleton`() {
        assertEquals(FieldMode.View, FieldMode.View)
    }

    @Test fun `Edit carries draft text`() {
        val mode = FieldMode.Edit("draft text")
        assertEquals("draft text", mode.draft)
    }

    @Test fun `Edit with empty draft represents cleared input`() {
        val mode = FieldMode.Edit("")
        assertTrue(mode.draft.isEmpty())
    }

    @Test fun `View and Edit are distinct`() {
        val view: FieldMode = FieldMode.View
        val edit: FieldMode = FieldMode.Edit("anything")
        assertTrue(view != edit)
    }
}
