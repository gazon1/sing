---
title: "The unreachable system-calendar flow is removed, and the guarantee stays where it can be reached"
date: 2026-11-23
status: accepted
tags: ["testing", "maestro", "traceability", "calendar-sync"]
---

# The unreachable system-calendar flow is removed, and the guarantee stays where it can be reached

## Context

`Maestro/flows/calendar_sync/02-system-calendar-unavailable.yaml` asserted that the
calendar-sync panel shows `calendar_sync_system_unavailable` — the message that says
the platform cannot reach a system calendar. It failed in every sampled full run, with
the cause `Assertion is false: id: calendar_sync_system_unavailable is visible`
(issue #835).

The audit that produced `docs/testing/maestro-guarantees-inventory.md` named the cause
as "a desktop scenario wearing an Android costume", and the reason is in the
production code. `CalendarSyncViewModel` computes

```kotlin
val deviceSyncSupported = calendarProvider.isSupported && syncRepo.isSupported &&
    appQueries.isSupported && scheduler.isSupported
```

and the panel renders the unavailable message only when that conjunction is false.
On Android all four seams are real implementations; only the JVM actuals set
`isSupported = false` (`NoopCalendarProvider`, `JvmCalendarAppQueries`,
`NoopCalendarSyncWorkScheduler`). The gate order in `SystemCalendarPanel` is
`!isSupported` → unavailable message, `!hasPermissions` → permission gate, else the
controls. So no Android device can reach the message the flow asserted.

Meanwhile the *same* flow family carried a second defect: flow 03 carried the bare
tag `CAL-SYNC-SYSTEM-01` instead of `scenario:CAL-SYNC-SYSTEM-02`, so the scenario it
actually verifies had no resolved carrier. `CAL-SYNC-SYSTEM-02` was a dark scenario
and `CAL-SYNC-SYSTEM-02/android` a hole, while the matrix showed a `●` for a cell the
bare tag never resolved.

## Idea

- Delete flow 02 rather than replace it: the guarantee it names is a desktop
  guarantee, and the desktop tier already owns it in
  `CalendarSyncSystemScenarioTest.the_system_panel_says_why_it_cannot_work` (exact
  wording, no enable switch) plus `CalendarSyncPlatformHonestyTest`.
- Do not build a permission-gate replacement flow in the same change. It needs a new
  test tag, Maestro permission control, a new scenario spec, and a device run — and
  the one thing this issue is about is an assertion that no device can satisfy. An
  unverified replacement flow would reproduce the failure mode.
- Fix flow 03's tag so the scenario it verifies has a carrier, and make the flow body
  assert what the scenario claims. The body cannot stay byte-identical: a carrier that
  does not assert the scenario's claim is a decoration.
- Withdraw the Android claim from `CAL-SYNC-SYSTEM-01` instead of keeping it as an
  `unreachable` cell: a state no platform can reach is not a guarantee to be covered.

## Decision

- `Maestro/flows/calendar_sync/02-system-calendar-unavailable.yaml` is deleted, and
  the catalogue, the qualification frozen set, the qualification baseline and the
  pinned digests in `scripts/check-maestro-flow-qualification.py` lose it in the same
  change.
- `Maestro/flows/calendar_sync/03-system-calendar-toggle.yaml` carries
  `scenario:CAL-SYNC-SYSTEM-02` and asserts the state transition the scenario claims:
  the block the panel renders only while the sync is enabled appears when the switch
  is turned on and is gone again when it is turned off. It is admitted by a
  qualification record in `config/docs/maestro-flow-qualification.yaml`: three
  consecutive passes on a real device (realme RMX3938, API 35, serial
  0O65321F32204DD5, Maestro 2.10.0), run against the APK built from this checkout.
- The switch itself carries no state a test can read, so the panel's calendar-app
  picker gets a test tag of its own (`calendar_sync_system_calendar_app_section`,
  `TestTags.CalendarSync.SYSTEM_CALENDAR_APP_SECTION`). It is the first block below
  the switch that the panel renders only while the sync is enabled, and the only one
  of them that is on screen without scrolling.
- The flow grants `calendar` itself (`setPermissions`). A runtime grant does not
  survive the reinstall the qualification runner does before every attempt, and
  without the grant the panel renders the permission gate instead of the controls.
  The grant stays in flow 03 rather than in the shared `launch-clean.yaml`, so a
  future carrier of the permission-gate state is not left without a way to reach it.
- The flow normalizes the switch to OFF before it starts. The enable flag lives in
  the app's DataStore and survives a reinstall, so the switch can begin in either
  state; the flow brings it to OFF, then proves the transition in both directions.
- `CAL-SYNC-SYSTEM-01` claims `desktop` only. Its spec comment now says why the
  Android branch cannot be reached, and names the permission gate as the reachable
  Android state this scenario does not cover and that no carrier owns yet.
- The debt cause #835 becomes `owner: test`, `status: fixed`: the message appearing
  on Android is now a regression, not an accepted state.
- The ratchet floors drop to 69 holes, 51 dark scenarios, 7 unreachable cells.

## Rationale

**Deleting is the honest disposition.** A replacement flow would have to assert
something reachable; the only reachable thing left is the permission gate, and that
is a different guarantee (missing permission, not a missing platform) owned by a
different code path. Renaming the assertion without changing what it means would
have kept a flow that cannot pass.

**The guarantee does not disappear with the flow.** The panel message is a real
product behaviour on desktop, and the desktop test asserts it with its exact wording.
Deleting the Android flow removes a claim about Android, not the behaviour.

**Withdrawing the claim beats marking it unreachable.** The `unreachable` flag says
"this tier cannot get there, so the cell is excused". It fits a scenario whose
*content* is unreachable on that tier. Here the scenario's expected text is the
Android-reachable message, so no Android target exists at all — keeping the cell
would represent a platform state that does not exist as a required product behaviour.

**The broken link was the more expensive half of the defect.** Flow 03 had been
passing and was verifying a real Android guarantee; it was invisible to the matrix
because its tag lacked the `scenario:` prefix. Fixing it moves a dark scenario to a
carrier.

**A carrier that does not assert the scenario's claim is a decoration.** Linking
flow 03 to `CAL-SYNC-SYSTEM-02` made the matrix print `●` for a cell whose scenario
required the status to follow the switch in both directions — but the flow only
waited for the switch to stay visible, so a build whose toggle did nothing would
still have passed. The link is now backed by an assertion of the transition itself:
the panel renders the calendar-app picker, the target-calendar list and the status
block only while `state.isEnabled` is true, so their appearance and disappearance is
the user-visible outcome of the toggle. The flow observes the picker as the
representative one of those three blocks — it is the first and the only one on
screen without scrolling — so the other two are covered by the same product rule
but not asserted individually. The scenario's wording was narrowed to
match what the carrier actually proves — the enabled-only controls follow the
switch — rather than leaving a stronger claim in the matrix than the flow backs.
The status *text* is not what the flow reads; it is the enabled state that the
picker stands for. The guarantee that the Google half stays usable and that
neither provider hides the other belongs to `CAL-SYNC-PROVIDER-01`, which flow 03
does not exercise.

**The switch cannot report its own state, so something else has to.** The enable row
is a clickable button and the `Switch` inside it is handed a null change callback
(the row owns the click), so the accessibility tree exposes no checked state for it:
Maestro's `checked` selector stays false on that row whatever the switch says. That
was measured on the device, not assumed — a flow that asserted `checked: true` on
the enable row would have passed by asserting nothing. The block below the switch
is the observable instead, and it needs a tag of its own to be addressable.

**The absence assertion has to be made from where the element would be.** Maestro's
`notVisible` is satisfied by an element that is merely below the fold, so an
assertion made after scrolling away from the block would pass whether the toggle
worked or not. The flow therefore asserts both directions from the same scroll
position, where the block is on screen while the sync is on.

## Consequences

- The `full` Maestro suite has one flow fewer — `calendar_sync/02-system-calendar-unavailable.yaml` is
  gone. The merged tree holds 124 automated flows; two further flows were retired on `main` after the
  audit (`profile/01-create-profile.yaml`, #3122, and `agenda/04-saved-view-results.yaml`, #3121).
- `CAL-SYNC-SYSTEM-02/android` is automated for the first time; the matrix prints `●`
  for a flow that has been passing all along.
- Flow 03 is qualified by three consecutive device passes, recorded in
  `config/docs/maestro-flow-qualification.yaml` (2026-11-23). The record pins the
  fingerprint of the file in the tree, so the next edit to that flow's body needs a
  new qualification.
- The flow needs a product test tag (`calendar_sync_system_calendar_app_section`) and
  the shared `Maestro/TAGS.md` catalogue grows a row for it. The tag names a block
  that already existed; it adds no behaviour.
- The qualification runner reinstalls the APK before every attempt, so the flow
  grants the calendar permission itself. The grant is local to flow 03 on purpose.
- Earlier runs of the same three attempts on the same device failed for reasons that
  were not the flow's: the Maestro server on the phone died before the flow started
  (`DeviceServerDiedException` at `deviceInfo`) once because the screen had gone to
  sleep during the install, and once because the phone's low-memory killer took the
  server down under memory pressure. Both are device-side failures, and they are
  kept in the run log rather than erased.
- The Android permission gate (READ/WRITE_CALENDAR not granted) is a reachable state
  with no test tag and no carrier. It is named in the `CAL-SYNC-SYSTEM-01` spec; it
  is not covered here.

## Links

- gazon1/sing#3115, gazon1/sing#835
- `docs/testing/maestro-guarantees-inventory.md` (Appendix A)
- `shared/src/commonMain/.../feature/calendarSync/presentation/CalendarSyncViewModel.kt`,
  `CalendarSyncSettingsScreen.kt`
- `desktopApp/src/jvmTest/.../feature/flows/calendar/CalendarSyncSystemScenarioTest.kt`
- `scripts/src/traceability/scenarios/calendar/sync/system/CAL-SYNC-SYSTEM-01.yaml`
