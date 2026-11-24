# Maestro Flow Conventions

This document is the canonical reference for authoring and maintaining Maestro E2E flows
in this project. All contributors must follow these rules.

## Table of Contents

1. [Naming](#naming)
2. [IDs vs Text](#ids-vs-text)
3. [Cyrillic and Unicode](#cyrillic-and-unicode)
4. [Helpers](#helpers)
5. [Timeout budget](#timeout-budget)
6. [Smoke Subset](#smoke-subset)
7. [Tags](#tags)
8. [Shell Setup](#shell-setup)
9. [Assertions over Tap-Counting](#assertions-over-tap-counting)
10. [App Launch and Clean State](#app-launch-and-clean-state)

---

## Naming

- Flow files: `NN-name.yaml` where `NN` is a sequence number within the category.
- Category directories: `flows/<category>/` (e.g., `flows/tasks/`, `flows/agenda/`).
- Helper files: `helpers/<name>.yaml`.
- Flow `name:` field: lowercase-kebab, e.g. `tasks-set-due-date`.
- Tag values: lowercase alphanumeric with hyphens, e.g. `smoke`, `regression`.
  One namespace is exempt: `scenario:<ID>`, the traceability linkage to a
  scenario spec in `scripts/src/traceability/scenarios/`. See [Tags](#tags).

---

## IDs vs Text

**Use `id:` for controls** (buttons, switches, tabs, FABs, menu items).
**Use `text:` for content** (labels, placeholders, snackbar messages).

```yaml
# ✅ Correct — id for control
- tapOn:
    id: task_editor_save

# ✅ Correct — text for content assertion
- assertVisible: "Buy milk"

# ❌ Wrong — text for a control that has an id
- tapOn:
    text: "Save"        # fragile: localisation breaks this
```

### Why

The app's UI strings are available in Russian (`ru`) and English (`en`). Text selectors
break when the user changes locale or when copy is updated. IDs are stable semantically-
anchored identifiers defined in `TestTags.kt`.

### Exception

Date picker day cells, priority picker rows, and language-specific content that has no
testTag must use `text:` with the full on-screen string from the **current emulator
screen** (never from a screenshot — screenshots hallucinate strings).

---

## Cyrillic and Unicode

**Do not hardcode Cyrillic strings in flows.** The emulator locale is `en-US`.

If a flow must handle Cyrillic content (e.g., testing search or title rendering), use
the `seed.yaml` helper with the `debug-seed` deep-link:

```yaml
- runFlow:
    file: ../../helpers/seed.yaml
    env:
      QUERY: "task=%D0%9A%D1%83%D0%BF%D0%B8%D1%82%D1%8C%20%D0%BC%D0%BE%D0%BB%D0%BE%D0%BA%D0%BE"
```

URL-encoding reference: `Купить молоко` → `%D0%9A%D1%83%D0%BF%D0%B8%D1%82%D1%8C%20%D0%BC%D0%BE%D0%BB%D0%BE%D0%BA%D0%BE`

The `note_editor_title_input` testTag uses `testTag(TestTags.taskAction(label))` where
`slug()` is Unicode-aware (`\p{L}`), so Cyrillic task titles produce valid testTags.

---

## Helpers

The helper files in `Maestro/helpers/` are listed below. Use them — do not duplicate
their logic.

| Helper | When to use |
|---|---|
| `launch-clean.yaml` | Any flow needing a fresh app state |
| `skip-spotlight.yaml` | Dismisses the first-run spotlight tour, which covers the agenda and swallows the taps under its scrim; `launch-clean.yaml` runs it |
| `seed.yaml` | Any flow needing a pre-created task/note/project via deep-link; `WAIT_FOR` names the entity to wait for, and the helper carries the contract for waiting for what a flow seeded |
| `seed-task.yaml` | Any flow needing one task titled "Buy milk" |
| `seed-and-open-task.yaml` | Seeds a task with `QUERY` and opens it from Inbox by `TASK_ID` |
| `open-task.yaml` | Opens a task from Inbox by `TASK_ID` and waits for the editor |
| `relaunch.yaml` | Flows testing persistence (state survives process restart) |
| `open-app-menu.yaml` | Flows opening the app menu (the bottom-bar Menu button) |
| `open-archive.yaml` | Opens the Archive screen from the app menu |
| `expand-projects-section.yaml` | Flows needing a project from the menu's tree; the Projects section is folded until it is unfolded, and the unfold is safe to repeat |
| `open-settings.yaml` | Opens Settings from the app menu and waits for the settings tab control |
| `settings-tab.yaml` | Flows opening a settings tab by name (`SETTINGS_TAB: settings_tab_account`); it opens the narrow settings sheet, scrolls the row into view and taps it |
| `open-menu-item.yaml` | Opens a menu entry by `ENTRY_ID`, scrolling the sheet until it is on screen, then waits for `DEST_ID` — the way to reach a destination that is not a tab |
| `session-signedin.yaml` | Flows needing a signed-in session persisted on the device |
| `session-signedout.yaml` | Flows needing a signed-out app — the auth screen |
| `calendar-day-cell.yaml` | Flows waiting for a calendar day cell by its date tag |
| `calendar-tap-day-cell.yaml` | Flows opening a calendar day by its date tag |
| `calendar-day-cell-task-count.yaml` | Flows asserting how many tasks a calendar day holds (`DAY_CELL`, `TASK_COUNT`) — the count, because a month cell clips the dots it cannot fit |
| `open-attachments-sheet.yaml` | Opens the task detail's attachments sheet. A task with no attachment is reached through the `+ Ещё` sheet; a task that already has one draws the section, and the sheet does not offer what is already on the screen |
| `reveal-attachments-section.yaml` | Scrolls an already-open attachments section's rows into view, so a flow can assert on a tile rather than on the header |
| `open-more-parameter.yaml` | Opens the task detail's `+ Ещё` sheet and picks one property from it (`PROPERTY`: `attachments`, `logbook`, `relatedtasks`, `timetracking`, `workflowstate`) — the way to reach a section that is not on the screen |
| `backup-create-via-saf.yaml` | Backup flows creating an archive through the system save dialog |

### Waiting for what you seeded

A flow waits for the entity it seeded before it asserts on it. Which waits
count — the entity's own row, or a container that renders only because the
entity is in it — is written down in one place: the contract at the top of
`helpers/seed.yaml`. Read it there; do not restate it in a flow.

Which of those waits live in the helper and which stay in the flow:

- **In the helper.** The seed helper waits for the shell, and — when the flow
  names the entity in `WAIT_FOR` — for that entity, before it returns:

  ```yaml
  - runFlow:
      file: ../../helpers/seed.yaml
      env:
        QUERY: "task=Buy%20milk&due=today"
        WAIT_FOR: "task_item_buy_milk"
  ```

  `WAIT_FOR` is the element id the flow is going to assert on — an id, not the
  name the entity is called: the helper waits with `visible: {id: …}`. A
  title-named entity is named by the element its title produces
  (`TestTags.slug`); an id-named entity is named by that id. The element has to
  be on the screen the seed leaves the app on — the agenda's Today tab, with
  its Overdue and Today sections: a task that is overdue or due today, and the
  section it is in. Three things are not: an element that is elsewhere (the
  helper does not navigate), a row of a seed that wrote several rows (the list
  is longer than the screen and the helper does not scroll), and a section the
  flow named before the seed (it was already there, so the wait for it returns
  at once and proves nothing about the row the seed wrote). The helper's wait
  does not scroll, so it covers an entity that is on that screen. The helper
  waits in this order — the link, the shell wait, the entity wait — and the
  wait after the link is the one that absorbs the arrival: a wait before it
  would be satisfied by the row the seed is about to rewrite. The shell wait
  stands between the link and the entity wait: it lets the app come up, and an
  entity wait above it would be a wait for a row the seed may not have written
  yet.

- **One link writes one kind.** The seeder takes the first kind the query
  names — task, then note, then project, then the bulk projects — so a link
  that names a task and a note writes the task and nothing else. A `count=`
  the seeder refuses (anything outside `1..10000`) writes no row at all, and a
  link that cleans up, resets or changes the session writes no row either —
  the seeder answers it and returns before it writes anything. Such a seed
  names no entity, and the helper waits for the shell alone.

- **In the flow.** An entity that is elsewhere — a note, a project, a row below
  the fold, a task in another tab — is waited for by the flow, after it
  navigates: the flow waits for the container that holds the entity, or
  scrolls the row into view. A seed that writes no entity
  (`session=anonymous`, `cleanup=`, `reset=`) names no entity, and the helper
  waits for the shell alone.

Two rules make such a wait mean something:

- **After the seed.** A wait that precedes the seed says nothing about the
  entity: the row could have been on screen before the seed wrote it. And a
  seed that *rewrote* the row needs a wait of its own — the row is the same
  row, but what the flow asserts on is what the last seed wrote.
- **By the name, when the entity was renamed.** A wait for the row cannot tell
  a rename from the row it renamed: the row was already there under the old
  name. The name can. `menu/06-tree-live` waits for the text "Renamed" and
  then asserts on the row's id.

Two more things the gate holds a flow to:

- **A wait that waits for the element to be gone covers nothing.** `notVisible`
  and `absent` wait for the opposite of an arrival.
- **An assertion by the entity's name is an assertion on the entity.** A flow
  that asserts `text: "Buy milk"` on a seeded task has to wait for that task,
  and the wait may address the node by the id of the field it is in *and* by
  the title it holds — `visible: {id: task_editor_title_input, text: "Buy
  milk"}` is one wait for one entity.

Two more rules about what a wait covers:

- **One wait covers one entity.** A seed that writes N rows writes N entities,
  one row each. A wait for one of those rows does not cover the others: each
  row is an entity with an assertion of its own, and each needs a wait that
  names it. The container is the one handle that covers a whole seed, because
  the section that holds the rows holds every entity of the seed.
- **A container covers an entity only while the seed brought the container
  in.** The container rule asks for a section that renders *only* because the
  entity is in it. A section the flow named before the seed was there before
  the seed, and a wait for it returns at once — the row the seed wrote is
  still on its way. The gate holds such a flow to the row rule. What the gate
  cannot see is the state the app was in before the flow ran: a section a
  previous run left behind is invisible to it, and a flow that waits for a
  section it did not bring in owns that.
- **A seed that outlasts every wait is written in chunks.** Writing a row
  costs the seeder about 30 ms, so a thousand rows take longer than the shell
  wait (30 s) — the largest budget there is. A seed that outlasts the budget
  cannot be waited for at all. Write it as several seeds of its own instead,
  each with a base title of its own (two seeds with the same base title write
  the same rows twice), each inside the budget; the flow then waits for the
  section that holds the rows once the last chunk is in. `agenda/07` seeds its
  thousand rows in five chunks for this reason.

### Sessions

A flow does not inherit a session from the flow before it, and it does not
leave one behind for the flow after it: the runner restarts the app before
every flow, and the E2E build keeps the session in the process. A flow that
needs a *signed-out* app says so itself, with the `session-signedout.yaml`
helper.

`clearState: true` takes the app's data with it, and the data belongs to the
runner, which wipes the active profile before every flow — so no flow may
clear state to get a clean start. The two that do,
`auth/08-signup-network-error.yaml` and `auth/09-signin-network-error.yaml`,
need it: the server-configuration screen only appears when no server is
configured, and a first-run app is the only way to reach it. A flow that
clears state says so in its header.

### Automated suites vs manual flows

There are four sets, and only two of them run in CI:

| Set | What it is | How it runs |
|---|---|---|
| The catalogue | every flow in the tree, recorded in `config/docs/maestro-flows.txt` | never as a whole |
| The automated `full` suite | every flow in the catalogue except flows tagged `manual` or `auth-live` | nightly, four shards |
| The `smoke` suite | every flow that declares `smoke` — the tag, not the directory | every pull request |
| Manual flows | flows tagged `manual` or `auth-live` | by hand, against the build/environment they name |

The tag is the selection, not a note. A flow tagged `manual` or `auth-live` is
run by hand against the environment it needs (a real auth provider/network, or
a non-stubbed build); the automated `full` CI run excludes both, so the default
run never executes a flow that cannot pass on the CI APK
(`TEST_AUTH_BYPASS=true`, StubAuthRepository). The exclusion applies only to
the automated run: both tags stay in the catalogue and in the tree.

The selection lives in `scripts/src/emulator/maestro/_flow.py`
(`suite_selection` describes it, `select_suite_flows` applies it); the runner,
the shard planner, the results gate and the Allure converter read it from
there, so they cannot disagree about what a suite contains. A `smoke` flow must
not also carry `manual` or `auth-live` — the smoke suite runs in CI, and
`scripts/tests/test_maestro_suite_selection.py` checks that invariant.

To run an excluded flow, name it. The runner applies no exclusion unless one is
asked for:

```bash
just ui-maestro Maestro/flows/auth/08-signup-network-error.yaml
```

`--exclude-tags manual,auth-live` (or `EXCLUDE_TAGS=manual,auth-live`)
reproduces the automated `full` selection; without it the CLI runs whatever
`--flow` selects.

### Qualification before a blocking scope

A new or materially changed flow (its content or any helper it runs,
transitively — helper flows and helper scripts alike) is not admitted to an
automated blocking scope until it is
qualified or explicitly excepted for its current fingerprint. Qualification is
a small, bounded repeat run of the affected flow(s) in the same documented
environment/build (default: 3 consecutive passing attempts on one device),
with every attempt
recorded and infrastructure failures never counted as test passes; an initial
failure followed by a pass means the flow is flaky, not qualified. The attempts
run an immutable snapshot of the flow tree, so they always see the same
content, whatever happens to the checkout while the run is in progress. The
attempts run the APK built from this checkout: the runner refuses an installed
APK that is not this checkout's build, and the qualification runner does not
accept `--allow-stale-apk`, so a qualification can never certify another
program than the one the change is about. A flow
that references content outside `Maestro/` is refused: the fingerprint and
the snapshot both cover that tree, and nothing else.

- Policy, evidence and exceptions: `docs/testing/maestro-flow-qualification.md`
- Frozen set (pre-rule in-scope flows, pinned by a digest in the gate): `config/docs/maestro-flow-qualification-initial.yaml`
- Baseline (fingerprints of the frozen set): `config/docs/maestro-flow-qualification-baseline.yaml`
- Evidence/exceptions: `config/docs/maestro-flow-qualification.yaml`
- Gate: `scripts/check-maestro-flow-qualification.py`
- Runner: `scripts/maestro-qualify-flows.py` (targets affected flows only, reuses existing JUnit/environment outputs)

### Dates and locale

A flow must not assert a date the app *formats*. The month title
("October 2026") and the day title ("October 15, 2026") are the app's
wording, in the app's word order, in the app's language; the Material3 date
picker writes the month name and the weekday in the *device's* language. A
flow asserts the date as data instead: the calendar day cells carry it in
their testTag (`calendar_day_2026_10_15`), and a day in the Material3 picker
is addressed by the day number inside its accessibility description, never by
the month name next to it.

### Helper threshold

A helper earns its keep at **≥4 lines of unique logic**. If a subflow is shorter, inline it.

The rule counts lines, not commands: a helper whose command block is five
lines of selector has earned its keep. Some helpers cannot be inlined at all —
the selector gate rejects a computed id in a flow (`${output.…}` exists only
inside the flow that computed it), so a value that has to reach a selector
goes through `runFlow … env:` and the helper waits for `${NAME}`.

---

## Action → outcome (waits and postconditions)

Drive flows as **action → bounded wait/assertion → next action**. After each meaningful
action, wait for or assert the expected user-visible outcome before continuing, so a
failure names the action/state that did not occur instead of cascading into later steps.

- **No blind waits.** Never use a fixed `delay`/sleep. Do not use `waitForAnimationToEnd`
  (with or without a timeout) when a specific observable condition is available — wait
  for that condition instead (`extendedWaitUntil` / `assertVisible` / `assertNotVisible`).
  `waitForAnimationToEnd` is only a last resort when animation genuinely blocks
  visibility checks and no stable condition can be observed; prefer a bounded condition.
- **Assert meaningful outcomes.** Editor opens, sheet/dialog opens or closes, task/row
  appears or disappears, checked state changes, mode/tab/screen becomes active, typed
  text is visible, save/navigation lands on the expected screen — assert the outcome the
  user would see. Do not assert implementation details or duplicate every intermediate
  animation.
- **Controls must be visible/actionable, not just present.** Before tapping a critical
  control, wait for it to be `visible` (and scroll it into view when needed). A selector
  existing in the UI/accessibility tree is not enough if the control is obscured or off
  screen.
- **Prefer bounded waits.** Use `extendedWaitUntil` with an explicit timeout when the
  outcome may take time; use `assertVisible`/`assertNotVisible` when it must already hold.

```yaml
# ✅ Correct — wait for the control, act, then assert the outcome
- extendedWaitUntil:
    visible:
      id: task_editor_save
    timeout: 8000
- tapOn:
    id: task_editor_save
- extendedWaitUntil:
    visible:
      id: task_item_buy_milk
    timeout: 8000

# ✅ Correct — dialog closes and result is visible
- tapOn:
    id: dialog_confirm
- extendedWaitUntil:
    notVisible:
      id: dialog_confirm
    timeout: 8000
- assertVisible:
    id: expected_result

# ✅ Correct — state change
- tapOn:
    id: task_checkbox_buy_milk
- extendedWaitUntil:
    visible:
      id: task_checkbox_buy_milk
      checked: true
    timeout: 5000

# ❌ Wrong — blind fixed/animation wait instead of an observable outcome
- tapOn:
    id: task_editor_save
- waitForAnimationToEnd:
    timeout: 2000
- tapOn:
    id: next_step
```

### Timeout budget

The timeout of a wait is not yours to choose: it is the budget of the *kind* of
element the flow waits for, and `config/docs/ui-wait-budgets.txt` is where those
budgets live. `config/docs/maestro-element-kinds.txt` says which kind each
element belongs to, and `scripts/check-ui-wait-budgets.py` fails a flow whose
timeout disagrees with its kind's budget.

| Kind | Budget | What it waits for |
|---|---|---|
| `shell` | 30s | the bottom navigation after a cold start |
| `boot` | 30s | the first screen after a cold start that is not the shell |
| `tab` | 10s | a tab switch |
| `menu` | 5s | a menu sheet, or an item in one |
| `field` | 5s | a text input |
| `button` | 8s | a FAB, a save, a submit, a switch, a chip |
| `item` | 8s | a list item or row |
| `dialog` | 8s | a modal or a sheet |
| `text` | 8s | a label or message that is not a control |
| `network` | 20s | an element that only appears after a server round-trip |

So a wait looks like this, and the number is the element's kind, not a guess:

```yaml
# ✅ Correct — the menu kind's budget
- extendedWaitUntil:
    visible:
      id: menu_settings
    timeout: 5000

# ❌ Wrong — 8s for a menu item, 10s for a tab: a drift the gate reports
- extendedWaitUntil:
    visible:
      id: menu_settings
    timeout: 8000
```

Adding a wait for a new element means adding it to
`config/docs/maestro-element-kinds.txt` — an element nobody budgeted is a
finding, because a budget nobody promised is a budget nobody keeps in step.

`waitForAnimationToEnd` is not a wait for an element and carries no element
budget; it is a fallback for animations, and its timeout is its own.

---

## Smoke Subset

The `smoke` tag marks the suite a pull request runs. The tag is the
selection, not a directory: every flow that declares `smoke` is in the suite,
wherever the flow lives. `scripts/src/emulator/maestro/_flow.py` reads the
tag, `scripts/ci/check-maestro-results.py` measures the same set, and
`scripts/check-maestro-smoke-suite.py` holds this number to the tag.

Current smoke flows (30):

The flows are not written out here on purpose. A written-out list is a second
place for the suite to be wrong, and this one was wrong for months: it said
six, four of the six paths it named did not exist, and the tag had meanwhile
spread to dozens of flows. Nobody noticed, because the tag is what selects and
the prose is what people plan against. To see the flows:

```
python3 scripts/check-maestro-smoke-suite.py --list
```

The suite runs on the shard count `config/docs/e2e-shards.txt` gives it, so a
pull request pays well under half of what one full-suite shard pays. It used to
run on one, and by the time the tag had spread, that single shard was doing
nearly a full-suite shard's work on one runner — the pull-request path was the
longest thing in the workflow, and it was called the minimal one. The gate
holds that too: a smoke shard that costs more than a full-suite shard is a
finding, so the tag cannot quietly grow past the shard count.

---

## Tags

Canonical tags (add new ones to `Maestro/config.yaml` comment block):

| Tag | Meaning |
|---|---|
| `scenario:<ID>` | **Traceability linkage.** Links this flow to a scenario spec (`TASK-REC-01`) in `scripts/src/traceability/scenarios/`. The only tag that is not lowercase-hyphen, and the only one carrying a value: `run-maestro` matches tags by exact string, so no code change is needed to filter on it. Validate with `just trace-validate`. |
| `smoke` | Minimal regression suite, run on every PR |
| `regression` | Guards a specific fixed bug |
| `known-bug` | Tracks a confirmed bug; failure is expected |
| `persistence` | Tests data survives process restart |
| `sync` | Tests offline/sync behaviour |
| `<feature>` | Feature area (e.g., `pomodoro`, `agenda`, `notes`) |

---

## Choosing a Tier: A Popup Opens Its Own Window

**Every popup opens a second window on desktop, and its content is reachable from a JVM
test — but not from the main tree.** Pick the tier from this table *before* writing
selectors.

| Surface | Desktop `jvmTest` | Maestro (Android) |
|---|---|---|
| Regular composable | yes | yes |
| `AlertDialog` | **yes, through the scene's roots** | yes |
| `ModalBottomSheet` | **yes, through the scene's roots** | yes |
| `DropdownMenu` | **yes, through the scene's roots** | yes |

Measured, not assumed (`#152`, re-measured when the editor's sheet state was fixed, and
again for the other two popups in `2026-10-21-the-popups-open-their-own-windows-too.md`).

On desktop, a popup is a **separate window**, not an overlay inside the main one:

- `ModalBottomSheet` resolves to `ModalBottomSheetDialog`, whose skiko actual is a
  `Dialog`;
- `AlertDialog` is a `Dialog`;
- `DropdownMenu` is a `Popup`.

The scene therefore has **more than one semantics root** while a popup is open, and the
roots are not the same tree:

- the main tree — the app's own window — is **unchanged** when the popup opens: same
  node count, same tags, same texts;
- the popup's nodes live in **their own root**, and the cross-root search finds them.
  `onNodeWithTag(…)` and `onAllNodesWithTag(…)` both look across the scene's roots, so a
  popup's `testTag` is assertable from a JVM test;
- a helper that counts a tag *in the main root* finds nothing, because the popup is not
  in that root.

`SheetReachabilityTest` and `PopupReachabilityTest` pin these claims, so a Compose
upgrade that folds a popup back into the main window fails the test instead of silently
changing what a selector sees.

**What this means in practice.** A behaviour that lives inside a popup *can* be covered
by the JVM tier, as long as the assertion waits for the popup (a sheet animates in) and
does not assume the popup's nodes are in the main tree. The recurrence picker is the
worked example: `TestTags.RECURRENCE_OPTION_*` are assertable from the desktop tier, and
`TASK-REC-01` is carried on **both** tiers — the desktop test chooses the rule and
commits it, and the Maestro flow
(`Maestro/flows/tasks/17-create-daily-recurring.yaml`) drives the rest of the journey on
the device.

**What a wrong row costs.** The `DropdownMenu` row used to say *no* for the desktop tier,
and it was never measured — the truth is *yes*. A wrong row does not merely cost a
test: it sends the next person to write a Maestro flow for something a JVM test can do,
and a Maestro flow runs on a device, in another tier, with another set of selectors.
Measure a row before you believe it, and measure it on the surface the row is about —
the sheet's row was once measured on an editor row that did nothing at all.

---

## Shell Setup

Every flow targeting the emulator should start with one of:

```yaml
# For flows that need fresh state:
- runFlow: ../../helpers/launch-clean.yaml

# For flows that need pre-seeded data (use seed.yaml env):
- runFlow: ../../helpers/seed.yaml
```

The `launch-clean.yaml` helper launches the app, grants notifications, and
disables airplane mode via `onFlowComplete`. Do not inline this logic.

The two flows that clear state to reach the server-configuration screen —
`auth/08-signup-network-error.yaml` and `auth/09-signin-network-error.yaml` —
are the documented exceptions, and they say so in their headers.

A flow that switches the network off carries the guard itself, in its own
header: `onFlowComplete: - setAirplaneMode: disabled`. A flow that fails
half-way through an offline step would otherwise leave the device offline for
every flow that runs after it.

---

## Assertions over Tap-Counting

**Never count taps** — always assert the resulting state.

```yaml
# ✅ Correct — assert the checkbox is checked
- tapOn:
    id: task_checkbox_buy_milk
- extendedWaitUntil:
    visible:
      id: task_checkbox_buy_milk
      checked: true
    timeout: 5000

# ❌ Wrong — no verification
- tapOn:
    id: task_checkbox_buy_milk
```

---

## App Launch and Clean State

**Never use `launchApp` directly** in a flow — always use `launch-clean.yaml` or
`relaunch.yaml`. Direct `launchApp` bypasses the airplane-mode guard and permission
setup that `launch-clean.yaml` provides.

```yaml
# ✅ Correct
- runFlow: ../../helpers/launch-clean.yaml

# ❌ Wrong — bypasses guard and permissions
- launchApp:
    appId: com.singularity.todo
```
