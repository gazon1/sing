# Maestro TestTag Catalogue

Auto-generated from `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`.
Every `id:` used in a Maestro flow must come from this catalogue or a dynamic
function listed below. Do NOT use raw strings in flows.

Dynamic functions expand at runtime. Example: `TestTags.navTab("Today")` produces
`nav_tab_today`. The slug algorithm (`slug()`) lowercases and replaces runs of
non-alphanumeric characters with `_`.

## Static constants

### Auth
| Constant | Value | Where |
|---|---|---|
| `AUTH_EMAIL_INPUT` | `auth_email_input` | LoginScreen |
| `AUTH_PASSWORD_INPUT` | `auth_password_input` | LoginScreen |
| `AUTH_SIGN_IN_BUTTON` | `auth_sign_in_button` | LoginScreen |
| `AUTH_TOGGLE_MODE_BUTTON` | `auth_toggle_mode_button` | LoginScreen |
| `AUTH_CONTINUE_OFFLINE_BUTTON` | `auth_continue_offline_button` | LoginScreen |
| `AUTH_ERROR_TEXT` | `auth_error_text` | LoginScreen |
| `AUTH_LOADING` | `auth_loading` | LoginScreen |

### Navigation
| Constant | Value | Where |
|---|---|---|
| `NAV_MENU_BUTTON` | `nav_menu_button` | AndroidShellNav3 (bottom bar) |
| `MENU_SHEET` | `menu_sheet` | MenuBottomSheet |

### Tasks
| Constant | Value | Where |
|---|---|---|
| `TASKS_LIST` | `tasks_list` | Task list container |
| `TASKS_FAB` | `tasks_fab` | ExtendedFloatingActionButton (shell) |

### Task Editor
| Constant | Value | Where |
|---|---|---|
| `TASK_EDITOR_TITLE_INPUT` | `task_editor_title_input` | TaskTitleRow |
| `TASK_EDITOR_SAVE` | `task_editor_save` | TaskSaveBar |
| `TASK_EDITOR_MORE_MENU` | `task_editor_more_menu` | TaskDetailTopBar overflow |
| `TASK_CONTEXT_MENU_SHEET` | `task_context_menu_sheet` | Long-press context menu sheet (Android) |

### Pomodoro
| Constant | Value | Where |
|---|---|---|
| `POMODORO_PHASE_LABEL` | `pomodoro_phase_label` | Phase name (Work / Short Break / Long Break) |
| `POMODORO_TIMER_LABEL` | `pomodoro_timer_label` | mm:ss countdown |
| `POMODORO_CYCLE_LABEL` | `pomodoro_cycle_label` | "Cycle N" |
| `POMODORO_STOP_BUTTON` | `pomodoro_stop_button` | Stop |
| `POMODORO_PLAY_PAUSE_BUTTON` | `pomodoro_play_pause_button` | One control, play or pause by state |
| `POMODORO_SKIP_BUTTON` | `pomodoro_skip_button` | Skip to next phase |

### Agenda
| Constant | Value | Where |
|---|---|---|
| `AGENDA_SAVED_VIEWS_BUTTON` | `agenda_saved_views_button` | Top-bar bookmark |
| `AGENDA_SAVE_CURRENT_BUTTON` | `agenda_save_current_button` | Top-bar bookmark-add |
| `SAVED_AGENDA_LIST_BACK` | `saved_agenda_list_back` | Saved Views list top bar |
| `SAVED_AGENDA_CREATE_FAB` | `saved_agenda_create_fab` | Saved Views FAB |
| `SAVED_AGENDA_NAME_INPUT` | `saved_agenda_name_input` | View editor name field (pre-filled with the agenda title) |
| `SAVED_AGENDA_SAVE_BUTTON` | `saved_agenda_save_button` | View editor save |
| `SAVED_AGENDA_DELETE_BUTTON` | `saved_agenda_delete_button` | View editor delete (edit mode only) |

### Shared chrome
| Constant | Value | Where |
|---|---|---|
| `TOP_BAR_BACK_BUTTON` | `top_bar_back_button` | `BackTopAppBar` arrow — use this, not system back, on screens that guard unsaved drafts |

### Tags
| Constant | Value | Where |
|---|---|---|
| `TAGS_LIST` | `tags_list` | TagsScreen |

### Notes
| Constant | Value | Where |
|---|---|---|
| `NOTES_LIST` | `notes_list` | NotesListScreen |
| `NOTES_QUICK_ADD_INPUT` | `notes_quick_add_input` | NotesListScreen quick-add field |

### Note Editor
| Constant | Value | Where |
|---|---|---|
| `NOTE_EDITOR_TITLE_INPUT` | `note_editor_title_input` | NoteEditorScreen |
| `NOTE_EDITOR_BODY` | `note_editor_body` | NoteEditorScreen rich text |
| `NOTE_EDITOR_SAVE` | `note_editor_save` | NoteEditorScreen save button |
| `NOTE_EDITOR_NOTIFICATION_HOST` | `note_editor_notification_host` | NoteEditorScreen |

### Note Preview
| Constant | Value | Where |
|---|---|---|
| `NOTES_BACKLINKS_BUTTON` | `notes_backlinks_button` | NotePreviewScreen |

### Search
| Constant | Value | Where |
|---|---|---|
| `SEARCH_INPUT` | `search_input` | SearchScreen |

### Projects
| Constant | Value | Where |
|---|---|---|
| `PROJECT_EDITOR_NAME_INPUT` | `project_editor_name_input` | ProjectEditorScreen |
| `PROJECT_EDITOR_DESCRIPTION_INPUT` | `project_editor_description_input` | ProjectEditorScreen |
| `PROJECT_EDITOR_SAVE` | `project_editor_save` | ProjectEditorScreen |
| `PROJECT_EDITOR_BACK` | `project_editor_back` | ProjectEditorScreen |
| `PROJECT_EDITOR_NOTIFICATION_HOST` | `project_editor_notification_host` | ProjectEditorScreen |

### Backup
| Constant | Value | Where |
|---|---|---|
| `BACKUP_TOP_BAR_BACK` | `backup_top_bar_back` | BackupScreen |

## Dynamic functions

Use `TestTags.<function>(<input>)` in Kotlin. In a Maestro flow, hard-code
the expanded form (the `id:` Maestro accepts does not call functions — flows
use the expanded string directly).

| Function | Input example | Expanded id | Used for |
|---|---|---|---|
| `navTab("Today")` | `"Today"` | `nav_tab_today` | Bottom nav tabs |
| `navTab("Inbox")` | `"Inbox"` | `nav_tab_inbox` | Bottom nav tabs |
| `navTab("Upcoming")` | `"Upcoming"` | `nav_tab_upcoming` | Bottom nav tabs |
| `navTab("Plans")` | `"Plans"` | `nav_tab_plans` | Bottom nav tabs |
| `navTab("Pomodoro")` | `"Pomodoro"` | `nav_tab_pomodoro` | Bottom nav tabs |
| `navTab("Calendar")` | `"Calendar"` | `nav_tab_calendar` | Bottom nav tabs |
| `menuItem("Settings")` | `"Settings"` | `menu_settings` | Menu bottom sheet items |
| `menuItem("Profile & sync")` | `"Profile & sync"` | `menu_profile_sync` | Menu bottom sheet items |
| `menuItem("Statistics")` | `"Statistics"` | `menu_statistics` | Menu bottom sheet items |
| `menuItem("Notes")` | `"Notes"` | `menu_notes` | Menu bottom sheet items |
| `menuItem("AI Chat")` | `"AI Chat"` | `menu_ai_chat` | Menu bottom sheet items |
| `menuItem("Search")` | `"Search"` | `menu_search` | Menu bottom sheet items |
| `menuItem("Archive")` | `"Archive"` | `menu_archive` | Menu bottom sheet items |
| `menuItem("Profiles")` | `"Profiles"` | `menu_profiles` | Menu bottom sheet items |
| `menuItem("Quick search")` | `"Quick search"` | `menu_quick_search` | Menu bottom sheet items |
| `settingsTab("Interface")` | `"Interface"` | `settings_tab_interface` | Settings nav rail tabs |
| `settingsTab("Agenda")` | `SettingsTab.Agenda.name` | `settings_tab_agenda` | Settings nav rail tabs |
| `settingsTab("Notifications")` | `SettingsTab.Notifications.name` | `settings_tab_notifications` | Settings nav rail tabs |
| `settingsTab("AIProvider")` | `SettingsTab.AIProvider.name` | `settings_tab_aiprovider` | Settings nav rail tabs |
| `settingsTab("WorkSchedule")` | `SettingsTab.WorkSchedule.name` | `settings_tab_workschedule` | Settings nav rail tabs |
| `settingsTab("Calendar")` | `SettingsTab.Calendar.name` | `settings_tab_calendar` | Settings nav rail tabs |
| `settingsTab("Tags")` | `SettingsTab.Tags.name` | `settings_tab_tags` | Settings nav rail tabs |
| `settingsTab("TagGroups")` | `SettingsTab.TagGroups.name` | `settings_tab_taggroups` | Settings nav rail tabs |
| `settingsTab("Files")` | `SettingsTab.Files.name` | `settings_tab_files` | Settings nav rail tabs |
| `settingsTab("Backup")` | `SettingsTab.Backup.name` | `settings_tab_backup` | Settings nav rail tabs |
| `settingsTab("Account")` | `SettingsTab.Account.name` | `settings_tab_account` | Settings nav rail tabs |
| `taskItem("Buy milk")` | `"Buy milk"` | `task_item_buy_milk` | Task list rows |
| `taskCheckbox("Buy milk")` | `"Buy milk"` | `task_checkbox_buy_milk` | Task checkboxes |
| `noteItemByTitle("Meeting notes")` | `"Meeting notes"` | `note_item_by_title_meeting_notes` | Note cards (automation) |
| `noteItem("01BXFF...")` | `"01BXFF..."` | `note_item_01bxff` | Note cards (desktop unit tests) |
| `projectCard("Project Alpha")` | `"Project Alpha"` | `project_card_project_alpha` | Project cards |
| `sheetItem("Archive")` | `"Archive"` | `sheet_item_archive` | Context-menu sheet rows |
| `pomodoroTaskChip("Buy milk")` | `"Buy milk"` | `pomodoro_task_chip_buy_milk` | Pomodoro focus-task chips |
| `genUi("whatsnew")` | `"whatsnew"` | `genui_whatsnew` | GenUI surfaces |

## Raw-string tags (NOT via TestTags.kt — avoid where possible)

These are used directly in composables with hardcoded strings. Prefer adding
them to `TestTags.kt` and using the constant instead when touching the file.

| Tag | File | Used for |
|---|---|---|
| `projects_notification_host` | `ProjectsScreen.kt` | Notification host |
| `chat_notification_host` | `ChatScreen.kt` | Notification host |
| `archive_notification_host` | `ArchiveScreen.kt` | Notification host |

## Missing testTags (known gaps — do NOT use for new flows)

These elements exist in the UI but have no stable testTag. When adding a flow
that needs one of these, add the testTag first (PR-0 phase for that screen).

- Task editor: priority card, due-date row, due-time row, reminder row,
  checklist section, checklist add input, checklist add button, attachments section,
  add attachment button, delete button, error message
- Note editor: delete button, markdown toolbar
- Project detail: top bar
- Settings tabs: all 11 have `settings_tab_<name>`, lower-case. The rail is a
  scrollable 80dp column, so a flow must swipe it directly — `scrollUntilVisible`
  swipes the content pane, not the rail.
- Search: none — `search_input` now exists
- AI Chat: chat input, send button

- Calendar: view mode button, today button, prev/next, mini-calendar toggle,
  day cells (`calendar_day_<YYYY-MM-DD>`), create task button
- Sync: auto-switch, interval slider, sync-now button
- Backup: create button, restore button, export settings, import settings
- Profile: create button, delete button, profile items
- Statistics: last-7-days chart
- Archive: archive-all button, restore button per row
- Tags: FAB
- Dialog buttons (Confirm/Delete/Cancel/Save): no testTag — use `text:` with
  the visible button label for now
- The detail overflow menu's own items (Архивировать / Удалить / Восстановить)
  are `text:`-selected: the overflow button is tagged, its rows are not.

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
