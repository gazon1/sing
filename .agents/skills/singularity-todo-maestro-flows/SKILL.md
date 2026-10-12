---
name: singularity-todo-maestro-flows
description: Author and run Maestro UI flows for the Android app. Covers selector conventions (TestTags over text), adaptive scrolling (scrollUntilVisible instead of hardcoded swipe), Maestro 2.10 syntax quirks, the edit-build-install-run loop, phone-viewport constraints, menu navigation patterns, animation timing, and app behaviour facts flows encode. Use when writing, fixing, or debugging Maestro YAML under Maestro/, when asked to "add a UI test", or when a flow fails on device.
---

# Maestro UI Flows

Maestro drives the Android app end-to-end. Flows live in `Maestro/flows/` across 11
subdirectories (~39 flows total). Helpers live in `Maestro/helpers/`.

Status (run 38223116125): 12 flows fixed across classes A–D (hardcoded swipe,
missing nav menu openers, menu item scroll, animation timing). Class E (27 flows)
is undiagnosed — seed/state leakage, selector mismatches, and agenda timing issues
require live device or detailed device logs to resolve.

## Prerequisites: emulator first

Flows need a booted emulator. Getting one up on this host has host-specific
traps (GPU dead ends, camera-thread crash, process-verification gotchas) —
delegate to `singularity-todo-emulator-launch`. Device prep (wake, unlock, IME
workaround, install, logcat) is `singularity-todo-adb-workflow` /
`scripts/run-maestro.sh`.

## Running

```bash
just tm                        # all flows
just tm tags=smoke             # smoke subset
just tm flow=Maestro/flows/tasks
RUN_MAESTRO=1 ./check.sh       # opt-in, from the local check pipeline
```

`just tm` forwards to `scripts/run-maestro.sh`, which picks or relaunches the
device, installs the APK, runs one flow per Maestro invocation, and fails on any
`FATAL EXCEPTION` in logcat even when Maestro exits 0.

Running flows by hand is a debugging aid, not the normal path:

```bash
maestro test Maestro/flows/smoke/01-launch-today.yaml   # one flow
```

Maestro 2.10 accepts neither a directory nor a glob as the target, and
**`--include-tags` is ignored when a single file is passed** — the runner
therefore filters the file list itself by parsing each flow's `tags:` header.

## The edit–build–install–run loop

Maestro never rebuilds anything. After touching any composable or `TestTags.kt`:

```bash
./gradlew :androidApp:assembleDebug
adb -s emulator-5554 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
maestro test Maestro/flows/<sub>/<file>.yaml
```

A flow failing on a selector that *should* exist usually means the installed APK
predates the tag — rebuild and reinstall before debugging the selector.

## Selector conventions

**Select by `id:`, never by `text:`.** The registry is
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`; the
expanded catalogue of usable ids is `Maestro/TAGS.md`.

```yaml
- tapOn:
    id: tasks_fab              # constant
- tapOn:
    id: task_item_buy_milk     # dynamic: TestTags.taskItem("Buy milk")
```

Dynamic tags run through `TestTags.slug()` — **lowercase**, non-alphanumerics
collapsed to `_`. `SettingsTab.AIProvider` becomes `settings_tab_aiprovider`,
not `settings_tab_AIProvider`. Write the lowercase form in flows.

If a screen has no testTag, **add one** (constant in `TestTags.kt`,
`Modifier.testTag(...)` at the call site, then the rebuild loop above) rather
than falling back to visible text.

`menuItem()` is keyed by the **visible label**, not the destination title, so
`Profile & sync` and `Settings` do not collide.

## Adaptive scrolling: `scrollUntilVisible` over hardcoded swipe

**Never use fixed swipe coordinates.** The phone AVD viewport is 320×640px
physical (~420dpi), giving ~1680dp vertical. A swipe from y=1600 to y=800 is
already at the edge of the usable area on a short screen, and goes off-screen
entirely on 640px-height phones. It only "worked" on wider 1280×720 AVDs because
y=1600 was 2/3 down the screen there.

Use `scrollUntilVisible` with a direction for anything that might be below the fold:

```yaml
# Always open the hamburger menu first — the sheet is centre-screen.
- tapOn:
    id: nav_menu_button
# Then scroll the item into view.
- scrollUntilVisible:
    element:
      id: menu_settings
    direction: DOWN        # DOWN for items near the bottom of the sheet
    timeout: 10000         # 10 s — phone AVD has 5+ items + section headers + dividers
- tapOn:
    id: menu_settings
```

**Direction convention:**
- `direction: DOWN` — target item is near the bottom of the sheet (most menu items)
- `direction: UP` — target item is at the top; Maestro scrolls from the centre,
  so UP works for items that are above the centre but below the visible top

**Timeout:** always 10 000 ms for menu items. The phone AVD sheet has
5+ items + section header + dividers; 5 000 ms is insufficient under emulator
load.

The old hardcoded swipe pattern is retired:
```yaml
# OLD — do not use. Breaks on phone viewports (y=1600 is out-of-bounds on 640px height).
- swipe:
    start: 105, 1600
    end: 105, 800
```

### Menu navigation patterns

Every flow that opens the nav drawer follows this sequence:

```
tapOn id: nav_menu_button
scrollUntilVisible element.id: <target_item> direction: DOWN timeout: 10000
tapOn id: <target_item>
```

**Items requiring scroll on phone viewport** (below the fold on 320×640):
- `menu_notes`, `menu_statistics`, `menu_ai_chat` — Destinations section, below fold
- `menu_settings`, `menu_profiles`, `menu_quick_search` — Account section, visible
  without scroll on most screens BUT `menu_settings` still uses scrollUntilVisible
  in existing flows for consistency and robustness

**Items that are always visible without scrolling:**
- `menu_archive`, `menu_search` — visible on most phone screens

## Maestro 2.10 syntax quirks

Verified against the installed CLI (2.10.0) — these fail at flow-parse or
step time, not at authoring time:

- **`content-desc:` is not a supported selector property** — `Unknown Property:
  content-desc`. Select by `id:` (add a testTag if one is missing) or `text:`.
- **`gone:` does not exist** — use `notVisible:` inside `extendedWaitUntil`.
- **`scrollUntilVisible` scrolls the screen centre.** It works for the
  centre-screen Menu bottom sheet. For edge-pinned elements (nav rail on phone,
  where the SettingsNavRail is the leftmost 80dp), a `direction: UP` swipe from
  below the element may be needed — but only as a fallback, and only with
  coords that are within the element's bounds.
- `index:` disambiguates duplicate `text:` matches (two buttons both reading
  "Удалить" in one dialog). A bare `text:` (no index) is often the right choice —
  an index that no longer resolves fails the step outright.
- **ContentDescription-only elements are unreachable.** Material3 pickers (the
  date picker) put the value in `contentDescription` and leave `text` empty, and
  there is no `content-desc` selector to fall back on. Select those with a regex
  over the same string, e.g. `text: ".* 1, 20[0-9][0-9]"` for the 1st of the
  month on screen — the year wildcard keeps the flow valid past the turn of the year.
- **The task detail screen has no save button.** Edits autosave; `task_editor_save`
  exists only on the create screen. A flow that expects it on the detail screen
  fails with a confusing "not found".
- **Pre-filled fields append.** `inputText` does not clear, so a field seeded
  with "Today" becomes "TodayFocus". `eraseText` first.
- **Leaving a dirty draft raises a guard.** The shared top bar is tagged
  `top_bar_back_button`; tapping it on a dirty editor opens a
  "Discard changes?" dialog that must be confirmed with `text: "Discard"`.
  System back does not pop the saved-view editor at all — use the top-bar arrow.
- **Some saves do not navigate.** The saved-view editor emits a "Saved" snackbar
  and stays open; the flow must leave explicitly.
- **Quick-add destinations are not stable.** The notes quick-add lands on the
  note's preview or straight in the editor, run to run. Tap the transition action
  with `optional: true` and then wait on the destination you actually need.
- **A feature can be modelled but unreachable.** Notes row actions (pin,
  archive, delete, multi-select), the Calendar Today / prev / next /
  mini-calendar controls, and the whole Sync configuration screen
  (`SyncConfigScreen` + a complete `SyncViewModel`, composed by nothing) exist
  with no control that dispatches them, and long-press on a note row does
  nothing. Before writing a flow, check it is reachable.
- **Day cells in a calendar grid are `text:`-addressable** — the number is
  unique within one month, so a full-string match is unambiguous. The mode
  control is labelled with the current mode ("Day", "4 days", "Week", "Month")
  and opens the switcher; other header controls may be dead.
- **A failing batch run right after the first flow usually means the device died.**
  Check `adb devices` before debugging selectors; the emulator on this host dies
  after a few minutes of use and every later flow then fails in ~10 ms.

## Animation and timing

- **`waitForAnimationToEnd` after PORTRAIT rotation: use 5 000 ms, not 2 000 ms.**
  The CI emulator is slower to complete the LANDSCAPE→PORTRAIT transition than a
  local machine. Flows that rotate back to portrait and immediately assert
  UI state will flake on CI with 2 000 ms.
- **Never sleep.** Use `extendedWaitUntil` with a bounded timeout. Cold start
  genuinely needs ~8s (Koin + Room + DataStore migrations + calendar sync).
- **Hide the keyboard before tapping a trailing action** — `hideKeyboard` after
  `inputText`, or the save button sits under the IME.
- After `inputText`, assert the field's value before tapping save, so a dropped
  keystroke fails at the real cause.

## Test data: ASCII only

`inputText` cannot type non-ASCII on Android. Type `Buy milk`, `Project Alpha`,
`Meeting notes` — not Cyrillic. `slug()` preserves non-Latin characters so a tag
stays addressable, but flows should not rely on it. (Russian *assertions* on
copy — "Удалить", "Архивировать" — are fine; only typed input is limited.)

## Helpers

| Helper | What it does |
|---|---|
| `launch-clean.yaml` | `launchApp`, waits 30s for `nav_tab_today`; includes `waitForAnimationToEnd` after launch |
| `seed-task.yaml` | Creates "Buy milk" on Today via UI (not deep-link), asserts `task_item_buy_milk`; 10s timeout + `waitForAnimationToEnd` |
| `seed.yaml` | Seeds via `todo-debug://seed?QUERY` deep-link; supports `{{title}}`, `{{due}}` via `QUERY` env var |
| `retry-3x.yaml` | Runs `TARGET_FLOW` up to 3 times; use only for confirmed device-variance issues, not selector bugs |
| `open-menu-item.yaml` | Opens a menu sheet item by `ITEM_ID` and waits for `DEST_ID` |
| `pick-date.yaml` | Date picker helper using `scripts/date.js` |

A flow failing right after the previous one has usually lost the *device*, not
its selectors — `adb devices` is the check. When running flows by hand, prefer
`./scripts/run-maestro.sh`: it runs one flow per invocation and relaunches the
emulator when the device dies, so a single crash costs one flow instead of the
suite. See `singularity-todo-emulator-launch`.

Start from `launch-clean` unless the flow needs pre-existing data. `seed-task`
hardcodes its title on purpose — see the comment in the file. Make new flows
self-seeding (create their own fixture) rather than inheriting state from an
earlier flow.

## App behaviour facts flows encode

These are behaviours a flow author would otherwise guess wrong; each was learned
by a flow failing on it:

- **Long-press on an agenda row opens a context-menu bottom sheet**
  (`task_context_menu_sheet`), with rows tagged `sheet_item_<label>`: Open,
  Mark as completed/uncompleted, Pin/Unpin, Archive. Use
  `longPressOn: id: task_item_<slug>` then `tapOn: id: sheet_item_Archive`.
  Before the fix long-press fell through to the click handler and opened the
  editor.
- **The detail overflow menu is state-dependent.** An active task shows
  Архивировать / Удалить; an archived (trashed) task shows Восстановить alone.
  A flow that archives and then expects Удалить will not find it.
- **Delete is immediate and reversible** — a "Task deleted" undo snackbar, no
  confirmation dialog. The flow asserts the row is gone after `back`, and the
  undo path is a separate concern.
- **Archive -> restore round trip works**: archive via the context menu, open the
  task from the Archive screen, overflow menu -> Восстановить, the screen pops
  back and the task reappears in Inbox.
- **An undated task lands in Inbox, not Today** — `create-task` taps to Inbox to
  find its row.
- The task detail keeps the shell FAB visible; detect a push by the editor
  appearing, not by the FAB disappearing.

## Root causes of known failures (run 38223116125)

| Class | Root cause | Flows | Fix |
|---|---|---|---|
| A | Hardcoded swipe coords out-of-bounds on phone AVD | `backup-round-trip`, `smoke-settings-cycle-tabs` | `scrollUntilVisible direction: UP` |
| B | Missing `nav_menu_button` + `scrollUntilVisible` before `menu_settings` | `calendar-sync-provider-toggle`, `calendar-sync-system-toggle`, `calendar-sync-system-unavailable`, `sync-change-interval`, `negative-empty-tags` | `scrollUntilVisible element.id: menu_settings direction: DOWN timeout: 10000` |
| C | `menu_notes` below fold + 5 s timeout | `notes-edit-body`, `notes-filter-chips`, `notes-create-note`, `negative-empty-notes` | Same pattern as class B |
| D | `waitForAnimationToEnd` 2000 ms after PORTRAIT rotation | `lifecycle-rotate-in-editor` | 2000 ms → 5000 ms |
| E | Seed/state leakage, wrong testTag, agenda section timing | 27 flows (undiagnosed) | Requires live device or detailed device logs |

## Recon before writing a flow — the probe trick

Do not write a flow against selectors you inferred from the source. Drive the
screen first and read what is actually there. The cheapest way is a **probe
flow**: navigate to the screen, then assert something that cannot exist. The
assertion fails, and Maestro captures the view hierarchy at exactly that
moment.

```yaml
appId: com.singularity.todo
name: probe-thing
---
- runFlow: ../../helpers/launch-clean.yaml
- tapOn:
    id: nav_tab_pomodoro
- assertVisible:
    id: zzz_nonexistent_probe      # fails on purpose, dumping the hierarchy
```

```bash
maestro test Maestro/flows/<sub>/<probe-name>.yaml
D=~/.maestro/tests/$(ls -1 ~/.maestro/tests | tail -1)          # names sort chronologically
f=$(find "$D" -path "*hierarchy*" -name "*.json" | tail -1)
grep -o '"resource-id" : "[^"]*"' "$f" | sort -u
grep -o '"text" : "[^"]*"' "$f" | sort -u
```

This is how the selectors in this suite were chosen, and it is what caught the
rows that turned out to be dead taps and the screens with no affordance. Guessed
selectors produced three wrong flows in a row before the probe habit stuck; the
flow *failing on a selector that should exist* is far more often "the APK
predates the tag" or "this row does nothing" than a bug in the selector.

Delete the probe once the real flow replaces it — probes are scaffolding, and a
stale one left in the tree is noise.

## Debugging a failure

1. **Crash or assertion?** `adb -s emulator-5554 logcat -d -b crash | grep -A30
   "FATAL EXCEPTION"`. An app crash surfaces as the system "keeps stopping"
   dialog on screen and every later step failing — the exception names the real
   bug (three shipped crashes were found exactly this way).
2. **Assertion failure:** read the captured hierarchy —
   `~/.maestro/tests/<timestamp>/<flow-name>/screen-hierarchy/<step>.json`.
   Extract what was actually on screen:
   ```bash
   grep -o '"resource-id" : "[^"]*"' <step>.json | sort -u
   grep -o '"text" : "[^"]*"' <step>.json | sort -u
   ```
   Compare against `Maestro/TAGS.md` — usually the id is mis-cased, below the
   fold, or on a screen the previous step never opened.
3. **Nothing on screen but the home screen** — the app crashed earlier in the
   flow; go back to step 1.
4. **Class E failures (27 flows):** The Maestro XML has no per-step detail.
   Re-run with Maestro's `--debug` flag and capture device logcat at failure
   time. The CI artifact `maestro-failures-*/maestro.log` shows only Maestro's
   own polling output; the actual Compose/hierarchy state requires logcat.

## Troubleshooting

### Retry strategy: when to fix vs when to retry

**Fix the root cause, not the symptom.** A flow that fails because an element
was not visible after 5 s is telling you the element needs 5+ s on that device
— the retry just delays that signal.

Use `Maestro/helpers/retry-3x.yaml` only when:
- The flow is confirmed to need > 2 script-level retries (`MAX_RETRIES=2` in
  `run-maestro.sh` covers CI device disconnects).
- The element timing has genuine variance (e.g. cold-start variance, not a
  broken selector).
- A `known-bug` tag is applied and the retry is a temporary measure while the
  bug is tracked.

Do NOT use retry when:
- Multiple flows fail simultaneously with the same selector — this is a cascade,
  not independent variance. Check `seed-task.yaml` first.
- The failure is `Not found` rather than `Not visible` — retry does not help.

### Seed cascade detection

The most common cascade failure mode in this suite: `seed-task.yaml` times out
(5 s was insufficient), `task_item_buy_milk` never appears, and 14 flows fail
in the same CI run with `task_item_buy_milk not visible` — but only the first
failure is in `seed-task.yaml`. Every other flow just used that fixture.

**How to recognise it:**
- 14 flows all failing with the same `Not visible: task_item_buy_milk` at the
  same step position (immediately after seed).
- The failure appeared after a CI runner was under load (seed takes longer when
  the host is busy).
- The seed-task itself passed on a previous run.

**Correct fix:** Increase the timeout in `seed-task.yaml` — do NOT add retry
wrappers to each consumer flow.

### CI vs local timing

The CI emulator runs on a shared host where gradle daemons, sandbox processes,
and the emulator itself compete for CPU. A flow that takes 8 s to seed a task
locally can take 25 s on CI. All hard timeouts should be calibrated against
CI, not local dev speed.

Practical multipliers observed on this CI host:

| Operation | Local (fast) | CI (loaded) | Safe timeout |
|---|---|---|---|
| App cold start | ~5 s | ~20 s | 30 000 ms |
| `waitForAnimationToEnd` after PORTRAIT | ~1 500 ms | ~4 500 ms | 5 000 ms |
| `extendedWaitUntil` for a visible element | < 2 s | < 8 s | 10 000 ms |
| Menu sheet scroll + tap | < 1 s | < 4 s | 10 000 ms |

When in doubt, use the CI-safe value. A timeout that is slightly too long
costs at most the duration of the wait; a timeout that is slightly too short
produces a cascade of failures that requires a full re-run to diagnose.

## Tags

`smoke` = the fast must-pass subset. Domain tags (`tasks`, `notes`, `settings`,
`archive`, `calendar_sync`, `sync`, `negative`, `lifecycle`, `smoke` — see
`Maestro/TAGS.md`) drive feature-scoped reruns. Add `smoke` when a failure
should be loud. Make every smoke flow earn the tag: it runs on every suite
invocation.

## Do not confuse with `singularity-todo-test-tag-strategy`

That skill is about JUnit 5 `@Tag("slow")` for Gradle test filtering. Maestro's
`tags:` header is unrelated despite the similar name. There is no `slow`/`fast`
split for flows — the split is `smoke` vs domain.

## Adding a flow

1. Subdirectory matching the feature (mirrors `feature/` in shared).
2. `runFlow: ../../helpers/launch-clean.yaml`, or a seed helper for existing data.
3. Follow the menu navigation pattern: `tapOn id: nav_menu_button` →
   `scrollUntilVisible element.id: <target> direction: DOWN timeout: 10000` →
   `tapOn id: <target>`.
4. Assert on `id:` selectors from `TestTags`; add a constant if one is missing
   (then the rebuild loop).
5. Tag it with `smoke` only if it must always pass; otherwise use a domain tag.
6. `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
7. On failure, follow "Debugging a failure" above.

Fuller guidance, including the tag table: `Maestro/README.md`.
