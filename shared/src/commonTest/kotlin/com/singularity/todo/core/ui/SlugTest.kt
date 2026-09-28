package com.singularity.todo.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the contract that makes dynamic test tags addressable by external
 * UI-automation tools (Maestro `id:`, UIAutomator `resource-id`).
 *
 * The regression this prevents: a user-entered title containing a space or a
 * dash used to produce a tag no selector could reliably match.
 */
class SlugTest {

    @Test
    fun `lowercases and replaces spaces`() {
        assertEquals("buy_milk", slug("Buy Milk"))
    }

    @Test
    fun `collapses runs of separators into one underscore`() {
        assertEquals("a_b", slug("a   b"))
        assertEquals("profile_sync", slug("Profile & sync"))
    }

    @Test
    fun `replaces dashes`() {
        assertEquals("my_project", slug("My-Project"))
    }

    @Test
    fun `trims separators from both ends`() {
        assertEquals("read", slug("  --read--  "))
    }

    @Test
    fun `keeps non-latin letters addressable`() {
        assertEquals("купить_хлеб", slug("Купить хлеб"))
    }

    @Test
    fun `keeps accented letters`() {
        assertEquals("café_run", slug("Café run"))
    }

    @Test
    fun `empty input yields empty slug`() {
        assertEquals("", slug(""))
        assertEquals("", slug("   "))
    }

    @Test
    fun `already-slugified input is idempotent`() {
        val once = slug("Buy Milk")
        assertEquals(once, slug(once))
    }

    @Test
    fun `dynamic tags compose a stable prefix`() {
        assertEquals("task_item_buy_milk", TestTags.taskItem("Buy Milk"))
        assertEquals("task_checkbox_buy_milk", TestTags.taskCheckbox("Buy Milk"))
        assertEquals("project_card_project_alpha", TestTags.projectCard("Project Alpha"))
        assertEquals("nav_tab_today", TestTags.navTab("Today"))
    }

    @Test
    fun `menu items are keyed by label so labels stay unique`() {
        // "Profile & sync" and "Settings" share a destination; keying the tag by
        // the destination title would collapse them into one duplicate tag.
        assertEquals("menu_profile_sync", TestTags.menuItem("Profile & sync"))
        assertEquals("menu_settings", TestTags.menuItem("Settings"))
    }
}
