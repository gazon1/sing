# ADR 2026-10-12 — Maestro E2E fixes: scrollUntilVisible, timeouts, seed cascade

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

## Links

- CI run: 38223116125
- Decision to use `scrollUntilVisible` over `swipe`: Maestro CONVENTIONS.md
- TestTags source of truth: `shared/src/commonMain/kotlin/.../TestTags.kt`
- seed-task helper: `Maestro/helpers/seed-task.yaml`
- launch-clean helper: `Maestro/helpers/launch-clean.yaml`
