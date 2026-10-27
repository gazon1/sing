package com.singularity.todo.core.ui

import com.singularity.todo.core.reminders.ReminderOffset
import kotlinx.datetime.DayOfWeek

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
 *
 * ## Adding a tag: regenerate the catalog
 *
 * `TestTagsCatalogJvmTest` fails when `Maestro/TAGS.md` disagrees with this
 * object, so a new constant is not usable until the catalog is rewritten:
 *
 * ```
 * ./gradlew :shared:jvmTest -PupdateGoldens=true --tests "*TestTagsCatalogJvmTest*"
 * ```
 *
 * then re-run without `-PupdateGoldens` — that second run must pass on a clean
 * checkout, which is what proves the file was actually written rather than the
 * test being skipped. Budget a couple of minutes; the cost is easy to forget and
 * the failure message points at the generated section rather than at the step
 * that fixes it.
 *
 * ### The fast check, before the JVM
 *
 * ```
 * python3 scripts/check-test-tags-catalog.py --check
 * ```
 *
 * The same comparison without the JVM: it parses this object and the generated
 * section of `Maestro/TAGS.md` and says in a second whether they still agree.
 * Run it right after adding a constant — it costs a second, and it is what
 * tells you *that* the catalogue is stale before you spend minutes finding out.
 * The pre-push hook runs it for you whenever the push touches this file.
 */
// The one flat namespace for every automation tag is the point of this object: a
// tag must be findable from a Compose call site, a unit test, and a Maestro flow
// without any of them learning a sub-object path. Splitting it to satisfy the
// function-count rule would break every existing call site and flow for no gain.
@Suppress("TooManyFunctions")
object TestTags {
    // ─── Global ──────────────────────────────────────────────────────────────

    /**
     * The spinner a screen shows while it has nothing to display yet.
     *
     * Untagged until a real bug needed it: `AuthGuard` renders [LoadingIndicator]
     * for the whole of session restore, and with no tag on it a launch stuck on
     * that branch could not be asserted on — a UI test could only check that
     * something eventually appeared, never that the spinner had gone. A test that
     * cannot name the failure cannot catch its return.
     */
    const val LOADING_INDICATOR = "loading_indicator"

    // ─── Auth ────────────────────────────────────────────────────────────────
    const val AUTH_EMAIL_INPUT = "auth_email_input"
    const val AUTH_PASSWORD_INPUT = "auth_password_input"
    const val AUTH_SIGN_IN_BUTTON = "auth_sign_in_button"
    const val AUTH_SIGN_OUT_BUTTON = "auth_sign_out_button"
    const val AUTH_TOGGLE_MODE_BUTTON = "auth_toggle_mode_button"
    const val AUTH_CONTINUE_OFFLINE_BUTTON = "auth_continue_offline_button"
    const val AUTH_ERROR_TEXT = "auth_error_text"

    /** A neutral "you have to do something" message, as opposed to a failure. */
    const val AUTH_MESSAGE_TEXT = "auth_message_text"
    const val AUTH_LOADING = "auth_loading"
    const val AUTH_CONFIRMATION_HEADLINE = "auth_confirmation_headline"
    const val AUTH_CONFIRMATION_EMAIL = "auth_confirmation_email"
    const val AUTH_RESEND_BUTTON = "auth_resend_button"
    const val AUTH_BACK_BUTTON = "auth_back_button"
    const val AUTH_SERVER_URL_INPUT = "auth_server_url_input"
    const val AUTH_SERVER_KEY_INPUT = "auth_server_key_input"
    const val AUTH_SAVE_SERVER_BUTTON = "auth_save_server_button"

    // ─── Attach Account (local session → signed-in) ─────────────────────────
    const val AUTH_ATTACH_ACCOUNT_BUTTON = "auth_attach_account_button"
    const val AUTH_ATTACH_EMAIL_INPUT = "auth_attach_email_input"
    const val AUTH_ATTACH_PASSWORD_INPUT = "auth_attach_password_input"
    const val AUTH_ATTACH_SUBMIT_BUTTON = "auth_attach_submit_button"
    const val AUTH_ATTACH_BACK_BUTTON = "auth_attach_back_button"

    /** "Move to account" — claims the offline rows rather than erasing them. */
    const val AUTH_ATTACH_KEEP_BUTTON = "auth_attach_keep_button"

    /** "Delete" — erases the offline rows, leaving the account empty. */
    const val AUTH_ATTACH_DISCARD_BUTTON = "auth_attach_discard_button"

    /** The sentence explaining what the currently selected disposition will do. */
    const val AUTH_ATTACH_DISPOSITION_HINT = "auth_attach_disposition_hint"

    // ─── Magic Link Confirm ────────────────────────────────────────────────
    const val AUTH_MAGIC_RESEND_BUTTON = "auth_magic_resend_button"

    // ─── Navigation ──────────────────────────────────────────────────────────
    const val NAV_MENU_BUTTON = "nav_menu_button"

    /**
     * The back arrow of the desktop shell's own top bar.
     *
     * It is not [TOP_BAR_BACK_BUTTON]: that one belongs to the shared
     * `BackTopAppBar` a screen draws inside itself, and a screen can be open
     * underneath the shell's arrow — two arrows, two meanings, two ids.
     */
    const val SHELL_BACK_BUTTON = "shell_back_button"

    /**
     * The desktop shell's drawer sheet.
     *
     * The sheet is the thing that slides in, so it is the thing to ask about
     * when a flow needs to know whether the drawer is open — not one of its
     * entries, which scroll out of view.
     */
    const val DESKTOP_DRAWER = "desktop_drawer"

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

    /** Dynamic: menu_<slug> — keyed by stable menu-item id. */
    @Suppress("UnusedParameter")
    fun menuItem(id: String) = id

    // ─── Menu bottom sheet items ───────────────────────────────────────────────
    // Stable ids for MenuBottomSheet items. These are not derived from labels
    // (that was the bug — label-based tags break on non-English locales).
    // Each id must appear in a Maestro flow or in LEGACY_RAW in check-tags.sh.
    const val MENU_SETTINGS = "menu_settings"
    const val MENU_PROFILES = "menu_profiles"
    const val MENU_QUICK_SEARCH = "menu_quick_search"
    const val MENU_STATISTICS = "menu_statistics"
    const val MENU_NOTES = "menu_notes"
    const val MENU_AI_CHAT = "menu_aichat"
    const val MENU_SEARCH = "menu_search"
    const val MENU_ARCHIVE = "menu_archive"

    /** The Calendar's entry in the menu — it left the tab bar for the menu. */
    const val MENU_CALENDAR = "menu_calendar"

    /** The proposals' entry in the menu. */
    const val MENU_PROPOSALS = "menu_proposals"

    /** The "connect an account" entry in the menu. */
    const val MENU_ATTACH_ACCOUNT = "menu_attach_account"

    /** The Projects section of the menu — the tree and the way to the full list. */
    const val MENU_PROJECTS_SECTION = "menu_projects_section"

    /** The entry at the end of the Projects section: the full project list. */
    const val MENU_ALL_PROJECTS = "menu_all_projects"

    /**
     * A project in the menu's tree, by the project's id.
     *
     * The id, not the name: a renamed project must stay reachable by the tag it
     * was first given.
     */
    fun menuProjectNode(projectId: String) = "menu_project_node_${slug(projectId)}"

    /** The expander of a project in the menu's tree, by the project's id. */
    fun menuProjectExpander(projectId: String) = "menu_project_expander_${slug(projectId)}"

    // ─── Tasks ───────────────────────────────────────────────────────────────
    const val TASKS_LIST = "tasks_list"
    const val TASKS_FAB = "tasks_fab"

    /** Root container of the long-press context-menu sheet on a task row. */
    const val TASK_CONTEXT_MENU_SHEET = "task_context_menu_sheet"

    /**
     * Stable action IDs for the [TaskContextMenuSheet] long-press action rows.
     * These are constant regardless of the task's current state (completed/pinned),
     * unlike [taskAction] which derives from the displayed label and changes when
     * the label does ("Mark as completed" → "Mark as uncompleted").
     */
    const val TASK_CONTEXT_MENU_OPEN = "task_action_open"
    const val TASK_CONTEXT_MENU_COMPLETE = "task_action_mark_as_completed"
    const val TASK_CONTEXT_MENU_PIN = "task_action_pin"
    const val TASK_CONTEXT_MENU_ARCHIVE = "task_action_archive"

    /** Dynamic tag of the form `task_item_<slug>`. */
    fun taskItem(title: String) = "task_item_${slug(title)}"

    /** Dynamic tag of the form `task_checkbox_<slug>`. */
    fun taskCheckbox(title: String) = "task_checkbox_${slug(title)}"

    // ─── Task Editor ─────────────────────────────────────────────────────────
    const val TASK_EDITOR_TITLE_INPUT = "task_editor_title_input"
    const val TASK_EDITOR_SAVE = "task_editor_save"
    const val TASK_EDITOR_MORE_MENU = "task_editor_more_menu"
    const val TASK_EDITOR_AI_BUTTON = "task_editor_ai_button"

    /** Confirm (✓) button in task-editor sheet hosts (recurrence picker, etc.). */
    const val SHEET_CONFIRM = "sheet_confirm"

    /** Due-date row in the task editor attribute list. */
    const val TASK_EDITOR_DUE_ROW = "task_editor_due_row"

    /** Label text inside the due-date row (shown when no date is set: "Add due date"). */
    const val TASK_EDITOR_DUE_DATE_ROW_LABEL = "task_editor_due_date_row_label"

    /** Priority row in the task editor attribute list. */
    const val TASK_EDITOR_PRIORITY_ROW = "task_editor_priority_row"

    /**
     * Dependencies row in the task editor.
     *
     * The row exists even when nothing is selected: that is the only way to add the
     * first relation, and a card hidden until something is chosen is a card that can
     * never be chosen for the first time.
     */
    const val TASK_EDITOR_DEPENDENCIES_ROW = "task_editor_dependencies_row"

    // The × button of a task editor's sheet.
    //
    // The sheet's × has the content description "Close", and so does the calendar
    // panel's — the calendar is composed behind the editor, so a test that means "the
    // sheet's close" finds two buttons and refuses to choose.
    const val EDITOR_SHEET_CLOSE = "editor_sheet_close"

    /** Checklist row in the task editor attribute list. */
    const val TASK_EDITOR_CHECKLIST_ROW = "task_editor_checklist_row"

    /** Attachments row in the task editor attribute list. */
    const val TASK_EDITOR_ATTACHMENTS_ROW = "task_editor_attachments_row"

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

    /** Estimate row in the task editor attribute list. */
    const val TASK_EDITOR_ESTIMATE_ROW = "task_editor_estimate_row"

    /** "Repeats" label text inside the recurrence row of the task editor. */
    const val TASK_EDITOR_RECURRENCE_LABEL = "task_editor_recurrence_label"

    /** "No tasks available" empty text in the dependency picker sheet. */
    const val DEPENDENCY_PICKER_EMPTY = "dependency_picker_empty"

    /** Dynamic tag of one selectable task row in the dependency picker. */
    fun dependencyItem(taskId: String) = "dependency_item_${slug(taskId)}"

    /**
     * Dynamic tag of the verb chip on a selected dependency row.
     *
     * [verb] is the wire name of the verb (`BLOCKS`, `FOLLOWS_UP`), not its label:
     * the label is user-facing copy and a tag built from it would break in another
     * locale.
     */
    fun dependencyVerb(taskId: String, verb: String) = "dependency_verb_${slug(taskId)}_${verb.lowercase()}"

    /** Workflow-state row in the task editor attribute list. */
    const val TASK_EDITOR_WORKFLOW_STATE_ROW = "task_editor_workflow_state_row"

    const val WORKFLOW_STATE_OPTION_OPEN = "workflow_state_option_open"
    const val WORKFLOW_STATE_OPTION_NEXT_UP = "workflow_state_option_next_up"
    const val WORKFLOW_STATE_OPTION_IN_PROGRESS = "workflow_state_option_in_progress"

    /**
     * First-run suggestion card in the task detail view.
     *
     * Shown for tasks < 5 min old that have no description, checklist items, or
     * completed subtasks. Contains "Write note", "Add checklist", and "Ask AI" chips.
     */
    const val FIRST_RUN_SECTION = "first_run_section"

    /**
     * The checklist bottom sheet (TaskEditorSheetHost with title "Checklist").
     *
     * Used in Maestro flows to wait for the sheet to appear before interacting
     * with checklist items inside it.
     */
    const val CHECKLIST_SHEET = "checklist_sheet"

    /** Text input field in the checklist editor sheet (placeholder: "Add item..."). */
    const val CHECKLIST_ADD_INPUT = "checklist_add_input"

    /** "Add" button in the checklist editor sheet — submits the current input. */
    const val CHECKLIST_ADD_BUTTON = "checklist_add_button"

    /**
     * The checklist section of the task detail screen.
     *
     * The section is always rendered, empty list or not: it carries the add-row,
     * so a task without checklist items is still a task the user can add one to.
     */
    const val TASK_CHECKLIST_SECTION = "task_checklist_section"

    /**
     * Text input of the inline add-row in the task detail's checklist section.
     *
     * Separate from [CHECKLIST_ADD_INPUT], which belongs to the checklist sheet:
     * two inputs with one id would leave a flow unable to say which one it typed
     * into, and the sheet is a window of its own on desktop.
     */
    const val CHECKLIST_INLINE_ADD_INPUT = "checklist_inline_add_input"

    /** Add/submit button of the inline add-row in the checklist section. */
    const val CHECKLIST_INLINE_ADD_BUTTON = "checklist_inline_add_button"

    /**
     * Dynamic tag of the form `checklist_item_<slug>` — one per checklist item row.
     * Derived from the item's id and title, slugified the same way as [taskItem].
     */
    @Suppress("UnusedParameter")
    fun checklistItem(id: String, title: String) = "checklist_item_${slug(title)}"

    /**
     * Dynamic tag of the form `checklist_item_checkbox_<slug>` — the toggle/checkbox
     * for each checklist item row.
     */
    @Suppress("UnusedParameter")
    fun checklistItemCheckbox(id: String, title: String) = "checklist_item_checkbox_${slug(title)}"

    /**
     * Dynamic tag of the form `checklist_item_delete_<slug>` — the delete (×) button
     * for each checklist item row.
     */
    @Suppress("UnusedParameter")
    fun checklistItemDelete(id: String, title: String) = "checklist_item_delete_${slug(title)}"

    // ─── Subtasks ─────────────────────────────────────────────────────────────

    /** The subtasks section container in the task detail view. */
    const val SUBTASKS_SECTION = "subtasks_section"

    /** Text input for entering a new subtask title in the subtasks section. */
    const val SUBTASK_ADD_INPUT = "subtask_add_input"

    /** Add/submit button for the subtask input row. */
    const val SUBTASK_ADD_BUTTON = "subtask_add_button"

    /**
     * Dynamic tag of the form `subtask_item_<slug>` — one per subtask row.
     * Derived from the subtask's title, slugified the same way as [taskItem].
     */
    fun subtaskItem(title: String) = "subtask_item_${slug(title)}"

    /**
     * Dynamic tag of the form `subtask_checkbox_<slug>` — the toggle/checkbox
     * for each subtask row.
     */
    fun subtaskCheckbox(title: String) = "subtask_checkbox_${slug(title)}"

    /**
     * Dynamic tag of the form `subtask_delete_<slug>` — the delete (×) button
     * for each subtask row.
     */
    fun subtaskDelete(title: String) = "subtask_delete_${slug(title)}"

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

    /**
     * Frequency options in the recurrence picker — one per option the sheet
     * renders.
     *
     * The recurrence sheet's options are `selectable` rows showing a bare
     * label ("Daily", "Weekly", …) and, unlike the priority sheet, carried no
     * tag at all. That left any test of scenario `TASK-REC-01` selecting on
     * translated copy, which breaks in every locale but the one it was written
     * in — the same reason the priority options are keyed on the enum. Tags are
     * added together with the test that consumes them, never as ballast.
     */
    const val RECURRENCE_OPTION_DAILY = "recurrence_option_daily"
    const val RECURRENCE_OPTION_WEEKLY = "recurrence_option_weekly"
    const val RECURRENCE_OPTION_MONTHLY = "recurrence_option_monthly"
    const val RECURRENCE_OPTION_YEARLY = "recurrence_option_yearly"
    const val RECURRENCE_OPTION_SPECIFIC_WEEKDAYS = "recurrence_option_specific_weekdays"
    const val RECURRENCE_OPTION_DAY_OF_MONTH = "recurrence_option_day_of_month"
    const val RECURRENCE_OPTION_YEARLY_ON_DATE = "recurrence_option_yearly_on_date"
    const val RECURRENCE_OPTION_NONE = "recurrence_option_none"

    // ── Recurrence end date ───────────────────────────────────────────────────
    const val RECURRENCE_ENDS_NEVER = "recurrence_ends_never"
    const val RECURRENCE_ENDS_ON_DATE = "recurrence_ends_on_date"
    const val RECURRENCE_END_DATE_VALUE = "recurrence_end_date_value"

    // ─── Agenda ────────────────────────────────────────────────────────────
    const val AGENDA_SAVED_VIEWS_BUTTON = "agenda_saved_views_button"

    /**
     * The top-bar button that opens the date picker for a task on several days.
     */
    const val AGENDA_MULTI_DATE_BUTTON = "agenda_multi_date_button"

    /**
     * The button of the selection bar that merges the two series the selection
     * spans. It is there only when the selection does span two.
     */
    const val AGENDA_MERGE_SERIES_BUTTON = "agenda_merge_series_button"
    const val AGENDA_SAVE_CURRENT_BUTTON = "agenda_save_current_button"
    const val SAVED_AGENDA_LIST_BACK = "saved_agenda_list_back"
    const val SAVED_AGENDA_CREATE_FAB = "saved_agenda_create_fab"
    const val SAVED_AGENDA_NAME_INPUT = "saved_agenda_name_input"
    const val SAVED_AGENDA_SAVE_BUTTON = "saved_agenda_save_button"
    const val SAVED_AGENDA_DELETE_BUTTON = "saved_agenda_delete_button"

    /** Confirm button of the second "add section" step, after values are chosen. */
    const val SAVED_AGENDA_ADD_SECTION_CONFIRM = "saved_agenda_add_section_confirm"

    /** A section *type* in the "add section" template list, by label. */
    fun agendaSectionTemplate(label: String) = "agenda_section_template_${slug(label)}"

    /** One selectable value in the section parameter picker, by id. */
    fun agendaSelectorOption(id: String) = "agenda_selector_option_${slug(id)}"

    /**
     * The "match all of these tags" checkbox in the tag section's parameter
     * picker — the switch between "any of" and "all of", which the engine has
     * always supported and the editor did not.
     */
    const val AGENDA_TAG_MATCH_ALL = "agenda_tag_match_all"

    /** The free-text pattern input in the ByRegexp section template picker. */
    const val AGENDA_REGEX_PATTERN_INPUT = "agenda_regex_pattern_input"

    /** Overflow menu button (⋮) on a saved-agenda card. */
    const val SAVED_AGENDA_OVERFLOW_BUTTON = "saved_agenda_overflow_button"

    /** "Edit" in a saved-agenda card overflow menu. */
    const val SAVED_AGENDA_MENU_EDIT = "saved_agenda_menu_edit"

    /** "Copy to profile" in a saved-agenda card overflow menu. */
    const val SAVED_AGENDA_MENU_COPY_TO_PROFILE = "saved_agenda_menu_copy_to_profile"

    /** "Copy to profile" sheet title (ListPickerSheet). */
    const val SAVED_AGENDA_COPY_TO_PROFILE_SHEET = "saved_agenda_copy_to_profile_sheet"

    /** "Delete" in a saved-agenda card overflow menu. */
    const val SAVED_AGENDA_MENU_DELETE = "saved_agenda_menu_delete"

    /** Empty-state title of the saved agenda list ("No saved views yet"). */
    const val SAVED_AGENDA_EMPTY_TITLE = "saved_agenda_empty_title"

    /** Dynamic tag of the form `saved_agenda_card_<slug>`. */
    fun savedAgendaCard(name: String) = "saved_agenda_card_${slug(name)}"

    /** Agenda section header, e.g. "Today", "No Date". */
    fun agendaSection(name: String) = "agenda_section_${slug(name)}"

    // ─── Plans timeline ───────────────────────────────────────────────────

    /** The screen itself — the root of the timeline. */
    const val PLANS_SCREEN = "plans_screen"

    /** The granularity control: the day option. */
    const val PLANS_GRANULARITY_DAY = "plans_granularity_day"

    /** The granularity control: the week option. */
    const val PLANS_GRANULARITY_WEEK = "plans_granularity_week"

    /** The granularity control: the month option. */
    const val PLANS_GRANULARITY_MONTH = "plans_granularity_month"

    /** The empty state — nothing is scheduled inside the horizon. */
    const val PLANS_EMPTY_STATE = "plans_empty_state"

    /** The message the screen shows when the plan could not be loaded. */
    const val PLANS_ERROR = "plans_error"

    /**
     * A timeline section header, by the section's id.
     *
     * The id, not the label: the label of a day section changes with the date,
     * and a tag that moves every morning is a tag no automation can rely on.
     */
    fun plansSection(id: String) = "plans_section_${slug(id)}"

    /** The "add a task to this section" control of a section header. */
    fun plansSectionAdd(id: String) = "plans_section_add_${slug(id)}"

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

        /**
         * Shown instead of the task chips when the platform cannot supply an Inbox
         * (Desktop). Distinguishes "no tasks here" from "this platform has no Inbox".
         */
        const val TASKS_UNSUPPORTED = "pomodoro_tasks_unsupported"

        /**
         * The focused-task label. Its **text** varies (`"Focus: Buy milk"`), so
         * select it by id — `[tag]` — instead of a locale-sensitive `visible:`.
         */
        const val FOCUS_CHIP = "pomodoro_focus_chip"
    }

    /** Dynamic tag of the form `pomodoro_task_chip_<slug>`. */
    fun pomodoroTaskChip(title: String) = "pomodoro_task_chip_${slug(title)}"

    // ─── Calendar view mode tabs (Month / Day / 4 days) ───────────────────

    /**
     * Calendar view-mode selector tabs in the agenda screen's top bar.
     * Use: `TestTags.calendarViewMode("month")`, `TestTags.calendarViewMode("day")`,
     * `TestTags.calendarViewMode("4_days")`.
     */
    fun calendarViewMode(mode: String) = "calendar_view_mode_${slug(mode)}"

    // ─── Tags ───────────────────────────────────────────────────────────────
    const val TAGS_LIST = "tags_list"
    const val TAGS_FAB = "tags_fab"

    /** Dynamic tag of the form `tag_rename_<slug>` — the pencil on a tag card. */
    fun tagRename(tagName: String) = "tag_rename_${slug(tagName)}"

    /** Dynamic tag of the form `tag_open_<slug>` — tapping a tag card opens its agenda. */
    fun tagOpen(tagName: String) = "tag_open_${slug(tagName)}"

    /** "No tags yet" empty text in the tags picker sheet. */
    const val TAGS_PICKER_EMPTY = "tags_picker_empty"

    /** "No tags yet" empty state title on the tags list screen. */
    const val TAGS_LIST_EMPTY = "tags_list_empty"

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

    /** Dynamic tag of the form `note_filter_chip_<slug>`. */
    fun noteFilterChip(name: String) = "note_filter_chip_${slug(name)}"

    /** Dynamic tag of the form `note_action_<slug>`. */
    fun noteAction(name: String) = "note_action_${slug(name)}"

    // ─── Note Editor ────────────────────────────────────────────────────────
    const val NOTE_EDITOR_TITLE_INPUT = "note_editor_title_input"
    const val NOTE_EDITOR_BODY = "note_editor_body"
    const val NOTE_EDITOR_SAVE = "note_editor_save"
    const val NOTE_EDITOR_NOTIFICATION_HOST = "note_editor_notification_host"

    /**
     * The "Saved" pill of the note editor.
     *
     * It appears *after* the write completed, so an automation that waits for it
     * knows the note is on disk — without it, leaving the editor right after the
     * save races the write, and the row may never appear.
     */
    const val NOTE_EDITOR_SAVED_INDICATOR = "note_editor_saved_indicator"

    // ─── Note Preview ────────────────────────────────────────────────────────
    const val NOTES_BACKLINKS_BUTTON = "notes_backlinks_button"

    // ─── Search ─────────────────────────────────────────────────────────────
    const val SEARCH_INPUT = "search_input"

    /** The "Word" segment of the search mode switch — the word-prefix rule. */
    const val SEARCH_MODE_WORD = "search_mode_word"

    /** The "Contains" segment of the search mode switch — the term occurs anywhere. */
    const val SEARCH_MODE_CONTAINS = "search_mode_contains"

    /** The caption that says what the contains mode costs. */
    const val SEARCH_MODE_CONTAINS_NOTE = "search_mode_contains_note"

    /** "Enter a search query" placeholder in the search screen (shown when query is blank). */
    const val SEARCH_EMPTY_PLACEHOLDER = "search_empty_placeholder"

    /** "No results found" empty state in the search screen (shown when query has no matches). */
    const val SEARCH_EMPTY_RESULTS = "search_empty_results"

    /**
     * The shared result/error dialog raised by `NotificationHost`.
     *
     * Assert the dialog is present rather than matching its copy: the messages are
     * user-facing strings that change with the feature (and are translated on this
     * device), so a flow matching the text either rots or silently stops matching.
     */
    const val RESULT_DIALOG = "result_dialog"

    /** "Archive is empty" empty state title on the archive list screen. */
    const val ARCHIVE_EMPTY = "archive_empty"

    /** "No notes yet" empty state title on the notes list screen. */
    const val NOTES_LIST_EMPTY = "notes_list_empty"

    // ─── Projects ───────────────────────────────────────────────────────────

    /** Quick-add task field on the project detail screen. */
    const val PROJECT_DETAIL_QUICK_ADD = "project_detail_quick_add"

    /**
     * The overflow button in the project detail's top bar.
     *
     * The project screen has two "More" buttons — this one in the top bar, and
     * the body's action bar at the bottom — and they open different menus. A
     * contentDescription cannot tell them apart, so the top one carries a tag.
     */
    const val PROJECT_DETAIL_OVERFLOW = "project_detail_overflow"

    /** "No projects yet" empty text in the project picker sheet. */
    const val PROJECT_PICKER_EMPTY = "project_picker_empty"

    /** "No projects yet" empty state title on the projects list screen. */
    const val PROJECTS_LIST_EMPTY = "projects_list_empty"

    /** The name filter on the projects list screen (placeholder: "Search projects"). */
    const val PROJECTS_SEARCH_INPUT = "projects_search_input"

    const val PROJECT_EDITOR_NAME_INPUT = "project_editor_name_input"
    const val PROJECT_EDITOR_DESCRIPTION_INPUT = "project_editor_description_input"
    const val PROJECT_EDITOR_SAVE = "project_editor_save"
    const val PROJECT_EDITOR_BACK = "project_editor_back"
    const val PROJECT_EDITOR_NOTIFICATION_HOST = "project_editor_notification_host"

    /** Dynamic tag of the form `project_card_<slug>`. */
    fun projectCard(name: String) = "project_card_${slug(name)}"

    /** "Tasks" section header on the project detail screen. */
    const val PROJECT_DETAIL_TASKS_HEADER = "project_detail_tasks_header"

    /** "No tasks yet" empty state title on the project detail screen. */
    const val PROJECT_DETAIL_EMPTY = "project_detail_empty"

    // ─── Settings ──────────────────────────────────────────────────────────

    object Settings {
        /** The Dark Theme toggle row's outer Row (clickable, semantic Role.Switch). */
        const val DARK_THEME_SWITCH = "settings_dark_theme_switch"

        /** The NavRail scroll container — allows Maestro to scroll clipped tabs into view. */
        const val NAV_RAIL_SCROLL = "settings_nav_rail_scroll"

        /** The accent-color swatch picker — one per [SingularityAccents]. */
        fun accentSwatch(accentName: String) = "settings_accent_swatch_${slug(accentName)}"

        /** Font-size slider in the interface settings screen. */
        const val FONT_SIZE_SLIDER = "settings_font_size_slider"

        /** Theme-mode segmented button (System / Light / Dark) in interface settings. */
        fun themeModeButton(modeName: String) = "settings_theme_mode_${slug(modeName)}"

        /** Master switch for notifications at all. */
        const val NOTIFICATIONS_ENABLED_SWITCH = "settings_notifications_enabled_switch"

        /** Per-event sound toggle. Rendered only while notifications are enabled. */
        const val NOTIFICATIONS_SOUND_SWITCH = "settings_notifications_sound_switch"

        /** Per-event vibration toggle. Rendered only while notifications are enabled. */
        const val NOTIFICATIONS_VIBRATION_SWITCH = "settings_notifications_vibration_switch"

        /** Section container for the "Default Reminder" radio group. */
        const val REMINDER_DEFAULTS_SECTION = "reminder_defaults_section"

        /** Dynamic tag for a reminder-offset radio row. Uses the locale-aware label as input. */
        fun reminderOffsetRadioRow(offset: ReminderOffset): String = "reminder_offset_radio_${slug(offset.label)}"

        /**
         * Toggle for one day of the working week, on the work schedule screen.
         *
         * One tag per day, built from the day's slug: `settings_working_day_monday`,
         * `settings_working_day_sunday`. The section renders the whole week, so
         * a test that wants "Saturday is off" says which Saturday it means
         * instead of asserting on a switch that used to be the only one.
         */
        fun workingDaySwitch(day: DayOfWeek): String = "settings_working_day_${slug(day.name.lowercase())}"

        /** Default tag for a [com.singularity.todo.core.ui.components.SettingsValueRow]. */
        const val VALUE_ROW = "settings_value_row"

        /** Default tag for a [com.singularity.todo.core.ui.components.SettingsActionRow]. */
        const val ACTION_ROW = "settings_action_row"

        /**
         * Content-visible marker for a Settings tab's main content area.
         * Use after tapping `settings_tab_<slug>` to assert the tab rendered.
         */
        fun content(tab: String) = "settings_content_${slug(tab)}"
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Facet toggles inside `SimpleFilterSheet`.
     *
     * Named per sheet rather than per facet because the sheet is a `ModalBottomSheet`,
     * and Compose Multiplatform renders those into a separate semantics root on
     * desktop — so these exist for the Android/Maestro tier, where the sheet's
     * controls are addressable.
     */
    object SearchFilter {
        const val HAS_DESCRIPTION_SWITCH = "search_filter_has_description_switch"
        const val PINNED_SWITCH = "search_filter_pinned_switch"

        /** Confirm button of the filter sheet — commits the pending filter. */
        const val APPLY_BUTTON = "search_filter_apply_button"

        /** Cancel button of the filter sheet — discards the pending filter. */
        const val CANCEL_BUTTON = "search_filter_cancel_button"
    }

    /**
     * Saved searches row — long-press context menu items.
     *
     * Desktop gap: `DropdownMenu` renders in a separate layer that the desktop
     * Compose test API cannot reach. These tags are for Android/Maestro.
     */
    object SavedSearches {
        const val SEARCH_MENU_RENAME = "search_menu_rename"
        const val SEARCH_MENU_DELETE = "search_menu_delete"
    }

    // ─── Calendar sync ───────────────────────────────────────────────────────

    /**
     * Settings → Calendar, the one screen that configures *both* calendar providers.
     *
     * The panel had no tags at all before this object existed, which made every
     * user-visible behaviour on it unverifiable from automation — a screen can be
     * complete, tested at the ViewModel level, and still have no carrier at all.
     * These are the selectors `CAL-SYNC-*` scenarios are written against.
     */
    object CalendarSync {
        /**
         * Provider segment in the `SingleChoiceSegmentedButtonRow` ("System calendar" /
         * "Google Calendar"). Segmented buttons render their own unlabelled internals,
         * so the tag goes on the button carrying the provider's user-visible label.
         */
        fun providerSegment(label: String) = "calendar_sync_provider_${slug(label)}"

        /** The connect button in the not-yet-connected Google account section. */
        const val GOOGLE_CONNECT_BUTTON = "calendar_sync_google_connect_button"

        /** The disconnect button once an account is connected. */
        const val GOOGLE_DISCONNECT_BUTTON = "calendar_sync_google_disconnect_button"

        /**
         * Warning shown when the grant carries no refresh token.
         *
         * Worth its own tag: it is the difference between "sync stopped" and "this
         * connection can never renew", and a test that only checked for the
         * disconnect button would pass with the warning missing.
         */
        const val GOOGLE_RENEW_WARNING = "calendar_sync_google_renew_warning"

        /** Why the calendar *list* could not be read (distinct from a failed pass). */
        const val GOOGLE_LIST_ERROR = "calendar_sync_google_list_error"

        /** Outcome line of the last Google pass: "Sync failed: …" or "Last synced …". */
        const val GOOGLE_SYNC_OUTCOME = "calendar_sync_google_sync_outcome"

        /** The manual pass button, present only once a calendar is selected. */
        const val GOOGLE_SYNC_NOW_BUTTON = "calendar_sync_google_sync_now_button"

        /** The prompt shown when no calendar has been chosen yet. */
        const val GOOGLE_SYNC_NEEDS_CALENDAR = "calendar_sync_google_sync_needs_calendar"

        /** Dynamic: `calendar_sync_google_calendar_<slug>` — one row per writable calendar. */
        fun googleCalendarRow(id: String) = "calendar_sync_google_calendar_${slug(id)}"

        /** The import-events toggle, and the window sentence describing what it imports. */
        const val GOOGLE_IMPORT_SWITCH = "calendar_sync_google_import_switch"
        const val GOOGLE_IMPORT_WINDOW = "calendar_sync_google_import_window"

        /** System-calendar half: the platform gate desktop hits. */
        const val SYSTEM_UNAVAILABLE = "calendar_sync_system_unavailable"
        const val SYSTEM_ENABLE_SWITCH = "calendar_sync_system_enable_switch"
        const val SYSTEM_SYNC_NOW_BUTTON = "calendar_sync_system_sync_now_button"
    }

    // ─── Onboarding ──────────────────────────────────────────────────────────

    object Onboarding {
        /** The spotlight explanation card. */
        const val SPOTLIGHT_CARD = "onboarding_spotlight_card"

        /** "Next" / "Got it" on the spotlight card. */
        const val SPOTLIGHT_NEXT = "onboarding_spotlight_next"

        /** "Skip" on the spotlight card. */
        const val SPOTLIGHT_SKIP = "onboarding_spotlight_skip"

        /** Settings row that replays the tour on demand. */
        const val REPLAY_TUTORIAL_ROW = "onboarding_replay_row"
    }

    // ─── Sync ───────────────────────────────────────────────────────────────

    object Sync {
        /** Tab slug for the sync settings tab, used with [Settings.content]. */
        const val TAB_SLUG = "sync"

        const val AUTO_SYNC_SWITCH = "sync_auto_sync_switch"
        const val INTERVAL_ROW = "sync_interval_row"
        const val ATTACHMENTS_SWITCH = "sync_attachments_switch"
        const val TEST_CONNECTION_ROW = "sync_test_connection_row"
        const val SYNC_NOW_ROW = "sync_now_row"
    }

    // ─── Refresh ─────────────────────────────────────────────────────────────

    /**
     * The pull-to-refresh indicator.
     *
     * One tag for every screen that offers the gesture: the indicator is the
     * same node everywhere, so a flow that pulls one screen down asserts on
     * the same thing it would assert on for any other.
     */
    const val REFRESH_INDICATOR = "refresh_indicator"

    /**
     * The host that shows the refresh report.
     *
     * The tag is on the host rather than on the report's text, because the
     * text is user-facing copy that changes with the feature and is
     * translated on this device — a flow that matched the words would rot.
     */
    const val REFRESH_NOTIFICATION_HOST = "refresh_notification_host"

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

        /**
         * The sheet body itself, as opposed to its action row.
         *
         * The OK/CANCEL/CLEAR tags sit at the bottom of a scrolling column and can
         * be below the fold on a phone, so they prove nothing about whether the
         * picker opened. This tag marks the body, which is what a flow wants to
         * assert first. (The "Select date" heading is Material3's own and carries
         * no app testTag.)
         */
        const val SHEET = "dialog_date_picker_sheet"
    }

    /**
     * The multi-date picker sheet — the one that lets the user pick several days
     * for one piece of work.
     *
     * Its day cells are tagged by ISO local date, the same way the calendar's
     * month grid is, so a flow that knows a date can address the day without
     * knowing the grid's position.
     */
    object MultiDatePicker {
        const val OK = "multi_date_picker_ok"
        const val CANCEL = "multi_date_picker_cancel"
        const val CLEAR = "multi_date_picker_clear"

        /** The month title ("Октябрь 2026"). */
        const val MONTH_TITLE = "multi_date_picker_month_title"

        /** The counter of the selected days ("Выбрано: 3"). */
        const val COUNTER = "multi_date_picker_counter"

        /** The button that pages to the previous month. */
        const val PREV_MONTH = "multi_date_picker_prev_month"

        /** The button that pages to the next month. */
        const val NEXT_MONTH = "multi_date_picker_next_month"

        /**
         * A day cell, tagged by ISO local date.
         * Format: `multi_date_picker_day_2026_09_15`.
         */
        fun day(isoDate: String) = "multi_date_picker_day_${isoDate.replace("-", "_")}"
    }

    /**
     * The series card on the task detail screen.
     *
     * The card is what tells the user that the task on screen is one occurrence
     * of a series, and its two buttons are the only way to reach the other
     * occurrences' shared settings or to archive the whole series.
     */
    object TaskSeries {
        /** The row of one occurrence. Tapping it opens that occurrence. */
        const val MEMBER = "task_series_member"

        /** Copies the time and the pin of this occurrence to the others. */
        const val APPLY_TO_ALL = "task_series_apply_to_all"

        /** Archives every occurrence of the series. */
        const val DELETE = "task_series_delete"

        /** The button that takes this occurrence out of the series. */
        const val DETACH = "task_series_detach"

        /** The mark that says the row is one date of a series. */
        const val MARKER = "task_series_marker"
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

    // ─── Time tracking ─────────────────────────────────────────────────────

    /**
     * Start/stop chip of [com.singularity.todo.feature.timetracking.presentation
     * .components.TimeTrackingSection] on the task detail screen.
     *
     * These two were added, removed and are now back, and the round trip is the
     * point worth keeping in mind. They were added for a desktop carrier that a
     * reachability probe then ruled out, and removed as "tags for a control
     * nothing can reach". The ruling-out turned out to be wrong: the section was
     * absent on desktop because the two platform graphs had diverged onto
     * different screens, not because the feature was Android-only. So the tags
     * were not pre-paid debt — they were the one thing that *would* have caught
     * it, sitting unused while a whole feature was missing from a platform.
     *
     * They carry distinct values because the chip swaps between the two states;
     * one shared tag would make "the chip is still Start after a click" the only
     * thing a test could assert.
     */
    object TimeTracking {
        const val START = "time_tracking_start"
        const val STOP = "time_tracking_stop"

        /**
         * The refusal text, rendered in place of the chip when a write failed.
         *
         * Tagged because "the click was refused and said so" is the only thing a
         * desktop carrier can assert for `TASK-TIME-01` under the anonymous
         * harness session — `startEntry` cannot succeed there, and until the
         * failure was a state the test could see, a click that did nothing and a
         * click that was never wired were the same observation.
         */
        const val ERROR = "time_tracking_error"
    }

    // ─── Task logbook ─────────────────────────────────────────────────────

    /**
     * The logbook section on the task detail screen — the notes attached to the task,
     * together with its time entries.
     *
     * Tagged as a whole, not only its rows: a flow has to be able to wait for the
     * section to be on screen before it can address anything inside it.
     */
    const val LOGBOOK_SECTION = "logbook_section"

    /**
     * The logbook's empty state — the card that says the task has no notes yet.
     *
     * Tagged because "the logbook is empty" is the state a note has to *not* change:
     * a note that only exists after the user saves anything is what this tag makes
     * observable.
     */
    const val LOGBOOK_EMPTY = "logbook_empty"

    /** The "+" button that opens the note editor with the task already attached. */
    const val LOGBOOK_ADD_NOTE_BUTTON = "logbook_add_note_button"

    /**
     * Dynamic tag of the form `logbook_note_by_title_<slug>` — a note row in the
     * logbook of a task, keyed by the visible title.
     *
     * Keyed by the title because the row's id is a generated ULID that a flow never
     * learns; the title is what the user sees.
     */
    fun logbookNoteByTitle(title: String) = "logbook_note_by_title_${slug(title)}"

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

    /**
     * The action button of a transient snackbar ("Undo", and whatever a feature
     * puts in [Notification.Undo.actionLabel]).
     *
     * The button used to be reachable only by its label, and the label is
     * translated — `tasks/06-delete-undo.yaml` waited for `text: "Undo"` and
     * could not pass on this project's Russian-locale device. A flow must never
     * have to know what a user sees in order to tap a button.
     *
     * Applied by [TaggedSnackbarHost] rather than by `NotificationHost`, because
     * Material3's own `SnackbarHost` renders the action internally and gives no
     * slot to tag it.
     */
    const val SNACKBAR_ACTION = "snackbar_action"

    /**
     * The snackbar's message.
     *
     * The copy is the translator's, so a flow that waits for "Backup created"
     * waits forever on a Russian device. This tag says "a snackbar was shown"
     * without saying what it said — the same reason [SNACKBAR_ACTION] exists.
     *
     * Applied by [TaggedSnackbarHost].
     */
    const val SNACKBAR_MESSAGE = "snackbar_message"

    // ─── Notification hosts ─────────────────────────────────────────────────
    //
    // These tag the invisible container a screen parks transient ResultDialogs in.
    // They are not clickable and carry no visible text; the tag exists so a test
    // can assert a dialog was (or was not) shown without matching its copy.

    const val PROJECTS_NOTIFICATION_HOST = "projects_notification_host"
    const val CHAT_NOTIFICATION_HOST = "chat_notification_host"
    const val ARCHIVE_NOTIFICATION_HOST = "archive_notification_host"

    // ─── Agent activity ─────────────────────────────────────────────────────
    //
    // The cards that show what an agent did. Shared by the built-in chat and the
    // external-agent screen, so a tag here names a thing both screens draw.

    const val AGENT_TOOL_CALL_CARD = "agent_tool_call_card"
    const val AGENT_TOOL_CALL_STATUS = "agent_tool_call_status"
    const val AGENT_TOOL_CALL_SUMMARY = "agent_tool_call_summary"
    const val AGENT_TOOL_CALL_SUMMARY_TOGGLE = "agent_tool_call_summary_toggle"
    const val AGENT_PLAN_CARD = "agent_plan_card"

    // ─── External agent ─────────────────────────────────────────────────────
    //
    // The screen that hosts a third-party agent. The command tag matters more
    // than it looks: the command is what the application is about to run, and a
    // test that asserts it is on screen is a test that the reader can see it too.

    const val ACP_SCREEN = "acp_screen"
    const val ACP_MESSAGES = "acp_messages"
    const val ACP_AGENT_LIST = "acp_agent_list"
    const val ACP_AGENT_COMMAND = "acp_agent_command"
    const val ACP_BACK_BUTTON = "acp_back_button"
    const val ACP_CONNECT_BUTTON = "acp_connect_button"
    const val ACP_DISCONNECT_BUTTON = "acp_disconnect_button"
    const val ACP_CONNECTING = "acp_connecting"
    const val ACP_CONNECTION_STATUS = "acp_connection_status"
    const val ACP_CONNECTION_ERROR = "acp_connection_error"
    const val ACP_NO_TOOL_ACCESS_WARNING = "acp_no_tool_access_warning"
    const val ACP_PERMISSION_SHEET = "acp_permission_sheet"
    const val ACP_PERMISSION_ALLOW = "acp_permission_allow"
    const val ACP_PERMISSION_DENY = "acp_permission_deny"
    const val ACP_CANCEL_BUTTON = "acp_cancel_button"
    const val ACP_TURN_FAILED = "acp_turn_failed"
    const val ACP_TURN_CANCELLED = "acp_turn_cancelled"
    const val ACP_NOTIFICATION_HOST = "acp_notification_host"

    const val ACP_AGENT_CHIP_PREFIX = "acp_agent_chip_"

    /** The chip that selects one agent of the registry. */
    fun acpAgentChip(agentId: String) = "${ACP_AGENT_CHIP_PREFIX}${slug(agentId)}"

    // ─── Backup ─────────────────────────────────────────────────────────────
    const val BACKUP_TOP_BAR_BACK = "backup_top_bar_back"
    const val BACKUP_CREATE_BUTTON = "backup_create_button"
    const val BACKUP_RESTORE_BUTTON = "backup_restore_button"
    const val BACKUP_EXPORT_SETTINGS = "backup_export_settings"
    const val BACKUP_IMPORT_SETTINGS = "backup_import_settings"

    /** The button that asks the cloud for its archives. */
    const val BACKUP_LOAD_REMOTE_BUTTON = "backup_load_remote_button"

    /**
     * The button that lets this device push backups without a person.
     *
     * It opens the password dialog, and a dialog that nothing can reach is a
     * dialog nobody tests.
     */
    const val BACKUP_AUTHORIZE_SCHEDULED_BUTTON = "backup_authorize_scheduled_button"

    /** The prefix of the "Restore" button of one archive in the cloud. */
    const val BACKUP_RESTORE_FROM_CLOUD_PREFIX = "backup_restore_from_cloud_"

    /** The restore button of the archive with this [ref]. */
    fun backupRestoreFromCloud(ref: String) = "${BACKUP_RESTORE_FROM_CLOUD_PREFIX}${slug(ref)}"

    /** The field the archive's password is typed into. */
    const val BACKUP_PASSWORD_FIELD = "backup_password_field"

    /** The button that hands the typed password over. */
    const val BACKUP_PASSWORD_CONFIRM = "backup_password_confirm"

    /**
     * The button that pushes one archive to the cloud.
     *
     * It opens the password dialog, and a dialog that nothing can reach is a
     * dialog nobody tests. The tag is the same for every archive on purpose: the
     * flow needs *a* push button to open the dialog, not a specific archive —
     * and a per-archive tag would have to name a file whose name carries the
     * moment it was made.
     */
    const val BACKUP_PUSH_BUTTON = "backup_push"

    // ─── Profile ────────────────────────────────────────────────────────────
    const val PROFILE_CREATE_BUTTON = "profile_create_button"
    const val PROFILE_CREATE_NAME_INPUT = "profile_create_name_input"
    const val PROFILE_ITEM_PREFIX = "profile_item_"

    fun profileItem(name: String) = "${PROFILE_ITEM_PREFIX}${slug(name)}"

    // ─── AI ─────────────────────────────────────────────────────────────────
    fun genUi(name: String) = "genui_${slug(name)}"

    /** The message field of the AI chat screen. */
    const val CHAT_INPUT = "chat_input"

    // ─── Calendar ──────────────────────────────────────────────────────────

    /**
     * A day cell in the calendar month grid, tagged by ISO local date.
     * Format: `calendar_day_2026_09_15`.
     */
    fun calendarDay(isoDate: String) = "calendar_day_${isoDate.replace("-", "_")}"

    /**
     * A weekday column header in the calendar month grid (Mon, Tue, …, Sun).
     * Static constants for the variants used in Maestro flows.
     */
    const val CALENDAR_WEEKDAY_MON = "calendar_weekday_mon"
    const val CALENDAR_WEEKDAY_SUN = "calendar_weekday_sun"

    fun calendarWeekdayHeader(label: String) = "calendar_weekday_${label.lowercase()}"

    /** The clickable trigger Row that opens the CalendarViewMode dropdown. */
    const val CALENDAR_VIEW_MODE_TRIGGER = "calendar_view_mode_trigger"

    /**
     * The view-mode the calendar is in, as a tag on the label of the trigger.
     *
     * The trigger shows the current mode as the app's own string ("Day",
     * "Week"), so a flow that asserted that string asserted the app's copy.
     * The dropdown items cannot stand in for it either: they exist only while
     * the dropdown is expanded, so they never say which mode is selected.
     */
    fun calendarViewModeSelected(mode: String) = "calendar_view_mode_selected_${mode.lowercase()}"

    /**
     * The title of the period the calendar is showing ("October 2026").
     *
     * The wording of the title is the app's — it may be reworded, translated or
     * reformatted without the calendar being wrong. Tagging the node lets a
     * flow assert that the calendar named the period at all, without pinning
     * the app to a sentence.
     */
    const val CALENDAR_MONTH_TITLE = "calendar_month_title"

    /**
     * A day cell of the calendar's day grid — the cell that holds one day.
     *
     * The month grid names its cells by date ([calendarDay]); the day grid has
     * no such naming, so the cell itself is what a flow can address. The day a
     * cell holds is tagged by date on the number inside it.
     */
    const val CALENDAR_DAY_CELL = "calendar_day_cell"

    /**
     * The bar that appears while the calendar picks days for one task.
     *
     * Its two buttons are the only way out of the selection mode: one creates
     * the task on the picked days, the other drops the picking.
     */
    object Calendar {
        /** The bar itself. */
        const val SELECTION_BAR = "calendar_selection_bar"

        /** Creates one task on the picked days. */
        const val CREATE_ON_SELECTED = "calendar_create_on_selected"

        /** Drops the picking and leaves the mode. */
        const val CANCEL_SELECTION = "calendar_cancel_selection"
    }

    // ─── Attachments (URL sheet) ──────────────────────────────────────────────
    //
    // Used in Maestro/flows/attachments/ — the URL attachment bottom sheet
    // that appears when adding a URL to a task.

    const val ATTACHMENTS_ADD_URL_BUTTON = "attachments_add_url_button"

    /**
     * The row in the task detail that opens the attachments sheet.
     *
     * The sheet's own file-picker button used to carry this id, which made the id
     * name two different things: the thing that opens the manager and the thing
     * inside the manager that picks a file. Only the row is reachable from the
     * task, so only the row keeps the id the flows look for.
     */
    const val ATTACHMENTS_ADD_FILE_ROW = "attachments_add_file_row"

    /** The sheet's file-picker button — inside the sheet, not on the task. */
    const val ATTACHMENTS_PICK_FILE_BUTTON = "attachments_pick_file_button"
    const val ATTACHMENTS_URL_INPUT = "attachments_url_input"
    const val ATTACHMENTS_TITLE_INPUT = "attachments_title_input"

    // ─── Statistics / Health drill-down ──────────────────────────────────

    /**
     * Health bucket card in the Statistics Health tab.
     * The health tab shows OVERDUE, BLOCKED, and STAGNANT as clickable StatCards
     * that drill down into the corresponding agenda view.
     */
    const val HEALTH_BUCKET_OVERDUE = "health_bucket_overdue"
    const val HEALTH_BUCKET_BLOCKED = "health_bucket_blocked"
    const val HEALTH_BUCKET_STAGNANT = "health_bucket_stagnant"

    /** Icon button in the Statistics screen header that opens the Saved Views agenda list. */
    const val STATISTICS_OPEN_SAVED_VIEWS = "statistics_open_saved_views"

    /**
     * Tabs of the Statistics screen.
     *
     * The health bucket cards live on the Health tab, and the screen opens on
     * Tasks — so a flow that opens Statistics and waits for a bucket without
     * selecting the tab waits for something that is on the next tab. The tabs
     * carry stable ids for that reason; a text selector would be a different
     * thing to break in every language the app ships.
     */
    const val STATISTICS_TAB_TASKS = "statistics_tab_tasks"
    const val STATISTICS_TAB_TIME = "statistics_tab_time"
    const val STATISTICS_TAB_HEALTH = "statistics_tab_health"
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
internal fun slug(input: String): String =
    buildString {
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
        // Trim leading and trailing separators (runs of non-alphanumeric chars at either end).
        while (isNotEmpty() && last().let { !it.isLetterOrDigit() }) {
            deleteCharAt(lastIndex)
        }
        while (isNotEmpty() && first().let { !it.isLetterOrDigit() }) {
            deleteCharAt(0)
        }
        if (isEmpty()) append("untitled")
    }
