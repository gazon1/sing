# Maestro flow runbook

This is the runbook for the `singularity-todo-maestro-flows` skill. It carries
the seeding walkthrough, the app's behaviour facts, the probe recipe, and the
failure classes of one dated CI run — a snapshot, not a live classification of
the suite — with the triage steps that go with them.
Load it when a flow needs data or misbehaves. Authoring a flow — selectors,
scrolling, syntax — lives in `SKILL.md`.

The app's behaviour facts live here and only here; `SKILL.md` points at
[App behaviour facts flows encode](#app-behaviour-facts-flows-encode) rather
than restating them.

## Diagnosing a Maestro flow failure

### Root causes of known failures — CI run 38223116125, recorded 2026-10-12

**This table is a snapshot of one CI run, not a live classification of the
suite.** It describes what CI run 38223116125 failed on and the fix applied to
each class; that run and its analysis are recorded in
`docs/decisions/2026-10-12-maestro-e2e-fixes.md` — which groups the same
failures under different class letters, so this table is the place to read the
class names from. A later run can fail on classes this table does not list, and
a fix listed here can since have been reverted — so read the table as history
and triage the current run on its own failures.

| Class | Root cause | Flows | Fix |
|---|---|---|---|
| A | Hardcoded swipe coords out-of-bounds on phone AVD | `backup-round-trip`, `smoke-settings-cycle-tabs` | `scrollUntilVisible direction: UP` |
| B | Missing `nav_menu_button` + `scrollUntilVisible` before `menu_settings` | `calendar-sync-provider-toggle`, `calendar-sync-system-toggle`, `calendar-sync-system-unavailable`, `sync-change-interval`, `negative-empty-tags` | `scrollUntilVisible element.id: menu_settings direction: DOWN timeout: 10000` |
| C | `menu_notes` below fold + 5 s timeout | `notes-edit-body`, `notes-filter-chips`, `notes-create-note`, `negative-empty-notes` | Same pattern as class B |
| D | `waitForAnimationToEnd` 2000 ms after PORTRAIT rotation | `lifecycle-rotate-in-editor` | 2000 ms → 5000 ms |
| E | Seed/state leakage, wrong testTag, agenda section timing | 4 flows | Fixed — see rows below |
| F | Action row laid out past the screen edge | `agenda-smart-lists` | `SheetScaffold` column made scrollable |

**Status of that run:** classes A–D fixed (hardcoded swipe, missing nav menu
openers, menu item scroll, animation timing). Class E diagnosed — four flows,
three separate defects (rows below); all four class E flows pass on
`emulator-5554` with a `pm clear` slate. Class F fixed by making
`SheetScaffold`'s column scrollable.

#### Class E resolved — three separate defects

Two of the four flows were test problems; the other two were real bugs — one
product, one harness. All four pass on `emulator-5554` with a `pm clear` slate.

| Flow | Actual cause | Owner |
|---|---|---|
| `calendar-view-mode` | fixture typing + wrong testTag | test |
| `settings-cycle-tabs` | fixture typing + wrong testTag | test |
| `agenda-smart-lists` | DatePicker actions off-screen | **product** |
| `auth-first-run-anonymous` | seeded row invisible until re-query | harness |

#### Refreshing this snapshot

This section is the one place the run's status is recorded, so the table, the
heading and the status line move together. When a later CI run fails:

1. **Replace the table, do not append to it.** The table is one run's classes;
   the next run's classes replace them, because two runs share no
   classification.
2. **Update the heading in the same commit** — the run id and the date the
   snapshot was recorded. A table whose heading still names an older run is
   worse than no table.
3. **Update the "Status of that run" line in the same commit** — which classes
   are fixed, diagnosed or still open. If the run's analysis lives in a
   decision record, cite it as this section does.
4. **Drop a class that no longer reproduces.** Keep its row only when the fix
   was reverted, and say so in the row.
5. **The old snapshot stays in git history.** Do not keep a second copy in the
   file: one table, one run, one date.

### A selector that cannot be reached

`Not found` on an element the source proves exists is a **layout** bug, not a
selector bug. Two causes, and the hierarchy dump separates them:

- **Wrong id or casing** — the element is present under a different tag. Compare
  against `Maestro/TAGS.md`.
- **Laid out off-screen** — the element is in the composition but sits below the
  screen edge, so it is **absent from the accessibility tree** and no selector or
  coordinate tap can reach it.

The second is a real product defect. `DatePickerSheet` put a 600 dp body plus its
actions row in a non-scrolling column, and on a 1080x2400 phone Clear/Cancel/OK
fell below the edge. The flow had been working around it with a coordinate tap at a
guessed `(920, 1390)` that missed. Making `SheetScaffold`'s column scrollable fixed
the touch target for users and tests together.

When a flow cannot tap something the code plainly wires up, check the geometry
before rewriting the selector.

### Debugging a failure

#### Check the runner's verdict against Maestro's own

**A flow can pass in Maestro and still be reported failed by
`run-maestro`.** Both happened here, and both made the suite's exit code
lie about reality:

- Maestro's report write (`--format=JUNIT --output`) threw *after* the flow
  passed, and that post-hoc exception became the process exit status.
- The APK provenance gate compared `versionName` (`<base>+g<sha>`, which
  already carries the `g`) against a bare `git rev-parse` sha, so every
  correctly built APK read as a mismatch.

So read Maestro's own `[Passed] … / N/N Flows Passed` line before believing the
script's summary. When the two disagree, the disagreement is in the harness, not
the flow — and reproducing with a direct `maestro test <flow>` is what
separates them.

#### Then triage

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
4. **The Maestro XML has no per-step detail.** `maestro-failures-*/maestro.log`
   shows only Maestro's own polling output; the Compose state needs logcat. Use
   `run-maestro`, which writes a raw framebuffer screenshot and a
   logcat dump per failed flow into `build/maestro-results/<flow>/`.

### Retry strategy: when to fix vs when to retry

**Fix the root cause, not the symptom.** A flow that fails because an element
was not visible after 5 s is telling you the element needs 5+ s on that device
— the retry just delays that signal.

The only retry mechanism is script-level: `MAX_RETRIES=2` in `run-maestro`,
which covers device disconnects. Retry a flow only when the element has genuine
timing variance (cold start), and a `known-bug` tag marks the flow while a real fix
is tracked.

Do NOT use retry when:
- Multiple flows fail simultaneously with the same selector — this is a cascade,
  not independent variance. Check `seed-task.yaml` first.
- The failure is `Not found` rather than `Not visible` — retry does not help.

### Seed cascade detection

The most common cascade failure mode in this suite: `seed-task.yaml` times out,
`task_item_buy_milk` never appears, and 14 flows fail in the same CI run with
`task_item_buy_milk not visible` — but only the first failure is in
`seed-task.yaml`. Every other flow just used that fixture.

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
| `task=<title>&id=<id>` | a task that carries the named id — a deep link names an id, and a flow cannot know the id the app generated |
| `note=<title>` | a note |
| `note=<title>&linkTo=<task title>` | a note whose body links to the task of that title — the link is written as an anchor with the grammar's URL (`task://<id>`), so the note is one the editor could have produced |
| `project=<name>` | a project |
| `profile=<name>` | a project profile and switches to it |

**An undated fixture lands in Inbox's "No Date" bucket, not Today.** A flow that
seeds `task=Buy milk` with no `due` and then looks on Today finds nothing — tap
`nav_tab_inbox`.

**`seed.yaml` waits for the shell, not for the seeded row — the flow waits for
the row.** The helper's own wait is on `nav_tab_today`, and in a flow that has
already launched the app (every flow that starts with `launch-clean`) the
shell is already up, so that wait says nothing about the seeded entity. The
helper cannot wait for the row either — the contract and its edge cases are in
`Maestro/helpers/seed.yaml`. So a flow that seeds a task, a note or a project
waits for that entity itself before asserting on it — `extendedWaitUntil` when
the row is on screen, `scrollUntilVisible` when it may be below the fold, never
a bare `assertVisible`. A `session=anonymous` seed creates no entity, so there
is nothing to wait for. `seed-task.yaml` is the helper that does the waiting
itself — it seeds the fixed title "Buy milk" and scrolls that row into view — so
a flow that uses it has already waited for what it seeded.

**`seed.yaml` has no tab round-trip, and does not need one.** An inbox→today
round-trip was added here on 2026-10-12 because a deep-link seed appeared not to
reach an already-composed agenda. Once the runner began wiping the profile's
content before each flow, that stopped reproducing: what looked like "the row
never arrived" was the row arriving *below* rows an earlier run had left
behind. So before blaming the agenda, check for leftovers — a stale fixture
masquerades as a missing one.

### `launch-clean` does not clear the database — on purpose

`launchApp` with `clearState: false` resets AMS state only. Room persists across
runs on the same emulator, so **fixtures accumulate**.

Do not "fix" this with `clearState: true` or a blanket `pm clear`: both destroy
the pre-authenticated session the emulator snapshot carries, so every flow would
land on the auth screen. The accumulation is handled by the runner's wipe of the
active profile's content before every flow (see below), which leaves the
session, the profile and the auth state alone.

`pm clear` remains available as a manual escape hatch when you want a genuinely
virgin device state — it costs you the authenticated session:

```bash
adb -s "$(ensure-emulator)" shell pm clear com.singularity.todo
```

Accumulation is harmless for a read-only fixture but corrupts a flow that *mutates*
its fixture and then asserts the mutated-away state — the previous run's already-dated
copy satisfies the selector. The runner stops that by **wiping the active profile's
content before every flow**: `todo-debug://seed?reset=content` deletes every task,
note and project of the active profile, seeded and typed alike. The wipe reaches
the profile's content and nothing else — the profile itself, the session and the
auth state are left alone — and it is the default scope (`all`);
`--maestro-cleanup off` sends nothing.

`run-maestro` mints `RUN_ID` on **every attempt** and declares it to the flow, so
a flow can name its attempt:

```yaml
- runFlow:
    file: ../../helpers/seed.yaml
    env:
      QUERY: "task=${RUN_ID}%20My%20fixture"
- scrollUntilVisible:
    element:
      id: "task_item_${RUN_ID}_my_fixture"     # slug of "<run id> My fixture"
```

The run id is **not a cleanup key**: nothing sweeps the rows by that prefix. The
wipe knows every row of the profile, so the id only names the attempt in the
runner's log. And per
`docs/decisions/2026-10-28-the-seed-replaces-the-task-it-names.md`, a seeded title
carries no run id at all: the seed replaces the row it names, so the title a flow
seeds is the title the flow can assert on, in every run.

The app still answers `todo-debug://seed?cleanup=<prefix>`, but the runner does
not send it — the wipe is the whole cleanup scope, and the narrow sweep by title
is gone.

`seed-task.yaml` deliberately keeps the **fixed** title "Buy milk": 17 flows
address its row by the stable `task_item_buy_milk` id, so a per-run stamp would
break every one of them. Nothing sweeps it by a literal prefix — the wipe before
the flow takes the previous run's copy, so it cannot accumulate into an ambiguous
`tapOn`.

**The wipe runs before the flow, not after it** — a failed flow's rows are still
there when its screenshot, screen tree and logcat are captured (they are the
evidence, same reason the failure screenshots exist), and the next flow's wipe
takes them away afterwards.

**A manual `maestro test <flow>` has no runner**: `RUN_ID` falls back to `manual`
from `Maestro/config.yaml`, and nothing wipes the profile before the flow, so
the rows of the previous run are still there. Debug from a `pm clear` slate;
`run-maestro --maestro-cleanup off` keeps a run's fixtures for inspection.

## App behaviour facts flows encode

These are behaviours a flow author would otherwise guess wrong; each was learned
from a flow failing on it, or from the code and the tests that pin it.

**This section is the single home for them.** `SKILL.md` carries only what
authoring a flow needs — selectors, scrolling, syntax, timing — and points here.
A behaviour stated in both files is a behaviour that will drift: keep the
statement in one place and make the other link to it.

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
- **The task detail screen has no save button.** Edits autosave; the
  `task_editor_save` tag exists only on the create screen (`TaskCreateScreen`).
  A flow that expects a save button on the detail screen fails with a
  confusing "not found".
- **Leaving a dirty draft raises a guard.** The shared back arrow is tagged
  `top_bar_back_button`; tapping it on a dirty editor opens a "Discard changes?"
  dialog, confirmed with `text: "Discard"`.
- **The saved-view editor's Save pops back to the list.** `SaveSuccess` maps to
  `Notification.NavigateBack` (`SavedAgendaScreen`), so assert the pop — there
  is no "Saved" snackbar to wait for.
- **A feature can be modelled but unreachable.** Notes row actions (pin,
  archive, delete, multi-select), the Calendar Today / prev / next /
  mini-calendar controls, and the whole Sync configuration screen
  (`SyncConfigScreen` + a complete `SyncViewModel`, composed by nothing) exist
  with no control that dispatches them, and long-press on a *note* row does
  nothing. Before writing a flow, check it is reachable.
- **Notes: tapping a row opens the note's *preview*, not its editor.** The
  editor is reached from the preview's Edit action (`note_action_edit`). The
  one exception is the quick-add row: it creates the note and opens the
  editor directly (`NavigateToEditor`), so a flow that types a title into the
  quick-add lands in the editor, never in the preview. The editor autosaves —
  saving does not pop it; `back` does.

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
