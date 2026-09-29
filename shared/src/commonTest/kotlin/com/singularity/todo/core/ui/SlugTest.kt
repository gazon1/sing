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
    fun `empty or blank input yields untitled`() {
        assertEquals("untitled", slug(""))
        assertEquals("untitled", slug("   "))
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

    @Test
    fun `settings tags use the Settings object`() {
        assertEquals("settings_dark_theme_switch", TestTags.Settings.DARK_THEME_SWITCH)
        assertEquals("settings_content_interface", TestTags.Settings.content("Interface"))
        assertEquals("settings_content_work_schedule", TestTags.Settings.content("Work Schedule"))
    }

    @Test
    fun `dialog tags use the Dialog object`() {
        assertEquals("dialog_confirm", TestTags.Dialog.CONFIRM)
        assertEquals("dialog_dismiss", TestTags.Dialog.DISMISS)
        assertEquals("dialog_title_discard_changes", TestTags.Dialog.title("Discard changes?"))
        assertEquals("dialog_date_picker_ok", TestTags.DatePicker.OK)
        assertEquals("dialog_date_picker_cancel", TestTags.DatePicker.CANCEL)
        assertEquals("dialog_date_picker_clear", TestTags.DatePicker.CLEAR)
    }

    @Test
    fun `editor overflow tags use the EditorOverflow object`() {
        assertEquals("overflow_archive", TestTags.EditorOverflow.ARCHIVE)
        assertEquals("overflow_delete", TestTags.EditorOverflow.DELETE)
        assertEquals("overflow_restore", TestTags.EditorOverflow.RESTORE)
        assertEquals("overflow_pin", TestTags.EditorOverflow.PIN)
        assertEquals("overflow_unpin", TestTags.EditorOverflow.UNPIN)
    }

    @Test
    fun `task actions use the taskAction function`() {
        assertEquals("task_action_archive", TestTags.taskAction("Archive"))
        assertEquals("task_action_mark_as_completed", TestTags.taskAction("Mark as completed"))
        assertEquals("task_action_open", TestTags.taskAction("Open"))
    }

    @Test
    fun `agenda section tags use the agendaSection function`() {
        assertEquals("agenda_section_no_date", TestTags.agendaSection("No Date"))
        assertEquals("agenda_section_today", TestTags.agendaSection("Today"))
        assertEquals("agenda_section_upcoming", TestTags.agendaSection("Upcoming"))
    }

    @Test
    fun `calendar day tags use the calendarDay function`() {
        assertEquals("calendar_day_2026_09_15", TestTags.calendarDay("2026-09-15"))
        assertEquals("calendar_day_2026_01_01", TestTags.calendarDay("2026-01-01"))
    }

    @Test
    fun `pomodoro tags use the Pomodoro object`() {
        assertEquals("pomodoro_phase_label", TestTags.Pomodoro.PHASE_LABEL)
        assertEquals("pomodoro_play_button", TestTags.Pomodoro.PLAY_BUTTON)
        assertEquals("pomodoro_pause_button", TestTags.Pomodoro.PAUSE_BUTTON)
        assertEquals("pomodoro_timer_label", TestTags.Pomodoro.TIMER_LABEL)
        assertEquals("pomodoro_cycle_label", TestTags.Pomodoro.CYCLE_LABEL)
        assertEquals("pomodoro_stop_button", TestTags.Pomodoro.STOP_BUTTON)
        assertEquals("pomodoro_skip_button", TestTags.Pomodoro.SKIP_BUTTON)
    }

    @Test
    fun `snackbar saved tag is a constant`() {
        assertEquals("snackbar_saved", TestTags.SNACKBAR_SAVED)
    }

    @Test
    fun `task editor row tags are constants`() {
        assertEquals("task_editor_due_row", TestTags.TASK_EDITOR_DUE_ROW)
        assertEquals("task_editor_priority_row", TestTags.TASK_EDITOR_PRIORITY_ROW)
    }

    @Test
    fun `priority options are constants`() {
        assertEquals("priority_option_high", TestTags.PRIORITY_OPTION_HIGH)
        assertEquals("priority_option_medium", TestTags.PRIORITY_OPTION_MEDIUM)
        assertEquals("priority_option_low", TestTags.PRIORITY_OPTION_LOW)
        assertEquals("priority_option_none", TestTags.PRIORITY_OPTION_NONE)
    }
}
