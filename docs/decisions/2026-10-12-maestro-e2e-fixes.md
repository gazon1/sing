---
title: "Maestro E2E fixes — scrollUntilVisible, timeouts, seed cascade, fixture isolation"
date: 2026-10-12
status: accepted
tags: [maestro, e2e, testing, emulator]
---

## Context

CI run 38223116125 produced 30 Maestro E2E failures across 4 shards. The failures
appeared to be scattered across many flows, which initially suggested widespread
regressions. A systematic root-cause analysis showed a very different picture.

## Root-cause classification

| Class | Mechanism | Count | Example |
|-------|-----------|-------|---------|
| A | **Cascade from seed-task timeout** | ~14 flows | All flows depending on `task_item_buy_milk` failing simultaneously |
| B | **Below-fold menu items + 5 s timeout** | 4 flows | `extendedWaitUntil menu_notes 5000` on phone AVD sheet |
| C | **Hardcoded swipe coordinates** | 2 flows | `swipe 105,1600→105,800` on Moto G4 640dp screen |
| D | **waitForAnimationToEnd: 2000 ms after PORTRAIT** | 1 flow | Rotation animation longer than 2 s on CI emulator |
| E | **True product failures** | ~4 flows | Requires live device debugging |

**Key finding**: 27 of 30 failures were cascade from a single root cause — the
`extendedWaitUntil` in `seed-task.yaml` using a 5-second timeout that is
insufficient on a loaded CI emulator for a cold-start + first-run migration +
agenda composition + inbox navigation + task row appearance.

## Decisions

### 1. scrollUntilVisible instead of swipe

**Decision**: Replace all hardcoded `swipe` commands with `scrollUntilVisible`.

**Rationale**: `swipe` uses absolute screen coordinates. The CI AVD is 320×640
(dp); a swipe from y=1600 to y=800 is physically impossible on that screen
(height=640 dp, ~1238 px at 2.0 density). The flow was passing in local testing
because a developer's desktop emulator uses a different default AVD size. Once
the CI AVD dimensions were confirmed as 320×640, the correct adaptive primitive
is `scrollUntilVisible` — it is viewport-relative and works across all screen
sizes.

**Direction heuristic**: `direction: DOWN` for menu sheet items that are at or
below the screen centre; `direction: UP` for elements above the centre (e.g.
save button at the bottom of a tall editor).

### 2. 10 000 ms timeout for phone AVD

**Decision**: Use `timeout: 10000` (10 s) for `extendedWaitUntil` on phone AVD
(320×640, density 2.0).

**Rationale**: A phone bottom sheet with 5+ items, section headers, dividers,
and a keyboard can exceed 5 s on a loaded CI runner. The emulator's scheduler
priority is lower than the gradle daemon, and first-run migration adds
non-deterministic deferred work. 10 s is a safe upper bound for a single
observable element; it does NOT slow down the happy path (the element appears in
< 2 s on a fast machine).

### 3. waitForAnimationToEnd: 5000 ms after PORTRAIT

**Decision**: `waitForAnimationToEnd` × 2 (default 2500 ms each = 5000 ms total)
after a `switchDeviceRotation` to PORTRAIT.

**Rationale**: Rotation animations on the CI emulator run at real-time speed,
not virtual time. The 3×60 fps frames required for a smooth rotation can exceed
the default 2000 ms on a loaded host. Two back-to-back `waitForAnimationToEnd`
calls are safe because they measure actual animation state, not wall-clock time —
they return as soon as the animation subsystem reports idle.

### 4. NOT adding retry to seed-task.yaml

**Decision**: Do NOT add a retry loop to `seed-task.yaml`.

**Rationale**: Retry masks real failures. The correct fix is to increase the
timeout (done: 5000 → 10 000 ms) so the seed appears reliably without retry.
The only legitimate retry mechanism is the script-level `MAX_RETRIES=2` in
`run-maestro.sh`, which handles device disconnects — not element visibility.

If a flow needs more than 2 script-level retries, it must be fixed, not
retry-wrapped.

### 5. screenMetrics in Maestro/config.yaml

**Decision**: Add explicit `screenMetrics: { width: 320, height: 640, density: 2.0 }`
to document the assumed CI viewport.

**Rationale**: While `scrollUntilVisible` is viewport-relative and works without
this, making the assumption explicit catches viewport drift (e.g. someone
running flows against a tablet AVD expecting them to pass).

### 6. Per-flow failure artifacts

**Decision**: `run-maestro.sh` now captures `adb shell screencap` and `adb logcat -d`
into `build/maestro-results/{flow_name}/` for every failed flow.

**Rationale**: Post-run triage without a screenshot is guesswork. The Maestro
view hierarchy (`~/.maestro/tests/`) tells us what Maestro thought was on screen;
the raw framebuffer tells us what was actually there. Both are needed for
accurate diagnosis.

## Class E (2026-10-12, local emulator)

The four Class E flows were investigated on `emulator-5554`. Three distinct
defects surfaced — two in the product, one in the harness. Only one of them was
in scope of "fix the failing test".

### 7. Compose's controlled BasicTextField discards injected text

**Decision**: Seed task fixtures through the `DebugSeedActivity` deep-link
(`helpers/seed.yaml`) instead of typing into the editor.

**Rationale**: Neither Maestro `inputText` nor `adb shell input text` can fill
`TaskTitleRow`. `BasicTextField(value = title, onValueChange = …)` is a
*controlled* field: the injected text lands in the wrapped `EditText`, but the
next composition writes the (still empty) `value` back over it. The probe that
settled this: type, reopen the editor, assert the text — it was gone, so
`onValueChange` never fired, `isDirty` stayed false, and Save never enabled.
An `ImeAction.None` patch was tried and reverted: it removed the action
`inputText` relies on and broke text entry outright.

This makes the existing debug seeding path the sanctioned fixture mechanism for
these flows rather than an ad-hoc workaround.

### 8. DatePickerSheet actions row was unreachable (product defect)

**Decision**: Make `SheetScaffold`'s column scrollable.

**Rationale**: The sheet body (DatePicker, capped at 600 dp) plus the actions row
exceeded the sheet height on a 1080x2400 phone. Clear/Cancel/OK were laid out
below the screen edge — not tappable by a user, and clipped out of the
accessibility tree, so no test could reach them either. The flow's old workaround
was a coordinate tap at a guessed `(920, 1390)`, which missed. Scrolling the
column makes the actions reachable for both users and tests.

### 9. Seeded rows invisible until a tab switch (harness defect)

**Decision**: `helpers/seed.yaml` ends with a tab round-trip (inbox → today).

**Rationale**: The deep link brings MainActivity up with CLEAR_TOP|SINGLE_TOP. On
a cold start the seed commits while MainActivity is still being created, so the
agenda's first composition reads a pre-seed query and the row never appears.
Verified by reading the database: the row was written, and it appeared the moment
a tab switch forced a re-query. Fixed in the shared helper rather than per-flow,
because every seeding flow was exposed to it.

### 10. Per-run unique titles only where a flow mutates its fixture

**Decision**: Stamp the fixture title with `${output.stamp}` (from
`helpers/scripts/date.js`) in `agenda-smart-lists` and `auth-first-run-anonymous`.
`helpers/seed-task.yaml` keeps its fixed "Buy milk" title.

**Rationale**: `launch-clean` does not clear Room, so fixtures accumulate across
runs on one emulator. That is harmless for a read-only fixture but corrupts a flow
that dates its task and then asserts the task is *no longer* undated — the
previous run's already-dated copy satisfies the selector. `seed-task.yaml` must
NOT be stamped: 17 flows address its row by the stable `task_item_buy_milk` id,
and a moving id would break every one of them. That constraint is recorded in the
helper's header so the next person does not "fix" it the obvious way.

## Per-run fixture identity and success-only cleanup (2026-10-12)

Room survives between Maestro runs, so fixtures accumulate and a flow can
assert against its own predecessor's leftovers. Decision 10 introduced
`${output.stamp}` as a per-flow workaround; this generalises it into a runner
contract.

### 11. The runner mints a run id per attempt, not per flow

**Decision**: `scripts/run-maestro.sh` generates `RUN_ID` on **every attempt**
and passes it with `--env RUN_ID=…`. Flows that mutate their own fixture prefix
the seeded title with it. On success the runner sweeps every title carrying that
prefix plus the fixed seed titles.

**Rationale**: per-*attempt*, because a retry after a lost device re-seeds the
same titles — reusing one id across attempts leaves two rows with the same
prefix, which is the ambiguity the mechanism exists to prevent.

The prefix is matched, not searched: a run's leaked fixtures name the run that
created them, and a substring match could not distinguish a fixture from real
data that merely contains the id.

### 12. Cleanup happens on success only

**Decision**: sweep after a pass; leave fixtures in place after a failure.

**Rationale**: a failed flow's fixtures are the evidence. This is the same
reason failure screenshots and logcat are captured only in the failure branch.
Cleanup is best-effort — a sweep that cannot run must not turn a green flow red
for a housekeeping problem.

### 13. `seed-task.yaml`'s stable title is swept by literal prefix

**Decision**: the success sweep covers `$RUN_ID` **and** the literal `Buy milk`.

**Rationale**: decision 10's constraint stands — 17 flows address that row by
the constant `task_item_buy_milk`, so it cannot be run-scoped. But a stable
title plus a persistent database is exactly the accumulation case: one copy per
run, after which a `tapOn` on the id matches several rows and fails. Sweeping it
by its own literal removes the accumulation without touching the stable id that
its consumers depend on.

### Two pre-existing runner bugs found while verifying this

Both made `run-maestro.sh` report results that did not match reality; both are
fixed here.

- **APK provenance gate rejected correct builds.** `versionName` is
  `<base>+g<sha>`, so the substring after `+` already carries the `g`, but the
  check compared it against a bare `git rev-parse` sha. Every correctly built
  APK read as a mismatch. Fixed by stripping the leading `g` before comparing.
- **JUnit `--output` was a directory.** Maestro 2.10 treats it as a *file*
  path; `saveReport` threw `FileNotFoundException … (Is a directory)` *after*
  the flow had passed, and that post-hoc exception became the exit status — so
  every flow came back red regardless of result. Fixed by writing
  `<dir>/<flow_name>.xml`.

Consequence worth stating plainly: **a flow can pass in Maestro and still be
reported failed by the runner.** Before these fixes the suite's own exit code
could not be trusted, which is why the earlier verification in this ADR cites
direct `maestro test` runs.

## Selector hardening and dead-helper cleanup (2026-10-13)

### 14. Most text selectors were already tag-addressable

**Decision**: Convert flow text selectors to `id:` where a testTag already
exists; add product-side tags only where none does.

**Rationale**: The premise was "26 places need TestTags". Inventory showed most
of those were not UI chrome at all — typed input values (`inputText` with
emails and passwords), user-facing data the flow itself created, calendar day
numbers, and Material3 `contentDescription` regexes that Maestro 2.10 cannot
address at all (no `content-desc` selector). Several genuine chrome selectors
(`search_empty_placeholder`, `search_empty_results`, `project_detail_empty`,
`project_detail_tasks_header`) were **already declared and already wired** — the
flows simply were not using them, so no product change was needed.

Only three needed new product-side tags: the search filter sheet's Apply/Cancel
buttons, `ResultDialog` (so flows assert a dialog structurally instead of
matching copy that is translated on this device), and `DatePicker.SHEET`.

### 15. A text assertion that could never pass

**Decision**: `backup/01-round-trip.yaml` asserted `text: "Backup created
successfully"`; `BackupViewModel` emits `"Backup created"`. The flow now asserts
`result_dialog` is present.

**Rationale**: The string never existed, so the assertion could never have
passed. It is a second instance of the lesson from `tasks/06-delete-undo`,
where matching UI copy fails on this device because it runs in Russian.

### 16. Five task-row conversions were wrong and were reverted

**Decision**: `lifecycle/03-rotate-in-editor`, `tasks/04-delete`,
`tasks/18-time-tracking-start-stop`, `tasks/create-task` and
`tasks/open-task-detail` keep `assertVisible: "Buy milk"`.

**Rationale**: They assert the title **inside the open editor**, right after
waiting for `task_editor_title_input`. `task_item_*` is only mounted once the
editor closes, so converting them would have turned a passing assertion into a
guaranteed failure. A tag for the editor's rendered title would fix the
localisation exposure properly; it does not exist yet.

### 17. `helpers/seed.yaml`'s tab round-trip was not load-bearing

**Decision**: Removed the inbox→today taps.

**Rationale**: §9 attributed it to cold-start staleness. Re-tested after the
success sweep (§11) removed accumulated fixtures, and the staleness did not
reproduce on `auth/01-first-run-anonymous`, `tasks/04-delete` or
`agenda/01-smart-lists`. The most likely reading of the original observation:
the new row *did* arrive, but below rows earlier runs had left behind, and that
looked like a missing row. `lifecycle/03-rotate-in-editor` fails either way —
with and without the taps — which is what confirmed the taps were not the cause.

### 18. `launch-clean` must not wipe the database

**Decision**: Keep `clearState: false`.

**Rationale**: A blanket `pm clear` destroys the pre-authenticated session the
emulator snapshot carries, so every flow would land on the auth screen. The
fixture-accumulation problem it would have solved is already solved by the
selective success sweep, which leaves the session, profile and auth state alone
and cannot delete another run's data under concurrency.

### 19. Dead helpers removed

**Decision**: Deleted `helpers/pick-date.yaml` (zero consumers) and the unused
`firstOfMonth` / `stamp` exports from `helpers/scripts/date.js`.

### 20. Device selection goes through `ensure-emulator.sh` only

**Decision**: `scripts/ensure-emulator.sh` is the single device-recovery
primitive. Hand-rolled `adb devices | awk` discovery is removed from the runner
and from `scripts/run-android-ui-tests.sh`.

**Rationale**: a local `adb | awk` can only ever find a device that is *already*
up. On this host the emulator crashes regularly (§ the gfxstream ADR), so every
crash surfaced as "no device found — connect one", advice that points away from
the actual remedy. The script already knows the flags that boot this AVD and
waits for readiness.

### 21. A caller-supplied pin must not switch recovery off

**Decision**: `run-maestro.sh` runs `export -n SERIAL`, and
`ensure-emulator.sh` restarts the AVD when the pinned serial is an `emulator-*`
that is not ready. Only a pinned *physical* device remains a hard error.

**Rationale**: a bash variable that arrives through the environment keeps its
export attribute when reassigned, so `SERIAL=emulator-5554 ./scripts/run-maestro.sh`
was leaking the pin into `ensure-emulator.sh`, which then took its
pinned-serial branch and hard-failed — refusing to resurrect the very device it
exists to resurrect. The failure mode is worse than the crash it papers over:
every subsequent flow fails as if the infrastructure were flaky, and the
diagnosis points at the emulator rather than at the runner.

The distinction that makes this safe: a pinned emulator is *recoverable* (start
the AVD); a pinned physical device is not (starting an AVD would land on
`emulator-5554` and not match), so only that case still errors.

Verified by killing the emulator and re-running the pinned path: recovery now
starts the AVD and the flow passes, where it previously reported "Could not
obtain a device".

## Consequences

- All 27 Class A–D failures are expected to pass on the next CI run.
- All 4 Class E flows pass locally (`EXIT=0` on `emulator-5554`).
- `SheetScaffold` is now scrollable: check any sheet whose body relied on the
  column never overflowing.
- Two pre-existing detekt regressions from the earlier `TestTags` work
  (`ArchiveScreen`, `ProjectPickerSheet` one line over `LongMethod`; two unused
  imports in `TaskTitleRow`) were folded in — they blocked the gate and were not
  related to these flows.
- Text-based selectors (26 remaining) cannot be replaced until the relevant
  `TestTags` IDs are added to the product code. These are tracked as open
  issues in `.specify/issues/`.
- **Concurrent runs are safe only for run-scoped fixtures.** A flow that seeds
  through `${RUN_ID}` cannot collide with another run. The 17 flows addressing
  the fixed `Buy milk` still share one title and therefore still require those
  runs to be serialised; their success sweep removes the accumulation, not the
  collision.
- A manual `maestro test <flow>` has no runner, so `RUN_ID` falls back to
  `manual` from `Maestro/config.yaml` and nothing is swept. Manual runs are
  debugging sessions: start them from a `pm clear` slate. `MAESTRO_CLEANUP=0`
  disables the sweep when a run's fixtures must be inspected afterwards.
- Three `seed-task.yaml` consumers were sampled and **fail independently of
  this work**: `tasks/01-set-priority` (`priority_option_none`),
  `tasks/02-set-due-date` (`dialog_date_picker_ok` — annotated in the flow as a
  known Compose a11y quirk), `tasks/16-return-to-source-tab`
  (`agenda_section_today`). They reference no `RUN_ID` and fail identically with
  `MAESTRO_CLEANUP=0`. This is the previously unverified consumer set, and it is
  not yet green.

## Links

- CI run: 38223116125
- Decision to use `scrollUntilVisible` over `swipe`: Maestro CONVENTIONS.md
- TestTags source of truth: `shared/src/commonMain/kotlin/.../TestTags.kt`
- seed-task helper: `Maestro/helpers/seed-task.yaml`
- launch-clean helper: `Maestro/helpers/launch-clean.yaml`
