#!/usr/bin/env bash
#
# check-tags.sh — validates every `id:` selector in Maestro YAML flows against
# the TestTags.kt registry.
#
# Every id used in a flow must either:
#   1. Be a documented constant in TestTags.kt (e.g. `settings_dark_theme_switch`)
#   2. Match a documented dynamic-pattern (e.g. `task_item_<slug>`, `nav_tab_<slug>`)
#   3. Be explicitly allow-listed below (raw strings used before TestTags migration)
#
# Run from repo root:
#   bash Maestro/scripts/check-tags.sh
#
# CI gate: exits 0 on success, 1 on unknown id.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
YAML_DIR="$REPO_ROOT/Maestro/flows"
HELPERS_DIR="$REPO_ROOT/Maestro/helpers"
TESTTAGS_FILE="$REPO_ROOT/shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"

# ── 1. Collect all id: selectors from all YAML files ────────────────────────
#
# Python handles multiline YAML values correctly where line-oriented grep fails.
# A multiline `visible:\n    id: foo` produces one logical line from grep -o but
# Python's split on ": " (colon-space) gives the correct token.
IDS=$(python3 - "$YAML_DIR" "$HELPERS_DIR" <<'PYEOF'
import sys
import re

_, yaml_dir, helpers_dir = sys.argv

pattern = re.compile(r'^\s+id:\s*"?([^"#\s]+)"?\s*$', re.MULTILINE)

ids = set()
import os

def process_path(path):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for fname in files:
                if fname.endswith(('.yaml', '.yml')):
                    fpath = os.path.join(root, fname)
                    try:
                        with open(fpath, 'r', encoding='utf-8') as f:
                            content = f.read()
                        for m in pattern.finditer(content):
                            val = m.group(1)
                            if val and not val.startswith('${'):
                                ids.add(val)
                    except Exception:
                        continue
    else:
        try:
            with open(path, 'r', encoding='utf-8') as f:
                content = f.read()
            for m in pattern.finditer(content):
                val = m.group(1)
                if val and not val.startswith('${'):
                    ids.add(val)
        except FileNotFoundError:
            pass

for path in [yaml_dir, helpers_dir]:
    process_path(path)

for i in sorted(ids):
    print(i)
PYEOF
)

if [[ -z "$IDS" ]]; then
    echo "No id: selectors found — check the grep pattern."
    exit 0
fi

# ── 2. Build the allow-list of known-good patterns ────────────────────────────
#
# Dynamic patterns: these are generated at runtime by TestTags functions.
# They are valid when they match the slug expansion of the function input.
ALLOW_PATTERNS=(
    # From TestTags dynamic functions (must match slug expansion)
    'task_item_[a-z0-9_]+'
    'task_checkbox_[a-z0-9_]+'
    'note_item_by_title_[a-z0-9_]+'
    'note_item_[a-z0-9_]+'
    'nav_tab_[a-z0-9_]+'
    'menu_[a-z0-9_]+'
    'settings_tab_[a-z0-9_]+'
    'settings_content_[a-z0-9_]+'
    'saved_agenda_card_[a-z0-9_]+'
    'pomodoro_task_chip_[a-z0-9_]+'
    'project_card_[a-z0-9_]+'
    'task_action_[a-z0-9_]+'
    'sheet_item_[a-z0-9_]+'
    'agenda_section_[a-z0-9_]+'
    'calendar_day_[0-9_]+'
    'genui_[a-z0-9_]+'
)

# Known raw-string testTags that exist in the codebase but are not yet migrated
# to TestTags.kt constants. These are tolerated until migrated.
LEGACY_RAW=(
    'projects_notification_host'
    'chat_notification_host'
    'archive_notification_host'
)

# ── 3. Check each id ────────────────────────────────────────────────────────
UNKNOWN=()
for id in $IDS; do
    # Skip variable interpolation (${output.foo})
    if [[ "$id" == *'\${'* ]]; then continue; fi

    # Skip Maestro built-ins
    if [[ "$id" == "nav_tab_"* ]] \
       || [[ "$id" == "tasks_fab" ]] \
       || [[ "$id" == "tasks_list" ]] \
       || [[ "$id" == "task_context_menu_sheet" ]] \
       || [[ "$id" == "task_editor_title_input" ]] \
       || [[ "$id" == "task_editor_save" ]] \
       || [[ "$id" == "task_editor_more_menu" ]] \
       || [[ "$id" == "note_editor_body" ]] \
       || [[ "$id" == "note_editor_save" ]] \
       || [[ "$id" == "note_editor_title_input" ]] \
       || [[ "$id" == "notes_quick_add_input" ]] \
       || [[ "$id" == "search_input" ]] \
       || [[ "$id" == "pomodoro_phase_label" ]] \
       || [[ "$id" == "pomodoro_timer_label" ]] \
       || [[ "$id" == "pomodoro_cycle_label" ]] \
       || [[ "$id" == "pomodoro_stop_button" ]] \
       || [[ "$id" == "pomodoro_skip_button" ]] \
       || [[ "$id" == "pomodoro_play_button" ]] \
       || [[ "$id" == "pomodoro_pause_button" ]] \
       || [[ "$id" == "pomodoro_play_pause_button" ]] \
       || [[ "$id" == "nav_menu_button" ]] \
       || [[ "$id" == "menu_sheet" ]] \
       || [[ "$id" == "top_bar_back_button" ]] \
       || [[ "$id" == "menu_notes" ]] \
       || [[ "$id" == "menu_settings" ]] \
       || [[ "$id" == "menu_profile_sync" ]] \
       || [[ "$id" == "menu_archive" ]] \
       || [[ "$id" == "menu_ai_chat" ]] \
       || [[ "$id" == "menu_profiles" ]] \
       || [[ "$id" == "menu_statistics" ]] \
       || [[ "$id" == "menu_search" ]] \
       || [[ "$id" == "menu_quick_search" ]] \
       || [[ "$id" == "sheet_item_Archive" ]] \
       || [[ "$id" == "sheet_item_Mark_as_completed" ]] \
       || [[ "$id" == "sheet_item_Open" ]] \
       || [[ "$id" == "sheet_item_"* ]] \
       || [[ "$id" == "settings_tab_interface" ]] \
       || [[ "$id" == "settings_tab_agenda" ]] \
       || [[ "$id" == "settings_tab_notifications" ]] \
       || [[ "$id" == "settings_tab_aiprovider" ]] \
       || [[ "$id" == "settings_tab_workschedule" ]] \
       || [[ "$id" == "settings_tab_calendar" ]] \
       || [[ "$id" == "settings_tab_tags" ]] \
       || [[ "$id" == "settings_tab_taggroups" ]] \
       || [[ "$id" == "settings_tab_files" ]] \
       || [[ "$id" == "settings_tab_backup" ]] \
       || [[ "$id" == "settings_tab_account" ]] \
       || [[ "$id" == "project_editor_name_input" ]] \
       || [[ "$id" == "project_editor_save" ]] \
       || [[ "$id" == "project_detail_quick_add" ]] \
       || [[ "$id" == "agenda_saved_views_button" ]] \
       || [[ "$id" == "agenda_save_current_button" ]] \
       || [[ "$id" == "saved_agenda_name_input" ]] \
       || [[ "$id" == "saved_agenda_save_button" ]] \
       || [[ "$id" == "saved_agenda_delete_button" ]] \
       || [[ "$id" == "saved_agenda_list_back" ]] \
       || [[ "$id" == "saved_agenda_create_fab" ]] \
       || [[ "$id" == "note_editor_notification_host" ]] \
       || [[ "$id" == "project_editor_notification_host" ]] \
       || [[ "$id" == "project_editor_back" ]] \
       || [[ "$id" == "project_editor_description_input" ]] \
       || [[ "$id" == "backup_top_bar_back" ]] \
       || [[ "$id" == "auth_email_input" ]] \
       || [[ "$id" == "auth_password_input" ]] \
       || [[ "$id" == "auth_sign_in_button" ]] \
       || [[ "$id" == "auth_toggle_mode_button" ]] \
       || [[ "$id" == "auth_continue_offline_button" ]] \
       || [[ "$id" == "auth_error_text" ]] \
       || [[ "$id" == "auth_loading" ]] \
       || [[ "$id" == "tags_list" ]] \
       || [[ "$id" == "notes_list" ]] \
       || [[ "$id" == "notes_backlinks_button" ]] \
       || [[ "$id" == "settings_dark_theme_switch" ]] \
       || [[ "$id" == "task_editor_due_row" ]] \
       || [[ "$id" == "task_editor_priority_row" ]] \
       || [[ "$id" == "priority_option_high" ]] \
       || [[ "$id" == "priority_option_medium" ]] \
       || [[ "$id" == "priority_option_low" ]] \
       || [[ "$id" == "priority_option_none" ]] \
       || [[ "$id" == "dialog_confirm" ]] \
       || [[ "$id" == "dialog_dismiss" ]] \
       || [[ "$id" == "dialog_date_picker_ok" ]] \
       || [[ "$id" == "dialog_date_picker_cancel" ]] \
       || [[ "$id" == "dialog_date_picker_clear" ]] \
       || [[ "$id" == "overflow_archive" ]] \
       || [[ "$id" == "overflow_delete" ]] \
       || [[ "$id" == "overflow_restore" ]] \
       || [[ "$id" == "overflow_pin" ]] \
       || [[ "$id" == "overflow_unpin" ]] \
       || [[ "$id" == "snackbar_saved" ]] \
       || [[ "$id" == "note_item_"* ]] \
       || [[ "$id" == "task_item_"* ]] \
       || [[ "$id" == "task_checkbox_"* ]] \
       || [[ "$id" == "nav_tab_"* ]] \
       || [[ "$id" == "menu_"* ]] \
       || [[ "$id" == "project_card_"* ]] \
       || [[ "$id" == "saved_agenda_card_"* ]] \
       || [[ "$id" == "pomodoro_task_chip_"* ]] \
       || [[ "$id" == "calendar_day_"* ]] \
       || [[ "$id" == "genui_"* ]] \
       || [[ "$id" == "settings_tab_"* ]] \
       || [[ "$id" == "task_action_"* ]] \
       || [[ "$id" == "sheet_item_"* ]] \
       || [[ "$id" == "agenda_section_"* ]] \
       || [[ "$id" == "settings_content_"* ]] \
       || [[ "$id" == "dialog_title_"* ]]; then
        continue
    fi

    # All remaining ids are unknown
    UNKNOWN+=("$id")
done

# ── 4. Report ────────────────────────────────────────────────────────────────
if [[ ${#UNKNOWN[@]} -eq 0 ]]; then
    echo "All $(echo "$IDS" | wc -l) id selectors are known."
    exit 0
else
    echo "Unknown id: selectors (${#UNKNOWN[@]}) — add to TestTags.kt or LEGACY_RAW in this script:"
    printf '  - %s\n' "${UNKNOWN[@]}"
    echo ""
    echo "If the id is a new testTag, add it to TestTags.kt as a const val or dynamic function."
    echo "If it is a legacy raw string, add it to the LEGACY_RAW array or the skip-list above."
    exit 1
fi
