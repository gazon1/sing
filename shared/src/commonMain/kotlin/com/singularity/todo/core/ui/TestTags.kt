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

    /** Dynamic tag of the form `settings_tab_<slug>`. */
    fun settingsTab(name: String) = "settings_tab_${slug(name)}"

    /** Dynamic tag of the form `nav_tab_<slug>`. */
    fun navTab(title: String) = "nav_tab_${slug(title)}"

    /** Dynamic: menu_<slug> — keyed by the *label* the user sees, not the destination. */
    fun menuItem(label: String) = "menu_${slug(label)}"

    /** Dynamic tag of the form `sheet_item_<slug>` — a row in a context-menu bottom sheet. */
    fun sheetItem(label: String) = "sheet_item_${slug(label)}"

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

    // ─── Tags ───────────────────────────────────────────────────────────────
    const val TAGS_LIST = "tags_list"

    // ─── Notes ───────────────────────────────────────────────────────────────
    const val NOTES_LIST = "notes_list"
    const val NOTES_QUICK_ADD_INPUT = "notes_quick_add_input"

    /**
     * Dynamic tag of the form `note_item_<slug>`, keyed by note id.
     *
     * Identity-based: correct for tests that already hold a [com.singularity.todo.feature.notes.domain.model.NoteId]
     * (see `desktopApp`'s `NotesScreenTest`). A ULID is not knowable in advance, so UI
     * automation that has just created a note cannot use this — see [noteItemByTitle].
     */
    fun noteItem(id: String) = "note_item_${slug(id)}"

    /**
     * Dynamic tag of the form `note_item_by_title_<slug>`, keyed by the visible title.
     *
     * This is the addressable one for end-to-end automation, which knows the title it
     * typed but never the generated id. Empty titles produce a bare
     * `note_item_by_title_` prefix, so a flow should create a titled note.
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
    const val PROJECT_EDITOR_NAME_INPUT = "project_editor_name_input"
    const val PROJECT_EDITOR_DESCRIPTION_INPUT = "project_editor_description_input"
    const val PROJECT_EDITOR_SAVE = "project_editor_save"
    const val PROJECT_EDITOR_BACK = "project_editor_back"
    const val PROJECT_EDITOR_NOTIFICATION_HOST = "project_editor_notification_host"

    /** Dynamic tag of the form `project_card_<slug>`. */
    fun projectCard(name: String) = "project_card_${slug(name)}"

    // ─── Backup ─────────────────────────────────────────────────────────────
    const val BACKUP_TOP_BAR_BACK = "backup_top_bar_back"

    // ─── AI ─────────────────────────────────────────────────────────────────

    /** Dynamic tag of the form `genui_<slug>`. */
    fun genUi(name: String) = "genui_${slug(name)}"
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
}
