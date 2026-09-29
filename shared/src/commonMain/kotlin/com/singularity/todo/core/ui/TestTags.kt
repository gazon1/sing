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
    const val TASK_EDITOR_AI_BUTTON = "task_editor_ai_button"

    // ─── Agenda ────────────────────────────────────────────────────────────

    /** Top-bar action opening the saved-agenda-views list. */
    const val AGENDA_SAVED_VIEWS_BUTTON = "agenda_saved_views_button"

    /** Top-bar action saving the current agenda definition as a new view. */
    const val AGENDA_SAVE_CURRENT_BUTTON = "agenda_save_current_button"

    /** Dynamic tag of the form `saved_agenda_card_<slug>` — a row in the views list. */
    fun savedAgendaCard(name: String) = "saved_agenda_card_${slug(name)}"

    const val SAVED_AGENDA_LIST_BACK = "saved_agenda_list_back"
    const val SAVED_AGENDA_CREATE_FAB = "saved_agenda_create_fab"
    const val SAVED_AGENDA_NAME_INPUT = "saved_agenda_name_input"
    const val SAVED_AGENDA_SAVE_BUTTON = "saved_agenda_save_button"
    const val SAVED_AGENDA_DELETE_BUTTON = "saved_agenda_delete_button"

    // ─── Pomodoro ───────────────────────────────────────────────────────────
    const val POMODORO_PHASE_LABEL = "pomodoro_phase_label"
    const val POMODORO_TIMER_LABEL = "pomodoro_timer_label"
    const val POMODORO_CYCLE_LABEL = "pomodoro_cycle_label"
    const val POMODORO_STOP_BUTTON = "pomodoro_stop_button"

    /**
     * Play/pause is one control whose meaning depends on [PomodoroState.isRunning],
     * so it is a single tag rather than a play/pause pair — a flow asserts the
     * icon via the rendered phase/timer instead.
     */
    const val POMODORO_PLAY_PAUSE_BUTTON = "pomodoro_play_pause_button"
    const val POMODORO_SKIP_BUTTON = "pomodoro_skip_button"

    /** Dynamic tag of the form `pomodoro_task_chip_<slug>` — a focus-task chip. */
    fun pomodoroTaskChip(title: String) = "pomodoro_task_chip_${slug(title)}"

    // ─── Tags ───────────────────────────────────────────────────────────────
    const val TAGS_LIST = "tags_list"
    const val TAGS_FAB = "tags_fab"

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

    /** Quick-add task field on the project detail screen. */
    const val PROJECT_DETAIL_QUICK_ADD = "project_detail_quick_add"
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
