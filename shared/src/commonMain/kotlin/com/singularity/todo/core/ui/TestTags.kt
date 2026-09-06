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
 * Dynamic tags (containing `${}`) are provided as functions, not constants.
 */
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
    const val MENU_SHEET = "menu_sheet"
    const val DESKTOP_SIDEBAR = "desktop_sidebar"

    /** Dynamic: nav_tab_<lowercase title> */
    fun navTab(title: String) = "nav_tab_${title.lowercase()}"

    /** Dynamic: menu_<lowercase title with underscores> */
    fun menuItem(title: String) = "menu_${title.lowercase().replace(" ", "_")}"

    // ─── Tasks ───────────────────────────────────────────────────────────────
    const val TASKS_LIST = "tasks_list"
    const val TASKS_FAB = "tasks_fab"
    const val TASKS_FILTER_CHIPS = "tasks_filter_chips"
    const val TASKS_SEARCH_BAR = "tasks_search_bar"

    /** Dynamic: task_item_<title> */
    fun taskItem(title: String) = "task_item_$title"

    /** Dynamic: task_checkbox_<title> */
    fun taskCheckbox(title: String) = "task_checkbox_$title"

    // ─── Task Editor ─────────────────────────────────────────────────────────
    const val TASK_EDITOR_TITLE_INPUT = "task_editor_title_input"
    const val TASK_EDITOR_DESCRIPTION_INPUT = "task_editor_description_input"
    const val TASK_EDITOR_DUE_DATE = "task_editor_due_date"
    const val TASK_EDITOR_DUE_TIME = "task_editor_due_time"
    const val TASK_EDITOR_REMINDER = "task_editor_reminder"
    const val TASK_EDITOR_CHECKLIST = "task_editor_checklist"
    const val TASK_EDITOR_CHECKLIST_ADD_INPUT = "task_editor_checklist_add_input"
    const val TASK_EDITOR_CHECKLIST_ADD_BUTTON = "task_editor_checklist_add_button"
    const val TASK_EDITOR_ATTACHMENTS = "task_editor_attachments"
    const val TASK_EDITOR_ADD_ATTACHMENT = "task_editor_add_attachment"
    const val TASK_EDITOR_SAVE = "task_editor_save"
    const val TASK_EDITOR_DELETE = "task_editor_delete"
    const val TASK_EDITOR_ERROR = "task_editor_error"
    const val TASK_EDITOR_NOTIFICATION_HOST = "task_editor_notification_host"

    // ─── Notes ───────────────────────────────────────────────────────────────
    const val NOTES_LIST = "notes_list"
    const val NOTES_FAB = "notes_fab"

    // ─── Tags ───────────────────────────────────────────────────────────────
    const val TAGS_LIST = "tags_list"
    const val TAGS_FAB = "tags_fab"

    // ─── Projects ──────────────────────────────────────────────────────────
    const val PROJECT_DETAIL_TOP_BAR = "project_detail_top_bar"

    // ─── Backup ───────────────────────────────────────────────────────────
    const val BACKUP_TOP_BAR_BACK = "backup_top_bar_back"

    /** Dynamic: note_item_<title> */
    fun noteItem(title: String) = "note_item_$title"

    // ─── Note Editor ────────────────────────────────────────────────────────
    const val NOTE_EDITOR_TITLE_INPUT = "note_editor_title_input"
    const val NOTE_EDITOR_BODY = "note_editor_body"
    const val NOTE_EDITOR_SAVE = "note_editor_save"
    const val NOTE_EDITOR_DELETE = "note_editor_delete"
    const val NOTE_EDITOR_MARKDOWN_TOOLBAR = "note_editor_markdown_toolbar"

    // ─── AI ─────────────────────────────────────────────────────────────────
    /** Dynamic: genui_<name> */
    fun genUi(name: String) = "genui_$name"
}
