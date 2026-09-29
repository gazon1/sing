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

`just tm` forwards to `scripts/run-maestro.sh`, which picks the device, installs
the APK, and fails on any `FATAL EXCEPTION` in logcat even when Maestro exits 0.

To run a tag subset directly against an already-prepared device, pass the file
list explicitly — Maestro 2.10 does not accept a directory or a glob as the
target:

```bash
maestro test $(find Maestro/flows -name "*.yaml") --include-tags smoke
```

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
  "Удалить" in one dialog).

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

Start from `launch-clean` unless the flow needs pre-existing data. `seed-task`
hardcodes its title on purpose — see the comment in the file. Make new flows
self-seeding (create their own fixture) rather than inheriting state from an
earlier flow — see `docs/decisions/2026-09-29-maestro-archive-seed-strategy.md`.

## App behaviour facts flows encode

These are behaviours a flow author would otherwise guess wrong; each was learned
by a flow failing on it:

- **Long-press on an agenda row opens the editor**, not a context menu. The
  archive/delete actions live in the task detail's overflow menu
  (`task_editor_more_menu`).
- **Delete is immediate and reversible** — a "Task deleted" undo snackbar, no
  confirmation dialog. The flow asserts the row is gone after `back`, and the
  undo path is a separate concern.
- **Archiving is a one-way door** — no restore UI exists yet
  (`TaskDetailIntent.Domain.Restore` is modelled but unwired). See
  `docs/decisions/2026-09-29-archive-has-no-restore-ui.md`. Do not write a flow
  step that expects "Восстановить".
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
