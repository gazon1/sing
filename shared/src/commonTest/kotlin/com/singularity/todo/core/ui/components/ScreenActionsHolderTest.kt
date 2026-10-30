package com.singularity.todo.core.ui.components

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenActionsHolderTest {
    @Test
    fun actions_default_to_null() {
        assertNull(ScreenActionsHolder().actions)
    }

    @Test
    fun override_toggle_clears_published_slots() {
        val holder = ScreenActionsHolder()
        // Enter selection mode: publish override slots.
        holder.title = { }
        holder.navigationIcon = { }
        holder.colors = null
        holder.actions = { }
        holder.override = true
        assertTrue(holder.override)
        // Exit selection mode: clear everything, mirroring TopAppBar onDispose
        // and AndroidShellNav3's per-navigation reset.
        holder.title = null
        holder.navigationIcon = null
        holder.colors = null
        holder.actions = null
        holder.override = false
        assertFalse(holder.override)
        assertNull(holder.title)
        assertNull(holder.navigationIcon)
        assertNull(holder.colors)
        assertNull(holder.actions)
    }
}
