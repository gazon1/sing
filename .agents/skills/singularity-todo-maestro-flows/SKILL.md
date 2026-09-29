---
name: singularity-todo-maestro-flows
description: Author and run Maestro UI flows for the Android app. Covers selector conventions (TestTags over text), Maestro 2.10 syntax quirks (no content-desc, notVisible over gone, rail scrolling), the edit-build-install-run loop, dynamic-id casing, artifacts debugging, and app behaviour facts flows encode (delete is undo-only, archive has no restore, long-press opens the editor). Use when writing or debugging Maestro YAML under Maestro/, when asked to "add a UI test" or "test this user flow", or when a flow fails on device.
---

# Maestro UI Flows

Maestro drives the Android app end-to-end. Flows live in `Maestro/flows/`, with
shared setup in `Maestro/helpers/`. Status: **green** — 10/10 smoke flows pass in
~3.5 min on the emulator (verified 2026-09-29), zero app crashes.

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

## Maestro 2.10 syntax quirks

Verified against the installed CLI (2.10.0) — these fail at flow-parse or
step time, not at authoring time:

- **`content-desc:` is not a supported selector property** — `Unknown Property:
  content-desc`. Select by `id:` (add a testTag if one is missing) or `text:`.
- **`gone:` does not exist** — use `notVisible:` inside `extendedWaitUntil`.
- **`scrollUntilVisible` swipes the screen centre.** It works for the
  centre-screen Menu bottom sheet, and fails for anything pinned to an edge —
  the Settings nav rail is the leftmost 80dp (~210px at 420dpi), so scrolling it
  needs an explicit swipe:
  ```yaml
  - swipe:
      start: 105, 1600
      end: 105, 800
  ```
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
  nothing. Before writing a flow, check it is reachable. The three ADRs named
  below list the current gaps.
- **Menu-sheet items below the fold are not tappable without a scroll.**
  `tapOn: id: menu_ai_chat` fails on an off-screen item; `scrollUntilVisible`
  first is required, exactly as for `menu_settings` and `menu_archive`.
- **Day cells in a calendar grid are `text:`-addressable** — the number is
  unique within one month, so a full-string match is unambiguous. The mode
  control is labelled with the current mode ("Day", "4 days", "Week", "Month")
  and opens the switcher; other header controls may be dead.
- **A failing batch run right after the first flow usually means the device died.**
  Check `adb devices` before debugging selectors; the emulator on this host dies
  after a few minutes of use and every later flow then fails in ~10 ms.

## Test data: ASCII only

`inputText` cannot type non-ASCII on Android. Type `Buy milk`, `Project Alpha`,
`Meeting notes` — not Cyrillic. `slug()` preserves non-Latin characters so a tag
stays addressable, but flows should not rely on it. (Russian *assertions* on
copy — "Удалить", "Архивировать" — are fine; only typed input is limited.)

## Helpers

| Helper | What it does |
|---|---|
| `launch-clean.yaml` | `launchApp` with `clearState: true`, waits 8s for `nav_tab_today` |
| `seed-task.yaml` | Creates "Buy milk" on Today and asserts `task_item_buy_milk` |

A flow failing right after the previous one has usually lost the *device*, not
its selectors — `adb devices` is the check. When running flows by hand, prefer
`./scripts/run-maestro.sh`: it runs one flow per invocation and relaunches the
emulator when the device dies, so a single crash costs one flow instead of the
suite. See `singularity-todo-emulator-launch`.

Start from `launch-clean` unless the flow needs pre-existing data. `seed-task`
hardcodes its title on purpose — see the comment in the file. Make new flows
self-seeding (create their own fixture) rather than inheriting state from an
earlier flow — see `docs/decisions/2026-09-29-maestro-archive-seed-strategy.md`.

## App behaviour facts flows encode

These are behaviours a flow author would otherwise guess wrong; each was learned
by a flow failing on it:

- **Long-press on an agenda row opens a context-menu bottom sheet**
  (`task_context_menu_sheet`), with rows tagged `sheet_item_<label>`: Open,
  Mark as completed/uncompleted, Pin/Unpin, Archive. Use
  `longPressOn: id: task_item_<slug>` then `tapOn: id: sheet_item_Archive`.
  Before the fix long-press fell through to the click handler and opened the
  editor — see `docs/decisions/2026-09-29-task-longpress-menu-and-archive-restore.md`.
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
- Menu-sheet items below the fold (`menu_settings`, `menu_archive`) need
  `scrollUntilVisible` — the sheet is centre-screen, so the command works there.
- The task detail keeps the shell FAB visible; detect a push by the editor
  appearing, not by the FAB disappearing.

## Timing and input

- **Never sleep.** Use `extendedWaitUntil` with a bounded timeout. Cold start
  genuinely needs ~8s (Koin + Room + DataStore migrations + calendar sync).
- **Hide the keyboard before tapping a trailing action** — `hideKeyboard` after
  `inputText`, or the save button sits under the IME.
- After `inputText`, assert the field's value before tapping save, so a dropped
  keystroke fails at the real cause.

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

## Tags

`smoke` = the fast must-pass subset. Domain tags (`tasks`, `notes`, `settings`,
`archive`, …) drive feature-scoped reruns. Add `smoke` when a failure should be
loud. Make every smoke flow earn the tag: it runs on every suite invocation.

## Do not confuse with `singularity-todo-test-tag-strategy`

That skill is about JUnit 5 `@Tag("slow")` for Gradle test filtering. Maestro's
`tags:` header is unrelated despite the similar name. There is no `slow`/`fast`
split for flows — the split is `smoke` vs domain.

## Adding a flow

1. Subdirectory matching the feature (mirrors `feature/` in shared).
2. `runFlow: ../../helpers/launch-clean.yaml`, or a seed helper for existing data.
3. Assert on `id:` selectors from `TestTags`; add a constant if one is missing
   (then the rebuild loop).
4. Tag it.
5. `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
6. On failure, follow "Debugging a failure" above.

Fuller guidance, including the tag table: `Maestro/README.md`.
