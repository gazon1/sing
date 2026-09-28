---
name: singularity-todo-maestro-flows
description: Author and run Maestro UI flows for the Android app. Covers selector conventions (TestTags over text), ASCII test data, the helper/subflow pattern, tag taxonomy, and how to add a flow. Use when writing or debugging Maestro YAML under Maestro/, when asked to "add a UI test", "test this user flow", or when a flow fails on device.
---

# Maestro UI Flows

Maestro drives the Android app end-to-end. Flows live in `Maestro/flows/`, with
shared setup in `Maestro/helpers/`.

## Device pre-flight is NOT here

Getting a device ready — finding a serial, waking, unlocking, installing, reading
logcat — is `singularity-todo-adb-workflow`. Delegate to it. `scripts/run-maestro.sh`
automates exactly those steps; don't re-implement them in a flow.

## Current state

The suite is **blocked**: the app crashes on Android cold start
(`SerializerAlreadyRegisteredException` in `navSavedStateConfig`), so every flow
fails at `launchApp`. See
`docs/decisions/2026-09-28-android-cold-start-nav3-serializer-crash.md`. If a flow
fails on the first assertion, check that crash before debugging selectors.

## Running

```bash
just tm                        # all flows
just tm tags=smoke             # smoke subset
just tm flow=Maestro/flows/tasks
RUN_MAESTRO=1 ./check.sh       # opt-in, from the local check pipeline
```

## Selector conventions

**Select by `id:`, never by `text:`.** The registry is
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`.

```yaml
- tapOn:
    id: tasks_fab              # constant
- tapOn:
    id: task_item_buy_milk     # dynamic: TestTags.taskItem("Buy milk")
```

Dynamic tags run through `TestTags.slug()` — lowercase, non-alphanumerics
collapsed to `_`. If a screen has no testTag, **add one** rather than falling back
to visible text; a text selector breaks the moment copy changes.

`menuItem()` is keyed by the **visible label**, not the destination title, so
`Profile & sync` and `Settings` do not collide.

## Test data: ASCII only

`inputText` cannot type non-ASCII on Android. Type `Buy milk`, `Project Alpha`,
`Meeting notes` — not Cyrillic. `slug()` preserves non-Latin characters so a tag
stays addressable, but flows should not rely on it.

## Helpers

| Helper | What it does |
|---|---|
| `launch-clean.yaml` | `launchApp` with `clearState: true`, waits 8s for `nav_tab_today` |
| `seed-task.yaml` | Creates "Buy milk" on Today and asserts `task_item_buy_milk` |

Start from `launch-clean` unless the flow needs pre-existing data. `seed-task`
hardcodes its title on purpose — see the comment in the file.

## Tags

`smoke` = the fast must-pass subset. Domain tags (`tasks`, `notes`, …) drive
feature-scoped reruns. Add `smoke` when a failure should be loud.

## Timing and input

- **Never sleep.** Use `extendedWaitUntil` with a bounded timeout. Cold start
  genuinely needs ~8s (Koin + Room + DataStore migrations + calendar sync).
- **Hide the keyboard before tapping a trailing action** — `hideKeyboard` after
  `inputText`, or the save button sits under the IME.
- After `inputText`, assert the field's value before tapping save, so a dropped
  keystroke fails at the real cause.

## Debugging a failure

`scripts/run-maestro.sh` prints the Maestro output and scans logcat for a fatal.
Artifacts land in `~/.maestro/tests/<timestamp>/` — the view hierarchy and
screenshots for the failing step are there.

If the app crashed, logcat in the same run output names the exception. That is
usually faster than reading a failed assertion.

## Do not confuse with `singularity-todo-test-tag-strategy`

That skill is about JUnit 5 `@Tag("slow")` for Gradle test filtering. Maestro's
`tags:` header is unrelated despite the similar name. There is no `slow`/`fast`
split for flows — the split is `smoke` vs domain.

## Adding a flow

1. Subdirectory matching the feature (mirrors `feature/` in shared).
2. `runFlow: ../../helpers/launch-clean.yaml`.
3. Assert on `id:` selectors from `TestTags`; add a constant if one is missing.
4. Tag it.
5. `just tm flow=Maestro/flows/<sub>/<file>.yaml`.
6. On failure, read the view hierarchy in `~/.maestro/tests/`.

Fuller guidance, including the tag table: `Maestro/README.md`.
