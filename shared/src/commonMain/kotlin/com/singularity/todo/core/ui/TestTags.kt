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

    /** A neutral "you have to do something" message, as opposed to a failure. */
    const val AUTH_MESSAGE_TEXT = "auth_message_text"
    const val AUTH_LOADING = "auth_loading"
    const val AUTH_SERVER_URL_INPUT = "auth_server_url_input"
    const val AUTH_SERVER_KEY_INPUT = "auth_server_key_input"
    const val AUTH_SAVE_SERVER_BUTTON = "auth_save_server_button"

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

    /** Label text inside the due-date row (shown when no date is set: "Add due date"). */
    const val TASK_EDITOR_DUE_DATE_ROW_LABEL = "task_editor_due_date_row_label"

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

    /** Estimate row in the task editor attribute list. */
    const val TASK_EDITOR_ESTIMATE_ROW = "task_editor_estimate_row"

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

    /** Overflow menu button (⋮) on a saved-agenda card. */
    const val SAVED_AGENDA_OVERFLOW_BUTTON = "saved_agenda_overflow_button"

    /** "Edit" in a saved-agenda card overflow menu. */
    const val SAVED_AGENDA_MENU_EDIT = "saved_agenda_menu_edit"

    /** "Copy to profile" in a saved-agenda card overflow menu. */
    const val SAVED_AGENDA_MENU_COPY_TO_PROFILE = "saved_agenda_menu_copy_to_profile"

    /** "Delete" in a saved-agenda card overflow menu. */
    const val SAVED_AGENDA_MENU_DELETE = "saved_agenda_menu_delete"

    /** Empty-state title of the saved agenda list ("No saved views yet"). */
    const val SAVED_AGENDA_EMPTY_TITLE = "saved_agenda_empty_title"

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

        /**
         * Shown instead of the task chips when the platform cannot supply an Inbox
         * (Desktop). Distinguishes "no tasks here" from "this platform has no Inbox".
         */
        const val TASKS_UNSUPPORTED = "pomodoro_tasks_unsupported"
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

        /** The accent-color swatch picker — one per [SingularityAccents]. */
        fun accentSwatch(accentName: String) = "settings_accent_swatch_${slug(accentName)}"

        /** Font-size slider in the interface settings screen. */
        const val FONT_SIZE_SLIDER = "settings_font_size_slider"

        /** Master switch for notifications at all. */
        const val NOTIFICATIONS_ENABLED_SWITCH = "settings_notifications_enabled_switch"

        /** Per-event sound toggle. Rendered only while notifications are enabled. */
        const val NOTIFICATIONS_SOUND_SWITCH = "settings_notifications_sound_switch"

        /** Per-event vibration toggle. Rendered only while notifications are enabled. */
        const val NOTIFICATIONS_VIBRATION_SWITCH = "settings_notifications_vibration_switch"

        /** Weekend-day toggles on the work schedule screen. */
        const val WORK_SCHEDULE_SATURDAY_SWITCH = "settings_work_schedule_saturday_switch"
        const val WORK_SCHEDULE_SUNDAY_SWITCH = "settings_work_schedule_sunday_switch"

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
    // Trim leading and trailing separators (runs of non-alphanumeric chars at either end).
    while (isNotEmpty() && last().let { !it.isLetterOrDigit() }) {
        deleteCharAt(lastIndex)
    }
    while (isNotEmpty() && first().let { !it.isLetterOrDigit() }) {
        deleteCharAt(0)
    }
    if (isEmpty()) append("untitled")
}
