package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertNull

class ScreenActionsHolderTest {
    @Test
    fun actions_default_to_null() {
        assertNull(ScreenActionsHolder().actions)
    }
}
