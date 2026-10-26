#!/usr/bin/env python3
"""
Validates Maestro flow id: selectors against the TestTags.kt catalogue.

Every id: used in a Maestro flow YAML must come from the canonical set in
TestTags.kt (static constants) or be a known dynamic expansion (e.g.
navTab("today") → nav_tab_today). An unknown id: is silently ignored by
Maestro and produces a false green — this script catches those before they
reach CI.

It also catches the second kind of a false green: a command whose selector is
*dropped* rather than unknown. Maestro's `inputText` types into the focused
field, so a selector on it goes nowhere — the text lands in the field above,
and the flow fails on the app's behaviour instead of on its own mistake.

Usage:
    python scripts/ci/validate-maestro-selectors.py [--warn] [flows-dir]

Args:
    flows-dir   Root of Maestro flows (default: Maestro/flows)
    --warn      Exit 0 even if errors are found (advisory mode for initial rollout)

Exit codes:
    0  All selectors valid (or only warnings in --warn mode)
    1  Unknown selectors found (blocking mode)
    2  Usage / file-not-found error
"""

import os
import re
import sys
from pathlib import Path
from typing import List, Set, Tuple

import yaml


# ---------------------------------------------------------------------------
# slug() — must match the algorithm in TestTags.kt
# ---------------------------------------------------------------------------
def slug(s: str) -> str:
    """Lowercase + replace runs of non-alphanumeric with underscore; trim separators at both ends."""
    result = []
    pending_sep = False
    for ch in s.strip():
        if ch.isalnum():
            if pending_sep and result:
                result.append('_')
            result.append(ch.lower())
            pending_sep = False
        else:
            pending_sep = True
    # Strip trailing non-alphanumeric
    while result and not result[-1].isalnum():
        result.pop()
    # Strip leading non-alphanumeric
    while result and not result[0].isalnum():
        result.pop(0)
    if not result:
        return "untitled"
    return ''.join(result)


# ---------------------------------------------------------------------------
# Canonical ID set from TestTags.kt static constants
#
# The set below was previously a hand-copied snapshot that drifted from
# TestTags.kt. As of 2026-10-13 we additionally parse TestTags.kt at runtime
# so new `const val` entries are picked up automatically. The hardcoded set
# remains as a fallback for tags that use string templates or are defined
# outside the simple `const val NAME = "value"` pattern.
# ---------------------------------------------------------------------------
_STATIC_CONST_PATTERN = re.compile(
    r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"'
)


def _parse_testtags_kt() -> Set[str]:
    """Extract all `const val NAME = "value"` strings from TestTags.kt."""
    testtags = (
        Path(__file__).resolve().parents[2]
        / "shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"
    )
    if not testtags.exists():
        return set()
    ids: Set[str] = set()
    for m in _STATIC_CONST_PATTERN.finditer(testtags.read_text(encoding="utf-8")):
        ids.add(m.group(2))
    return ids


#: `fun savedAgendaCard(name: String) = "saved_agenda_card_${slug(name)}"` — the
#: prefix a dynamic generator stamps onto everything it emits. Parsed from
#: TestTags.kt so the question "can this id still be produced?" is answered by the
#: file that declares the generator, rather than by whether a flow happens to
#: exercise that path today.
_DYNAMIC_GENERATOR_PATTERN = re.compile(r'"([a-z_0-9]+)_\$\{')


def _live_dynamic_prefixes() -> Set[str]:
    """Prefixes of every dynamic id generator currently declared in TestTags.kt."""
    testtags = (
        Path(__file__).resolve().parents[2]
        / "shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"
    )
    if not testtags.exists():
        return set()
    return {
        f"{m.group(1)}_"
        for m in _DYNAMIC_GENERATOR_PATTERN.finditer(
            testtags.read_text(encoding="utf-8")
        )
    }



def _is_stale_entry(de_id: str, live_prefixes: Set[str]) -> bool:
    """True when no generator left in TestTags.kt can still produce `de_id`.

    Split out so the predicate is exercised directly rather than re-implemented in
    a test. A second copy of this rule is the exact drift this repository has
    already paid for twice — the copy agrees until the rule changes, and then the
    test keeps passing the version nobody runs.
    """
    return not any(de_id.startswith(prefix) for prefix in live_prefixes)


# ---------------------------------------------------------------------------
# Known finite input spaces for TestTags dynamic functions.
#
# Documents all inputs whose output is deterministic and enumerable.
# Each entry is: (function_name_in_TestTags, input_string, expected_id_prefix).
# The validator uses this only to warn about DYNAMIC_EXPANSIONS entries that
# are NOT used in any flow — which suggests a stale or removed feature.
#
# Functions with unbounded inputs (e.g. taskItem(title), noteItemByTitle(title))
# are not listed here; their outputs are validated solely by being present in
# DYNAMIC_EXPANSIONS. When adding a new finite-space dynamic function, add its
# known inputs here so they are tracked and the validator can catch removals.
#
# Format per entry: (TestTags_function_name, input_display, id_prefix_for_check)
# ---------------------------------------------------------------------------
FINITE_DYNAMIC_SPACES: List[tuple[str, str, str]] = [
    # navTab — 7 bottom nav tabs (prefix: nav_tab_)
    ("navTab", "Today", "nav_tab_"),
    ("navTab", "Inbox", "nav_tab_"),
    ("navTab", "Upcoming", "nav_tab_"),
    ("navTab", "Plans", "nav_tab_"),
    ("navTab", "Pomodoro", "nav_tab_"),
    # The Calendar left the tab bar for the app menu — there is no
    # `nav_tab_calendar` any more, and the menu entry is `menu_calendar`.
    # themeModeButton — 3 ThemeMode values (prefix: theme_mode_button_)
    ("themeModeButton", "System", "theme_mode_button_"),
    ("themeModeButton", "Light", "theme_mode_button_"),
    ("themeModeButton", "Dark", "theme_mode_button_"),
    # noteFilterChip — filter names from NoteFilter enum
    ("noteFilterChip", "All", "note_filter_chip_"),
    ("noteFilterChip", "Archived", "note_filter_chip_"),
    ("noteFilterChip", "Pinned", "note_filter_chip_"),
    # noteAction — action names from NoteAction enum
    ("noteAction", "Edit", "note_action_"),
    ("noteAction", "Delete", "note_action_"),
    # calendarViewMode — the four CalendarViewMode constants, lowercased
    ("calendarViewMode", "day", "calendar_view_mode_"),
    ("calendarViewMode", "four_days", "calendar_view_mode_"),
    ("calendarViewMode", "week", "calendar_view_mode_"),
    ("calendarViewMode", "month", "calendar_view_mode_"),
    # calendarViewModeSelected — the same four constants, lowercased
    ("calendarViewModeSelected", "day", "calendar_view_mode_selected_"),
    ("calendarViewModeSelected", "four_days", "calendar_view_mode_selected_"),
    ("calendarViewModeSelected", "week", "calendar_view_mode_selected_"),
    ("calendarViewModeSelected", "month", "calendar_view_mode_selected_"),
]

# ---------------------------------------------------------------------------
# Dynamic ID expansions — known inputs for TestTags dynamic functions
# ---------------------------------------------------------------------------
DYNAMIC_EXPANSIONS: Set[str] = {
    # navTab("Today") etc. — bottom nav tabs
    "nav_tab_today",
    "nav_tab_inbox",
    "nav_tab_upcoming",
    "nav_tab_plans",
    "nav_tab_pomodoro",
    # The Calendar is not a tab any more. The id stays in the known set because
    # the NAV-01 flow asserts its *absence* from the bottom bar, and an id the
    # validator has never heard of cannot be asserted to be missing.
    "nav_tab_calendar",
    # menuItem(...) — menu sheet items
    "menu_archive",
    "menu_profiles",
    "menu_settings",
    "menu_calendar",
    "menu_all_projects",
    # plansSection("overdue") — the overdue section's id is a constant of the
    # grouper, so the expansion is finite.
    "plans_section_overdue",
    # taskItem(...) — from seed flows
    "task_item_buy_milk",
    "task_item_uniquesearchabletask2026",
    "task_item_water_the_plants",
    "task_item_return_to_today",
    "task_item_return_to_inbox",
    "task_item_task_from_inbox_tab",
    "task_item_task_from_today_tab",
    "task_item_full_task_with_all_fields",
    "task_item_renameme",
    "task_item_yesterdaytask",
    "task_item_todaytask",
    "task_item_tomorrowtask",
    "task_item_write_spec",
    "task_item_worktask",
    "task_item_grocery_run",
    # taskCheckbox(...) — from seed flows
    "task_checkbox_buy_milk",
    # subtaskItem(...) — from TASK-SUB-01 flow
    "subtask_item_child_task_one",
    # subtaskDelete(...) — from TASK-SUB-01 flow
    "subtask_delete_child_task_one",
    # taskItem(...) — seeded via DebugSeedActivity for TASK-SUB-01
    "task_item_parent_task",
    # taskItem("Anonymous task") — AUTH-FIRSTRUN-01 anonymous first-run flow
    "task_item_anonymous_task",
    # taskItem(...) — seeded by the SEARCH-08 flow: one title begins with the
    # term, the other only contains it
    "task_item_architecture_of_the_house",
    "task_item_airport_run",
    # pomodoroTaskChip(...) — from seed flows
    "pomodoro_task_chip_buy_milk",
    # savedAgendaCard(...) — used in flows
    "saved_agenda_card_work",
    "saved_agenda_card_personal",
    "saved_agenda_card_journey_view",
    "saved_agenda_card_journey_renamed",
    # agendaSection(...) — section headers used in flows
    "agenda_section_today",
    "agenda_section_no_date",
    "agenda_section_overdue",
    "agenda_section_upcoming",
    # byBlocked() — Statistics health drill-down
    "agenda_section_blocked",
    # byStagnant() — Statistics health drill-down
    "agenda_section_not_touched_in_a_while",
    # projectCard(...) — used in flows
    "project_card_project_alpha",
    # profileItem(...) — used in profile flows
    "profile_item_personal",
    "profile_item_work",
    # Dialog.title(...) — used in flows
    "dialog_title_priority",
    "dialog_title_discard",
    "dialog_title_delete",
    "dialog_title_delete_view",
    "dialog_title_archive",
    # Note items — desktop/seed
    "note_item_by_title_meeting_notes",
    "note_item_by_title_link_source",
    # agendaSectionTemplate(...)
    "agenda_section_template_by_tag",
    "agenda_section_template_due_date",
    # agendaSelectorOption(...)
    "agenda_selector_option_work",
    # Calendar day cells (hard-coded expanded form)
    "calendar_day_2026_09_15",
    # genUi(...)
    "genui_whatsnew",
    # Menu item slug — "Quick Search" slug = "quick_search"
    "menu_quick_search",
    # Calendar sync provider segments
    "calendar_sync_provider_google_calendar",
    "calendar_sync_provider_system_calendar",
    # Settings tabs (settingsTab function — slug of the tab name)
    "settings_tab_backup",
    "settings_tab_account",
    "settings_tab_aiprovider",
    "settings_tab_calendar",
    "settings_tab_files",
    "settings_tab_taggroups",
    "settings_tab_tags",
    "settings_tab_workschedule",
    # Menu items (menuItem function — slug of the label)
    "menu_notes",
    "menu_search",
    "menu_statistics",
    # Task actions (taskAction function)
    "task_action_archive",
    "task_action_mark_as_completed",
    "task_action_open",
    # Calendar view mode tabs (calendarViewMode function)
    "calendar_view_mode_day",
    # The tag is spelled from the enum constant's name, `FOUR_DAYS`, not from
    # the label the app shows ("4 days"). The label is the app's wording; the
    # constant's name is the mode's identity.
    "calendar_view_mode_four_days",
    "calendar_view_mode_week",
    "calendar_view_mode_month",
    # calendarViewModeSelected — the mode the dropdown says is selected.
    "calendar_view_mode_selected_day",
    "calendar_view_mode_selected_four_days",
    "calendar_view_mode_selected_week",
    "calendar_view_mode_selected_month",
    # Calendar weekday headers (calendarWeekdayHeader function)
    "calendar_weekday_mon",
    "calendar_weekday_tue",
    "calendar_weekday_wed",
    "calendar_weekday_thu",
    "calendar_weekday_fri",
    "calendar_weekday_sat",
    "calendar_weekday_sun",
    # Accent color swatches — used in settings flows (blue is exercised in theme-toggle.yaml)
    "settings_accent_swatch_blue",
    "settings_accent_swatch_purple",
    "settings_accent_swatch_pink",
    "settings_accent_swatch_red",
    "settings_accent_swatch_orange",
    "settings_accent_swatch_yellow",
    "settings_accent_swatch_green",
    "settings_accent_swatch_teal",
    # Agenda selector options (agendaSelectorOption function)
    "agenda_selector_option_none",
    # Reminder offset radio rows (reminderOffsetRadioRow function — slug of label)
    "reminder_defaults_section",
    "reminder_offset_radio_at_due_time",
    "reminder_offset_radio_15_minutes_before",
    "reminder_offset_radio_1_hour_before",
    "reminder_offset_radio_1_day_before",
}

# ---------------------------------------------------------------------------
# Allowlist: IDs that appear in flows but are NOT in TestTags.kt yet are
# intentionally used (known gaps tracked in TAGS.md missing testTags list).
# These are NOT errors — the validator warns but does not fail on them.
# ---------------------------------------------------------------------------
ALLOWLIST: Set[str] = {
    # Seeded task titles used across multiple flows (not in TestTags as function inputs)
    "task_item_купить_молоко",  # Cyrillic seeded task (tasks/07-cyrillic-title.yaml)
    # Seeded task from backup round-trip
    "task_item_backuproundtriptask",
    # Seeded note titles
    "note_item_by_title_alpha",
    "note_item_by_title_beta",
    # Seeded agenda card
    "saved_agenda_card_my_active_tasks",
    "saved_agenda_card_focus",
    # Seeded offline task
    "task_item_offline_task",
    # Dynamic task_item tags from seed data (validated at runtime, not statically)
    "task_item_attachtesttask2026",
    "task_item_deleteattachtask2026",
    "task_item_emptyurltask2026",
    "task_item_persistencetask2026",
    "task_item_pinnedtask2026",
    "task_item_pokupki",
    "task_item_rendertesttask2026",
    "task_item_savedalpha2026",
    "task_item_seed_task",
    # Seeded task titles that are now stable: the seed replaces a task with the
    # same title instead of appending one, so the title a flow seeds is the
    # title the flow can assert on — and the run id has nothing to do with it.
    "task_item_sdnodate",
    "task_item_signout_task",
    "task_item_clearsearchtask2026",
    "task_item_tasknodesc2026",
    "task_item_taskwithdesc2026",
    "task_item_unpinnedtask2026",
    "task_item_anonymous_task",
    "task_item_offline_task",
    "task_item_parent_task",
    "task_item_return_to_inbox",
    "task_item_return_to_today",
    "task_item_todaytask",
    "task_item_tomorrowtask",
    "task_item_uniquesearchabletask2026",
    "task_item_worktask",
    "task_item_yesterdaytask",
    # Dynamic checklist item from seed data
    "checklist_item_milk",
    # Settings tab IDs used in sync flows but not yet in TestTags.kt (pre-existing gap)
    "settings_tab_interface",
    "settings_tab_sync",
    "settings_content_sync",
    "settings_tab_agenda",
    "settings_tab_notifications",
    "settings_theme_mode_System",
    "settings_theme_mode_Light",
    "settings_theme_mode_Dark",
    # Note filter chips used in flows but not in TestTags.kt (pre-existing gap)
    "note_filter_chip_all",
    "note_filter_chip_archived",
    "note_filter_chip_pinned",
    # Note actions used in flows but not in TestTags.kt (pre-existing gap)
    "note_action_edit",
    # Add more as discovered
}

# ---------------------------------------------------------------------------
# Template variable pattern (${VAR}) used in shared sub-flows
# ---------------------------------------------------------------------------
TEMPLATE_VAR_PATTERN = re.compile(r'\$\{[A-Z_][A-Z0-9_]*\}')


# ---------------------------------------------------------------------------
# Collect all id: values from a YAML file
# ---------------------------------------------------------------------------
def collect_ids_from_file(path: Path) -> List[Tuple[str, int, str]]:
    """Return list of (id_value, line_number, flow_file) tuples."""
    ids = []
    try:
        docs = yaml.safe_load_all(path.read_text(encoding='utf-8'))
    except Exception as e:
        print(f"::error::failed to parse {path}: {e}", file=sys.stderr)
        return []

    for doc in docs:
        if not doc:
            continue
        ids.extend(_collect_ids_recursive(doc, path))

    return ids


# The keys a Maestro `inputText` command may carry besides `text`. Every one of
# them is a selector, and every one of them is dropped without a word.
INPUT_TEXT_SELECTOR_KEYS = ('id', 'idRegex', 'text', 'textRegex', 'label')


def collect_input_text_selectors(path: Path) -> List[Tuple[List[str], str]]:
    """Return the `inputText` commands in a flow that carry a selector.

    Maestro's `inputText` takes the text and types it into the *focused* field.
    A selector on the command is dropped without a word: the command log of
    Maestro 2.10 shows `inputTextCommand {text}` where the flow said
    `{id, text}`. The text therefore lands in whatever had the focus — usually
    the field above — and the flow fails on the app's behaviour instead of on
    its own mistake. On 2026-10-25 that is exactly what three auth flows did:
    the password was typed into the email field, the form stayed invalid, and
    the assertion that the error text was on the screen failed for a reason
    that had nothing to do with the app.

    The rule is about the *shape* of the command, not about the id: the id may
    be perfectly valid and still be ignored. A flow that wants to type into a
    field taps it first — `tapOn: {id: …}` then `inputText: "…"` — which is
    the pattern the rest of the tree uses.
    """
    found: List[Tuple[List[str], str]] = []
    try:
        docs = yaml.safe_load_all(path.read_text(encoding='utf-8'))
    except Exception:
        return found

    def walk(node) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == 'inputText' and isinstance(value, dict):
                    selectors = [k for k in INPUT_TEXT_SELECTOR_KEYS if k in value]
                    if selectors:
                        found.append((selectors, str(path)))
                walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    for doc in docs:
        if doc:
            walk(doc)
    return found


def _collect_ids_recursive(node, path: Path, line_base: int = 0) -> List[Tuple[str, int, str]]:
    """Walk a YAML node and extract all id: values."""
    results = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == 'id' and isinstance(value, str):
                # Get line number from YAML parse info if available
                lc = getattr(value, '__line__', None) or 0
                results.append((value, lc, str(path)))
            elif key in ('assertVisible', 'tapOn', 'extendedWaitUntil',
                         'scrollUntilVisible', 'openLink'):
                # These keys hold sub-structures that may contain id:
                results.extend(_collect_ids_recursive(value, path))
            else:
                results.extend(_collect_ids_recursive(value, path))
    elif isinstance(node, list):
        for item in node:
            results.extend(_collect_ids_recursive(item, path))
    return results


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    warn_mode = "--warn" in sys.argv
    if warn_mode:
        sys.argv.remove("--warn")

    flows_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("Maestro/flows")
    if not flows_root.exists():
        print(f"::error::flows directory not found: {flows_root}", file=sys.stderr)
        return 2

    all_valid_ids = DYNAMIC_EXPANSIONS | ALLOWLIST | _parse_testtags_kt()

    errors: List[str] = []
    warnings: List[str] = []
    flows_checked = 0

    # Collect all IDs actually used in flows (excluding helpers which use ${VAR} templates)
    ids_used_in_flows: Set[str] = set()
    for flow_path in sorted(flows_root.rglob("*.yaml")):
        if "helpers" in flow_path.parts:
            continue
        flows_checked += 1
        for id_val, line_no, _ in collect_ids_from_file(flow_path):
            # A template variable (${VAR}) anywhere in the id means the concrete value
            # is resolved at runtime by Maestro — we cannot statically validate it.
            # match() only anchors at the start, so `task_item_${RUN_ID}_x` slipped
            # through and was reported as unknown; search() catches the embedded form.
            if TEMPLATE_VAR_PATTERN.search(id_val):
                continue
            ids_used_in_flows.add(id_val)
            if id_val not in all_valid_ids:
                location = f"{flow_path}"
                if line_no:
                    location += f":{line_no}"
                errors.append(f"  {location}: unknown id: {id_val!r}")

        # A selector on `inputText` is ignored by Maestro, so the text goes to
        # the focused field. The flow still type-checks — the id is a real id —
        # so nothing else in this gate would say a word.
        for selectors, location in collect_input_text_selectors(flow_path):
            errors.append(
                f"  {location}: inputText carries a selector "
                f"({', '.join(selectors)}) — Maestro drops it and types into the "
                "focused field; tap the field first: tapOn: {id: …} then inputText"
            )

    # Warn about DYNAMIC_EXPANSIONS entries that are neither used by any flow nor
    # producible by any generator left in TestTags.kt.
    #
    # "Unused" alone is not "stale", and the difference cost this warning its
    # credibility: it named 28 entries, and every one of them was the output of a
    # generator that still exists (`savedAgendaCard("personal")`,
    # `calendarDay("2026-09-15")`, `genUi("whatsnew")`). They are unused because no
    # flow covers that path *yet* — a feature without a test, not a removed one.
    # Deleting them would not have cleaned anything up: a flow written next month
    # would come back "unknown id" against an id this file had deleted, because
    # flow ids are validated against this literal set and nothing expands
    # `saved_agenda_card_${...}` at validation time.
    #
    # So an entry is only reported once its generator is gone from TestTags.kt —
    # which is the event this warning was written to catch.
    live_prefixes = _live_dynamic_prefixes()
    stale_dynamic: List[str] = []
    for de_id in sorted(DYNAMIC_EXPANSIONS):
        if de_id not in ids_used_in_flows:
            # Skip seed-task entries — they are invoked via helpers, not directly.
            if de_id.startswith("task_item_") or de_id.startswith("task_checkbox_"):
                continue
            # Skip pomodoroTaskChip seed entries.
            if de_id.startswith("pomodoro_task_chip_"):
                continue
            if not _is_stale_entry(de_id, live_prefixes):
                continue
            stale_dynamic.append(de_id)

    if stale_dynamic:
        warnings.append(
            f"  DYNAMIC_EXPANSIONS has {len(stale_dynamic)} entry/entries no longer "
            "produced by any generator in TestTags.kt (possible stale):"
        )
        for sid in stale_dynamic:
            warnings.append(f"    - {sid}")
        warnings.append("  Remove stale entries from DYNAMIC_EXPANSIONS when the corresponding feature is removed.")

    # Sort errors by file for stable output
    errors.sort()

    if errors:
        print("::group::Maestro selector validation errors")
        print(f"Failed: {len(errors)} selector problem(s) in {flows_checked} flow(s)")
        print()
        for e in errors:
            print(e)
        print("::endgroup::")
        return 1

    if warnings:
        print("::group::Maestro selector warnings")
        for w in warnings:
            print(w)
        print("::endgroup::")

    print(f"✓ All selectors valid ({flows_checked} flow(s) checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
