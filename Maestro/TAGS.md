# Maestro TestTag Catalogue

The **Static constants** and **Dynamic functions** tables below are generated from
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`. They sit
between two marker lines (`GENERATED:BEGIN` / `GENERATED:END`, each alone on its
own line) and are verified by
`TestTagsCatalogJvmTest.generated section of TAGS md matches TestTags`.
Regenerate them with:

```bash
./gradlew :shared:jvmTest -PupdateGoldens=true
```

Everything outside the markers is hand-maintained — including the
"Missing testTags" list, which is *not* machine-checked.

Every `id:` used in a Maestro flow must come from this catalogue or a dynamic
function listed below. Do NOT use raw strings in flows: that is enforced by
`TestTagsCatalogJvmTest.no raw testTag strings outside TestTags and test sources`.

Dynamic functions expand at runtime. Example: `TestTags.navTab("Today")` produces
`nav_tab_today`. The slug algorithm (`slug()`) lowercases and replaces runs of
non-alphanumeric characters with `_`.

<!-- GENERATED:BEGIN -->
## Static constants

### Auth
| Constant | Value | Where |
|---|---|---|
| `AUTH_ATTACH_ACCOUNT_BUTTON` | `auth_attach_account_button` | |
| `AUTH_ATTACH_BACK_BUTTON` | `auth_attach_back_button` | |
| `AUTH_ATTACH_DISCARD_BUTTON` | `auth_attach_discard_button` | |
| `AUTH_ATTACH_DISPOSITION_HINT` | `auth_attach_disposition_hint` | |
| `AUTH_ATTACH_EMAIL_INPUT` | `auth_attach_email_input` | |
| `AUTH_ATTACH_KEEP_BUTTON` | `auth_attach_keep_button` | |
| `AUTH_ATTACH_PASSWORD_INPUT` | `auth_attach_password_input` | |
| `AUTH_ATTACH_SUBMIT_BUTTON` | `auth_attach_submit_button` | |
| `AUTH_BACK_BUTTON` | `auth_back_button` | |
| `AUTH_CONFIRMATION_EMAIL` | `auth_confirmation_email` | |
| `AUTH_CONFIRMATION_HEADLINE` | `auth_confirmation_headline` | |
| `AUTH_CONTINUE_OFFLINE_BUTTON` | `auth_continue_offline_button` | |
| `AUTH_EMAIL_INPUT` | `auth_email_input` | |
| `AUTH_ERROR_TEXT` | `auth_error_text` | |
| `AUTH_LOADING` | `auth_loading` | |
| `AUTH_MAGIC_RESEND_BUTTON` | `auth_magic_resend_button` | |
| `AUTH_MESSAGE_TEXT` | `auth_message_text` | |
| `AUTH_PASSWORD_INPUT` | `auth_password_input` | |
| `AUTH_RESEND_BUTTON` | `auth_resend_button` | |
| `AUTH_SAVE_SERVER_BUTTON` | `auth_save_server_button` | |
| `AUTH_SERVER_KEY_INPUT` | `auth_server_key_input` | |
| `AUTH_SERVER_URL_INPUT` | `auth_server_url_input` | |
| `AUTH_SIGN_IN_BUTTON` | `auth_sign_in_button` | |
| `AUTH_SIGN_OUT_BUTTON` | `auth_sign_out_button` | |
| `AUTH_TOGGLE_MODE_BUTTON` | `auth_toggle_mode_button` | |

### Navigation
| Constant | Value | Where |
|---|---|---|
| `MENU_AI_CHAT` | `menu_aichat` | |
| `MENU_ARCHIVE` | `menu_archive` | |
| `MENU_NOTES` | `menu_notes` | |
| `MENU_PROFILES` | `menu_profiles` | |
| `MENU_QUICK_SEARCH` | `menu_quick_search` | |
| `MENU_SEARCH` | `menu_search` | |
| `MENU_SETTINGS` | `menu_settings` | |
| `MENU_SHEET` | `menu_sheet` | |
| `MENU_STATISTICS` | `menu_statistics` | |
| `NAV_MENU_BUTTON` | `nav_menu_button` | |
| `TOP_BAR_BACK_BUTTON` | `top_bar_back_button` | |

### Tasks
| Constant | Value | Where |
|---|---|---|
| `CHECKLIST_ADD_BUTTON` | `checklist_add_button` | |
| `CHECKLIST_ADD_INPUT` | `checklist_add_input` | |
| `CHECKLIST_SHEET` | `checklist_sheet` | |
| `FIRST_RUN_SECTION` | `first_run_section` | |
| `PRIORITY_OPTION_HIGH` | `priority_option_high` | |
| `PRIORITY_OPTION_LOW` | `priority_option_low` | |
| `PRIORITY_OPTION_MEDIUM` | `priority_option_medium` | |
| `PRIORITY_OPTION_NONE` | `priority_option_none` | |
| `PRIORITY_OPTION_URGENT` | `priority_option_urgent` | |
| `RECURRENCE_ENDS_NEVER` | `recurrence_ends_never` | |
| `RECURRENCE_ENDS_ON_DATE` | `recurrence_ends_on_date` | |
| `RECURRENCE_END_DATE_VALUE` | `recurrence_end_date_value` | |
| `RECURRENCE_OPTION_DAILY` | `recurrence_option_daily` | |
| `RECURRENCE_OPTION_DAY_OF_MONTH` | `recurrence_option_day_of_month` | |
| `RECURRENCE_OPTION_MONTHLY` | `recurrence_option_monthly` | |
| `RECURRENCE_OPTION_NONE` | `recurrence_option_none` | |
| `RECURRENCE_OPTION_SPECIFIC_WEEKDAYS` | `recurrence_option_specific_weekdays` | |
| `RECURRENCE_OPTION_WEEKLY` | `recurrence_option_weekly` | |
| `RECURRENCE_OPTION_YEARLY` | `recurrence_option_yearly` | |
| `RECURRENCE_OPTION_YEARLY_ON_DATE` | `recurrence_option_yearly_on_date` | |
| `SHEET_CONFIRM` | `sheet_confirm` | |
| `SUBTASKS_SECTION` | `subtasks_section` | |
| `SUBTASK_ADD_BUTTON` | `subtask_add_button` | |
| `SUBTASK_ADD_INPUT` | `subtask_add_input` | |
| `TASKS_FAB` | `tasks_fab` | |
| `TASKS_LIST` | `tasks_list` | |
| `TASK_CONTEXT_MENU_ARCHIVE` | `task_action_archive` | |
| `TASK_CONTEXT_MENU_COMPLETE` | `task_action_mark_as_completed` | |
| `TASK_CONTEXT_MENU_OPEN` | `task_action_open` | |
| `TASK_CONTEXT_MENU_PIN` | `task_action_pin` | |
| `TASK_CONTEXT_MENU_SHEET` | `task_context_menu_sheet` | |
| `TASK_EDITOR_AI_BUTTON` | `task_editor_ai_button` | |
| `TASK_EDITOR_ATTACHMENTS_ROW` | `task_editor_attachments_row` | |
| `TASK_EDITOR_CHECKLIST_ROW` | `task_editor_checklist_row` | |
| `TASK_EDITOR_DUE_DATE_ROW_LABEL` | `task_editor_due_date_row_label` | |
| `TASK_EDITOR_DUE_ROW` | `task_editor_due_row` | |
| `TASK_EDITOR_ESTIMATE_ROW` | `task_editor_estimate_row` | |
| `TASK_EDITOR_MORE_MENU` | `task_editor_more_menu` | |
| `TASK_EDITOR_PIN_ROW` | `task_editor_pin_row` | |
| `TASK_EDITOR_PRIORITY_ROW` | `task_editor_priority_row` | |
| `TASK_EDITOR_PROJECT_ROW` | `task_editor_project_row` | |
| `TASK_EDITOR_RECURRENCE_LABEL` | `task_editor_recurrence_label` | |
| `TASK_EDITOR_RECURRENCE_ROW` | `task_editor_recurrence_row` | |
| `TASK_EDITOR_SAVE` | `task_editor_save` | |
| `TASK_EDITOR_START_DATE_ROW` | `task_editor_start_date_row` | |
| `TASK_EDITOR_TAGS_ROW` | `task_editor_tags_row` | |
| `TASK_EDITOR_TITLE_INPUT` | `task_editor_title_input` | |
| `TimeTracking.ERROR` | `time_tracking_error` | |
| `TimeTracking.START` | `time_tracking_start` | |
| `TimeTracking.STOP` | `time_tracking_stop` | |

### Agenda
| Constant | Value | Where |
|---|---|---|
| `AGENDA_MERGE_SERIES_BUTTON` | `agenda_merge_series_button` | |
| `AGENDA_MULTI_DATE_BUTTON` | `agenda_multi_date_button` | |
| `AGENDA_REGEX_PATTERN_INPUT` | `agenda_regex_pattern_input` | |
| `AGENDA_SAVED_VIEWS_BUTTON` | `agenda_saved_views_button` | |
| `AGENDA_SAVE_CURRENT_BUTTON` | `agenda_save_current_button` | |
| `AGENDA_TAG_MATCH_ALL` | `agenda_tag_match_all` | |
| `SAVED_AGENDA_ADD_SECTION_CONFIRM` | `saved_agenda_add_section_confirm` | |
| `SAVED_AGENDA_COPY_TO_PROFILE_SHEET` | `saved_agenda_copy_to_profile_sheet` | |
| `SAVED_AGENDA_CREATE_FAB` | `saved_agenda_create_fab` | |
| `SAVED_AGENDA_DELETE_BUTTON` | `saved_agenda_delete_button` | |
| `SAVED_AGENDA_EMPTY_TITLE` | `saved_agenda_empty_title` | |
| `SAVED_AGENDA_LIST_BACK` | `saved_agenda_list_back` | |
| `SAVED_AGENDA_MENU_COPY_TO_PROFILE` | `saved_agenda_menu_copy_to_profile` | |
| `SAVED_AGENDA_MENU_DELETE` | `saved_agenda_menu_delete` | |
| `SAVED_AGENDA_MENU_EDIT` | `saved_agenda_menu_edit` | |
| `SAVED_AGENDA_NAME_INPUT` | `saved_agenda_name_input` | |
| `SAVED_AGENDA_OVERFLOW_BUTTON` | `saved_agenda_overflow_button` | |
| `SAVED_AGENDA_SAVE_BUTTON` | `saved_agenda_save_button` | |

### Pomodoro
| Constant | Value | Where |
|---|---|---|
| `Pomodoro.CYCLE_LABEL` | `pomodoro_cycle_label` | |
| `Pomodoro.FOCUS_CHIP` | `pomodoro_focus_chip` | |
| `Pomodoro.PAUSE_BUTTON` | `pomodoro_pause_button` | |
| `Pomodoro.PHASE_LABEL` | `pomodoro_phase_label` | |
| `Pomodoro.PLAY_BUTTON` | `pomodoro_play_button` | |
| `Pomodoro.SKIP_BUTTON` | `pomodoro_skip_button` | |
| `Pomodoro.STOP_BUTTON` | `pomodoro_stop_button` | |
| `Pomodoro.TASKS_UNSUPPORTED` | `pomodoro_tasks_unsupported` | |
| `Pomodoro.TIMER_LABEL` | `pomodoro_timer_label` | |

### Tags
| Constant | Value | Where |
|---|---|---|
| `TAGS_FAB` | `tags_fab` | |
| `TAGS_LIST` | `tags_list` | |
| `TAGS_LIST_EMPTY` | `tags_list_empty` | |
| `TAGS_PICKER_EMPTY` | `tags_picker_empty` | |

### Notes
| Constant | Value | Where |
|---|---|---|
| `NOTES_BACKLINKS_BUTTON` | `notes_backlinks_button` | |
| `NOTES_LIST` | `notes_list` | |
| `NOTES_LIST_EMPTY` | `notes_list_empty` | |
| `NOTES_QUICK_ADD_INPUT` | `notes_quick_add_input` | |
| `NOTE_EDITOR_BODY` | `note_editor_body` | |
| `NOTE_EDITOR_NOTIFICATION_HOST` | `note_editor_notification_host` | |
| `NOTE_EDITOR_SAVE` | `note_editor_save` | |
| `NOTE_EDITOR_TITLE_INPUT` | `note_editor_title_input` | |

### Search
| Constant | Value | Where |
|---|---|---|
| `SEARCH_EMPTY_PLACEHOLDER` | `search_empty_placeholder` | |
| `SEARCH_EMPTY_RESULTS` | `search_empty_results` | |
| `SEARCH_INPUT` | `search_input` | |
| `SEARCH_MODE_CONTAINS` | `search_mode_contains` | |
| `SEARCH_MODE_CONTAINS_NOTE` | `search_mode_contains_note` | |
| `SEARCH_MODE_WORD` | `search_mode_word` | |
| `SavedSearches.SEARCH_MENU_DELETE` | `search_menu_delete` | |
| `SavedSearches.SEARCH_MENU_RENAME` | `search_menu_rename` | |

### Projects
| Constant | Value | Where |
|---|---|---|
| `PROJECT_DETAIL_EMPTY` | `project_detail_empty` | |
| `PROJECT_DETAIL_OVERFLOW` | `project_detail_overflow` | |
| `PROJECT_DETAIL_QUICK_ADD` | `project_detail_quick_add` | |
| `PROJECT_DETAIL_TASKS_HEADER` | `project_detail_tasks_header` | |
| `PROJECT_EDITOR_BACK` | `project_editor_back` | |
| `PROJECT_EDITOR_DESCRIPTION_INPUT` | `project_editor_description_input` | |
| `PROJECT_EDITOR_NAME_INPUT` | `project_editor_name_input` | |
| `PROJECT_EDITOR_NOTIFICATION_HOST` | `project_editor_notification_host` | |
| `PROJECT_EDITOR_SAVE` | `project_editor_save` | |
| `PROJECT_PICKER_EMPTY` | `project_picker_empty` | |

### Settings
| Constant | Value | Where |
|---|---|---|
| `Settings.ACTION_ROW` | `settings_action_row` | |
| `Settings.DARK_THEME_SWITCH` | `settings_dark_theme_switch` | |
| `Settings.FONT_SIZE_SLIDER` | `settings_font_size_slider` | |
| `Settings.NAV_RAIL_SCROLL` | `settings_nav_rail_scroll` | |
| `Settings.NOTIFICATIONS_ENABLED_SWITCH` | `settings_notifications_enabled_switch` | |
| `Settings.NOTIFICATIONS_SOUND_SWITCH` | `settings_notifications_sound_switch` | |
| `Settings.NOTIFICATIONS_VIBRATION_SWITCH` | `settings_notifications_vibration_switch` | |
| `Settings.REMINDER_DEFAULTS_SECTION` | `reminder_defaults_section` | |
| `Settings.VALUE_ROW` | `settings_value_row` | |

### Onboarding
| Constant | Value | Where |
|---|---|---|
| `Onboarding.REPLAY_TUTORIAL_ROW` | `onboarding_replay_row` | |
| `Onboarding.SPOTLIGHT_CARD` | `onboarding_spotlight_card` | |
| `Onboarding.SPOTLIGHT_NEXT` | `onboarding_spotlight_next` | |
| `Onboarding.SPOTLIGHT_SKIP` | `onboarding_spotlight_skip` | |

### Sync
| Constant | Value | Where |
|---|---|---|
| `Sync.ATTACHMENTS_SWITCH` | `sync_attachments_switch` | |
| `Sync.AUTO_SYNC_SWITCH` | `sync_auto_sync_switch` | |
| `Sync.INTERVAL_ROW` | `sync_interval_row` | |
| `Sync.SYNC_NOW_ROW` | `sync_now_row` | |
| `Sync.TAB_SLUG` | `sync` | |
| `Sync.TEST_CONNECTION_ROW` | `sync_test_connection_row` | |

### Dialog
| Constant | Value | Where |
|---|---|---|
| `DatePicker.CANCEL` | `dialog_date_picker_cancel` | |
| `DatePicker.CLEAR` | `dialog_date_picker_clear` | |
| `DatePicker.OK` | `dialog_date_picker_ok` | |
| `DatePicker.SHEET` | `dialog_date_picker_sheet` | |
| `Dialog.CONFIRM` | `dialog_confirm` | |
| `Dialog.DISMISS` | `dialog_dismiss` | |

### Editor Overflow menu
| Constant | Value | Where |
|---|---|---|
| `EditorOverflow.ARCHIVE` | `overflow_archive` | |
| `EditorOverflow.DELETE` | `overflow_delete` | |
| `EditorOverflow.PIN` | `overflow_pin` | |
| `EditorOverflow.RESTORE` | `overflow_restore` | |
| `EditorOverflow.UNPIN` | `overflow_unpin` | |

### Snackbar / transient UI
| Constant | Value | Where |
|---|---|---|
| `ARCHIVE_NOTIFICATION_HOST` | `archive_notification_host` | |
| `CHAT_NOTIFICATION_HOST` | `chat_notification_host` | |
| `PROJECTS_NOTIFICATION_HOST` | `projects_notification_host` | |
| `SNACKBAR_ACTION` | `snackbar_action` | |
| `SNACKBAR_MESSAGE` | `snackbar_message` | |

### Backup
| Constant | Value | Where |
|---|---|---|
| `BACKUP_CREATE_BUTTON` | `backup_create_button` | |
| `BACKUP_EXPORT_SETTINGS` | `backup_export_settings` | |
| `BACKUP_IMPORT_SETTINGS` | `backup_import_settings` | |
| `BACKUP_RESTORE_BUTTON` | `backup_restore_button` | |
| `BACKUP_TOP_BAR_BACK` | `backup_top_bar_back` | |

### Profile
| Constant | Value | Where |
|---|---|---|
| `PROFILE_CREATE_BUTTON` | `profile_create_button` | |
| `PROFILE_CREATE_NAME_INPUT` | `profile_create_name_input` | |
| `PROFILE_ITEM_PREFIX` | `profile_item_` | |

### Calendar
| Constant | Value | Where |
|---|---|---|
| `CALENDAR_DAY_CELL` | `calendar_day_cell` | |
| `CALENDAR_MONTH_TITLE` | `calendar_month_title` | |
| `CALENDAR_VIEW_MODE_TRIGGER` | `calendar_view_mode_trigger` | |
| `CALENDAR_WEEKDAY_MON` | `calendar_weekday_mon` | |
| `CALENDAR_WEEKDAY_SUN` | `calendar_weekday_sun` | |

### Calendar sync
| Constant | Value | Where |
|---|---|---|
| `CalendarSync.GOOGLE_CONNECT_BUTTON` | `calendar_sync_google_connect_button` | |
| `CalendarSync.GOOGLE_DISCONNECT_BUTTON` | `calendar_sync_google_disconnect_button` | |
| `CalendarSync.GOOGLE_IMPORT_SWITCH` | `calendar_sync_google_import_switch` | |
| `CalendarSync.GOOGLE_IMPORT_WINDOW` | `calendar_sync_google_import_window` | |
| `CalendarSync.GOOGLE_LIST_ERROR` | `calendar_sync_google_list_error` | |
| `CalendarSync.GOOGLE_RENEW_WARNING` | `calendar_sync_google_renew_warning` | |
| `CalendarSync.GOOGLE_SYNC_NEEDS_CALENDAR` | `calendar_sync_google_sync_needs_calendar` | |
| `CalendarSync.GOOGLE_SYNC_NOW_BUTTON` | `calendar_sync_google_sync_now_button` | |
| `CalendarSync.GOOGLE_SYNC_OUTCOME` | `calendar_sync_google_sync_outcome` | |
| `CalendarSync.SYSTEM_ENABLE_SWITCH` | `calendar_sync_system_enable_switch` | |
| `CalendarSync.SYSTEM_SYNC_NOW_BUTTON` | `calendar_sync_system_sync_now_button` | |
| `CalendarSync.SYSTEM_UNAVAILABLE` | `calendar_sync_system_unavailable` | |

### Search filters
| Constant | Value | Where |
|---|---|---|
| `SearchFilter.APPLY_BUTTON` | `search_filter_apply_button` | |
| `SearchFilter.CANCEL_BUTTON` | `search_filter_cancel_button` | |
| `SearchFilter.HAS_DESCRIPTION_SWITCH` | `search_filter_has_description_switch` | |
| `SearchFilter.PINNED_SWITCH` | `search_filter_pinned_switch` | |

### Statistics
| Constant | Value | Where |
|---|---|---|
| `HEALTH_BUCKET_BLOCKED` | `health_bucket_blocked` | |
| `HEALTH_BUCKET_OVERDUE` | `health_bucket_overdue` | |
| `HEALTH_BUCKET_STAGNANT` | `health_bucket_stagnant` | |

### Other
| Constant | Value | Where |
|---|---|---|
| `ARCHIVE_EMPTY` | `archive_empty` | |
| `ATTACHMENTS_ADD_FILE_ROW` | `attachments_add_file_row` | |
| `ATTACHMENTS_ADD_URL_BUTTON` | `attachments_add_url_button` | |
| `ATTACHMENTS_PICK_FILE_BUTTON` | `attachments_pick_file_button` | |
| `ATTACHMENTS_TITLE_INPUT` | `attachments_title_input` | |
| `ATTACHMENTS_URL_INPUT` | `attachments_url_input` | |
| `CHAT_INPUT` | `chat_input` | |
| `Calendar.CANCEL_SELECTION` | `calendar_cancel_selection` | |
| `Calendar.CREATE_ON_SELECTED` | `calendar_create_on_selected` | |
| `Calendar.SELECTION_BAR` | `calendar_selection_bar` | |
| `DEPENDENCY_PICKER_EMPTY` | `dependency_picker_empty` | |
| `EDITOR_SHEET_CLOSE` | `editor_sheet_close` | |
| `LOADING_INDICATOR` | `loading_indicator` | |
| `MultiDatePicker.CANCEL` | `multi_date_picker_cancel` | |
| `MultiDatePicker.CLEAR` | `multi_date_picker_clear` | |
| `MultiDatePicker.COUNTER` | `multi_date_picker_counter` | |
| `MultiDatePicker.MONTH_TITLE` | `multi_date_picker_month_title` | |
| `MultiDatePicker.NEXT_MONTH` | `multi_date_picker_next_month` | |
| `MultiDatePicker.OK` | `multi_date_picker_ok` | |
| `MultiDatePicker.PREV_MONTH` | `multi_date_picker_prev_month` | |
| `PROJECTS_LIST_EMPTY` | `projects_list_empty` | |
| `PROJECTS_SEARCH_INPUT` | `projects_search_input` | |
| `RESULT_DIALOG` | `result_dialog` | |
| `STATISTICS_OPEN_SAVED_VIEWS` | `statistics_open_saved_views` | |
| `STATISTICS_TAB_HEALTH` | `statistics_tab_health` | |
| `STATISTICS_TAB_TASKS` | `statistics_tab_tasks` | |
| `STATISTICS_TAB_TIME` | `statistics_tab_time` | |
| `TaskSeries.APPLY_TO_ALL` | `task_series_apply_to_all` | |
| `TaskSeries.DELETE` | `task_series_delete` | |
| `TaskSeries.DETACH` | `task_series_detach` | |
| `TaskSeries.MARKER` | `task_series_marker` | |
| `TaskSeries.MEMBER` | `task_series_member` | |

## Dynamic functions

Use `TestTags.<function>(<input>)` in Kotlin. In a Maestro flow, hard-code
the expanded form (the `id:` Maestro accepts does not call functions — flows
use the expanded string directly).

| Function | Input example | Expanded id | Used for |
|---|---|---|---|
| `navTab("Today")` | `"Today"` | `nav_tab_today` | Bottom nav tabs |
| `Settings.accentSwatch("Blue")` | `"Blue"` | `settings_accent_swatch_blue` | Accent colour swatches in Settings |
| `Settings.content("Interface")` | `"Interface"` | `settings_content_interface` | Settings tab content area |
| `Settings.reminderOffsetRadioRow("At due time")` | `"At due time"` | `reminder_offset_radio_at_due_time` | Reminder-offset radio rows in notification settings |
| `Settings.themeModeButton("Dark")` | `"Dark"` | `settings_theme_mode_dark` | Theme-mode radio buttons |
| `Settings.workingDaySwitch("MONDAY")` | `"MONDAY"` | `settings_working_day_monday` | Working-day toggles in the work schedule section |
| `settingsTab("Interface")` | `"Interface"` | `settings_tab_interface` | Settings nav rail tabs |
| `checklistItem("Item")` | `"Item"` | `checklist_item_item` | Checklist item rows |
| `checklistItemCheckbox("Item")` | `"Item"` | `checklist_item_checkbox_item` | Checklist item checkboxes |
| `checklistItemDelete("Item")` | `"Item"` | `checklist_item_delete_item` | Checklist item delete buttons |
| `subtaskCheckbox("Child task one")` | `"Child task one"` | `subtask_checkbox_child_task_one` | Subtask checkboxes |
| `subtaskDelete("Child task one")` | `"Child task one"` | `subtask_delete_child_task_one` | Subtask delete buttons |
| `subtaskItem("Child task one")` | `"Child task one"` | `subtask_item_child_task_one` | Subtask list rows |
| `taskCheckbox("Buy milk")` | `"Buy milk"` | `task_checkbox_buy_milk` | Task checkboxes |
| `taskItem("Buy milk")` | `"Buy milk"` | `task_item_buy_milk` | Task list rows |
| `noteAction("edit")` | `"edit"` | `note_action_edit` | Note editor action buttons |
| `noteFilterChip("pinned")` | `"pinned"` | `note_filter_chip_pinned` | Note list filter chips |
| `noteItem("01BXFF...")` | `"01BXFF..."` | `note_item_01bxff` | Note cards (desktop unit tests) |
| `noteItemByTitle("Meeting notes")` | `"Meeting notes"` | `note_item_by_title_meeting_notes` | Note cards (automation) |
| `agendaSection("Today")` | `"Today"` | `agenda_section_today` | Agenda section headers |
| `agendaSectionTemplate("By tag")` | `"By tag"` | `agenda_section_template_by_tag` | Section *types* in the add-section sheet |
| `agendaSelectorOption("work")` | `"work"` | `agenda_selector_option_work` | Values in the section parameter picker |
| `savedAgendaCard("Work")` | `"Work"` | `saved_agenda_card_work` | Saved agenda cards |
| `projectCard("Project Alpha")` | `"Project Alpha"` | `project_card_project_alpha` | Project cards |
| `pomodoroTaskChip("Buy milk")` | `"Buy milk"` | `pomodoro_task_chip_buy_milk` | Pomodoro focus-task chips |
| `Dialog.title("discard")` | `"discard"` | `dialog_title_discard` | Dialog title |
| `taskAction("Archive")` | `"Archive"` | `task_action_archive` | Long-press action rows |
| `profileItem("Personal")` | `"Personal"` | `profile_item_personal` | Profile list items |
| `genUi("whatsnew")` | `"whatsnew"` | `genui_whatsnew` | GenUI surfaces |
| `calendarViewMode("month")` | `"month"` | `calendar_view_mode_month` | Calendar view-mode tabs (month / day / 4_days) |
| `CalendarSync.googleCalendarRow("primary-cal")` | `"primary-cal"` | `calendar_sync_google_calendar_primary_cal` | One row per writable Google calendar |
| `CalendarSync.providerSegment("Google Calendar")` | `"Google Calendar"` | `calendar_sync_provider_google_calendar` | Provider segments in the panel |
<!-- GENERATED:END -->

## Raw-string tags (NOT via TestTags.kt)

**Empty, and enforced to stay that way.**
`TestTagsCatalogJvmTest.no raw testTag strings outside TestTags and test sources`
scans every production source set for `Modifier.testTag("literal")` and fails on
any hit. A raw string is invisible to `TestTagsCatalog`, so the generated tables
above would silently go stale.

The three notification-host tags that used to be listed here
(`projects_notification_host`, `chat_notification_host`, `archive_notification_host`)
are now `TestTags` constants.

## Missing testTags (known gaps — do NOT use for new flows)

These elements exist in the UI but have no stable testTag. When adding a flow
that needs one of these, add the testTag first (PR-0 phase for that screen).

This list is hand-maintained and is **not** checked by the golden test — only the
two generated tables are. Treat a stale entry here as a bug and fix it in the
same change that adds or removes a tag.

- Task editor: due-time row, reminder row, checklist section, checklist add
  input, checklist add button, attachments section, add attachment button,
  delete button, error message
- Note editor: delete button, markdown toolbar
- Project detail: top bar
- Settings tabs: all 11 have `settingsTab(<name>)`, lower-case. The rail is a
  scrollable 80dp column, so a flow must swipe it directly — `scrollUntilVisible`
  swipes the content pane, not the rail.
- Search: none — `SEARCH_INPUT` exists
- AI Chat: chat input, send button
- Calendar: today button, prev/next, mini-calendar toggle. The day cells *do* have
  a tag — `calendarDay("2026-09-15")` expands to `calendar_day_2026_09_15` — but it
  is a Kotlin function, so a Maestro flow must hard-code the expanded form. The
  month header (`calendar_month_title`), the day cells of the day grid
  (`calendar_day_cell`) and the selected view mode
  (`calendarViewModeSelected("day")` expands to `calendar_view_mode_selected_day`)
  are tagged as well; the mode's *label* is still the app's wording, so a flow
  asserts the tag and not the word.
- Sync: auto-switch, interval slider, sync-now button
- Profile: delete button
- Statistics: last-7-days chart
- Archive: archive-all button, restore button per row

Closed since this list was first written (tags now in `TestTags.kt`):

- Task editor due row and priority row → `TASK_EDITOR_DUE_ROW` /
  `TASK_EDITOR_PRIORITY_ROW`
- Priority picker rows → `PRIORITY_OPTION_*`
- Dialog buttons → `Dialog.CONFIRM` / `Dialog.DISMISS` / `Dialog.title(...)`
- Date-picker sheet buttons → `DatePicker.OK` / `CANCEL` / `CLEAR`
- Backup actions → `BACKUP_CREATE_BUTTON` / `BACKUP_RESTORE_BUTTON` /
  `BACKUP_EXPORT_SETTINGS` / `BACKUP_IMPORT_SETTINGS`
- Tags FAB → `TAGS_FAB`; profile creation → `PROFILE_CREATE_BUTTON`; profile rows
  → `profileItem(name)`
- Editor overflow items → `EditorOverflow.ARCHIVE` / `DELETE` / `RESTORE` / `PIN`
  / `UNPIN`

## slug() behaviour

```kotlin
"Buy milk"     → "buy_milk"
"Profile & sync" → "profile_sync"
"Café run"     → "caf_runn"      // accent dropped by isLetterOrDigit
"Today"        → "today"
"Задача"       → "задача"        // Cyrillic preserved
```

Flows should always use ASCII titles (`Buy milk`, `Project Alpha`) so the
expanded tag is deterministic.
