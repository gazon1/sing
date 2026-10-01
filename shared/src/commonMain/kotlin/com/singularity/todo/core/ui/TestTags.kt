package com.singularity.todo.core.ui

/**
 * Test tag constants for UI test identification.
 *
 * Usage in Composable:
 *   Text("Hello", modifier = Modifier.testTag(TestTags.AUTH_EMAIL_INPUT))
 *
 * Usage in test:
 *   composeRule.onNodeWithTag(TestTags.AUTH_EMAIL_INPUT).performTextInput("user@example.com")
 *
 * Dynamic tags (containing `${}`) are provided as functions, not constants,
 * and every one of them routes its input through [slug] so that a user-entered
 * title ("Buy milk", "Café run", "Profile & sync") can never produce a tag that
 * a UI-automation selector cannot address.
 */
// The one flat namespace for every automation tag is the point of this object: a
// tag must be findable from a Compose call site, a unit test, and a Maestro flow
// without any of them learning a sub-object path. Splitting it to satisfy the
// function-count rule would break every existing call site and flow for no gain.
@Suppress("TooManyFunctions")
object TestTags {

    // ─── Auth ────────────────────────────────────────────────────────────────
    const val AUTH_EMAIL_INPUT = "auth_email_input"
    const val AUTH_PASSWORD_INPUT = "auth_password_input"
    const val AUTH_SIGN_IN_BUTTON = "auth_sign_in_button"
    const val AUTH_TOGGLE_MODE_BUTTON = "auth_toggle_mode_button"
    const val AUTH_CONTINUE_OFFLINE_BUTTON = "auth_continue_offline_button"
    const val AUTH_ERROR_TEXT = "auth_error_text"
    const val AUTH_LOADING = "auth_loading"

    // ─── Navigation ──────────────────────────────────────────────────────────
    const val NAV_MENU_BUTTON = "nav_menu_button"

    /**
     * Back arrow of the shared `BackTopAppBar`. Maestro cannot select on
     * contentDescription, and several screens route back through this component
     * with their own dirty-state confirmation, so the arrow needs a stable id.
     */
    const val TOP_BAR_BACK_BUTTON = "top_bar_back_button"
    const val MENU_SHEET = "menu_sheet"

    /** Dynamic tag of the form `settings_tab_<slug>`. */
    fun settingsTab(name: String) = "settings_tab_${slug(name)}"

    /** Dynamic tag of the form `nav_tab_<slug>`. */
    fun navTab(title: String) = "nav_tab_${slug(title)}"

    /** Dynamic: menu_<slug> — keyed by the *label* the user sees, not the destination. */
    fun menuItem(label: String) = "menu_${slug(label)}"

    // ─── Tasks ───────────────────────────────────────────────────────────────
    const val TASKS_LIST = "tasks_list"
    const val TASKS_FAB = "tasks_fab"

    /** Root container of the long-press context-menu sheet on a task row. */
    const val TASK_CONTEXT_MENU_SHEET = "task_context_menu_sheet"

    /** Dynamic tag of the form `task_item_<slug>`. */
    fun taskItem(title: String) = "task_item_${slug(title)}"

    /** Dynamic tag of the form `task_checkbox_<slug>`. */
    fun taskCheckbox(title: String) = "task_checkbox_${slug(title)}"

    // ─── Task Editor ─────────────────────────────────────────────────────────
    const val TASK_EDITOR_TITLE_INPUT = "task_editor_title_input"
    const val TASK_EDITOR_SAVE = "task_editor_save"
    const val TASK_EDITOR_MORE_MENU = "task_editor_more_menu"
    const val TASK_EDITOR_AI_BUTTON = "task_editor_ai_button"

    /** Due-date row in the task editor attribute list. */
    const val TASK_EDITOR_DUE_ROW = "task_editor_due_row"

    /** Priority row in the task editor attribute list. */
    const val TASK_EDITOR_PRIORITY_ROW = "task_editor_priority_row"

    /** Start-date row in the task editor attribute list. */
    const val TASK_EDITOR_START_DATE_ROW = "task_editor_start_date_row"

    /** Project row in the task editor attribute list. */
    const val TASK_EDITOR_PROJECT_ROW = "task_editor_project_row"

    /** Tags row in the task editor attribute list. */
    const val TASK_EDITOR_TAGS_ROW = "task_editor_tags_row"

    /** Recurrence row in the task editor attribute list. */
    const val TASK_EDITOR_RECURRENCE_ROW = "task_editor_recurrence_row"

    /** Pin toggle row in the task editor attribute list. */
    const val TASK_EDITOR_PIN_ROW = "task_editor_pin_row"

    /**
     * Priority options in the priority picker dialog — one per [TaskPriority].
     *
     * The enum has five values including `Urgent`, and the sheet renders
     * `TaskPriority.entries`, so all five are addressable here. A tag per option
     * keeps a flow from selecting on `meta.label`, which is translated copy.
     */
    const val PRIORITY_OPTION_HIGH = "priority_option_high"
    const val PRIORITY_OPTION_MEDIUM = "priority_option_medium"
    const val PRIORITY_OPTION_LOW = "priority_option_low"
    const val PRIORITY_OPTION_NONE = "priority_option_none"
    const val PRIORITY_OPTION_URGENT = "priority_option_urgent"

    // ─── Agenda ────────────────────────────────────────────────────────────
    const val AGENDA_SAVED_VIEWS_BUTTON = "agenda_saved_views_button"
    const val AGENDA_SAVE_CURRENT_BUTTON = "agenda_save_current_button"
    const val SAVED_AGENDA_LIST_BACK = "saved_agenda_list_back"
    const val SAVED_AGENDA_CREATE_FAB = "saved_agenda_create_fab"
    const val SAVED_AGENDA_NAME_INPUT = "saved_agenda_name_input"
    const val SAVED_AGENDA_SAVE_BUTTON = "saved_agenda_save_button"
    const val SAVED_AGENDA_DELETE_BUTTON = "saved_agenda_delete_button"

    /** Dynamic tag of the form `saved_agenda_card_<slug>`. */
    fun savedAgendaCard(name: String) = "saved_agenda_card_${slug(name)}"

    /** Agenda section header, e.g. "Today", "No Date". */
    fun agendaSection(name: String) = "agenda_section_${slug(name)}"

    // ─── Pomodoro ───────────────────────────────────────────────────────────

    object Pomodoro {
        const val PHASE_LABEL = "pomodoro_phase_label"
        const val TIMER_LABEL = "pomodoro_timer_label"
        const val CYCLE_LABEL = "pomodoro_cycle_label"
        const val STOP_BUTTON = "pomodoro_stop_button"
        const val SKIP_BUTTON = "pomodoro_skip_button"

        /** Play icon — shown when the timer is paused. */
        const val PLAY_BUTTON = "pomodoro_play_button"

        /** Pause icon — shown when the timer is running. */
        const val PAUSE_BUTTON = "pomodoro_pause_button"
    }

    /** Dynamic tag of the form `pomodoro_task_chip_<slug>`. */
    fun pomodoroTaskChip(title: String) = "pomodoro_task_chip_${slug(title)}"

    // ─── Tags ───────────────────────────────────────────────────────────────
    const val TAGS_LIST = "tags_list"
    const val TAGS_FAB = "tags_fab"

    /** Dynamic tag of the form `tag_rename_<slug>` — the pencil on a tag card. */
    fun tagRename(tagName: String) = "tag_rename_${slug(tagName)}"

    // ─── Notes ───────────────────────────────────────────────────────────────
    const val NOTES_LIST = "notes_list"
    const val NOTES_QUICK_ADD_INPUT = "notes_quick_add_input"

    /**
     * Dynamic tag of the form `note_item_<slug>`, keyed by note id.
     */
    fun noteItem(id: String) = "note_item_${slug(id)}"

    /**
     * Dynamic tag of the form `note_item_by_title_<slug>`, keyed by the visible title.
     */
    fun noteItemByTitle(title: String) = "note_item_by_title_${slug(title)}"

    // ─── Note Editor ────────────────────────────────────────────────────────
    const val NOTE_EDITOR_TITLE_INPUT = "note_editor_title_input"
    const val NOTE_EDITOR_BODY = "note_editor_body"
    const val NOTE_EDITOR_SAVE = "note_editor_save"
    const val NOTE_EDITOR_NOTIFICATION_HOST = "note_editor_notification_host"

    // ─── Note Preview ────────────────────────────────────────────────────────
    const val NOTES_BACKLINKS_BUTTON = "notes_backlinks_button"

    // ─── Search ─────────────────────────────────────────────────────────────
    const val SEARCH_INPUT = "search_input"

    // ─── Projects ───────────────────────────────────────────────────────────

    /** Quick-add task field on the project detail screen. */
    const val PROJECT_DETAIL_QUICK_ADD = "project_detail_quick_add"
    const val PROJECT_EDITOR_NAME_INPUT = "project_editor_name_input"
    const val PROJECT_EDITOR_DESCRIPTION_INPUT = "project_editor_description_input"
    const val PROJECT_EDITOR_SAVE = "project_editor_save"
    const val PROJECT_EDITOR_BACK = "project_editor_back"
    const val PROJECT_EDITOR_NOTIFICATION_HOST = "project_editor_notification_host"

    /** Dynamic tag of the form `project_card_<slug>`. */
    fun projectCard(name: String) = "project_card_${slug(name)}"

    // ─── Settings ──────────────────────────────────────────────────────────

    object Settings {
        /** The Dark Theme toggle row's outer Row (clickable, semantic Role.Switch). */
        const val DARK_THEME_SWITCH = "settings_dark_theme_switch"

        /**
         * Content-visible marker for a Settings tab's main content area.
         * Use after tapping `settings_tab_<slug>` to assert the tab rendered.
         */
        fun content(tab: String) = "settings_content_${slug(tab)}"
    }

    // ─── Dialog ──────────────────────────────────────────────────────────────

    /**
     * Dialog buttons shared across `ConfirmActionDialog`, `TaskEditorDiscardDialog`,
     * and other AlertDialog-based components.
     */
    object Dialog {
        /** Positive / destructive confirmation ("Delete", "Discard", "OK"). */
        const val CONFIRM = "dialog_confirm"

        /** Cancel / dismiss ("Cancel", "Keep editing"). */
        const val DISMISS = "dialog_dismiss"

        /** The title of a dialog (e.g. "Discard changes?", "Delete view?"). */
        fun title(key: String) = "dialog_title_${slug(key)}"
    }

    /**
     * App-owned date-picker sheet buttons (the Material3 DatePicker has no testTags
     * on its internal day cells or navigation controls).
     */
    object DatePicker {
        const val OK = "dialog_date_picker_ok"
        const val CANCEL = "dialog_date_picker_cancel"
        const val CLEAR = "dialog_date_picker_clear"
    }

    // ─── Editor Overflow menu ─────────────────────────────────────────────

    /**
     * Actions in the task detail / task editor overflow menu (three-dot menu).
     * These are distinct from [TaskContextMenuSheet] which is the long-press bottom sheet.
     */
    object EditorOverflow {
        const val ARCHIVE = "overflow_archive"
        const val DELETE = "overflow_delete"
        const val RESTORE = "overflow_restore"
        const val PIN = "overflow_pin"
        const val UNPIN = "overflow_unpin"
    }

    // ─── Long-press context menu sheet ─────────────────────────────────────

    /**
     * Dynamic tag of the form `task_action_<slug>` — a row in the long-press
     * bottom sheet ([TaskContextMenuSheet]).
     *
     * Actions are sourced from the [TaskAction] domain enum so that the sheet
     * and the editor overflow menu share a single identifier namespace.
     */
    fun taskAction(action: String) = "task_action_${slug(action)}"

    // ─── Snackbar / transient UI ───────────────────────────────────────────

    /** The "Saved" [ResultDialog] shown after a save in editors and the saved-agenda screen. */
    const val SNACKBAR_SAVED = "snackbar_saved"

    // ─── Notification hosts ─────────────────────────────────────────────────
    //
    // These tag the invisible container a screen parks transient ResultDialogs in.
    // They are not clickable and carry no visible text; the tag exists so a test
    // can assert a dialog was (or was not) shown without matching its copy.

    const val PROJECTS_NOTIFICATION_HOST = "projects_notification_host"
    const val CHAT_NOTIFICATION_HOST = "chat_notification_host"
    const val ARCHIVE_NOTIFICATION_HOST = "archive_notification_host"

    // ─── Backup ─────────────────────────────────────────────────────────────
    const val BACKUP_TOP_BAR_BACK = "backup_top_bar_back"
    const val BACKUP_CREATE_BUTTON = "backup_create_button"
    const val BACKUP_RESTORE_BUTTON = "backup_restore_button"
    const val BACKUP_EXPORT_SETTINGS = "backup_export_settings"
    const val BACKUP_IMPORT_SETTINGS = "backup_import_settings"

    // ─── Profile ────────────────────────────────────────────────────────────
    const val PROFILE_CREATE_BUTTON = "profile_create_button"
    const val PROFILE_CREATE_NAME_INPUT = "profile_create_name_input"
    const val PROFILE_ITEM_PREFIX = "profile_item_"

    fun profileItem(name: String) = "${PROFILE_ITEM_PREFIX}${slug(name)}"

    // ─── AI ─────────────────────────────────────────────────────────────────
    fun genUi(name: String) = "genui_${slug(name)}"

    // ─── Calendar ──────────────────────────────────────────────────────────

    /**
     * A day cell in the calendar month grid, tagged by ISO local date.
     * Format: `calendar_day_2026_09_15`.
     */
    fun calendarDay(isoDate: String) = "calendar_day_${isoDate.replace("-", "_")}"
}

/**
 * Normalizes a dynamic tag fragment so it is usable both as a Compose
 * `Modifier.testTag` and as an external UI-automation selector (Maestro `id:`,
 * UIAutomator `resource-id`).
 *
 * Rules: lowercase, collapse every run of non-alphanumeric characters into a
 * single `_`, trim separators from both ends. `isLetterOrDigit` is
 * Unicode-aware, so non-Latin titles stay addressable rather than collapsing to
 * an empty tag.
 *
 * ASCII-only test *data* remains the project default — see
 * `.agents/skills/singularity-todo-maestro-flows/SKILL.md`. This function is the
 * safety net, not an invitation to type Cyrillic into flows.
 */
internal fun slug(input: String): String = buildString {
    var pendingSeparator = false
    input.trim().lowercase().forEach { char ->
        if (char.isLetterOrDigit()) {
            if (pendingSeparator && isNotEmpty()) {
                append('_')
            }
            append(char)
            pendingSeparator = false
        } else {
            pendingSeparator = true
        }
    }
    if (isEmpty()) append("untitled")
}
