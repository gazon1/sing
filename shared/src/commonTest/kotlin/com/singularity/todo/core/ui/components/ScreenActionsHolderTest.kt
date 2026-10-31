package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNull

@Tag("fast")
class ScreenActionsHolderTest {
    @Test
    fun actions_default_to_null() {
        assertNull(ScreenActionsHolder().actions)
    }

    @Test
    fun selection_state_defaults_to_null() {
        val holder = ScreenActionsHolder()
        assertNull(holder.selectionTitle)
        assertNull(holder.onExitSelection)
    }
}
