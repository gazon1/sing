# Maestro Flow Conventions

This document is the canonical reference for authoring and maintaining Maestro E2E flows
in this project. All contributors must follow these rules.

## Table of Contents

1. [Naming](#naming)
2. [IDs vs Text](#ids-vs-text)
3. [Cyrillic and Unicode](#cyrillic-and-unicode)
4. [Helpers](#helpers)
5. [Timeouts](#timeouts)
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

Ten helper files exist in `Maestro/helpers/` — eight live, two deprecated
stubs. Use them — do not duplicate their logic.

| Helper | When to use |
|---|---|
| `launch-clean.yaml` | Any flow needing a fresh app state |
| `seed.yaml` | Any flow needing a pre-created task/note/project via deep-link |
| `seed-task.yaml` | Any flow needing one task titled "Buy milk" |
| `relaunch.yaml` | Flows testing persistence (state survives process restart) |
| `start-focus-task.yaml` | Pomodoro flows needing a focused task (deprecated) |
| `open-menu-item.yaml` | Flows navigating via the menu sheet (deprecated) |
| `session-signedout.yaml` | Flows needing a signed-out app — the auth screen |
| `settings-account-tab.yaml` | Flows needing the Account tab in Settings (it is the last of twelve rows and starts below the fold) |
| `calendar-day-cell.yaml` | Flows waiting for a calendar day cell by its date tag |
| `calendar-tap-day-cell.yaml` | Flows opening a calendar day by its date tag |

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
configured, and a first-run app is the only way to reach it. They carry
`manual` and are not tagged `smoke`; the nightly full suite runs every flow in
`config/docs/maestro-flows.txt`, these two included. A flow that clears state
says so in its header.

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

---

## Timeouts

Use `extendedWaitUntil` with a specific condition — never a fixed `delay`.

```yaml
# ✅ Correct
- extendedWaitUntil:
    visible:
      id: nav_tab_today
    timeout: 30000

# ❌ Wrong — no condition, arbitrary sleep
- waitForAnimationToEnd:
    timeout: 2000
```

Exception: `waitForAnimationToEnd` may be used as a fallback when the app uses animations
that interfere with visibility checks (e.g., shared element transitions), but a specific
id assertion is always preferred.

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

Current smoke flows (27):

The flows are not written out here on purpose. A written-out list is a second
place for the suite to be wrong, and this one was wrong for months: it said
six, four of the six paths it named did not exist, and the tag had meanwhile
spread to dozens of flows. Nobody noticed, because the tag is what selects and
the prose is what people plan against. To see the flows:

    python3 scripts/check-maestro-smoke-suite.py --list

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

The `launch-clean.yaml` helper clears state, grants notifications, and disables
airplane mode via `onFlowComplete`. Do not inline this logic.

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
