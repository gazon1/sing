---
name: singularity-todo-maestro-flows
description: Author and run Maestro UI flows for the Android app. Covers seeding fixtures through the DebugSeedActivity deep-link instead of typing into the editor, why Compose's controlled BasicTextField cannot be filled by Maestro, selector conventions (TestTags over text), adaptive scrolling (scrollUntilVisible instead of hardcoded swipe), Maestro 2.10 syntax quirks, the edit-build-install-run loop, phone-viewport constraints, diagnosing an element that exists in code but is unreachable on screen, menu navigation patterns, animation timing, and app behaviour facts flows encode. Use when writing, fixing, or debugging Maestro YAML under Maestro/, when asked to "add a UI test", or when a flow fails on device.
---

# Maestro UI Flows

Maestro drives the Android app end-to-end. Flows live in `Maestro/flows/` across 11
subdirectories (~39 flows total). Helpers live in `Maestro/helpers/`.

Status (run 38223116125): classes A–D fixed (hardcoded swipe, missing nav menu
openers, menu item scroll, animation timing). Class E diagnosed on `emulator-5554`
— see "Seed fixtures, never type them" and `diagnosing-failures.md`.
All 4 Class E flows pass.

## Prerequisites: emulator first

Flows need a booted emulator. `./scripts/ensure-emulator.sh` is the one way to get
one — it prints a ready serial, or starts the AVD and waits for boot. Getting one up
on this host has host-specific traps (GPU dead ends, camera-thread crash,
process-verification gotchas) — delegate to `singularity-todo-emulator-launch`.
Device prep (wake, unlock, IME workaround, install, logcat) is
`singularity-todo-adb-workflow` / `scripts/run-maestro.sh`.

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
SERIAL=$(./scripts/ensure-emulator.sh)
adb -s "$SERIAL" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
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

### Before converting a `text:` selector to an `id:`

Three traps, each of which turns a passing flow red:

- **Check the tag actually exists.** More often than not it does — several chrome
  selectors (`search_empty_placeholder`, `project_detail_empty`,
  `calendar_weekday_*`) were declared and wired long before any flow used them.
  Grep `TestTags.kt` before adding one.
- **Check what is on screen at that step.** A title asserted while the editor is
  open is *not* a list row: `task_item_*` is only mounted once the editor
  closes. Five `assertVisible: "Buy milk"` lines sit in this position and must
  stay text. Read the two steps above the one you are changing.
- **Check the copy exists at all.** `backup/01-round-trip` asserted
  `"Backup created successfully"` while the ViewModel emits `"Backup created"` —
  an assertion that could never pass, and nobody noticed because the flow was
  already failing for other reasons.

Text that is *typed* (`inputText:` with an email or password) is data, not a
selector — leave it. So are calendar day numbers (unique within a month) and
the Material3 `contentDescription` regexes: Maestro 2.10 has no `content-desc`
selector to fall back on.

## Seed fixtures, never type them

**Seed through `helpers/seed.yaml`, which drives the `DebugSeedActivity` deep-link.
Typing a fixture into the editor does not work in this app.**

`TaskTitleRow` uses Compose's *controlled* field:

```kotlin
BasicTextField(value = title, onValueChange = { title = it; isDirty = true })
```

Neither Maestro `inputText` nor `adb shell input text` can fill it. Injected text
lands in the wrapped `EditText`, then the next composition writes the still-empty
`value` back over it. `onValueChange` never fires, `isDirty` stays false, and Save
never enables — the flow hangs on a title that looks filled in but is not in state.

The probe that settles it: type, reopen the editor, assert the text. Gone, so the
value never reached state. An `ImeAction` patch was tried and reverted — it removed
the action `inputText` depends on and broke entry outright.

`helpers/seed.yaml` takes a URL-encoded `QUERY`:

```yaml
- runFlow:
    file: ../../helpers/seed.yaml
    env:
      QUERY: "task=Buy%20milk&due=today"
```

| Query key | Creates |
|---|---|
| `task=<title>` | a task |
| `task=<title>&due=…` | task due `today` / `tomorrow` / `-1d` / `2026-10-01` |
| `note=<title>` | a note |
| `project=<name>` | a project |
| `profile=<name>` | a project profile and switches to it |

**An undated fixture lands in Inbox's "No Date" bucket, not Today.** A flow that
seeds `task=Buy milk` with no `due` and then looks on Today finds nothing — tap
`nav_tab_inbox`.

**`seed.yaml` has no tab round-trip, and does not need one.** An inbox→today
round-trip was added here on 2026-10-12 because a deep-link seed appeared not to
reach an already-composed agenda. Once the runner began sweeping each run's
fixtures on success, that stopped reproducing: what looked like "the row never
arrived" was the row arriving *below* rows earlier runs had left behind. So
before blaming the agenda, check for leftovers — a stale fixture masquerades as a
missing one.

### `launch-clean` does not clear the database — on purpose

`launchApp` with `clearState: false` resets AMS state only. Room persists across
runs on the same emulator, so **fixtures accumulate**.

Do not "fix" this with `clearState: true` or a blanket `pm clear`: both destroy
the pre-authenticated session the emulator snapshot carries, so every flow would
land on the auth screen. The accumulation is handled by the runner's selective
success sweep instead (see below), which leaves session, profile and auth state
alone and cannot touch another run's data.

`pm clear` remains available as a manual escape hatch when you want a genuinely
virgin device state — it costs you the authenticated session:

```bash
adb -s "$(./scripts/ensure-emulator.sh)" shell pm clear com.singularity.todo
```

Accumulation is harmless for a read-only fixture but corrupts a flow that *mutates*
its fixture and then asserts the mutated-away state — the previous run's already-dated
copy satisfies the selector. **Prefix the title with `${RUN_ID}`** and the runner
sweeps it for you:

```yaml
- runFlow:
    file: ../../helpers/seed.yaml
    env:
      QUERY: "task=${RUN_ID}%20My%20fixture"
- scrollUntilVisible:
    element:
      id: "task_item_${RUN_ID}_my_fixture"     # slug of "<run id> My fixture"
```

`scripts/run-maestro.sh` mints `RUN_ID` on **every attempt** and, after a
successful flow, deep-links `todo-debug://seed?cleanup=<RUN_ID>` to delete every
task, note and project whose title starts with that prefix. A retry after a lost
device gets a *new* id, so it cannot collide with the attempt it replaces.

**Prefix, not substring** — cleanup matches `startsWith`, so a leaked fixture
names the run that made it and real data is never caught by it.

`seed-task.yaml` deliberately keeps the **fixed** title "Buy milk": 17 flows
address its row by the stable `task_item_buy_milk` id, so a per-run stamp would
break every one of them. The runner sweeps it by its own literal prefix instead,
so it cannot accumulate into an ambiguous `tapOn`.

**Cleanup runs on success only** — a failed flow keeps its fixtures, because they
are the evidence. Same reason failure screenshots exist.

**A manual `maestro test <flow>` has no runner**: `RUN_ID` falls back to `manual`
from `Maestro/config.yaml` and nothing is swept. Debug from a `pm clear` slate;
set `MAESTRO_CLEANUP=0` to keep a run's fixtures for inspection.

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
  centre-screen Menu bottom sheet. It cannot reach an element **clipped by a
  sibling layout** — on a phone viewport the `SettingsNavRail` is the leftmost
  80dp and its lower tabs are cut off by the window, so no scroll direction
  brings them into view. Switch to the bottom nav or the menu sheet; there is no
  scroll that fixes a clipped element.
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
- **`hideKeyboard` is a commit, not a dismiss.** In this app it triggers the
  editor's autosave-on-dismiss and closes the screen. Never place it before the
  `task_editor_save` tap — it saves and navigates away, and the following step
  fails on a screen that is already gone. Use it only when leaving an editor is
  the intent, and assert on the destination.

## Test data: ASCII only

`inputText` cannot type non-ASCII on Android. Type `Buy milk`, `Project Alpha`,
`Meeting notes` — not Cyrillic. `slug()` preserves non-Latin characters so a tag
stays addressable, but flows should not rely on it. (Russian *assertions* on
copy — "Удалить", "Архивировать" — are fine; only typed input is limited.)

## Helpers

| Helper | What it does |
|---|---|
| `launch-clean.yaml` | `launchApp`, waits 30s for `nav_tab_today`; includes `waitForAnimationToEnd` after launch |
| `seed-task.yaml` | `launch-clean` + `seed.yaml` with the fixed title "Buy milk"; ends on Inbox and asserts `task_item_buy_milk` |
| `seed.yaml` | Seeds via `todo-debug://seed?QUERY` deep-link (task/note/project/profile); waits for the shell and leaves the app on Today |
| `open-menu-item.yaml` | Opens a menu sheet item by `ITEM_ID` and waits for `DEST_ID` |
| `pick-date.yaml` | Date picker helper using `scripts/date.js` |

A flow failing right after the previous one has usually lost the *device*, not
its selectors — `adb devices` is the check. When running flows by hand, prefer
`./scripts/run-maestro.sh`: it runs one flow per invocation and relaunches the
emulator when the device dies, so a single crash costs one flow instead of the
suite. See `singularity-todo-emulator-launch`.

Start from `launch-clean`, then `seed.yaml` when the flow needs data. Make new
flows self-seeding rather than inheriting state from an earlier flow. Note that
`launch-clean` does not clear Room, so a fixture written by a previous run is
still there — see "Seed fixtures, never type them".

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
- The task detail keeps the shell FAB visible; detect a push by the editor
  appearing, not by the FAB disappearing.

## When a flow fails

Open [`diagnosing-failures.md`](diagnosing-failures.md) — the failure-class table
for the last CI run, the logcat/hierarchy triage steps, when a selector is
unreachable because the element is laid out off-screen, the retry policy, and the
CI-vs-local timeout multipliers.

## Recon before writing a flow — the probe trick

**Probe the mechanism before changing product code.** A test failure names a
symptom; a product change guessed from a symptom is as likely to break the real
path as to fix it. A failing test is evidence about the test until a probe shows
otherwise — the defect is in the product only when you have *seen* the
user-visible behaviour, not inferred it from a step failing. This session wrote
and reverted two speculative changes to `TaskCreateScreen` before a probe (type,
reopen, assert the text) settled the actual mechanism.

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
2. **Paths are relative to the flow file, not the repo root.** A flow in
   `Maestro/flows/<sub>/` references a helper as `../../helpers/<name>.yaml`.
   The helpers' own internal `runFlow` calls use a bare filename, because they
   sit next to each other.
3. `runFlow: ../../helpers/launch-clean.yaml`, then `runFlow: ../../helpers/seed.yaml`
   with a `QUERY` env var if the flow needs data. Seed rather than type.
4. Follow the menu navigation pattern: `tapOn id: nav_menu_button` →
   `scrollUntilVisible element.id: <target> direction: DOWN timeout: 10000` →
   `tapOn id: <target>`.
5. Assert on `id:` selectors from `TestTags`; add a constant if one is missing
   (then the rebuild loop).
6. Stamp the fixture title with `${output.stamp}` if the flow mutates it, unless
   it consumes `seed-task.yaml`'s fixed title.
7. Tag it with `smoke` only if it must always pass; otherwise use a domain tag.
8. `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
9. On failure, open `diagnosing-failures.md`.

Fuller guidance, including the tag table: `Maestro/README.md`.
