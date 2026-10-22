package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a back press means, on a screen that can hold the keyboard.
 *
 * The decision is a pure function so that it can be asserted without a device:
 * the behaviour it describes was measured on a phone, and the part that can be
 * checked everywhere is the part that decides.
 */
@Tag("fast")
class BackActionTest {

    @Test
    fun `the keyboard open means the keyboard is what back is for`() {
        assertEquals(
            BackAction.HideKeyboard,
            backAction(imeVisible = true),
            "a back press with the keyboard open must close the keyboard, not the screen",
        )
    }

    @Test
    fun `the keyboard closed means the screen's own meaning applies`() {
        assertEquals(
            BackAction.Leave,
            backAction(imeVisible = false),
            "a back press with the keyboard closed is the screen's own back press",
        )
    }
}
