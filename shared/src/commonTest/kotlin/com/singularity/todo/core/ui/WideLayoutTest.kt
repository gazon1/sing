package com.singularity.todo.core.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The breakpoint the wide layout is decided by.
 *
 * The boundary is the whole contract: a window one dp too narrow must stay in
 * the single-pane layout, and the breakpoint itself must be wide. A `>=` that
 * drifted to `>` would leave a window at exactly 840 dp without a pane, and no
 * screen would say why.
 */
class WideLayoutTest {
    @Test
    fun a_window_narrower_than_the_breakpoint_is_not_wide() {
        assertFalse(isWideLayout(WideLayoutMinWidth - 1.dp))
        assertFalse(isWideLayout(WideLayoutMinWidth / 2))
    }

    @Test
    fun the_breakpoint_itself_is_wide() {
        assertTrue(isWideLayout(WideLayoutMinWidth))
    }

    @Test
    fun a_window_wider_than_the_breakpoint_is_wide() {
        assertTrue(isWideLayout(WideLayoutMinWidth + 1.dp))
    }
}
