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
| `AUTH_CONTINUE_OFFLINE_BUTTON` | `auth_continue_offline_button` | |
| `AUTH_EMAIL_INPUT` | `auth_email_input` | |
| `AUTH_ERROR_TEXT` | `auth_error_text` | |
| `AUTH_LOADING` | `auth_loading` | |
| `AUTH_PASSWORD_INPUT` | `auth_password_input` | |
| `AUTH_SIGN_IN_BUTTON` | `auth_sign_in_button` | |
| `AUTH_TOGGLE_MODE_BUTTON` | `auth_toggle_mode_button` | |

### Navigation
| Constant | Value | Where |
|---|---|---|
| `MENU_SHEET` | `menu_sheet` | |
| `NAV_MENU_BUTTON` | `nav_menu_button` | |
| `TOP_BAR_BACK_BUTTON` | `top_bar_back_button` | |

### Tasks
| Constant | Value | Where |
|---|---|---|
| `PRIORITY_OPTION_HIGH` | `priority_option_high` | |
| `PRIORITY_OPTION_LOW` | `priority_option_low` | |
| `PRIORITY_OPTION_MEDIUM` | `priority_option_medium` | |
| `PRIORITY_OPTION_NONE` | `priority_option_none` | |
| `PRIORITY_OPTION_URGENT` | `priority_option_urgent` | |
| `TASKS_FAB` | `tasks_fab` | |
| `TASKS_LIST` | `tasks_list` | |
| `TASK_CONTEXT_MENU_SHEET` | `task_context_menu_sheet` | |
| `TASK_EDITOR_AI_BUTTON` | `task_editor_ai_button` | |
| `TASK_EDITOR_DUE_ROW` | `task_editor_due_row` | |
| `TASK_EDITOR_ESTIMATE_ROW` | `task_editor_estimate_row` | |
| `TASK_EDITOR_MORE_MENU` | `task_editor_more_menu` | |
| `TASK_EDITOR_PIN_ROW` | `task_editor_pin_row` | |
| `TASK_EDITOR_PRIORITY_ROW` | `task_editor_priority_row` | |
| `TASK_EDITOR_PROJECT_ROW` | `task_editor_project_row` | |
| `TASK_EDITOR_RECURRENCE_ROW` | `task_editor_recurrence_row` | |
| `TASK_EDITOR_SAVE` | `task_editor_save` | |
| `TASK_EDITOR_START_DATE_ROW` | `task_editor_start_date_row` | |
| `TASK_EDITOR_TAGS_ROW` | `task_editor_tags_row` | |
| `TASK_EDITOR_TITLE_INPUT` | `task_editor_title_input` | |

### Agenda
| Constant | Value | Where |
|---|---|---|
| `AGENDA_SAVED_VIEWS_BUTTON` | `agenda_saved_views_button` | |
| `AGENDA_SAVE_CURRENT_BUTTON` | `agenda_save_current_button` | |
| `SAVED_AGENDA_CREATE_FAB` | `saved_agenda_create_fab` | |
| `SAVED_AGENDA_DELETE_BUTTON` | `saved_agenda_delete_button` | |
| `SAVED_AGENDA_LIST_BACK` | `saved_agenda_list_back` | |
| `SAVED_AGENDA_NAME_INPUT` | `saved_agenda_name_input` | |
| `SAVED_AGENDA_SAVE_BUTTON` | `saved_agenda_save_button` | |

### Pomodoro
| Constant | Value | Where |
|---|---|---|
| `Pomodoro.CYCLE_LABEL` | `pomodoro_cycle_label` | |
| `Pomodoro.PAUSE_BUTTON` | `pomodoro_pause_button` | |
| `Pomodoro.PHASE_LABEL` | `pomodoro_phase_label` | |
| `Pomodoro.PLAY_BUTTON` | `pomodoro_play_button` | |
| `Pomodoro.SKIP_BUTTON` | `pomodoro_skip_button` | |
| `Pomodoro.STOP_BUTTON` | `pomodoro_stop_button` | |
| `Pomodoro.TIMER_LABEL` | `pomodoro_timer_label` | |

### Tags
| Constant | Value | Where |
|---|---|---|
| `TAGS_FAB` | `tags_fab` | |
| `TAGS_LIST` | `tags_list` | |

### Notes
| Constant | Value | Where |
|---|---|---|
| `NOTES_BACKLINKS_BUTTON` | `notes_backlinks_button` | |
| `NOTES_LIST` | `notes_list` | |
| `NOTES_QUICK_ADD_INPUT` | `notes_quick_add_input` | |
| `NOTE_EDITOR_BODY` | `note_editor_body` | |
| `NOTE_EDITOR_NOTIFICATION_HOST` | `note_editor_notification_host` | |
| `NOTE_EDITOR_SAVE` | `note_editor_save` | |
| `NOTE_EDITOR_TITLE_INPUT` | `note_editor_title_input` | |

### Search
| Constant | Value | Where |
|---|---|---|
| `SEARCH_INPUT` | `search_input` | |

### Projects
| Constant | Value | Where |
|---|---|---|
| `PROJECT_DETAIL_QUICK_ADD` | `project_detail_quick_add` | |
| `PROJECT_EDITOR_BACK` | `project_editor_back` | |
| `PROJECT_EDITOR_DESCRIPTION_INPUT` | `project_editor_description_input` | |
| `PROJECT_EDITOR_NAME_INPUT` | `project_editor_name_input` | |
| `PROJECT_EDITOR_NOTIFICATION_HOST` | `project_editor_notification_host` | |
| `PROJECT_EDITOR_SAVE` | `project_editor_save` | |

### Settings
| Constant | Value | Where |
|---|---|---|
| `Settings.DARK_THEME_SWITCH` | `settings_dark_theme_switch` | |

### Dialog
| Constant | Value | Where |
|---|---|---|
| `DatePicker.CANCEL` | `dialog_date_picker_cancel` | |
| `DatePicker.CLEAR` | `dialog_date_picker_clear` | |
| `DatePicker.OK` | `dialog_date_picker_ok` | |
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
| `SNACKBAR_SAVED` | `snackbar_saved` | |

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

## Dynamic functions

Use `TestTags.<function>(<input>)` in Kotlin. In a Maestro flow, hard-code
the expanded form (the `id:` Maestro accepts does not call functions — flows
use the expanded string directly).

| Function | Input example | Expanded id | Used for |
|---|---|---|---|
| `navTab("Today")` | `"Today"` | `nav_tab_today` | Bottom nav tabs |
| `Settings.content("Interface")` | `"Interface"` | `settings_content_interface` | Settings tab content area |
| `settingsTab("Interface")` | `"Interface"` | `settings_tab_interface` | Settings nav rail tabs |
| `menuItem("Settings")` | `"Settings"` | `menu_settings` | Menu bottom sheet items |
| `taskCheckbox("Buy milk")` | `"Buy milk"` | `task_checkbox_buy_milk` | Task checkboxes |
| `taskItem("Buy milk")` | `"Buy milk"` | `task_item_buy_milk` | Task list rows |
| `noteItem("01BXFF...")` | `"01BXFF..."` | `note_item_01bxff` | Note cards (desktop unit tests) |
| `noteItemByTitle("Meeting notes")` | `"Meeting notes"` | `note_item_by_title_meeting_notes` | Note cards (automation) |
| `agendaSection("Today")` | `"Today"` | `agenda_section_today` | Agenda section headers |
| `savedAgendaCard("Work")` | `"Work"` | `saved_agenda_card_work` | Saved agenda cards |
| `projectCard("Project Alpha")` | `"Project Alpha"` | `project_card_project_alpha` | Project cards |
| `pomodoroTaskChip("Buy milk")` | `"Buy milk"` | `pomodoro_task_chip_buy_milk` | Pomodoro focus-task chips |
| `Dialog.title("discard")` | `"discard"` | `dialog_title_discard` | Dialog title |
| `taskAction("Archive")` | `"Archive"` | `task_action_archive` | Long-press action rows |
| `profileItem("Personal")` | `"Personal"` | `profile_item_personal` | Profile list items |
| `genUi("whatsnew")` | `"whatsnew"` | `genui_whatsnew` | GenUI surfaces |
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
  month header and the mode control ("Day"/"4 days"/"Week"/"Month") are
  `text:`-selected.
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
